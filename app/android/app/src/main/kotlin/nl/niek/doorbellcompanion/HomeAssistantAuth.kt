package nl.niek.doorbellcompanion

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
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
)

object HomeAssistantAuth {
    const val FALLBACK_CLIENT_ID = "https://doorbell-companion.nivadema.workers.dev"
    const val ANDROID_CLIENT_ID = "$FALLBACK_CLIENT_ID/android"
    const val PUSH_RELAY_URL = "https://doorbell-companion.nivadema.workers.dev/push"
    private const val PREFS = "doorbell_companion"
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

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConfigured(context: Context): Boolean {
        val p = prefs(context)
        return !p.getString("ha_url", null).isNullOrBlank() &&
            !p.getString("ha_refresh_token", null).isNullOrBlank()
    }

    fun getBaseUrl(context: Context): String? = prefs(context).getString("ha_url", null)

    fun getInternalUrl(context: Context): String? {
        val p = prefs(context)
        val internal = p.getString("ha_internal_url", null)?.takeIf { it.isNotBlank() } ?: return null
        val external = p.getString("ha_url", null)?.trimEnd('/')
        return internal.trimEnd('/').takeIf { !it.equals(external, ignoreCase = true) }
    }

    fun saveInternalUrl(context: Context, rawUrl: String?) {
        val normalized = rawUrl?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            ?.let(::normalizeBaseUrl) ?: return
        val base = getBaseUrl(context)?.trimEnd('/')
        if (normalized.equals(base, ignoreCase = true)) return
        prefs(context).edit().putString("ha_internal_url", normalized).apply()
    }

