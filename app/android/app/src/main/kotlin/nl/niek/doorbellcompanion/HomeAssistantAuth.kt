package nl.niek.doorbellcompanion

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.flutter.FlutterInjector
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class DoorbellInfo(
    val cameraEntityId: String,
    val name: String,
    val ringEntityIds: List<String>,
    val nativeRingEvents: Set<String> = emptySet(),
)

object HomeAssistantAuth {
    const val RELAY_BASE_URL = "https://doorbell-companion.nivadema.workers.dev"
    const val ANDROID_CLIENT_ID = "$RELAY_BASE_URL/android"
    const val PUSH_RELAY_URL = "$RELAY_BASE_URL/push"
    private const val PREFS = "doorbell_companion"
    private const val PENDING_REVOCATION = "ha_pending_revocation"
    private const val SYNCED_SUBSCRIPTION = "ha_synced_subscription"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val nonAlnum = Regex("[^a-z0-9]+")

    val ioExecutor: ExecutorService = Executors.newCachedThreadPool()

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedExpiresAt: Long = 0L
    private val tokenLock = Any()
    private val pushLock = Any()

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConfigured(context: Context): Boolean {
        val p = prefs(context)
        return !p.getString("ha_url", null).isNullOrBlank() &&
            !p.getString("ha_refresh_token", null).isNullOrBlank()
    }

    fun isPendingDisconnect(context: Context): Boolean =
        prefs(context).contains(PENDING_REVOCATION)

    fun isPushSynced(context: Context): Boolean {
        val p = prefs(context)
        return !p.contains(PENDING_REVOCATION) &&
            !p.getString("ha_webhook_id", null).isNullOrBlank() &&
            !p.getString("ha_notify_service", null).isNullOrBlank() &&
            p.getString("ha_push_url", null) == PUSH_RELAY_URL &&
            !p.getString("fcm_token", null).isNullOrBlank()
    }

    fun getBaseUrl(context: Context): String? = prefs(context).getString("ha_url", null)

    fun getInternalUrl(context: Context): String? {
        val p = prefs(context)
        val internal = p.getString("ha_internal_url", null)?.takeIf { it.isNotBlank() } ?: return null
        val external = p.getString("ha_url", null)?.trimEnd('/')
        return internal.trimEnd('/').takeIf { !it.equals(external, ignoreCase = true) }
    }

    fun getExternalUrl(context: Context): String? =
        prefs(context).getString("ha_external_url", null)?.takeIf { it.isNotBlank() }?.trimEnd('/')

    /** Deduplicated Home Assistant endpoints ordered LAN-first, then external, then primary sign-in URL. */
    fun endpoints(context: Context): List<String> =
        listOfNotNull(getInternalUrl(context), getExternalUrl(context), getBaseUrl(context)?.trimEnd('/'))
            .distinctBy { it.lowercase() }

    fun saveInternalUrl(context: Context, rawUrl: String?) {
        val normalized = rawUrl?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            ?.let(::normalizeBaseUrl) ?: return
        val base = getBaseUrl(context)?.trimEnd('/')
        if (normalized.equals(base, ignoreCase = true)) return
        prefs(context).edit().putString("ha_internal_url", normalized).apply()
    }

    fun disconnect(context: Context) = synchronized(pushLock) {
        val p = prefs(context)
        val baseUrl = p.getString("ha_url", null) ?: return@synchronized
        val refresh = p.getString("ha_refresh_token", null) ?: error("Home Assistant sign-in credentials are missing")
        val candidates = endpoints(context).ifEmpty { listOf(baseUrl) }
        var endpoint = p.getString(PENDING_REVOCATION, null)
        if (endpoint == null) {
            val token = getAccessToken(context, forceRefresh = true)
                ?: error("Could not verify your Home Assistant sign-in. Check the connection and try again.")
            endpoint = firstReachableEndpoint(candidates, token)
                ?: error("Could not reach Home Assistant. Check the connection and try again.")

            val entryId = registrationEntryId(context, endpoint, token, p.getString("ha_webhook_id", null))
            if (entryId != null) {
                deleteRegistration(endpoint, token, entryId)
            }
            // A retry after revocation must work even if the server already invalidated this session.
            check(p.edit().putString(PENDING_REVOCATION, endpoint).commit()) { "Could not save disconnect progress" }
        }

        check((listOf(endpoint) + candidates).distinct().any { revokeSession(it, refresh) }) {
            "Could not revoke the Home Assistant session. Check the connection and try Disconnect again."
        }

        synchronized(tokenLock) {
            cachedToken = null
            cachedExpiresAt = 0L
            p.edit()
                .remove("ha_url")
                .remove("ha_internal_url")
                .remove("ha_external_url")
                .remove("ha_client_id")
                .remove("ha_access_token")
                .remove("ha_refresh_token")
                .remove("ha_token_expires_at")
                .remove("ha_doorbells")
                .remove("ha_disabled_cameras")
                .remove("ha_ice_servers")
                .remove("ha_ice_servers_time")
                .remove("ha_notify_service")
                .remove("ha_webhook_id")
                .remove("ha_push_url")
                .remove("ha_two_way_cameras")
                .remove("ha_data_channels")
                .remove("ha_registered_push_token")
                .remove("ha_legacy_device")
                .remove(SYNCED_SUBSCRIPTION)
                .remove(PENDING_REVOCATION)
                .apply()
        }
    }

