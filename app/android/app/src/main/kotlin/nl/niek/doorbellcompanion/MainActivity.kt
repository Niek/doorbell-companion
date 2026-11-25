package nl.niek.doorbellcompanion

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : FlutterActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val syncExecutor = Executors.newSingleThreadExecutor()
    private var channel: MethodChannel? = null
    private var discovery: HomeAssistantDiscovery? = null
    private var passiveLanProbe: HomeAssistantDiscovery? = null
    private val discoveredInternalUrls = mutableMapOf<String, String>()
    @Volatile private var loopbackSocket: ServerSocket? = null
    private var pendingBaseUrl: String = ""

    private val syncAutomationRunnable = Runnable {
        syncExecutor.execute {
            try {
                if (HomeAssistantAuth.isConfigured(applicationContext)) {
                    HomeAssistantAuth.setupPushAutomation(applicationContext)
                }
            } catch (e: Exception) {
                Log.w("DoorbellCompanion", "Automation sync error: ${e.message}")
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        WebRtcEngine.warmUpAsync(this)
        RingNotification.createChannel(this)
        FirebaseMessaging.getInstance().token.addOnSuccessListener { saveToken(it) }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (HomeAssistantAuth.isConfigured(this)) {
            syncExecutor.execute {
                try { HomeAssistantAuth.discoverDoorbellsAndWarmup(applicationContext) } catch (_: Exception) {}
                main.post {
                    if (!isDestroyed) {
                        pushStatusToFlutter()
                        maybeProbeLanUrl()
                    }
                }
            }
        }

        val methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "doorbell_companion")
        channel = methodChannel
        methodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getStatus" -> result.success(buildStatusMap())

                "startDiscovery" -> {
                    startMdnsDiscovery()
                    result.success(null)
                }

                "startOAuth" -> {
                    val rawUrl = call.argument<String>("url")?.trim().orEmpty()
                    if (rawUrl.isEmpty()) {
                        result.error("INVALID_URL", "Enter a Home Assistant URL", null)
                    } else {
                        startBrowserOAuth(HomeAssistantAuth.normalizeBaseUrl(rawUrl))
                        result.success(null)
                    }
                }

                "cancelOAuth" -> {
                    closeLoopback()
                    startMdnsDiscovery()
                    result.success(null)
                }

                "refreshDoorbells" -> {
                    syncExecutor.execute {
                        try {
                            HomeAssistantAuth.discoverDoorbellsAndWarmup(applicationContext)
                            HomeAssistantAuth.setupPushAutomation(applicationContext)
                            main.post {
                                if (!isDestroyed) {
                                    maybeProbeLanUrl()
                                    result.success(buildStatusMap())
                                }
                            }
                        } catch (e: Exception) {
                            main.post {
                                if (!isDestroyed) {
                                    result.error("REFRESH_FAILED", e.message ?: "Could not refresh", null)
                                }
                            }
                        }
                    }
                }

                "setDoorbellEnabled" -> {
                    val entityId = call.argument<String>("cameraEntityId")
                    val enabled = call.argument<Boolean>("enabled") ?: true
                    if (!entityId.isNullOrBlank()) {
                        HomeAssistantAuth.setDoorbellEnabled(this, entityId, enabled)
                        // Debounce HA automation update so rapid checkbox toggles feel instant
                        main.removeCallbacks(syncAutomationRunnable)
                        main.postDelayed(syncAutomationRunnable, 400L)
                    }
                    result.success(buildStatusMap())
                }

                "disconnect" -> {
                    main.removeCallbacks(syncAutomationRunnable)
                    HomeAssistantAuth.disconnect(this)
                    result.success(buildStatusMap())
                }

                "openUrl" -> {
                    val url = call.argument<String>("url")
                    if (!url.isNullOrBlank()) openCustomTab(url)
                    result.success(null)
                }

                "testCall" -> {
                    CallTiming.start(System.currentTimeMillis().toString())
                    val (camId, doorbellName) = HomeAssistantAuth.resolveCameraForPush(this, emptyMap())
                    HomeAssistantAuth.prefs(this).edit()
                        .putString("last_doorbell_name", doorbellName)
                        .apply { if (camId != null) putString("last_ha_camera", camId) }
                        .apply()
                    if (camId != null) {
                        HomeAssistantPrewarm.start(this, camId)
                    }
                    startActivity(
                        Intent(this, CallActivity::class.java)
                            .putExtra("doorbell_name", doorbellName)
                            .putExtra("camera_entity_id", camId)
                            .putExtra("answer", false)
                    )
                    result.success(null)
                }

                "testRemotePush" -> {
                    HomeAssistantAuth.ioExecutor.execute {
                        try {
                            Thread.sleep(3_000L)
                            HomeAssistantAuth.sendTestPushViaHa(applicationContext)
                            main.post { result.success(null) }
                        } catch (e: Exception) {
                            Log.w("DoorbellCompanion", "Remote test push failed: ${e.message}")
                            main.post {
                                result.error("TEST_PUSH_FAILED", e.message ?: "Could not send the test call", null)
                            }
                        }
                    }
                }

                "requestPermissions" -> {
                    when (call.argument<String>("type")) {
                        "notifications" -> {
                            val canAsk = Build.VERSION.SDK_INT >= 33 &&
                                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                                canShowPermissionDialog(Manifest.permission.POST_NOTIFICATIONS)
                            if (canAsk) {
                                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                            } else {
                                // Denied twice, or notifications/channel switched off in system settings.
                                openSettings(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                )
                            }
                        }
                        "microphone" -> {
                            if (canShowPermissionDialog(Manifest.permission.RECORD_AUDIO)) {
                                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                            } else {
                                openSettings(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                                )
                            }
                        }
                        "fullScreen" -> {
                            if (Build.VERSION.SDK_INT >= 34) {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        Uri.parse("package:$packageName")
                                    )
                                )
                            }
                        }
                    }
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pushStatusToFlutter()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val prefs = HomeAssistantAuth.prefs(this).edit()
        permissions.forEach { prefs.putBoolean("asked_permission_$it", true) }
        prefs.apply()
        pushStatusToFlutter()
    }

    /**
     * Android stops showing the permission dialog after the user denies it twice. Once we have
     * asked before and the system no longer wants a rationale, the dialog would silently no-op.
     */
    private fun canShowPermissionDialog(permission: String): Boolean {
        val askedBefore = HomeAssistantAuth.prefs(this).getBoolean("asked_permission_$permission", false)
        return !askedBefore || shouldShowRequestPermissionRationale(permission)
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun buildStatusMap(): Map<String, Any?> {
        val prefs = HomeAssistantAuth.prefs(this)
        val haConnected = HomeAssistantAuth.isConfigured(this)
        val haUrl = prefs.getString("ha_url", "") ?: ""
        val doorbells = HomeAssistantAuth.getSavedDoorbells(this)
        val disabled = HomeAssistantAuth.getDisabledCameraIds(this)
        val doorbellMaps = doorbells.map { db ->
            mapOf(
                "cameraEntityId" to db.cameraEntityId,
                "name" to db.name,
                "enabled" to (db.cameraEntityId !in disabled),
            )
        }
        val firstCameraName = (doorbells.firstOrNull { it.cameraEntityId !in disabled } ?: doorbells.firstOrNull())?.name ?: ""
        // Covers the runtime permission and the app-level switch in system settings.
        val notifGranted = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val fullScreenGranted = if (Build.VERSION.SDK_INT >= 34) {
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else true

        return mapOf(
            "haConnected" to haConnected,
            "haUrl" to haUrl,
            "doorbells" to doorbellMaps,
            "firstCameraName" to firstCameraName,
            "notificationsGranted" to notifGranted,
            "microphoneGranted" to micGranted,
            "fullScreenGranted" to fullScreenGranted,
        )
    }

    private fun pushStatusToFlutter() {
        channel?.invokeMethod("onStatusChanged", buildStatusMap())
    }

    private fun startMdnsDiscovery() {
        if (discovery?.active == true) return
        discovery?.stop()
        discovery = HomeAssistantDiscovery(this) { instances ->
            if (isDestroyed) return@HomeAssistantDiscovery
            instances.forEach { inst ->
                if (!inst.internalUrl.isNullOrBlank()) {
                    discoveredInternalUrls[inst.url] = inst.internalUrl
                }
            }
            val list = instances.map {
                mapOf("name" to it.name, "url" to it.url, "version" to (it.version ?: ""))
            }
            channel?.invokeMethod("onDiscoveredInstances", list)
        }.also { it.start() }
    }

    /**
     * If HA's get_config didn't advertise an internal_url, do a short passive mDNS check
     * and only accept a local LAN URL whose external_url matches our configured ha_url.
     */
    private fun maybeProbeLanUrl() {
        val current = HomeAssistantAuth.getInternalUrl(this)
        val isMdnsHost = current?.contains(".local", ignoreCase = true) == true
        if ((current != null && !isMdnsHost) || passiveLanProbe?.active == true) return
        val expectedExternal = HomeAssistantAuth.getBaseUrl(this)?.trimEnd('/') ?: return
        val probe = HomeAssistantDiscovery(this) { instances ->
            val match = instances.firstOrNull {
                it.externalUrl?.equals(expectedExternal, ignoreCase = true) == true &&
                    !it.internalUrl.isNullOrBlank()
            }
            if (match != null) {
                HomeAssistantAuth.saveInternalUrl(applicationContext, match.internalUrl)
                Log.i("DoorbellCompanion", "Discovered LAN URL via mDNS for low-latency Wi-Fi calls")
                passiveLanProbe?.stop()
                passiveLanProbe = null
            }
        }
        passiveLanProbe = probe
        probe.start()
        main.postDelayed({
            if (passiveLanProbe === probe) {
                probe.stop()
                passiveLanProbe = null
            }
        }, 4_000L)
    }

    private fun startBrowserOAuth(baseUrl: String) {
        pendingBaseUrl = baseUrl
        discovery?.stop()
        discovery = null
        closeLoopback()

        try {
            val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
            loopbackSocket = server
            val port = server.localPort
            val clientId = "http://127.0.0.1:$port"
            val redirectUri = "$clientId/callback"
            val handled = AtomicBoolean(false)

            HomeAssistantAuth.ioExecutor.execute {
                try {
                    while (!server.isClosed && !handled.get()) {
                        server.accept().use { client ->
                            client.soTimeout = 5_000
                            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                            val reqLine = reader.readLine() ?: return@use
                            val path = reqLine.split(' ').getOrNull(1) ?: "/"
                            if (!path.startsWith("/callback")) {
                                client.getOutputStream().write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                                return@use
                            }
                            val code = Uri.parse("http://127.0.0.1$path").getQueryParameter("code")
                            val html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                                "<meta http-equiv=\"refresh\" content=\"0;url=doorbell-companion://auth-done\"></head>" +
                                "<body style=\"font-family:sans-serif;padding:32px;text-align:center\">" +
                                "<h2>Connected to Doorbell Companion</h2><p>Returning to the app…</p></body></html>"
                            val resp = "HTTP/1.1 302 Found\r\n" +
                                "Location: doorbell-companion://auth-done\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${html.toByteArray().size}\r\n" +
                                "Connection: close\r\n\r\n$html"
                            client.getOutputStream().apply {
                                write(resp.toByteArray())
                                flush()
                            }
                            if (handled.compareAndSet(false, true)) {
                                closeLoopback()
                                main.post {
                                    if (!isDestroyed) {
                                        startActivity(
                                            Intent(this@MainActivity, MainActivity::class.java)
                                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        )
                                        completeOAuthCode(code, clientId)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            val authUrl = "$baseUrl/auth/authorize?response_type=code" +
                "&client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
                "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}"
            if (!openCustomTab(authUrl)) {
                closeLoopback()
                channel?.invokeMethod("onOAuthError", "No browser app found to sign in")
            }
        } catch (e: Exception) {
            closeLoopback()
            channel?.invokeMethod("onOAuthError", e.message ?: "Could not open browser")
        }
    }

    private fun completeOAuthCode(code: String?, clientId: String) {
        if (code.isNullOrBlank()) {
            channel?.invokeMethod("onOAuthError", "Sign-in was cancelled")
            return
        }
        channel?.invokeMethod("onOAuthProgress", "Discovering doorbells & setting up notifications…")
        val knownInternalUrl = discoveredInternalUrls[pendingBaseUrl]
        syncExecutor.execute {
            try {
                val ok = HomeAssistantAuth.exchangeCode(applicationContext, pendingBaseUrl, code, clientId)
                if (!ok) error("Token exchange failed")
                HomeAssistantAuth.saveInternalUrl(applicationContext, knownInternalUrl)
                HomeAssistantAuth.discoverDoorbellsAndWarmup(applicationContext)
                try {
                    HomeAssistantAuth.setupPushAutomation(applicationContext)
                } catch (e: Exception) {
                    Log.w("DoorbellCompanion", "Initial push setup note: ${e.message}")
                }
                main.post {
                    if (!isDestroyed) {
                        channel?.invokeMethod("onOAuthComplete", buildStatusMap())
                        maybeProbeLanUrl()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (!isDestroyed) {
                        channel?.invokeMethod("onOAuthError", e.message ?: "Connection failed")
                    }
                }
            }
        }
    }

    private fun openCustomTab(url: String): Boolean =
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                val extras = Bundle()
                extras.putBinder("android.support.customtabs.extra.SESSION", null)
                putExtras(extras)
            }
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    private fun saveToken(token: String) {
        val prefs = HomeAssistantAuth.prefs(this)
        prefs.edit().putString("fcm_token", token).apply()
        if (HomeAssistantAuth.isConfigured(this)) {
            if (prefs.getString("ha_notify_service", null).isNullOrBlank() ||
                prefs.getString("ha_push_url", null) != HomeAssistantAuth.PUSH_RELAY_URL
            ) {
                syncExecutor.execute {
                    try {
                        HomeAssistantAuth.setupPushAutomation(applicationContext)
                    } catch (e: Exception) {
                        Log.w("DoorbellCompanion", "Auto push setup failed: ${e.message}")
                    }
                }
            } else {
                HomeAssistantAuth.syncPushTokenIfRegistered(this, token)
            }
        }
    }

    private fun closeLoopback() {
        try { loopbackSocket?.close() } catch (_: Exception) {}
        loopbackSocket = null
    }

    override fun onDestroy() {
        main.removeCallbacks(syncAutomationRunnable)
        discovery?.stop()
        passiveLanProbe?.stop()
        closeLoopback()
        syncExecutor.shutdownNow()
        super.onDestroy()
    }
}
