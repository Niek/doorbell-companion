package nl.niek.doorbellcompanion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class CallActivity : Activity() {
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var answer: CallButton
    private lateinit var muteBtn: CallButton
    private lateinit var dismiss: CallButton
    private lateinit var remoteVideo: SurfaceViewRenderer
    private lateinit var snapshotView: ImageView
    private lateinit var retryButton: Button
    private val main = Handler(Looper.getMainLooper())
    private var automaticRetryUsed = false
    private var retryDeadline = 0L
    private val retryWhenOnline = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (getSystemService(ConnectivityManager::class.java).activeNetwork != null) {
                reconnectCamera()
            } else if (SystemClock.elapsedRealtime() < retryDeadline) {
                main.postDelayed(this, 500)
            } else {
                showCameraFailure("Could not reach Home Assistant")
            }
        }
    }

    private var haRemote: HomeAssistantRemote? = null
    private var remoteTrack: VideoTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var activeCameraEntityId: String = ""
    private var rendererInitialized = false
    private var firstFrameRendered = false
    private var fillScreen = true
    private var answered = false
    private var muted = false
    private var doorbellName: String = "Doorbell"
    private var callStart = SystemClock.elapsedRealtime()
    private var timing = CallTiming.current

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /** A round icon button with a caption underneath, styled like the system call screen. */
    private inner class CallButton(iconRes: Int, label: String, color: Int, onClick: () -> Unit) {
        private val icon = ImageView(this@CallActivity).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(70, 255, 255, 255)),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                },
                null,
            )
            contentDescription = label
            isClickable = true
            setOnClickListener { onClick() }
        }
        private val caption = TextView(this@CallActivity).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, Color.argb(160, 0, 0, 0))
        }
        val view = LinearLayout(this@CallActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(icon, LinearLayout.LayoutParams(72.dp(), 72.dp()))
            addView(
                caption,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = 8.dp() }
            )
        }
        var visibility: Int
            get() = view.visibility
            set(value) { view.visibility = value }

        fun update(iconRes: Int, label: String) {
            icon.setImageResource(iconRes)
            icon.contentDescription = label
            caption.text = label
        }
    }

    private val liveDot = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setSize(8, 8)
    }

    private fun setStatus(text: String, live: Boolean = false) {
        subtitle.text = text
        liveDot.setColor(if (live) Color.parseColor("#34C759") else Color.parseColor("#FFB627"))
    }

    private fun applyScalingMode() {
        val videoScale = if (fillScreen) {
            RendererCommon.ScalingType.SCALE_ASPECT_FILL
        } else {
            RendererCommon.ScalingType.SCALE_ASPECT_FIT
        }
        val imageScale = if (fillScreen) {
            ImageView.ScaleType.CENTER_CROP
        } else {
            ImageView.ScaleType.FIT_CENTER
        }
        remoteVideo.setScalingType(videoScale)
        snapshotView.scaleType = imageScale
    }

    private fun dismissCall() {
        HomeAssistantPrewarm.cancel()
        RingNotification.stop(this)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callStart = SystemClock.elapsedRealtime()
        timing?.mark("activity_created")
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                dismissCall()
            }
        }

        doorbellName = intent.getStringExtra("doorbell_name")?.takeIf { it.isNotBlank() }
            ?: HomeAssistantAuth.prefs(this).getString("last_doorbell_name", "Doorbell") ?: "Doorbell"

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        remoteVideo = SurfaceViewRenderer(this).apply {
            setEnableHardwareScaler(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }
        snapshotView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        // WebRTC needs flexible bounds in both dimensions to honor aspect fit.
        root.addView(
            remoteVideo,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        root.addView(
            snapshotView,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        root.setOnClickListener {
            fillScreen = !fillScreen
            applyScalingMode()
        }

        title = TextView(this).apply {
            text = doorbellName
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, Color.argb(180, 0, 0, 0))
            gravity = Gravity.CENTER
            maxLines = 2
        }
        subtitle = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#E6EDF5"))
            gravity = Gravity.CENTER_VERTICAL
            liveDot.setSize(8.dp(), 8.dp())
            setCompoundDrawablesRelativeWithIntrinsicBounds(liveDot, null, null, null)
            compoundDrawablePadding = 8.dp()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(Color.argb(140, 15, 17, 21))
            }
            setPadding(14.dp(), 6.dp(), 14.dp(), 6.dp())
        }
        setStatus("Connecting camera…")
        retryButton = Button(this).apply {
            text = "Retry"
            visibility = View.GONE
            setOnClickListener {
                automaticRetryUsed = false
                reconnectCamera()
            }
        }

        val topOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(185, 0, 0, 0), Color.TRANSPARENT)
            )
            setPadding(20.dp(), 48.dp(), 20.dp(), 28.dp())
            addView(title)
            addView(
                subtitle,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 10.dp()
                }
            )
            addView(retryButton)
        }

        dismiss = CallButton(R.drawable.ic_call_end, "Decline", Color.parseColor("#E5383B")) { dismissCall() }
        muteBtn = CallButton(R.drawable.ic_mic, "Mute", Color.argb(110, 255, 255, 255)) { toggleMute() }.apply {
            visibility = View.GONE
        }
        answer = CallButton(R.drawable.ic_call, "Answer", Color.parseColor("#2DB84D")) { answerCall() }

        // Equal-weight slots keep Decline on the left and Answer on the right, like the system dialer.
        fun slot() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(dismiss.view, slot())
            addView(muteBtn.view, slot())
            addView(answer.view, slot())
        }

        val bottomOverlay = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(195, 0, 0, 0), Color.TRANSPARENT)
            )
            setPadding(20.dp(), 32.dp(), 20.dp(), 32.dp())
            addView(
                buttonRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                )
            )
        }

        root.addView(
            topOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
        )
        root.addView(
            bottomOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
        )

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                topOverlay.setPadding(20.dp(), bars.top + 16.dp(), 20.dp(), 28.dp())
                bottomOverlay.setPadding(20.dp(), 32.dp(), 20.dp(), bars.bottom + 24.dp())
                insets
            }
        }

        setContentView(root)

        startHomeAssistantCamera(intent.getStringExtra("camera_entity_id"))
        if (intent.getBooleanExtra("answer", false)) answerCall()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        callStart = SystemClock.elapsedRealtime()
        timing = CallTiming.current
        timing?.mark("activity_new_intent")
        doorbellName = intent.getStringExtra("doorbell_name")?.takeIf { it.isNotBlank() }
            ?: HomeAssistantAuth.prefs(this).getString("last_doorbell_name", doorbellName) ?: doorbellName
        val incomingCamera = intent.getStringExtra("camera_entity_id")
        if (intent.getBooleanExtra("answer", false)) {
            if (!incomingCamera.isNullOrBlank() && incomingCamera != activeCameraEntityId) {
                releaseWebRtcView()
                startHomeAssistantCamera(incomingCamera)
            }
            answerCall()
        } else {
            releaseAudio()
            answered = false
            muted = false
            title.text = doorbellName
            setStatus("Connecting camera…")
            answer.visibility = View.VISIBLE
            muteBtn.visibility = View.GONE
            muteBtn.update(R.drawable.ic_mic, "Mute")
            dismiss.update(R.drawable.ic_call_end, "Decline")
            releaseWebRtcView()
            startHomeAssistantCamera(incomingCamera)
        }
    }

    private fun releaseWebRtcView() {
        main.removeCallbacks(retryWhenOnline)
        automaticRetryUsed = false
        retryButton.visibility = View.GONE
        activeCameraEntityId = ""
        snapshotView.visibility = View.GONE
        snapshotView.setImageDrawable(null)
        closeCameraConnection()
    }

    private fun closeCameraConnection() {
        firstFrameRendered = false
        val previous = haRemote
        haRemote = null
        remoteTrack?.removeSink(remoteVideo)
        remoteTrack = null
        previous?.close()
        muteBtn.visibility = View.GONE
        if (rendererInitialized) {
            remoteVideo.release()
            rendererInitialized = false
        }
    }

    private fun showCameraFailure(message: String, canRetry: Boolean = true) {
        setStatus(message)
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
    }

    private fun onCameraFailure(failure: HomeAssistantRemote.Failure) {
        timing?.mark("camera_connection_failed", "retryable=${failure.retryable} sign_in_required=${failure.requiresSignIn}")
        if (snapshotView.drawable != null) snapshotView.visibility = View.VISIBLE
        closeCameraConnection()
        if (!failure.retryable || automaticRetryUsed) {
            showCameraFailure(failure.message, canRetry = !failure.requiresSignIn)
            return
        }
        automaticRetryUsed = true
        setStatus("Reconnecting camera…")
        retryDeadline = SystemClock.elapsedRealtime() + 15_000
        main.postDelayed(retryWhenOnline, 1_000)
    }

    private fun reconnectCamera() {
        callStart = SystemClock.elapsedRealtime()
        timing?.mark("camera_reconnect_start")
        retryButton.visibility = View.GONE
        setStatus("Reconnecting camera…")
        startHomeAssistantCamera(activeCameraEntityId, usePrewarm = false)
    }

    private fun startHomeAssistantCamera(intentCameraId: String?, usePrewarm: Boolean = true) {
        if (!HomeAssistantAuth.isConfigured(this)) {
            setStatus("Connect Home Assistant first")
            return
        }
        val prefs = HomeAssistantAuth.prefs(this)
        val entityId = intentCameraId?.takeIf { it.isNotBlank() }
            ?: prefs.getString("last_ha_camera", null)?.takeIf { it.isNotBlank() }
            ?: HomeAssistantAuth.resolveCameraForPush(this, emptyMap()).first
        if (entityId == null) {
            setStatus("No doorbell camera selected in Home Assistant")
            return
        }
        activeCameraEntityId = entityId

        WebRtcEngine.ensureReady(applicationContext)
        val prewarmed = if (usePrewarm) HomeAssistantPrewarm.take(entityId) else null
        val connection = prewarmed ?: HomeAssistantRemote(applicationContext, entityId)
        haRemote = connection
        fun isCurrent() = !isDestroyed && !isFinishing && haRemote === connection

        remoteVideo.init(WebRtcEngine.egl.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                val ms = SystemClock.elapsedRealtime() - callStart
                timing?.mark("first_frame_rendered")
                Log.i("DoorbellCompanion", "HA video first frame after $ms ms")
                runOnUiThread {
                    if (isCurrent()) {
                        firstFrameRendered = true
                        snapshotView.visibility = View.GONE
                        setStatus(String.format("Live · %.1f s", ms / 1000.0), live = true)
                    }
                }
            }
            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                Log.i("DoorbellCompanion", "HA video resolution: ${width}x${height} rot=$rotation")
            }
        })
        rendererInitialized = true
        applyScalingMode()

        connection.attachListeners(
            videoListener = { track ->
                runOnUiThread {
                    if (isCurrent() && rendererInitialized) {
                        remoteTrack?.removeSink(remoteVideo)
                        remoteTrack = track
                        track.addSink(remoteVideo)
                        if (!firstFrameRendered && snapshotView.visibility != View.VISIBLE) {
                            setStatus("Starting live video…")
                        }
                    }
                }
            },
            statusListener = { message ->
                runOnUiThread {
                    val connected = message.startsWith("Connected")
                    // Before answering, keep the "Live" label once video is up; still surface errors.
                    if (isCurrent() && (!firstFrameRendered || answered || !connected)) {
                        setStatus(message, live = connected)
                    }
                }
            },
            failureListener = { failure ->
                runOnUiThread {
                    if (isCurrent()) onCameraFailure(failure)
                }
            },
            micStateListener = { micActive ->
                runOnUiThread {
                    if (isCurrent() && answered) {
                        muteBtn.visibility = if (micActive) View.VISIBLE else View.GONE
                    }
                }
            },
            snapshotListener = { bitmap ->
                runOnUiThread {
                    if (!isCurrent()) return@runOnUiThread
                    snapshotView.setImageBitmap(bitmap)
                    if (!firstFrameRendered) {
                        val ms = SystemClock.elapsedRealtime() - callStart
                        timing?.mark("snapshot_rendered")
                        snapshotView.visibility = View.VISIBLE
                        Log.i("DoorbellCompanion", "HA snapshot after $ms ms")
                        setStatus("Connecting live video…")
                    }
                }
            }
        )

        if (!isCurrent()) return
        if (answered) {
            connection.setMicMuted(muted)
            connection.answer(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        }
        if (prewarmed == null) {
            connection.connect()
        } else {
            timing?.mark("ha_used_prewarmed_session")
        }
    }

    private fun toggleMute() {
        val remote = haRemote ?: return
        muted = !muted
        remote.setMicMuted(muted)
        muteBtn.update(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic, if (muted) "Unmute" else "Mute")
    }

    private fun configureCallAudio(audio: AudioManager) {
        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        audioFocusRequest = focusReq
        audio.requestAudioFocus(focusReq)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val preferredHeadset = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val target = preferredHeadset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (target != null) audio.setCommunicationDevice(target)
        } else {
            val hasExternalAudio = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = !hasExternalAudio
        }
    }

    private fun releaseAudio() {
        if (!answered && audioFocusRequest == null) return
        val audio = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.mode = AudioManager.MODE_NORMAL
        audioFocusRequest?.let { audio.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    private fun answerCall() {
        RingNotification.stop(this)
        if (!answered) {
            configureCallAudio(getSystemService(AudioManager::class.java))
            answered = true
            timing?.mark("answered")
        }
        val twoWay = HomeAssistantAuth.supportsTwoWayAudio(this, activeCameraEntityId)
        val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        haRemote?.answer(hasMic)
        if (twoWay && !hasMic) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
        title.text = doorbellName
        answer.visibility = View.GONE
        muteBtn.visibility = if (haRemote?.isMicAttached() == true) View.VISIBLE else View.GONE
        dismiss.update(R.drawable.ic_call_end, "Hang up")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && answered) {
            haRemote?.answer(true)
        }
    }

    override fun onDestroy() {
        timing?.mark("activity_destroyed")
        if (isFinishing) {
            RingNotification.stop(this)
            if (!answered) HomeAssistantPrewarm.cancel()
        }
        releaseWebRtcView()
        releaseAudio()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        dismissCall()
    }
}