    private fun registrationEntryId(context: Context, endpoint: String, token: String, webhookId: String?): String? {
        val deviceId = webhookId?.let { id ->
            val request = Request.Builder().url("$endpoint/api/webhook/$id")
                .post(JSONObject().put("type", "get_config").toString().toRequestBody(jsonMedia))
                .build()
            try {
                cleanupCall(request).use { response ->
                    when (response.code) {
                        404, 410 -> null
                        200 -> response.body?.string()?.takeIf { it.isNotBlank() }?.let { body ->
                            runCatching { JSONObject(body).optString("hass_device_id") }.getOrNull()
                                ?.takeIf(String::isNotBlank)
                                ?: error("Home Assistant did not identify the phone registration")
                        }
                        else -> error("Could not verify the phone registration (HTTP ${response.code})")
                    }
                }
            } catch (_: IOException) {
                error("Could not verify the phone registration. Check the connection and try again.")
            }
        }
        val expectedRegId = registrationDeviceId(context)
        val devices = registeredDevices(endpoint, token)
        val matching = (0 until devices.length()).mapNotNull { devices.optJSONObject(it) }.filter { device ->
            val identifiers = device.optJSONArray("identifiers") ?: return@filter false
            (0 until identifiers.length()).any {
                val identifier = identifiers.optJSONArray(it)
                identifier?.optString(0) == "mobile_app" && identifier.optString(1) == expectedRegId
            }
        }
        if (deviceId == null && matching.isEmpty()) return null
        val device = matching.singleOrNull { it.optString("id") == deviceId }
            ?: error("Could not verify that the Home Assistant registration belongs to this phone")
        val entries = device.optJSONArray("config_entries")
        val entryId = if (entries == null) device.optString("config_entry_id")
            else if (entries.length() == 1) entries.optString(0) else ""
        return entryId.takeIf { it.isNotBlank() }
            ?: error("Could not identify the phone's Mobile App entry in Home Assistant")
    }

