package nl.niek.doorbellcompanion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

    private var haRemote: HomeAssistantRemote? = null
    private var remoteTrack: VideoTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var activeCameraEntityId: String = ""
    private var rendererInitialized = false
    private var firstFrameRendered = false
    private var fillScreen = true
    private var answered = false
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
        remoteVideo.requestLayout()
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

        fun fullCenteredParams() = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        )
        root.addView(remoteVideo, fullCenteredParams())
        root.addView(snapshotView, fullCenteredParams())
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
        }

        dismiss = CallButton(R.drawable.ic_call_end, "Decline", Color.parseColor("#E5383B")) {
            HomeAssistantPrewarm.cancel()
            RingNotification.stop(this@CallActivity)
            finish()
        }
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
        if (intent.getBooleanExtra("answer", false)) {
            answerCall()
        } else {
            title.text = doorbellName
            answer.visibility = View.VISIBLE
            muteBtn.visibility = View.GONE
            dismiss.update(R.drawable.ic_call_end, "Decline")
            answered = false
            releaseWebRtcView()
            startHomeAssistantCamera(intent.getStringExtra("camera_entity_id"))
        }
    }

    private fun releaseWebRtcView() {
        firstFrameRendered = false
        snapshotView.visibility = View.GONE
        snapshotView.setImageDrawable(null)
        remoteTrack?.removeSink(remoteVideo)
        remoteTrack = null
        haRemote?.close()
        haRemote = null
        if (rendererInitialized) {
            remoteVideo.release()
            rendererInitialized = false
        }
    }

    private fun startHomeAssistantCamera(intentCameraId: String?) {
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
        val prewarmed = HomeAssistantPrewarm.take(entityId)
        val connection = prewarmed ?: HomeAssistantRemote(applicationContext, entityId)
        haRemote = connection

        remoteVideo.init(WebRtcEngine.egl.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                val ms = SystemClock.elapsedRealtime() - callStart
                timing?.mark("first_frame_rendered")
                Log.i("DoorbellCompanion", "HA video first frame after $ms ms")
                runOnUiThread {
                    if (!isDestroyed) {
                        firstFrameRendered = true
                        snapshotView.visibility = View.GONE
                        snapshotView.setImageDrawable(null)
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
                    if (!isDestroyed && rendererInitialized) {
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
                    if (!isDestroyed && (!firstFrameRendered || answered || !connected)) {
                        setStatus(message, live = connected)
                    }
                }
            },
            micStateListener = { micActive ->
                runOnUiThread {
                    if (!isDestroyed && answered) {
                        muteBtn.visibility = if (micActive) View.VISIBLE else View.GONE
                    }
                }
            },
            snapshotListener = { bitmap ->
                runOnUiThread {
                    if (!isDestroyed && !firstFrameRendered) {
                        val ms = SystemClock.elapsedRealtime() - callStart
                        timing?.mark("snapshot_rendered")
                        snapshotView.setImageBitmap(bitmap)
                        snapshotView.visibility = View.VISIBLE
                        Log.i("DoorbellCompanion", "HA snapshot after $ms ms")
                        setStatus("Connecting live video…")
                    }
                }
            }
        )

        if (answered) {
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
        val next = !remote.isMicMuted()
        remote.setMicMuted(next)
        muteBtn.update(if (next) R.drawable.ic_mic_off else R.drawable.ic_mic, if (next) "Unmute" else "Mute")
    }

    private fun answerCall() {
        RingNotification.stop(this)
        if (!answered) {
            val audio = getSystemService(AudioManager::class.java)
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
                val speaker = audio.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker != null) audio.setCommunicationDevice(speaker)
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = true
            }
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
        releaseWebRtcView()
        if (answered) {
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
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        HomeAssistantPrewarm.cancel()
        RingNotification.stop(this)
        super.onBackPressed()
    }
}
