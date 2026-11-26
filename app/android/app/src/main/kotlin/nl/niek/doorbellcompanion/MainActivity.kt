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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val syncExecutor = Executors.newSingleThreadExecutor()
    private var channel: MethodChannel? = null
    private var discovery: HomeAssistantDiscovery? = null
    private var passiveLanProbe: HomeAssistantDiscovery? = null
    private val discoveredInternalUrls = mutableMapOf<String, String>()
    private var pendingCallbackUri: Uri? = null
    private var pushReady = false
    private var pushError: String? = null
    private val random = SecureRandom()
    private val base64Url = Base64.getUrlEncoder().withoutPadding()
    private val hostedOAuth by lazy { getSharedPreferences("pending_hosted_oauth", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pushReady = HomeAssistantAuth.isPushSynced(this)
        if (HomeAssistantAuth.isPendingDisconnect(this)) {
            pushReady = false
            pushError = "Run Disconnect again to finish signing out of Home Assistant"
        }
        val data = intent.data
        if (channel != null) {
            handleOAuthCallback(data)
        } else {
            pendingCallbackUri = data
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthCallback(intent.data)
    }

    private val syncAutomationRunnable = Runnable { syncPush(refreshTriggers = false) }

    private fun syncPush(refreshTriggers: Boolean) {
        syncExecutor.execute {
            if (!HomeAssistantAuth.isConfigured(applicationContext)) return@execute
            try {
                HomeAssistantAuth.setupPushAutomation(applicationContext, refreshTriggers = refreshTriggers)
                main.post {
                    if (!isDestroyed) {
                        pushReady = true
                        pushError = null
                        pushStatusToFlutter()
                    }
                }
            } catch (e: Exception) {
                Log.w("DoorbellCompanion", "Push sync error: ${e.message}")
                main.post {
                    if (!isDestroyed) {
                        pushReady = false
                        pushError = e.message ?: "Could not register for doorbell calls"
                        pushStatusToFlutter()
                    }
                }
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        WebRtcEngine.warmUpAsync(this)
        RingNotification.createChannel(this)
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { saveToken(it) }
            .addOnFailureListener { e ->
                Log.w("DoorbellCompanion", "FCM token fetch failed: ${e.message}")
                if (HomeAssistantAuth.isConfigured(this) && !pushReady) {
                    pushError = e.message ?: "Could not get push token"
                    pushStatusToFlutter()
                }
            }

        if (HomeAssistantAuth.isConfigured(this)) {
            syncExecutor.execute {
                try {
                    HomeAssistantAuth.discoverDoorbellsAndWarmup(applicationContext)
                } catch (e: Exception) {
                    Log.w("DoorbellCompanion", "Doorbell discovery on launch failed: ${e.message}")
                }
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
                    cancelPendingOAuth()
                    startMdnsDiscovery()
                    result.success(null)
                }

                "refreshDoorbells" -> {
                    syncExecutor.execute {
                        try {
                            HomeAssistantAuth.discoverDoorbellsAndWarmup(applicationContext)
                            HomeAssistantAuth.setupPushAutomation(applicationContext, refreshTriggers = true)
                            main.post {
                                if (!isDestroyed) {
                                    pushReady = true
                                    pushError = null
                                    maybeProbeLanUrl()
                                    result.success(buildStatusMap())
                                }
                            }
                        } catch (e: Exception) {
                            main.post {
                                if (!isDestroyed) {
                                    pushReady = false
                                    pushError = e.message ?: "Could not refresh"
                                    pushStatusToFlutter()
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
                        // Debounce HA subscription update so rapid toggles feel instant.
                        main.removeCallbacks(syncAutomationRunnable)
                        main.postDelayed(syncAutomationRunnable, 400L)
                    }
                    result.success(buildStatusMap())
                }

                "disconnect" -> {
                    main.removeCallbacks(syncAutomationRunnable)
                    syncExecutor.execute {
                        try {
                            HomeAssistantAuth.disconnect(applicationContext)
                            main.post {
                                if (!isDestroyed) {
                                    pushReady = false
                                    pushError = null
                                    result.success(buildStatusMap())
                                }
                            }
                        } catch (e: Exception) {
                            main.post {
                                if (!isDestroyed) {
                                    if (HomeAssistantAuth.isPendingDisconnect(this)) {
                                        pushReady = false
                                        pushError = "Run Disconnect again to finish signing out of Home Assistant"
                                        pushStatusToFlutter()
                                    }
                                    result.error("DISCONNECT_FAILED", e.message ?: "Could not disconnect", null)
                                }
                            }
                        }
                    }
                }

                "openUrl" -> {
                    val url = call.argument<String>("url")
                    if (!url.isNullOrBlank()) openCustomTab(url)
                    result.success(null)
                }

                "testCall" -> {
                    CallTiming.start(System.currentTimeMillis().toString())
                    val camera = call.argument<String>("camera").orEmpty()
                    val (camId, doorbellName) = HomeAssistantAuth.resolveCameraForPush(
                        this,
                        mapOf("camera_entity_id" to camera),
                    )
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
                            .putExtra("answer", true)
                    )
                    result.success(null)
                }

                "testRemotePush" -> {
                    val camera = call.argument<String>("camera")
                    main.postDelayed({
                        HomeAssistantAuth.ioExecutor.execute {
                            try {
                                HomeAssistantAuth.sendTestPushViaHa(applicationContext, camera)
                                main.post { if (!isDestroyed) result.success(null) }
                            } catch (e: Exception) {
                                Log.w("DoorbellCompanion", "Remote test push failed: ${e.message}")
                                main.post {
                                    if (!isDestroyed) {
                                        result.error("TEST_PUSH_FAILED", e.message ?: "Could not send the test call", null)
                                    }
                                }
                            }
                        }
                    }, 3_000L)
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
                                openSettings(
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

        pendingCallbackUri?.let { uri ->
            pendingCallbackUri = null
            handleOAuthCallback(uri)
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
        val notifGranted = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val fullScreenGranted = if (Build.VERSION.SDK_INT >= 34) {
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else true

        val map = mutableMapOf<String, Any?>(
            "haConnected" to haConnected,
            "haUrl" to haUrl,
            "doorbells" to doorbellMaps,
            "notificationsGranted" to notifGranted,
            "microphoneGranted" to micGranted,
            "fullScreenGranted" to fullScreenGranted,
            "pushReady" to pushReady,
        )
        pushError?.let { map["pushError"] = it }
        return map
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
     * If HA's get_config didn't advertise an internal_url (or only advertised a .local hostname),
     * do a short passive mDNS check and accept a local LAN IP whose external_url matches ours.
     */
    private fun maybeProbeLanUrl() {
        val current = HomeAssistantAuth.getInternalUrl(this)
        val isMdnsHost = current?.contains(".local", ignoreCase = true) == true
        if ((current != null && !isMdnsHost) || passiveLanProbe?.active == true) return
        val expectedExternal = (HomeAssistantAuth.getExternalUrl(this) ?: HomeAssistantAuth.getBaseUrl(this))
            ?.trimEnd('/') ?: return
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
        discovery?.stop()
        discovery = null
        cancelPendingOAuth()
        val state = oauthNonce()
        val verifier = oauthNonce()
        // Persist in SharedPreferences so the callback still completes after process recreation.
        hostedOAuth.edit()
            .putString("url", baseUrl)
            .putString("state", state)
            .putString("verifier", verifier)
            .putLong("expires", System.currentTimeMillis() + 10 * 60_000)
            .apply()
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val url = Uri.parse("$baseUrl/auth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", HomeAssistantAuth.ANDROID_CLIENT_ID)
            .appendQueryParameter("redirect_uri", "doorbell-companion://auth-callback")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", base64Url.encodeToString(challenge))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
        if (!openCustomTab(url)) {
            cancelPendingOAuth()
            channel?.invokeMethod("onOAuthError", "No browser app found to sign in")
        }
    }

    private fun oauthNonce(): String = base64Url.encodeToString(ByteArray(32).also(random::nextBytes))

    private fun handleOAuthCallback(uri: Uri?) {
        if (uri == null || uri.scheme != "doorbell-companion" ||
            uri.encodedAuthority != "auth-callback" || !uri.encodedPath.isNullOrEmpty()
        ) return
        if (System.currentTimeMillis() >= hostedOAuth.getLong("expires", 0)) return
        val state = hostedOAuth.getString("state", null) ?: return
        if (uri.getQueryParameter("state") != state) return
        val url = hostedOAuth.getString("url", null) ?: return
        val verifier = hostedOAuth.getString("verifier", null) ?: return
        completeOAuthCode(uri.getQueryParameter("code"), url, verifier)
    }

    private fun completeOAuthCode(code: String?, baseUrl: String, verifier: String) {
        cancelPendingOAuth()
        if (code.isNullOrBlank()) {
            channel?.invokeMethod("onOAuthError", "Sign-in was cancelled")
            return
        }
        channel?.invokeMethod("onOAuthProgress", "Discovering doorbells & setting up notifications…")
        val knownInternalUrl = discoveredInternalUrls[baseUrl]
        syncExecutor.execute {
            try {
                val ok = HomeAssistantAuth.exchangeCode(
                    applicationContext, baseUrl, code, HomeAssistantAuth.ANDROID_CLIENT_ID, verifier,
                )
                if (!ok) error("Token exchange failed")
                HomeAssistantAuth.saveInternalUrl(applicationContext, knownInternalUrl)
                HomeAssistantAuth.discoverDoorbellsAndWarmup(applicationContext)
                var setupErr: String? = null
                var ready = false
                try {
                    HomeAssistantAuth.setupPushAutomation(applicationContext, refreshTriggers = true)
                    ready = true
                } catch (e: Exception) {
                    Log.w("DoorbellCompanion", "Initial push setup note: ${e.message}")
                    setupErr = e.message
                }
                main.post {
                    if (!isDestroyed) {
                        pushReady = ready
                        pushError = setupErr
                        channel?.invokeMethod("onOAuthComplete", buildStatusMap())
                        requestPostOnboardingPermissions()
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

    private fun requestPostOnboardingPermissions() {
        val toRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            canShowPermissionDialog(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (HomeAssistantAuth.hasAnyTwoWayAudio(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            canShowPermissionDialog(Manifest.permission.RECORD_AUDIO)
        ) {
            toRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (toRequest.isNotEmpty()) {
            requestPermissions(toRequest.toTypedArray(), 1)
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
        if (HomeAssistantAuth.isConfigured(this) && !HomeAssistantAuth.isPendingDisconnect(this)) {
            if (!HomeAssistantAuth.isPushSynced(this) ||
                prefs.getString("ha_registered_push_token", null) != token
            ) {
                syncPush(refreshTriggers = false)
            }
        }
    }

    private fun cancelPendingOAuth() {
        hostedOAuth.edit().clear().apply()
    }

    override fun onDestroy() {
        main.removeCallbacks(syncAutomationRunnable)
        discovery?.stop()
        passiveLanProbe?.stop()
        syncExecutor.shutdownNow()
        super.onDestroy()
    }
}
