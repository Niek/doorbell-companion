package nl.niek.doorbellcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private object NoOpVideoEncoderFactory : VideoEncoderFactory {
    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = null
    override fun getSupportedCodecs(): Array<VideoCodecInfo> = emptyArray()
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}

private open class SimplePeerConnectionObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(dc: DataChannel) {}
    override fun onRenegotiationNeeded() {}
}

/**
 * Process-wide singleton WebRTC factory & EGL context so incoming calls skip
 * JNI + MediaCodecList enumeration and never contend on EGL teardown across calls.
 */
object WebRtcEngine {
    private val lock = Any()
    @Volatile private var initialized = false
    lateinit var egl: EglBase
        private set
    lateinit var factory: PeerConnectionFactory
        private set
    private lateinit var adm: JavaAudioDeviceModule

    fun isReady(): Boolean = initialized

    fun ensureReady(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val app = context.applicationContext
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app)
                    .createInitializationOptions()
            )
            val rootEgl = EglBase.create()
            val audioModule = JavaAudioDeviceModule.builder(app)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            val pcFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .setVideoEncoderFactory(NoOpVideoEncoderFactory)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEgl.eglBaseContext))
                .createPeerConnectionFactory()
            egl = rootEgl
            adm = audioModule
            factory = pcFactory
            initialized = true
        }
    }

    fun warmUpAsync(context: Context) {
        if (initialized) return
        HomeAssistantAuth.ioExecutor.execute { try { ensureReady(context) } catch (_: Exception) {} }
    }
}

object HomeAssistantPrewarm {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pending: HomeAssistantRemote? = null
    private val timeoutRunnable = Runnable { cancel() }

    @Synchronized
    fun start(context: Context, cameraEntityId: String) {
        pending?.close()
        val remote = HomeAssistantRemote(context.applicationContext, cameraEntityId)
        pending = remote
        main.removeCallbacks(timeoutRunnable)
        main.postDelayed(timeoutRunnable, RingNotification.RING_TIMEOUT_MS)
        remote.connect()
    }

    @Synchronized
    fun take(expectedCameraEntityId: String?): HomeAssistantRemote? {
        val current = pending ?: return null
        if (expectedCameraEntityId != null && current.cameraEntityId != expectedCameraEntityId) {
            current.close()
            pending = null
            main.removeCallbacks(timeoutRunnable)
            return null
        }
        pending = null
        main.removeCallbacks(timeoutRunnable)
        return current
    }

    @Synchronized
    fun cancel() {
        main.removeCallbacks(timeoutRunnable)
        pending?.close()
        pending = null
    }
}