    fun disconnect(context: Context) {
        val p = prefs(context)
        val baseUrl = p.getString("ha_url", null)
        val endpoints = listOfNotNull(getInternalUrl(context), baseUrl).distinct()
        val storedAccess = p.getString("ha_access_token", null)
        val storedExp = p.getLong("ha_token_expires_at", 0L)
        val validAccess = storedAccess?.takeIf { System.currentTimeMillis() < storedExp - 60_000L }
        val refresh = p.getString("ha_refresh_token", null)
        val clientId = p.getString("ha_client_id", FALLBACK_CLIENT_ID) ?: FALLBACK_CLIENT_ID
        val autoId = automationId()

        synchronized(tokenLock) {
            cachedToken = null
            cachedExpiresAt = 0L
            p.edit()
                .remove("ha_url")
                .remove("ha_internal_url")
                .remove("ha_client_id")
                .remove("ha_llat")
                .remove("ha_access_token")
                .remove("ha_refresh_token")
                .remove("ha_token_expires_at")
                .remove("ha_doorbells")
                .remove("ha_disabled_cameras")
                .remove("ha_default_camera")
                .remove("ha_ice_servers")
                .remove("ha_ice_servers_time")
                .remove("ha_notify_service")
                .remove("ha_webhook_id")
                .remove("ha_push_url")
                .remove("ha_two_way_audio")
                .remove("ha_two_way_cameras")
                .apply()
        }

        if (!baseUrl.isNullOrBlank()) {
            ioExecutor.execute {
                try {
                    val token = validAccess
                        ?: refresh?.takeIf { it.isNotBlank() }?.let { refreshOAuthToken(endpoints, it, clientId)?.first }
                    if (!token.isNullOrBlank()) {
                        deleteAutomation(baseUrl, token, autoId)
                    }
                } catch (_: Exception) {}
            }
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

    fun supportsPkce(baseUrl: String): Boolean = try {
        val request = Request.Builder().url("$baseUrl/.well-known/oauth-authorization-server").build()
        val call = httpClient.newCall(request)
        call.timeout().timeout(2, TimeUnit.SECONDS)
        call.execute().use { response ->
            val methods = if (response.isSuccessful) {
                JSONObject(response.body?.string().orEmpty()).optJSONArray("code_challenge_methods_supported")
            } else null
            methods != null && (0 until methods.length()).any { methods.optString(it) == "S256" }
        }
    } catch (_: Exception) {
        false
    }

    fun exchangeCode(context: Context, baseUrl: String, code: String, clientId: String, verifier: String?): Boolean {
        val cleanUrl = normalizeBaseUrl(baseUrl)
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .apply { if (verifier != null) add("code_verifier", verifier) }
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
                        prefs.edit()
                            .apply { if (prefs.getString("ha_url", null) != cleanUrl) remove("ha_internal_url") }
                            .remove("ha_llat")
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

    fun getAccessToken(context: Context, forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            peekAccessToken(context)?.let { return it }
        }
        synchronized(tokenLock) {
            val p = prefs(context)
            if (!forceRefresh) {
                peekAccessToken(context)?.let { return it }
            }
            val baseUrl = p.getString("ha_url", null) ?: return null
            val refresh = p.getString("ha_refresh_token", null) ?: return null
            val clientId = p.getString("ha_client_id", FALLBACK_CLIENT_ID) ?: FALLBACK_CLIENT_ID
            val endpoints = listOfNotNull(getInternalUrl(context), baseUrl).distinct()
            val refreshed = refreshOAuthToken(endpoints, refresh, clientId) ?: return null
            cachedToken = refreshed.first
            cachedExpiresAt = refreshed.second
            p.edit()
                .remove("ha_llat")
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
        val pending = AtomicInteger(calls.size)
        val done = CountDownLatch(1)
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (pending.decrementAndGet() == 0) done.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) return
                        val body = JSONObject(it.body?.string().orEmpty())
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
            result.get().also { if (it == null) Log.w("DoorbellCompanion", "HA token refresh failed") }
        } finally {
            calls.forEach { it.cancel() }
        }
    }

    fun syncPushTokenIfRegistered(context: Context, newToken: String) {
        val p = prefs(context)
        val baseUrl = p.getString("ha_url", null) ?: return
        val webhookId = p.getString("ha_webhook_id", null)?.takeIf { it.isNotBlank() } ?: return
        val pushUrl = p.getString("ha_push_url", null)?.takeIf { it.isNotBlank() } ?: return
        ioExecutor.execute {
            try {
                val ok = updateRegistration(baseUrl, webhookId, pushUrl, newToken)
                Log.i("DoorbellCompanion", "Push token sync via webhook: ok=$ok")
                if (!ok) {
                    setupPushAutomation(context)
                }
            } catch (e: Exception) {
                Log.w("DoorbellCompanion", "Push token sync failed: ${e.message}")
            }
        }
    }

    fun discoverDoorbellsAndWarmup(context: Context): List<DoorbellInfo> {
        val baseUrl = getBaseUrl(context) ?: error("Home Assistant not configured")
        val p = prefs(context)
        var token = getAccessToken(context) ?: error("Could not authenticate with Home Assistant")

        var results = runWarmupSession(baseUrl, token)
        if (results == null) {
            // The cached access token was rejected. Refresh it and retry once.
            token = getAccessToken(context, forceRefresh = true) ?: error("Authentication rejected by Home Assistant")
            results = runWarmupSession(baseUrl, token)
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
            )
        }

        val editor = p.edit().putString("ha_doorbells", arr.toString())
        doorbells.firstOrNull()?.cameraEntityId?.let { editor.putString("ha_default_camera", it) }

        msgMap[4]?.optJSONObject("result")?.optString("internal_url")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let(::normalizeBaseUrl)
            ?.takeIf { !it.equals(baseUrl.trimEnd('/'), ignoreCase = true) }
            ?.let { editor.putString("ha_internal_url", it) }

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
            .remove("ha_two_way_audio")
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
                                val doorbells = parseDoorbells(devices, entities, states)
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

        latch.await(15, TimeUnit.SECONDS)
        ws.close(1000, null)
        if (authRejected.get()) return null
        errorRef.get()?.let { error(it) }
        return doorbellsRef.get() to results
    }

    private fun parseDoorbells(devices: JSONArray, entities: JSONArray, states: JSONArray): List<DoorbellInfo> {
        val deviceNames = mutableMapOf<String, String>()
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            val id = d.optString("id")
            val name = d.optString("name_by_user").takeIf { it.isNotBlank() && it != "null" }
                ?: d.optString("name").takeIf { it.isNotBlank() && it != "null" }
            if (id.isNotBlank() && name != null) deviceNames[id] = name
        }

        val friendlyNames = mutableMapOf<String, String>()
        for (i in 0 until states.length()) {
            val s = states.optJSONObject(i) ?: continue
            val eid = s.optString("entity_id")
            val fn = s.optJSONObject("attributes")?.optString("friendly_name")
            if (eid.isNotBlank() && !fn.isNullOrBlank()) friendlyNames[eid] = fn
        }

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