    private fun registeredDevices(endpoint: String, token: String): JSONArray {
        val devices = AtomicReference<JSONArray>()
        val done = CountDownLatch(1)
        val socket = httpClient.newWebSocket(Request.Builder().url(wsUrl(endpoint)).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                        "auth_ok" -> webSocket.send("""{"id":1,"type":"config/device_registry/list"}""")
                        "auth_invalid" -> done.countDown()
                        "result" -> if (message.optInt("id") == 1) {
                            if (message.optBoolean("success")) devices.set(message.optJSONArray("result"))
                            done.countDown()
                        }
                    }
                } catch (_: Exception) {
                    done.countDown()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.countDown() }
        })
        try {
            done.await(8, TimeUnit.SECONDS)
            return devices.get() ?: error("Could not read the Home Assistant device registry. Try Disconnect again.")
        } finally {
            socket.cancel()
        }
    }

    private fun revokeSession(endpoint: String, token: String): Boolean {
        val form = FormBody.Builder().add("token", token)
        fun post(path: String, body: FormBody) = cleanupCall(Request.Builder().url("$endpoint/$path").post(body).build())
        return try {
            post("auth/revoke", form.build()).use {
                if (it.code != 404 && it.code != 405) return it.code == 200 && it.body?.string().orEmpty().isEmpty()
            }
            post("auth/token", form.add("action", "revoke").build()).use {
                it.code == 200 && it.body?.string().orEmpty().isEmpty()
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun cleanupCall(request: Request): Response = httpClient.newCall(request).apply {
        timeout().timeout(8, TimeUnit.SECONDS)
    }.execute()

    /** Races all candidate endpoints in parallel and returns the first that responds OK to `/api/`. */
    private fun firstReachableEndpoint(endpoints: List<String>, token: String): String? {
        if (endpoints.isEmpty()) return null
        if (endpoints.size == 1) {
            val only = endpoints.first()
            return try {
                cleanupCall(haRequest(only, token, "/api/").build()).use { if (it.isSuccessful) only else null }
            } catch (_: IOException) {
                null
            }
        }
        val winner = AtomicReference<String?>(null)
        val pending = AtomicInteger(endpoints.size)
        val done = CountDownLatch(1)
        val calls = endpoints.map { url ->
            val call = httpClient.newCall(haRequest(url, token, "/api/").build()).apply {
                timeout().timeout(8, TimeUnit.SECONDS)
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (pending.decrementAndGet() == 0) done.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (it.isSuccessful && winner.compareAndSet(null, url)) done.countDown()
                        }
                    } finally {
                        if (pending.decrementAndGet() == 0) done.countDown()
                    }
                }
            })
            call
        }
        return try {
            done.await(8, TimeUnit.SECONDS)
            winner.get()
        } finally {
            calls.forEach { it.cancel() }
        }
    }

    fun normalizeBaseUrl(raw: String): String {
        var trimmed = raw.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed = "https://$trimmed"
        }
        return trimmed.trimEnd('/')
    }

    fun wsUrl(baseUrl: String): String {
        val clean = baseUrl.trimEnd('/')
        return when {
            clean.startsWith("https://", ignoreCase = true) -> "wss://" + clean.substring(8) + "/api/websocket"
            clean.startsWith("http://", ignoreCase = true) -> "ws://" + clean.substring(7) + "/api/websocket"
            else -> "wss://$clean/api/websocket"
        }
    }

    fun exchangeCode(context: Context, baseUrl: String, code: String, clientId: String, verifier: String): Boolean {
        val cleanUrl = normalizeBaseUrl(baseUrl)
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        val req = Request.Builder().url("$cleanUrl/auth/token").post(form).build()
        var lastError: Exception? = null
        for (attempt in 1..6) {
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return false
                    val body = JSONObject(resp.body?.string() ?: return false)
                    val access = body.optString("access_token")
                    val refresh = body.optString("refresh_token")
                    val expiresIn = body.optLong("expires_in", 1800L)
                    if (access.isBlank() || refresh.isBlank()) return false
                    val expiresAt = System.currentTimeMillis() + expiresIn * 1000L
                    synchronized(tokenLock) {
                        cachedToken = access
                        cachedExpiresAt = expiresAt
                        val prefs = prefs(context)
                        val editor = prefs.edit()
                        if (prefs.getString("ha_url", null) != cleanUrl) {
                            editor.remove("ha_internal_url")
                                .remove("ha_external_url")
                                .remove("ha_webhook_id")
                                .remove("ha_registered_push_token")
                                .remove("ha_notify_service")
                                .remove("ha_ice_servers")
                                .remove("ha_ice_servers_time")
                                .remove(SYNCED_SUBSCRIPTION)
                        }
                        editor
                            .putString("ha_url", cleanUrl)
                            .putString("ha_client_id", clientId)
                            .putString("ha_access_token", access)
                            .putString("ha_refresh_token", refresh)
                            .putLong("ha_token_expires_at", expiresAt)
                            .apply()
                    }
                    return true
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < 6) Thread.sleep(350L)
            }
        }
        throw (lastError ?: IOException("Network unavailable"))
    }

    /** Fast non-blocking check for a still-valid access token. */
    fun peekAccessToken(context: Context): String? {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { it.isNotBlank() && now < cachedExpiresAt - 120_000L }?.let { return it }
        val p = prefs(context)
        val stored = p.getString("ha_access_token", null)
        val exp = p.getLong("ha_token_expires_at", 0L)
        if (!stored.isNullOrBlank() && now < exp - 120_000L) {
            cachedToken = stored
            cachedExpiresAt = exp
            return stored
        }
        return null
    }

    class RefreshTokenRejected : Exception()

    fun getAccessToken(context: Context, forceRefresh: Boolean = false): String? = try {
        cameraAccessToken(context, forceRefresh)
    } catch (_: RefreshTokenRejected) {
        null
    }

    fun cameraAccessToken(context: Context, forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            peekAccessToken(context)?.let { return it }
        }
        synchronized(tokenLock) {
            val p = prefs(context)
            if (!forceRefresh) {
                peekAccessToken(context)?.let { return it }
            }
            val refresh = p.getString("ha_refresh_token", null) ?: return null
            val clientId = p.getString("ha_client_id", ANDROID_CLIENT_ID) ?: ANDROID_CLIENT_ID
            val candidates = endpoints(context)
            val refreshed = refreshOAuthToken(candidates, refresh, clientId) ?: return null
            cachedToken = refreshed.first
            cachedExpiresAt = refreshed.second
            p.edit()
                .putString("ha_access_token", refreshed.first)
                .putLong("ha_token_expires_at", refreshed.second)
                .apply()
            return refreshed.first
        }
    }

    private fun refreshOAuthToken(endpoints: List<String>, refreshToken: String, clientId: String): Pair<String, Long>? {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        val calls = endpoints.mapNotNull { endpoint ->
            val url = endpoint.toHttpUrlOrNull() ?: return@mapNotNull null
            val request = Request.Builder().url(url.newBuilder().addPathSegments("auth/token").build()).post(form).build()
            httpClient.newCall(request).apply { timeout().timeout(8, TimeUnit.SECONDS) }
        }
        if (calls.isEmpty()) return null
        val result = AtomicReference<Pair<String, Long>?>(null)
        val rejected = AtomicBoolean(false)
        val pending = AtomicInteger(calls.size)
        val done = CountDownLatch(1)
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (pending.decrementAndGet() == 0) done.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful && it.code != 400) return
                        val body = JSONObject(it.body?.string().orEmpty())
                        if (!it.isSuccessful) {
                            if (body.optString("error") == "invalid_grant") rejected.set(true)
                            return
                        }
                        val token = body.optString("access_token")
                        if (token.isBlank()) return
                        val expiresAt = System.currentTimeMillis() + body.optLong("expires_in", 1800L) * 1000L
                        if (result.compareAndSet(null, token to expiresAt)) done.countDown()
                    }
                } catch (_: Exception) {
                    // The other endpoint may still return a usable token.
                } finally {
                    if (pending.decrementAndGet() == 0) done.countDown()
                }
            }
        }
        return try {
            calls.forEach { it.enqueue(callback) }
            done.await(8, TimeUnit.SECONDS)
            val token = result.get()
            if (token == null && rejected.get()) throw RefreshTokenRejected()
            token.also { if (it == null) Log.w("DoorbellCompanion", "HA token refresh failed") }
        } finally {
            calls.forEach { it.cancel() }
        }
    }

    fun syncPushTokenIfRegistered(context: Context, newToken: String) {
        ioExecutor.execute {
            synchronized(pushLock) {
                val p = prefs(context)
                if (p.contains(PENDING_REVOCATION)) return@synchronized
                val webhookId = p.getString("ha_webhook_id", null)?.takeIf { it.isNotBlank() } ?: return@synchronized
                val pushUrl = p.getString("ha_push_url", null)?.takeIf { it.isNotBlank() } ?: return@synchronized
                val candidates = endpoints(context)
                if (candidates.isEmpty()) return@synchronized
                try {
                    val ok = candidates.any { endpoint ->
                        try {
                            updateRegistration(context, endpoint, webhookId, pushUrl, newToken)
                        } catch (_: IOException) {
                            false
                        }
                    }
                    Log.i("DoorbellCompanion", "Push token sync via webhook: ok=$ok")
                    if (ok) {
                        p.edit().putString("ha_registered_push_token", newToken).apply()
                    } else {
                        setupPushAutomation(context)
                    }
                } catch (e: Exception) {
                    Log.w("DoorbellCompanion", "Push token sync failed: ${e.message}")
                }
            }
        }
    }

    fun discoverDoorbellsAndWarmup(context: Context): List<DoorbellInfo> {
        val candidates = endpoints(context)
        if (candidates.isEmpty()) error("Home Assistant not configured")
        val p = prefs(context)
        var token = getAccessToken(context) ?: error("Could not authenticate with Home Assistant")
        val endpoint = firstReachableEndpoint(candidates, token) ?: candidates.first()

        var results = runWarmupSession(endpoint, token)
        if (results == null) {
            // The cached access token was rejected. Refresh it and retry once.
            token = getAccessToken(context, forceRefresh = true) ?: error("Authentication rejected by Home Assistant")
            val retryEndpoint = firstReachableEndpoint(candidates, token) ?: endpoint
            results = runWarmupSession(retryEndpoint, token)
                ?: error("Authentication rejected by Home Assistant")
        }

        val (doorbells, msgMap) = results
        val arr = JSONArray()
        for (db in doorbells) {
            arr.put(
                JSONObject()
                    .put("camera_entity_id", db.cameraEntityId)
                    .put("name", db.name)
                    .put("ring_entity_ids", JSONArray(db.ringEntityIds))
                    .put("native_ring_events", JSONArray(db.nativeRingEvents))
            )
        }

        val editor = p.edit().putString("ha_doorbells", arr.toString())
        val baseUrl = getBaseUrl(context)?.trimEnd('/')

        val configResult = msgMap[4]?.optJSONObject("result")
        configResult?.optString("internal_url")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let(::normalizeBaseUrl)
            ?.takeIf { !it.equals(baseUrl, ignoreCase = true) }
            ?.let { advertised ->
                val existingInternal = getInternalUrl(context)
                val existingIsIp = existingInternal != null && !existingInternal.contains(".local", ignoreCase = true)
                val advertisedIsLocal = advertised.contains(".local", ignoreCase = true)
                if (!(existingIsIp && advertisedIsLocal)) {
                    editor.putString("ha_internal_url", advertised)
                }
            }

        configResult?.optString("external_url")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let(::normalizeBaseUrl)
            ?.let { editor.putString("ha_external_url", it) }

        msgMap[900]?.optJSONObject("result")?.optJSONObject("configuration")?.optJSONArray("iceServers")?.let {
            editor.putString("ha_ice_servers", it.toString())
            editor.putLong("ha_ice_servers_time", System.currentTimeMillis())
        }
        val twoWayCameras = mutableSetOf<String>()
        doorbells.forEachIndexed { idx, db ->
            if (msgMap[1000 + idx]?.optJSONObject("result")?.optBoolean("two_way_audio", false) == true) {
                twoWayCameras.add(db.cameraEntityId)
            }
        }
        editor.putStringSet("ha_two_way_cameras", twoWayCameras)
        val dataChannels = JSONObject()
        doorbells.forEachIndexed { idx, db ->
            // Keep the previous label if this camera's config did not arrive before the warmup finished.
            val label = msgMap[2000 + idx]?.let { it.optJSONObject("result")?.optString("dataChannel") }
                ?: dataChannel(context, db.cameraEntityId)
            label?.takeIf { it.isNotBlank() }?.let { dataChannels.put(db.cameraEntityId, it) }
        }
        editor.putString("ha_data_channels", dataChannels.toString())
        editor.apply()
        return doorbells
    }

    /** Returns null specifically on auth_invalid so caller can force-refresh and retry once. */
    private fun runWarmupSession(
        baseUrl: String,
        token: String,
    ): Pair<List<DoorbellInfo>, Map<Int, JSONObject>>? {
        val results = ConcurrentHashMap<Int, JSONObject>()
        val phase1Ids = listOf(1, 2, 3, 4)
        val phase2Started = AtomicBoolean(false)
        val expectedPhase2Ids = ConcurrentHashMap.newKeySet<Int>()
        val doorbellsRef = AtomicReference<List<DoorbellInfo>>(emptyList())
        val authRejected = AtomicBoolean(false)
        val errorRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        val ws = httpClient.newWebSocket(Request.Builder().url(wsUrl(baseUrl)).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "auth_required" -> {
                            webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                        }
                        "auth_invalid" -> {
                            authRejected.set(true)
                            latch.countDown()
                        }
                        "auth_ok" -> {
                            webSocket.send(JSONObject().put("id", 1).put("type", "config/device_registry/list").toString())
                            webSocket.send(JSONObject().put("id", 2).put("type", "config/entity_registry/list").toString())
                            webSocket.send(JSONObject().put("id", 3).put("type", "get_states").toString())
                            webSocket.send(JSONObject().put("id", 4).put("type", "get_config").toString())
                        }
                        "result" -> {
                            val id = msg.optInt("id")
                            results[id] = msg

                            if (phase1Ids.all { results.containsKey(it) } && phase2Started.compareAndSet(false, true)) {
                                val devices = results[1]?.optJSONArray("result") ?: JSONArray()
                                val entities = results[2]?.optJSONArray("result") ?: JSONArray()
                                val states = results[3]?.optJSONArray("result") ?: JSONArray()
                                val version = results[4]?.optJSONObject("result")?.optString("version")
                                val doorbells = parseDoorbells(devices, entities, states, version)
                                doorbellsRef.set(doorbells)

                                var nextId = 10
                                for (db in doorbells) {
                                    // Keep preload_stream=false so Protect flushes an immediate IDR keyframe on play
                                    webSocket.send(
                                        JSONObject()
                                            .put("id", nextId++)
                                            .put("type", "camera/update_prefs")
                                            .put("entity_id", db.cameraEntityId)
                                            .put("preload_stream", false)
                                            .toString()
                                    )
                                }
                                val primary = doorbells.firstOrNull()?.cameraEntityId
                                if (primary != null) {
                                    expectedPhase2Ids.add(900)
                                    webSocket.send(
                                        JSONObject().put("id", 900).put("type", "camera/webrtc/get_client_config")
                                            .put("entity_id", primary).toString()
                                    )
                                }
                                doorbells.forEachIndexed { idx, db ->
                                    val capId = 1000 + idx
                                    expectedPhase2Ids.add(capId)
                                    webSocket.send(
                                        JSONObject().put("id", capId).put("type", "camera/capabilities")
                                            .put("entity_id", db.cameraEntityId).toString()
                                    )
                                    // Some cameras, such as Nest WebRTC models, need a data channel in the offer.
                                    val configId = 2000 + idx
                                    expectedPhase2Ids.add(configId)
                                    webSocket.send(
                                        JSONObject().put("id", configId).put("type", "camera/webrtc/get_client_config")
                                            .put("entity_id", db.cameraEntityId).toString()
                                    )
                                }
                                if (expectedPhase2Ids.isEmpty()) latch.countDown()
                            } else if (phase2Started.get() && expectedPhase2Ids.isNotEmpty() &&
                                expectedPhase2Ids.all { results.containsKey(it) }
                            ) {
                                latch.countDown()
                            }
                        }
                    }
                } catch (e: Exception) {
                    errorRef.set(e.message ?: "Failed to parse Home Assistant response")
                    latch.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                errorRef.set(t.message ?: "WebSocket connection failed")
                latch.countDown()
            }
        })

        val completed = latch.await(15, TimeUnit.SECONDS)
        ws.close(1000, null)
        if (authRejected.get()) return null
        errorRef.get()?.let { error(it) }
        if (!completed && !phase2Started.get()) error("Timed out connecting to Home Assistant")
        return doorbellsRef.get() to results
    }

    private fun parseDoorbells(
        devices: JSONArray, entities: JSONArray, states: JSONArray, version: String?,
    ): List<DoorbellInfo> {
        val deviceNames = mutableMapOf<String, String>()
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            val id = d.optString("id")
            val name = d.optString("name_by_user").takeIf { it.isNotBlank() && it != "null" }
                ?: d.optString("name").takeIf { it.isNotBlank() && it != "null" }
            if (id.isNotBlank() && name != null) deviceNames[id] = name
        }

        val friendlyNames = mutableMapOf<String, String>()
        val doorbellStates = mutableSetOf<String>()
        val ringEvents = mutableSetOf<String>()
        for (i in 0 until states.length()) {
            val s = states.optJSONObject(i) ?: continue
            val eid = s.optString("entity_id")
            val attributes = s.optJSONObject("attributes")
            val fn = attributes?.optString("friendly_name")
            if (eid.isNotBlank() && !fn.isNullOrBlank()) friendlyNames[eid] = fn
            if (attributes?.optString("device_class") == "doorbell") doorbellStates.add(eid)
            val eventTypes = attributes?.optJSONArray("event_types") ?: continue
            if ((0 until eventTypes.length()).any { eventTypes.optString(it) == "ring" }) ringEvents.add(eid)
        }

        // The event.received trigger became available without opting in to Labs in 2026.7.
        val versionParts = version.orEmpty().split('.')
        val year = versionParts.first().toIntOrNull() ?: 0
        val month = versionParts.getOrNull(1)?.toIntOrNull() ?: 0
        val supportsEventTrigger = year > 2026 || (year == 2026 && month >= 7)

        val camerasByDevice = mutableMapOf<String, MutableList<JSONObject>>()
        val ringsByDevice = mutableMapOf<String, MutableList<String>>()
        val standaloneCameras = mutableListOf<JSONObject>()

        for (i in 0 until entities.length()) {
            val e = entities.optJSONObject(i) ?: continue
            if (!e.isNull("disabled_by")) continue
            val entityId = e.optString("entity_id")
            val deviceId = e.optString("device_id")
            val translationKey = e.optString("translation_key")
            val origClass = e.optString("original_device_class")
            // Reolink only creates its Visitor sensor for doorbell channels, and it has no device class.
            val reolinkVisitor = entityId.startsWith("binary_sensor.") &&
                e.optString("platform") == "reolink" && translationKey == "visitor"

            if (entityId.startsWith("camera.")) {
                if (deviceId.isNotBlank() && deviceId != "null") {
                    camerasByDevice.getOrPut(deviceId) { mutableListOf() }.add(e)
                } else {
                    standaloneCameras.add(e)
                }
            } else if ((entityId.startsWith("binary_sensor.") || entityId.startsWith("event.")) &&
                (entityId in doorbellStates || reolinkVisitor || translationKey == "doorbell" || origClass == "doorbell" ||
                    e.optString("device_class") == "doorbell" || entityId.endsWith("_doorbell"))
            ) {
                if (deviceId.isNotBlank() && deviceId != "null") {
                    ringsByDevice.getOrPut(deviceId) { mutableListOf() }.add(entityId)
                    if (entityId.startsWith("event.") && e.optString("platform") == "unifiprotect") {
                        ringEvents.add(entityId)
                    }
                }
            }
        }

        val doorbells = mutableListOf<DoorbellInfo>()
        for ((deviceId, ringIds) in ringsByDevice) {
            val cams = camerasByDevice[deviceId] ?: continue
            val chosen = cams.minBy {
                val uid = it.optString("unique_id")
                val eid = it.optString("entity_id")
                when {
                    uid.endsWith("_1") || eid.contains("medium") -> 0
                    uid.endsWith("_0") -> 1
                    eid.contains("package") || uid.endsWith("_3") -> 3
                    else -> 2
                }
            }
            val camId = chosen.optString("entity_id")
            val events = ringIds.filter { it.startsWith("event.") }
            val preferredRings = events.ifEmpty { ringIds }
            val nativeEvents = if (supportsEventTrigger) events.filter { it in ringEvents }.toSet() else emptySet()
            val name = deviceNames[deviceId] ?: friendlyNames[camId] ?: camId
            doorbells.add(DoorbellInfo(camId, name, preferredRings, nativeEvents))
        }

        if (doorbells.isEmpty()) {
            for (c in camerasByDevice.values.flatten() + standaloneCameras) {
                val camId = c.optString("entity_id")
                val devId = c.optString("device_id")
                val name = deviceNames[devId] ?: friendlyNames[camId] ?: camId
                doorbells.add(DoorbellInfo(camId, name, emptyList()))
            }
        }

        return doorbells.sortedBy { it.name.lowercase() }
    }

    fun getSavedDoorbells(context: Context): List<DoorbellInfo> {
        val raw = prefs(context).getString("ha_doorbells", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val rings = o.optJSONArray("ring_entity_ids") ?: JSONArray()
                val nativeEvents = o.optJSONArray("native_ring_events") ?: JSONArray()
                DoorbellInfo(
                    cameraEntityId = o.optString("camera_entity_id"),
                    name = o.optString("name", "Doorbell"),
                    ringEntityIds = (0 until rings.length()).map { rings.getString(it) },
                    nativeRingEvents = (0 until nativeEvents.length()).map { nativeEvents.getString(it) }.toSet(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun supportsTwoWayAudio(context: Context, cameraEntityId: String): Boolean =
        cameraEntityId in (prefs(context).getStringSet("ha_two_way_cameras", emptySet()) ?: emptySet())

    /** Data channel label Home Assistant asks for in this camera's WebRTC offer (Nest WebRTC cameras need one). */
    fun dataChannel(context: Context, cameraEntityId: String): String? = try {
        JSONObject(prefs(context).getString("ha_data_channels", null) ?: "{}")
            .optString(cameraEntityId).takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun saveDataChannel(context: Context, cameraEntityId: String, label: String?) {
        val p = prefs(context)
        val channels = try {
            JSONObject(p.getString("ha_data_channels", null) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        if (label.isNullOrBlank()) channels.remove(cameraEntityId) else channels.put(cameraEntityId, label)
        p.edit().putString("ha_data_channels", channels.toString()).apply()
    }

    fun hasAnyTwoWayAudio(context: Context): Boolean =
        !(prefs(context).getStringSet("ha_two_way_cameras", emptySet()).isNullOrEmpty())

    /** Returns camera entity IDs explicitly unchecked by the user (empty by default = all selected). */
    fun getDisabledCameraIds(context: Context): Set<String> =
        prefs(context).getStringSet("ha_disabled_cameras", emptySet())?.toSet() ?: emptySet()

    fun setDoorbellEnabled(context: Context, cameraEntityId: String, enabled: Boolean) {
        val updated = HashSet(getDisabledCameraIds(context))
        if (enabled) updated.remove(cameraEntityId) else updated.add(cameraEntityId)
        prefs(context).edit().putStringSet("ha_disabled_cameras", updated).apply()
    }

    fun getEnabledDoorbells(context: Context): List<DoorbellInfo> {
        val disabled = getDisabledCameraIds(context)
        return getSavedDoorbells(context).filter { it.cameraEntityId !in disabled }
    }

    /** Picks the explicit push camera if provided, else the first selected doorbell (falling back to the first camera). */
    fun resolveCameraForPush(context: Context, data: Map<String, String>): Pair<String?, String> {
        val directEntity = data["camera_entity_id"]?.takeIf { it.isNotBlank() }
        val directName = data["doorbell_name"]?.takeIf { it.isNotBlank() }
        if (directEntity != null && directName != null) {
            return directEntity to directName
        }

        val allDoorbells = getSavedDoorbells(context)
        if (directEntity != null) {
            val name = allDoorbells.firstOrNull { it.cameraEntityId == directEntity }?.name ?: "Doorbell"
            return directEntity to name
        }

        val first = getEnabledDoorbells(context).firstOrNull() ?: allDoorbells.firstOrNull()
        return first?.cameraEntityId to (directName ?: first?.name ?: "Doorbell")
    }

    private fun subscriptionFingerprint(context: Context): String {
        val routes = sortedMapOf<String, String>()
        for (db in getEnabledDoorbells(context)) {
            for (ringEntity in db.ringEntityIds) {
                routes[ringEntity] = "${db.cameraEntityId}|${db.name}"
            }
        }
        return routes.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    fun setupPushAutomation(context: Context, refreshTriggers: Boolean = false): String = synchronized(pushLock) {
        val p = prefs(context)
        check(!p.contains(PENDING_REVOCATION)) { "Run Disconnect again to finish signing out of Home Assistant" }
        val candidates = endpoints(context)
        if (candidates.isEmpty()) error("Home Assistant not configured")
        val fcmToken = p.getString("fcm_token", null)?.takeIf { it.isNotBlank() }
            ?: error("Firebase token not ready yet")
        val pushUrl = PUSH_RELAY_URL
        val notifyService = "notify.${deviceSlug(context)}"
        val fingerprint = subscriptionFingerprint(context)
        var webhookId = p.getString("ha_webhook_id", null)

        if (!refreshTriggers &&
            !webhookId.isNullOrBlank() &&
            p.getString("ha_push_url", null) == pushUrl &&
            p.getString("ha_registered_push_token", null) == fcmToken &&
            p.getString("ha_notify_service", null) == notifyService &&
            p.getString(SYNCED_SUBSCRIPTION, null) == fingerprint
        ) {
            return@synchronized notifyService
        }

        val token = getAccessToken(context) ?: error("Not authenticated with Home Assistant")
        val endpoint = firstReachableEndpoint(candidates, token)
            ?: error("Could not reach Home Assistant")

        val needsTokenUpdate = p.getString("ha_registered_push_token", null) != fcmToken ||
            p.getString("ha_push_url", null) != pushUrl
        if (!webhookId.isNullOrBlank() && (needsTokenUpdate || refreshTriggers)) {
            val ok = updateRegistration(context, endpoint, webhookId, pushUrl, fcmToken)
            if (!ok) webhookId = null
        }

        if (webhookId.isNullOrBlank()) {
            val regBody = JSONObject()
                .put("device_id", registrationDeviceId(context))
                .put("app_id", "nl.niek.doorbellcompanion")
                .put("app_name", "Doorbell Companion")
                .put("app_version", appVersion(context))
                .put("device_name", deviceName(context))
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("os_name", "Android")
                .put("os_version", Build.VERSION.RELEASE)
                .put("supports_encryption", false)
                .put("app_data", JSONObject().put("push_url", pushUrl).put("push_token", fcmToken))

            val req = haRequest(endpoint, token, "/api/mobile_app/registrations")
                .post(regBody.toString().toRequestBody(jsonMedia))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("mobile_app registration failed (HTTP ${resp.code})")
                val body = JSONObject(resp.body?.string() ?: "{}")
                webhookId = body.optString("webhook_id").takeIf { it.isNotBlank() }
                    ?: error("Home Assistant did not return a webhook_id")
            }
            p.edit().putString("ha_webhook_id", webhookId).remove("ha_notify_service").apply()
            Thread.sleep(500)
        }

        syncSharedAutomation(context, endpoint, token, refreshTriggers)
        val routes = JSONObject()
        for (db in getEnabledDoorbells(context)) {
            for (ringEntity in db.ringEntityIds) {
                routes.put(
                    ringEntity,
                    JSONObject()
                        .put("camera_entity_id", db.cameraEntityId)
                        .put("doorbell_name", db.name)
                )
            }
        }
        val subscription = JSONObject().put("service", notifyService).put("doorbells", routes)
        val sensor = JSONObject()
            .put("type", "sensor")
            .put("unique_id", "doorbell_companion_subscription")
            .put("name", "Doorbell subscription")
            .put("entity_category", "diagnostic")
            .put("state", routes.length())
            .put("attributes", JSONObject().put("doorbell_companion_subscription", subscription))
        val command = JSONObject().put("type", "register_sensor").put("data", sensor)
        val request = Request.Builder().url("$endpoint/api/webhook/$webhookId")
            .post(command.toString().toRequestBody(jsonMedia)).build()
        httpClient.newCall(request).execute().use { response ->
            val result = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            check(response.isSuccessful && result?.optBoolean("success") == true) {
                "Home Assistant could not save this phone's doorbell subscriptions"
            }
        }
        p.edit()
            .putString("ha_push_url", pushUrl)
            .putString("ha_registered_push_token", fcmToken)
            .putString("ha_notify_service", notifyService)
            .putString(SYNCED_SUBSCRIPTION, fingerprint)
            .apply()
        notifyService
    }

    private fun syncSharedAutomation(context: Context, baseUrl: String, token: String, refreshTriggers: Boolean) {
        val asset = FlutterInjector.instance().flutterLoader().getLookupKeyForAsset("assets/doorbell_automation.json")
        val config = context.assets.open(asset).bufferedReader().use { JSONObject(it.readText()) }
        val path = "/api/config/automation/config/${config.getString("id")}"
        val existing = httpClient.newCall(haRequest(baseUrl, token, path).get().build()).execute().use { resp ->
            val data = resp.body?.string().orEmpty()
            when {
                resp.isSuccessful -> JSONObject(data)
                resp.code == 404 || (resp.code == 400 && JSONObject(data).optString("message") == "Resource not found") -> null
                else -> error("Could not read the shared Home Assistant automation (HTTP ${resp.code})")
            }
        }
        if (!refreshTriggers && existing != null) return
        val triggers = config.getJSONArray("triggers")
        val ringTriggers = sortedMapOf<String, JSONObject>()
        for (db in getSavedDoorbells(context)) {
            for (ringEntity in db.ringEntityIds) {
                val trig = JSONObject().put("id", ringEntity)
                if (ringEntity in db.nativeRingEvents) {
                    trig.put("trigger", "event.received")
                    trig.put("target", JSONObject().put("entity_id", ringEntity))
                    trig.put("options", JSONObject().put("event_type", JSONArray().put("ring")))
                } else {
                    trig.put("trigger", "state").put("entity_id", ringEntity)
                    if (ringEntity.startsWith("binary_sensor.")) {
                        trig.put("to", "on")
                    } else {
                        trig.put("not_from", JSONArray().put("unknown").put("unavailable"))
                        trig.put("not_to", JSONArray().put("unknown").put("unavailable"))
                    }
                }
                ringTriggers[ringEntity] = trig
            }
        }
        if (ringTriggers.isEmpty() && existing != null) return
        ringTriggers.values.forEach { triggers.put(it) }
        if (existing != null && config.keys().asSequence().all {
                it == "id" || jsonEquals(config.opt(it), existing.opt(it))
            }
        ) return
        val request = haRequest(baseUrl, token, path).post(config.toString().toRequestBody(jsonMedia)).build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Could not save HA automation (HTTP ${resp.code})")
        }
    }

    private fun jsonEquals(a: Any?, b: Any?): Boolean = when {
        a === b -> true
        a is JSONObject && b is JSONObject ->
            a.length() == b.length() && a.keys().asSequence().all { key -> b.has(key) && jsonEquals(a.opt(key), b.opt(key)) }
        a is JSONArray && b is JSONArray ->
            a.length() == b.length() && (0 until a.length()).all { i -> jsonEquals(a.opt(i), b.opt(i)) }
        else -> a == b
    }

    fun sendTestPushViaHa(context: Context, camera: String? = null) {
        val candidates = endpoints(context)
        if (candidates.isEmpty()) error("Home Assistant not configured")
        val token = getAccessToken(context) ?: error("Not authenticated")
        val endpoint = firstReachableEndpoint(candidates, token) ?: error("Could not reach Home Assistant")
        val notifyService = prefs(context).getString("ha_notify_service", null)?.takeIf { it.isNotBlank() }
            ?: setupPushAutomation(context)
        val serviceSlug = notifyService.removePrefix("notify.")
        val (camId, name) = resolveCameraForPush(context, mapOf("camera_entity_id" to camera.orEmpty()))
        val payload = JSONObject()
            .put("title", name)
            .put("message", "Someone is at the door")
            .put(
                "data",
                JSONObject()
                    .put("type", "doorbell_ring")
                    .put("camera_entity_id", camId ?: "")
                    .put("doorbell_name", name)
                    .put("event_ms", System.currentTimeMillis().toString())
            )
        val req = haRequest(endpoint, token, "/api/services/notify/$serviceSlug")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            Log.i("DoorbellCompanion", "Test push via $notifyService -> HTTP ${resp.code}")
            if (!resp.isSuccessful) error("HA notify failed: HTTP ${resp.code}")
        }
    }

    private fun haRequest(baseUrl: String, token: String, path: String): Request.Builder =
        Request.Builder().url("$baseUrl$path").header("Authorization", "Bearer $token")

    private fun deleteRegistration(endpoint: String, token: String, entryId: String) {
        val path = "/api/config/config_entries/entry/$entryId"
        try {
            cleanupCall(haRequest(endpoint, token, path).delete().build()).use { response ->
                when {
                    response.isSuccessful || response.code == 404 -> Unit
                    response.code == 401 || response.code == 403 -> error("A Home Assistant administrator must remove the phone registration")
                    else -> error("Could not remove the phone registration (HTTP ${response.code})")
                }
            }
        } catch (_: IOException) {
            error("Could not remove the phone registration. Check the connection and try again.")
        }
    }

    private fun updateRegistration(
        context: Context,
        baseUrl: String,
        webhookId: String,
        pushUrl: String,
        fcmToken: String,
    ): Boolean {
        val payload = JSONObject().put("type", "update_registration").put(
            "data",
            JSONObject()
                .put("app_version", appVersion(context))
                .put("device_name", deviceName(context))
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("os_version", Build.VERSION.RELEASE)
                .put("app_data", JSONObject().put("push_url", pushUrl).put("push_token", fcmToken)),
        )
        val req = Request.Builder()
            .url("$baseUrl/api/webhook/$webhookId")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            val body = try { resp.body?.string().orEmpty() } catch (_: Exception) { "" }
            val valid = resp.isSuccessful && body.trim().startsWith("{")
            Log.i("DoorbellCompanion", "updateRegistration -> HTTP ${resp.code} len=${body.length} valid=$valid")
            valid
        }
    }

    private fun appVersion(context: Context): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName?.takeIf { it.isNotBlank() } ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }

    /**
     * Preserves legacy Build.MODEL-based registration IDs for existing installs until disconnect,
     * while giving new installs a unique per-install ID (matching iOS) so identical phone models
     * in the same household never collide.
     */
    private fun deviceIdentity(context: Context): Pair<String, String> {
        val p = prefs(context)
        val hasExistingLegacyReg = p.getBoolean("ha_legacy_device", false) ||
            (!p.getString("ha_webhook_id", null).isNullOrBlank() && p.getString("device_id", null).isNullOrBlank())
        if (hasExistingLegacyReg) {
            p.edit().putBoolean("ha_legacy_device", true).apply()
            return "doorbell_companion_${sanitizeSlug(Build.MODEL)}" to "Doorbell Companion ${Build.MODEL}"
        }
        val id = p.getString("device_id", null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().replace("-", "").take(12).lowercase().also {
                p.edit().putString("device_id", it).apply()
            }
        return id to "Doorbell Companion Android $id"
    }

    private fun sanitizeSlug(raw: String): String = raw.lowercase().replace(nonAlnum, "_").trim('_')
    private fun deviceName(context: Context): String = deviceIdentity(context).second
    private fun registrationDeviceId(context: Context): String = deviceIdentity(context).first
    private fun deviceSlug(context: Context): String = "mobile_app_${sanitizeSlug(deviceName(context))}"
}