class HomeAssistantRemote(
    private val context: Context,
    val cameraEntityId: String,
    @Volatile private var onVideo: (VideoTrack) -> Unit = {},
    @Volatile private var status: (String) -> Unit = {},
    @Volatile private var onMicState: (Boolean) -> Unit = {},
    @Volatile private var onSnapshot: (Bitmap) -> Unit = {},
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val timing = CallTiming.current

    private var ws: WebSocket? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val racingSockets = mutableSetOf<WebSocket>()
    private val authRequiredSockets = mutableSetOf<WebSocket>()
    private val snapshotCalls = Collections.synchronizedList(mutableListOf<Call>())
    private val snapshotStarted = AtomicBoolean(false)
    private val snapshotDelivered = AtomicBoolean(false)
    private var candidateEndpoints: List<Pair<String, String>> = emptyList()
    private var currentToken: String? = null
    private var needInitialIceConfig = false
    private val peerSetupStarted = AtomicBoolean(false)

    private var peer: PeerConnection? = null
    private var audioTransceiver: RtpTransceiver? = null
    private var remoteAudio: AudioTrack? = null
    private var localAudio: AudioTrack? = null
    private var audioSource: AudioSource? = null

    @Volatile private var currentVideoTrack: VideoTrack? = null
    @Volatile private var latestSnapshot: Bitmap? = null
    @Volatile private var firstFrameDecoded = false
    @Volatile private var latestStatus: String = "Connecting to Home Assistant…"
    @Volatile private var answered = false
    @Volatile private var microphoneAllowed = false
    @Volatile private var micMuted = false
    @Volatile private var serverAcceptsMic = false
    @Volatile private var micAttached = false
    @Volatile private var closed = false

    private var wsAuthed = false
    private var retriedAuth = false
    private var offerSent = false
    private var localOfferSdp: String? = null
    private var sessionId: String? = null
    private var remoteDescriptionSet = false

    private val msgIdGen = AtomicInteger(1)
    private var offerMsgId = -1
    private var configMsgId = -1
    private val localCandidates = mutableListOf<IceCandidate>()
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private fun schedule(block: () -> Unit) {
        if (closed) return
        try {
            executor.execute { if (!closed) block() }
        } catch (_: Exception) {}
    }

    private fun reportStatus(msg: String) {
        latestStatus = msg
        status(msg)
    }

    fun attachListeners(
        videoListener: (VideoTrack) -> Unit,
        statusListener: (String) -> Unit,
        micStateListener: (Boolean) -> Unit = {},
        snapshotListener: (Bitmap) -> Unit = {},
    ) {
        onVideo = videoListener
        status = statusListener
        onMicState = micStateListener
        onSnapshot = snapshotListener
        statusListener(latestStatus)
        if (!firstFrameDecoded) {
            latestSnapshot?.let(snapshotListener)
        }
        currentVideoTrack?.let(videoListener)
        micStateListener(micAttached)
    }

    fun connect() {
        timing?.mark("ha_remote_start", "entity=$cameraEntityId")
        val prefs = HomeAssistantAuth.prefs(context)
        val urls = HomeAssistantAuth.endpoints(context)
        if (urls.isEmpty()) {
            reportStatus("Connect Home Assistant first")
            return
        }
        val internalUrl = HomeAssistantAuth.getInternalUrl(context)
        val endpoints = urls.mapIndexed { idx, url ->
            val label = if (url == internalUrl) "lan" else if (idx == 0) "primary" else "ext"
            label to url
        }

        val cachedIceJson = prefs.getString("ha_ice_servers", null)
        val cachedIceAge = System.currentTimeMillis() - prefs.getLong("ha_ice_servers_time", 0L)
        // STUN-only configs don't expire; TURN configs are kept warm for 6h so real rings still prewarm at t=0ms.
        val hasUsableIce = !cachedIceJson.isNullOrBlank() &&
            (!hasTurnCredentials(cachedIceJson) || cachedIceAge < 6 * 60 * 60 * 1000L)

        // Wake Wi-Fi out of 802.11 DTIM power-save (100-200ms beacon sleep) immediately at t=0ms.
        acquireLowLatencyWifiLock()

        // Start WebSocket connection(s) FIRST (~1ms, non-blocking) so TCP/TLS handshake overlaps peer setup.
        schedule {
            candidateEndpoints = endpoints
            needInitialIceConfig = !hasUsableIce
            openRacingWebSockets(endpoints)
        }
        if (hasUsableIce) {
            startPeerSetup(parseIceServers(cachedIceJson))
        }

        // Resolve token in parallel with TCP/TLS/WebSocket handshake, and kick off instant snapshot fetch.
        val immediateToken = HomeAssistantAuth.peekAccessToken(context)
        if (immediateToken != null) {
            fetchSnapshotAsync(endpoints, immediateToken)
            schedule { onTokenReady(immediateToken) }
        } else {
            HomeAssistantAuth.ioExecutor.execute {
                val token = HomeAssistantAuth.getAccessToken(context)
                if (token != null) fetchSnapshotAsync(endpoints, token)
                schedule {
                    if (token.isNullOrBlank()) {
                        reportStatus("Reconnect Home Assistant account")
                    } else {
                        onTokenReady(token)
                    }
                }
            }
        }
    }

    /** Keeps cold WebRtcEngine initialization off `executor` so WebSocket auth is never blocked. */
    private fun startPeerSetup(iceServers: List<PeerConnection.IceServer>) {
        if (closed || !peerSetupStarted.compareAndSet(false, true)) return
        if (WebRtcEngine.isReady()) {
            schedule { createPeerAndOffer(iceServers) }
        } else {
            HomeAssistantAuth.ioExecutor.execute {
                if (closed) return@execute
                try {
                    WebRtcEngine.ensureReady(context)
                    schedule { createPeerAndOffer(iceServers) }
                } catch (e: Exception) {
                    Log.w("DoorbellCompanion", "WebRTC warmup failed: ${e.javaClass.simpleName}")
                    reportStatus("Could not start WebRTC")
                }
            }
        }
    }

    private fun fetchSnapshotAsync(endpoints: List<Pair<String, String>>, token: String) {
        if (closed || firstFrameDecoded || !snapshotStarted.compareAndSet(false, true)) return
        for ((label, url) in endpoints) {
            val req = Request.Builder()
                .url("$url/api/camera_proxy/$cameraEntityId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val call = HomeAssistantAuth.httpClient.newCall(req)
            snapshotCalls.add(call)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    snapshotCalls.remove(call)
                }

                override fun onResponse(call: Call, response: Response) {
                    snapshotCalls.remove(call)
                    response.use { resp ->
                        if (!resp.isSuccessful || closed || firstFrameDecoded || snapshotDelivered.get()) return
                        val bytes = try { resp.body?.bytes() } catch (_: Exception) { null } ?: return
                        if (bytes.isEmpty() || closed || firstFrameDecoded) return
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                        if (!closed && !firstFrameDecoded && snapshotDelivered.compareAndSet(false, true)) {
                            timing?.mark("snapshot_loaded", "endpoint=$label bytes=${bytes.size} size=${bmp.width}x${bmp.height}")
                            cancelSnapshotCalls()
                            latestSnapshot = bmp
                            onSnapshot(bmp)
                        }
                    }
                }
            })
        }
    }

    private fun cancelSnapshotCalls() {
        val pending = synchronized(snapshotCalls) {
            snapshotCalls.toList().also { snapshotCalls.clear() }
        }
        pending.forEach { it.cancel() }
    }

    private fun openRacingWebSockets(endpoints: List<Pair<String, String>>) {
        if (closed) return
        timing?.mark("ha_ws_connect_start", "targets=${endpoints.joinToString(",") { it.first }}")
        for ((label, url) in endpoints) {
            val socket = HomeAssistantAuth.httpClient.newWebSocket(
                Request.Builder().url(HomeAssistantAuth.wsUrl(url)).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        timing?.mark("ha_ws_open", "endpoint=$label")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        schedule { handleWsMessage(webSocket, label, text) }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        schedule {
                            racingSockets.remove(webSocket)
                            authRequiredSockets.remove(webSocket)
                            if (wsAuthed && webSocket !== ws) return@schedule
                            Log.w("DoorbellCompanion", "HA WebSocket ($label) failed: ${t.javaClass.simpleName}: ${t.message}")
                            if (!wsAuthed && racingSockets.isEmpty() && currentVideoTrack == null) {
                                reportStatus("Could not reach Home Assistant")
                            }
                        }
                    }
                }
            )
            racingSockets.add(socket)
        }
    }

    private fun onTokenReady(token: String) {
        currentToken = token
        if (wsAuthed) return
        val pending = authRequiredSockets.toList()
        authRequiredSockets.clear()
        pending.forEach { sendAuth(it, token) }
    }

    private fun sendAuth(webSocket: WebSocket, token: String) {
        webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
    }

    private fun cancelOtherSockets(winner: WebSocket?) {
        val losers = racingSockets.filter { it !== winner }
        racingSockets.clear()
        authRequiredSockets.clear()
        if (winner != null) racingSockets.add(winner)
        losers.forEach { it.cancel() }
    }

    private fun handleWsMessage(webSocket: WebSocket, label: String, text: String) {
        if (wsAuthed && webSocket !== ws) {
            webSocket.cancel()
            return
        }
        try {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "auth_required" -> {
                    val token = currentToken
                    if (token != null) sendAuth(webSocket, token) else authRequiredSockets.add(webSocket)
                }
                "auth_invalid" -> {
                    if (!retriedAuth) {
                        retriedAuth = true
                        currentToken = null
                        cancelOtherSockets(null)
                        HomeAssistantAuth.ioExecutor.execute {
                            val refreshed = HomeAssistantAuth.getAccessToken(context, forceRefresh = true)
                            schedule {
                                if (refreshed != null) {
                                    currentToken = refreshed
                                    openRacingWebSockets(candidateEndpoints)
                                } else {
                                    reportStatus("Home Assistant login expired")
                                }
                            }
                        }
                    } else {
                        reportStatus("Home Assistant login expired")
                    }
                }
                "auth_ok" -> {
                    if (wsAuthed) {
                        webSocket.cancel()
                        return
                    }
                    wsAuthed = true
                    ws = webSocket
                    cancelOtherSockets(webSocket)
                    timing?.mark("ha_auth_ok", "endpoint=$label")
                    if (!needInitialIceConfig) {
                        trySendOffer()
                    }
                    val cid = msgIdGen.getAndIncrement()
                    configMsgId = cid
                    webSocket.send(
                        JSONObject()
                            .put("id", cid)
                            .put("type", "camera/webrtc/get_client_config")
                            .put("entity_id", cameraEntityId)
                            .toString()
                    )
                }
                "result" -> {
                    val id = msg.optInt("id")
                    if (id == configMsgId) {
                        val iceArr = msg.optJSONObject("result")
                            ?.optJSONObject("configuration")
                            ?.optJSONArray("iceServers")
                        if (iceArr != null) {
                            HomeAssistantAuth.prefs(context).edit()
                                .putString("ha_ice_servers", iceArr.toString())
                                .putLong("ha_ice_servers_time", System.currentTimeMillis())
                                .apply()
                        }
                        if (!peerSetupStarted.get()) {
                            startPeerSetup(parseIceServers(iceArr?.toString()))
                        }
                    } else if (id == offerMsgId && !msg.optBoolean("success", true)) {
                        val err = msg.optJSONObject("error")?.optString("message") ?: "WebRTC offer rejected"
                        Log.w("DoorbellCompanion", "HA offer failed: $err")
                        reportStatus(err)
                    }
                }
                "event" -> {
                    if (msg.optInt("id") != offerMsgId) return
                    val ev = msg.optJSONObject("event") ?: return
                    when (ev.optString("type")) {
                        "session" -> {
                            val sid = ev.optString("session_id")
                            if (sid.isNotBlank()) {
                                sessionId = sid
                                timing?.mark("ha_session")
                                flushLocalCandidates()
                            }
                        }
                        "answer" -> {
                            val sdp = ev.optString("answer")
                            if (sdp.isNotBlank() && !remoteDescriptionSet) {
                                timing?.mark("ha_answer")
                                serverAcceptsMic = answerAcceptsClientAudio(sdp)
                                peer?.setRemoteDescription(object : SimpleSdpObserver() {
                                    override fun onSetSuccess() {
                                        schedule {
                                            remoteDescriptionSet = true
                                            pendingRemoteCandidates.forEach { peer?.addIceCandidate(it) }
                                            pendingRemoteCandidates.clear()
                                            if (answered) applyAnsweredAudioState()
                                        }
                                    }
                                    override fun onSetFailure(error: String) {
                                        Log.w("DoorbellCompanion", "HA setRemoteDescription failed: $error")
                                        reportStatus("Camera stream negotiation failed")
                                    }
                                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                            }
                        }
                        "candidate" -> {
                            val candObj = ev.optJSONObject("candidate") ?: return
                            val candStr = candObj.optString("candidate")
                            if (candStr.isBlank()) return
                            val sdpMid = candObj.optString("sdpMid").takeIf { it.isNotBlank() && it != "null" } ?: "0"
                            val mLine = if (candObj.has("sdpMLineIndex") && !candObj.isNull("sdpMLineIndex")) {
                                candObj.optInt("sdpMLineIndex", 0)
                            } else 0
                            val ice = IceCandidate(sdpMid, mLine, candStr)
                            if (remoteDescriptionSet) {
                                peer?.addIceCandidate(ice)
                            } else {
                                pendingRemoteCandidates.add(ice)
                            }
                        }
                        "error" -> {
                            val errMsg = ev.optString("message", "Camera stream error")
                            Log.w("DoorbellCompanion", "HA WebRTC event error: $errMsg")
                            reportStatus(errMsg)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("DoorbellCompanion", "HA WS message error: ${e.javaClass.simpleName}")
        }
    }

    private fun createPeerAndOffer(iceServers: List<PeerConnection.IceServer>) {
        if (closed || peer != null) return
        try {
            WebRtcEngine.ensureReady(context)
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceCandidatePoolSize = 1
            }

            val connection = WebRtcEngine.factory.createPeerConnection(rtcConfig, object : SimplePeerConnectionObserver() {
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.i("DoorbellCompanion", "HA ICE state: $state")
                    if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                        timing?.mark("ice_${state.name.lowercase()}")
                        logSelectedCandidatePair()
                    } else if (state == PeerConnection.IceConnectionState.FAILED) {
                        timing?.mark("ice_failed")
                        reportStatus("Remote media connection failed (NAT/ICE)")
                    }
                }

                override fun onIceCandidate(candidate: IceCandidate) {
                    schedule {
                        if (sessionId != null) sendCandidate(candidate) else localCandidates.add(candidate)
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    when (transceiver.mediaType) {
                        MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> {
                            val audioTrack = transceiver.receiver.track() as? AudioTrack
                            schedule {
                                remoteAudio = audioTrack
                                remoteAudio?.setEnabled(answered)
                            }
                        }
                        MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                            (transceiver.receiver.track() as? VideoTrack)?.let { track ->
                                val first = AtomicBoolean(false)
                                val sink = object : VideoSink {
                                    override fun onFrame(frame: VideoFrame) {
                                        if (first.compareAndSet(false, true)) {
                                            firstFrameDecoded = true
                                            latestSnapshot = null
                                            cancelSnapshotCalls()
                                            timing?.mark("first_frame_decoded")
                                            schedule {
                                                try { track.removeSink(this) } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                }
                                track.addSink(sink)
                                currentVideoTrack = track
                                onVideo(track)
                            }
                        }
                        else -> {}
                    }
                }
            }) ?: error("Could not create PeerConnection")

            peer = connection
            connection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            audioTransceiver = connection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            )

            timing?.mark("ha_create_offer")
            connection.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(offer: SessionDescription) {
                    connection.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            schedule {
                                timing?.mark("ha_local_sdp_ready")
                                localOfferSdp = offer.description
                                trySendOffer()
                            }
                        }
                        override fun onSetFailure(error: String) { reportStatus("Could not prepare WebRTC offer") }
                    }, offer)
                }
                override fun onCreateFailure(error: String) { reportStatus("Could not create WebRTC offer") }
            }, MediaConstraints())
        } catch (e: Exception) {
            Log.w("DoorbellCompanion", "HA PeerConnection setup failed: ${e.javaClass.simpleName}")
            reportStatus("Could not start WebRTC")
        }
    }

    private fun trySendOffer() {
        val baseSdp = localOfferSdp ?: return
        val socket = ws ?: return
        if (!wsAuthed || offerSent) return
        offerSent = true
        val latestSdp = peer?.localDescription?.description?.takeIf { it.isNotBlank() } ?: baseSdp
        val inlinedCount = latestSdp.lineSequence().count { it.startsWith("a=candidate:") }
        if (inlinedCount > 0) {
            localCandidates.removeAll { latestSdp.contains(it.sdp) }
        }
        val oid = msgIdGen.getAndIncrement()
        offerMsgId = oid
        timing?.mark("ha_offer_sent", "inlined_candidates=$inlinedCount")
        reportStatus("Connecting camera…")
        socket.send(
            JSONObject()
                .put("id", oid)
                .put("type", "camera/webrtc/offer")
                .put("entity_id", cameraEntityId)
                .put("offer", latestSdp)
                .toString()
        )
    }

    private fun logSelectedCandidatePair() {
        val pc = peer ?: return
        pc.getStats { report ->
            try {
                val statsMap = report.statsMap
                val pair = statsMap.values.firstOrNull {
                    it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true
                } ?: statsMap.values.firstOrNull {
                    it.type == "candidate-pair" && it.members["state"] == "succeeded"
                } ?: return@getStats
                val localId = pair.members["localCandidateId"] as? String
                val remoteId = pair.members["remoteCandidateId"] as? String
                val localType = localId?.let { statsMap[it]?.members?.get("candidateType") } ?: "?"
                val localProto = localId?.let { statsMap[it]?.members?.get("protocol") } ?: "?"
                val remoteType = remoteId?.let { statsMap[it]?.members?.get("candidateType") } ?: "?"
                val rttMs = (pair.members["currentRoundTripTime"] as? Number)?.let { (it.toDouble() * 1000).toInt() } ?: -1
                Log.i("DoorbellCompanion", "ICE pair: local=$localType/$localProto <-> remote=$remoteType rtt=${rttMs}ms")
            } catch (_: Exception) {}
        }
    }

    private fun flushLocalCandidates() {
        if (sessionId == null) return
        localCandidates.forEach { sendCandidate(it) }
        localCandidates.clear()
    }

    private fun sendCandidate(candidate: IceCandidate) {
        val sid = sessionId ?: return
        val candJson = JSONObject()
            .put("candidate", candidate.sdp)
            .put("sdpMid", candidate.sdpMid ?: "0")
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
        ws?.send(
            JSONObject()
                .put("id", msgIdGen.getAndIncrement())
                .put("type", "camera/webrtc/candidate")
                .put("entity_id", cameraEntityId)
                .put("session_id", sid)
                .put("candidate", candJson)
                .toString()
        )
    }

    fun answer(allowMicrophone: Boolean) {
        answered = true
        microphoneAllowed = allowMicrophone
        schedule {
            remoteAudio?.setEnabled(true)
            if (remoteDescriptionSet) {
                applyAnsweredAudioState()
            }
        }
    }

    fun setMicMuted(muted: Boolean) {
        micMuted = muted
        schedule { localAudio?.setEnabled(!muted) }
    }

    fun isMicMuted(): Boolean = micMuted
    fun isMicAttached(): Boolean = micAttached

    private fun applyAnsweredAudioState() {
        remoteAudio?.setEnabled(true)
        val haTwoWay = HomeAssistantAuth.supportsTwoWayAudio(context, cameraEntityId)
        if (!haTwoWay || !serverAcceptsMic) {
            micAttached = false
            onMicState(false)
            reportStatus("Connected • Live audio (1-way)")
            return
        }
        if (!microphoneAllowed) {
            micAttached = false
            onMicState(false)
            reportStatus("Connected • Live audio (mic off)")
            return
        }
        if (localAudio != null) {
            localAudio?.setEnabled(!micMuted)
            micAttached = true
            onMicState(true)
            reportStatus("Connected • Two-way audio")
            return
        }
        try {
            val transceiver = audioTransceiver ?: return
            val src = WebRtcEngine.factory.createAudioSource(MediaConstraints())
            val track = WebRtcEngine.factory.createAudioTrack("ha-talkback-mic", src)
            audioSource = src
            localAudio = track
            val attached = transceiver.sender.setTrack(track, true)
            track.setEnabled(attached && !micMuted)
            micAttached = attached
            onMicState(attached)
            Log.i("DoorbellCompanion", "HA talkback microphone attached: $attached")
            reportStatus(if (attached) "Connected • Two-way audio" else "Connected • Live audio (1-way)")
        } catch (e: Exception) {
            Log.w("DoorbellCompanion", "HA microphone attach failed: ${e.javaClass.simpleName}")
            micAttached = false
            onMicState(false)
            reportStatus("Connected • Live audio")
        }
    }

    private fun answerAcceptsClientAudio(sdp: String): Boolean {
        var inAudio = false
        for (rawLine in sdp.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("m=")) {
                inAudio = line.startsWith("m=audio ") && !line.startsWith("m=audio 0 ")
            } else if (inAudio) {
                if (line == "a=sendrecv" || line == "a=recvonly") return true
                if (line == "a=sendonly" || line == "a=inactive") return false
            }
        }
        return false
    }

    private fun hasTurnCredentials(rawJson: String): Boolean =
        rawJson.contains("\"credential\"") || rawJson.contains("turn:") || rawJson.contains("turns:")

    private fun parseIceServers(rawJson: String?): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        if (!rawJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(rawJson)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val urls = mutableListOf<String>()
                    val urlsArr = item.optJSONArray("urls")
                    if (urlsArr != null) {
                        for (j in 0 until urlsArr.length()) {
                            val u = urlsArr.optString(j)
                            if (u.isNotBlank()) urls.add(u)
                        }
                    } else {
                        val single = item.optString("urls")
                        if (single.isNotBlank()) urls.add(single)
                    }
                    if (urls.isEmpty()) continue
                    val builder = PeerConnection.IceServer.builder(urls)
                    val user = item.optString("username")
                    val cred = item.optString("credential")
                    if (user.isNotBlank() && user != "null") builder.setUsername(user)
                    if (cred.isNotBlank() && cred != "null") builder.setPassword(cred)
                    servers.add(builder.createIceServer())
                }
            } catch (_: Exception) {}
        }
        if (servers.isEmpty()) {
            servers.add(
                PeerConnection.IceServer.builder(
                    listOf("stun:stun.home-assistant.io:3478", "stun:stun.cloudflare.com:3478")
                ).createIceServer()
            )
        }
        return servers
    }

    private fun acquireLowLatencyWifiLock() {
        try {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "doorbell_companion:call")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
    }

    fun close() {
        if (closed) return
        closed = true
        latestSnapshot = null
        cancelSnapshotCalls()
        releaseWifiLock()
        try {
            executor.execute {
                try {
                    localAudio?.setEnabled(false)
                    ws?.close(1000, null)
                    racingSockets.forEach { if (it !== ws) it.cancel() }
                    racingSockets.clear()
                    authRequiredSockets.clear()
                    peer?.dispose()
                    audioSource?.dispose()
                } catch (e: Exception) {
                    Log.w("DoorbellCompanion", "HA cleanup error: ${e.javaClass.simpleName}")
                } finally {
                    executor.shutdown()
                }
            }
        } catch (_: Exception) {}
    }
}