            if (entityId.startsWith("camera.")) {
                if (deviceId.isNotBlank() && deviceId != "null") {
                    camerasByDevice.getOrPut(deviceId) { mutableListOf() }.add(e)
                } else {
                    standaloneCameras.add(e)
                }
            } else if ((entityId.startsWith("binary_sensor.") || entityId.startsWith("event.")) &&
                (translationKey == "doorbell" || origClass == "doorbell")
            ) {
                if (deviceId.isNotBlank() && deviceId != "null") {
                    ringsByDevice.getOrPut(deviceId) { mutableListOf() }.add(entityId)
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
            val preferredRings = ringIds.filter { it.startsWith("binary_sensor.") }.ifEmpty { ringIds }
            val name = deviceNames[deviceId] ?: friendlyNames[camId] ?: camId
            doorbells.add(DoorbellInfo(camId, name, preferredRings))
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
                DoorbellInfo(
                    cameraEntityId = o.optString("camera_entity_id"),
                    name = o.optString("name", "Doorbell"),
                    ringEntityIds = (0 until rings.length()).map { rings.getString(it) },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun supportsTwoWayAudio(context: Context, cameraEntityId: String): Boolean =
        cameraEntityId in (prefs(context).getStringSet("ha_two_way_cameras", emptySet()) ?: emptySet())

    /** Returns camera entity IDs explicitly unchecked by the user (empty by default = all selected). */
    fun getDisabledCameraIds(context: Context): Set<String> =
        prefs(context).getStringSet("ha_disabled_cameras", emptySet())?.toSet() ?: emptySet()

    fun setDoorbellEnabled(context: Context, cameraEntityId: String, enabled: Boolean) {
        val updated = HashSet(getDisabledCameraIds(context))
        if (enabled) updated.remove(cameraEntityId) else updated.add(cameraEntityId)
        val editor = prefs(context).edit().putStringSet("ha_disabled_cameras", updated)
        val all = getSavedDoorbells(context)
        val firstSelected = all.firstOrNull { it.cameraEntityId !in updated } ?: all.firstOrNull()
        firstSelected?.let { editor.putString("ha_default_camera", it.cameraEntityId) }
        editor.apply()
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

    fun setupPushAutomation(context: Context): String {
        val baseUrl = getBaseUrl(context) ?: error("Home Assistant not configured")
        val token = getAccessToken(context) ?: error("Not authenticated with Home Assistant")
        val p = prefs(context)
        val fcmToken = p.getString("fcm_token", null)?.takeIf { it.isNotBlank() }
            ?: error("Firebase token not ready yet")
        val pushUrl = PUSH_RELAY_URL

        val devName = deviceName()
        var webhookId = p.getString("ha_webhook_id", null)

        val updated = !webhookId.isNullOrBlank() && updateRegistration(baseUrl, webhookId, pushUrl, fcmToken)
        if (!updated) {
            val regBody = JSONObject()
                .put("device_id", "doorbell_companion_${sanitizeSlug(Build.MODEL)}")
                .put("app_id", "nl.niek.doorbellcompanion")
                .put("app_name", "Doorbell Companion")
                .put("app_version", "1.0.0")
                .put("device_name", devName)
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("os_name", "Android")
                .put("os_version", Build.VERSION.RELEASE)
                .put("supports_encryption", false)
                .put("app_data", JSONObject().put("push_url", pushUrl).put("push_token", fcmToken))

            val req = haRequest(baseUrl, token, "/api/mobile_app/registrations")
                .post(regBody.toString().toRequestBody(jsonMedia))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("mobile_app registration failed (HTTP ${resp.code})")
                val body = JSONObject(resp.body?.string() ?: "{}")
                webhookId = body.optString("webhook_id").takeIf { it.isNotBlank() }
                    ?: error("Home Assistant did not return a webhook_id")
            }
            Thread.sleep(500)
        }

        val slug = deviceSlug()
        val notifyService = findMobileAppNotifyService(baseUrl, token, slug) ?: "notify.$slug"
        p.edit()
            .putString("ha_webhook_id", webhookId)
            .putString("ha_push_url", pushUrl)
            .putString("ha_notify_service", notifyService)
            .apply()

        val autoId = automationId()
        val selectedDoorbells = getEnabledDoorbells(context).filter { it.ringEntityIds.isNotEmpty() }

        // If the user unchecked all doorbells, remove the HA automation cleanly so no pushes fire
        if (selectedDoorbells.isEmpty()) {
            deleteAutomation(baseUrl, token, autoId)
            Log.i("DoorbellCompanion", "All doorbells unchecked; removed HA automation $autoId")
            return notifyService
        }

        val triggers = JSONArray()
        val chooseBranches = JSONArray()
        selectedDoorbells.forEachIndexed { idx, db ->
            val trigId = "db_$idx"
            for (ringEntity in db.ringEntityIds) {
                val trig = JSONObject()
                    .put("trigger", "state")
                    .put("entity_id", ringEntity)
                    .put("id", trigId)
                if (ringEntity.startsWith("binary_sensor.")) {
                    trig.put("to", "on")
                } else {
                    trig.put("not_from", JSONArray().put("unavailable").put("unknown"))
                    trig.put("not_to", JSONArray().put("unavailable").put("unknown"))
                }
                triggers.put(trig)
            }
            chooseBranches.put(
                JSONObject()
                    .put("conditions", JSONArray().put(JSONObject().put("condition", "trigger").put("id", trigId)))
                    .put(
                        "sequence",
                        JSONArray().put(
                            JSONObject()
                                .put("action", notifyService)
                                .put(
                                    "data",
                                    JSONObject()
                                        .put("title", db.name)
                                        .put("message", "Someone is at the door")
                                        .put(
                                            "data",
                                            JSONObject()
                                                .put("type", "doorbell_ring")
                                                .put("camera_entity_id", db.cameraEntityId)
                                                .put("doorbell_name", db.name)
                                        )
                                )
                        )
                    )
            )
        }

        val automationConfig = JSONObject()
            .put("id", autoId)
            .put("alias", "Doorbell Companion: Doorbell Call (${Build.MODEL})")
            .put("description", "Sends doorbell call notifications to Doorbell Companion")
            .put("mode", "parallel")
            .put("triggers", triggers)
            .put("conditions", JSONArray())
            .put("actions", JSONArray().put(JSONObject().put("choose", chooseBranches)))

        val autoReq = haRequest(baseUrl, token, "/api/config/automation/config/$autoId")
            .post(automationConfig.toString().toRequestBody(jsonMedia))
            .build()
        httpClient.newCall(autoReq).execute().use { resp ->
            if (!resp.isSuccessful) error("Could not save HA automation (HTTP ${resp.code})")
        }
        Log.i("DoorbellCompanion", "HA automation synced (${selectedDoorbells.size} doorbells) -> $notifyService")
        return notifyService
    }

    fun sendTestPushViaHa(context: Context) {
        val baseUrl = getBaseUrl(context) ?: error("Home Assistant not configured")
        val token = getAccessToken(context) ?: error("Not authenticated")
        val notifyService = prefs(context).getString("ha_notify_service", null)?.takeIf { it.isNotBlank() }
            ?: setupPushAutomation(context)
        val serviceSlug = notifyService.removePrefix("notify.")
        val (camId, name) = resolveCameraForPush(context, emptyMap())
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
        val req = haRequest(baseUrl, token, "/api/services/notify/$serviceSlug")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            Log.i("DoorbellCompanion", "Test push via $notifyService -> HTTP ${resp.code}")
            if (!resp.isSuccessful) error("HA notify failed: HTTP ${resp.code}")
        }
    }

    private fun haRequest(baseUrl: String, token: String, path: String): Request.Builder =
        Request.Builder().url("$baseUrl$path").header("Authorization", "Bearer $token")

    private fun deleteAutomation(baseUrl: String, token: String, autoId: String) {
        httpClient.newCall(haRequest(baseUrl, token, "/api/config/automation/config/$autoId").delete().build())
            .execute().close()
    }

    private fun updateRegistration(baseUrl: String, webhookId: String, pushUrl: String, fcmToken: String): Boolean {
        val payload = JSONObject().put("type", "update_registration").put(
            "data",
            JSONObject()
                .put("app_version", "1.0.0")
                .put("device_name", deviceName())
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

    private fun findMobileAppNotifyService(baseUrl: String, token: String, expectedSlug: String): String? =
        try {
            httpClient.newCall(haRequest(baseUrl, token, "/api/services").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val domains = JSONArray(resp.body?.string() ?: "[]")
                for (i in 0 until domains.length()) {
                    val d = domains.optJSONObject(i) ?: continue
                    if (d.optString("domain") != "notify") continue
                    val services = d.optJSONObject("services") ?: continue
                    if (services.has(expectedSlug)) return "notify.$expectedSlug"
                    val match = services.keys().asSequence().firstOrNull { it.startsWith("mobile_app_doorbell_companion") }
                    if (match != null) return "notify.$match"
                }
                null
            }
        } catch (_: Exception) {
            null
        }

    private fun sanitizeSlug(raw: String): String = raw.lowercase().replace(nonAlnum, "_").trim('_')
    private fun deviceName(): String = "Doorbell Companion ${Build.MODEL}"
    private fun deviceSlug(): String = "mobile_app_${sanitizeSlug(deviceName())}"
    private fun automationId(): String = sanitizeSlug(deviceName())
}
