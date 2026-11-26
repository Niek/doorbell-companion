package nl.niek.doorbellcompanion

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class RingService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        WebRtcEngine.warmUpAsync(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "doorbell_ring") return
        CallTiming.start(message.data["received_at"], message.data["event_ms"])
        Log.i("DoorbellCompanion", "Doorbell push received")

        var doorbellName = message.data["doorbell_name"]?.takeIf { it.isNotBlank() } ?: "Doorbell"
        var camId: String? = null
        if (HomeAssistantAuth.isConfigured(this)) {
            val resolved = HomeAssistantAuth.resolveCameraForPush(this, message.data)
            camId = resolved.first
            doorbellName = resolved.second
            val editor = HomeAssistantAuth.prefs(this).edit()
                .putString("last_doorbell_name", doorbellName)
            if (camId != null) {
                editor.putString("last_ha_camera", camId)
            }
            editor.apply()
        }
        RingNotification.show(this, doorbellName, camId)
    }

    override fun onNewToken(token: String) {
        HomeAssistantAuth.prefs(this).edit().putString("fcm_token", token).apply()
        HomeAssistantAuth.syncPushTokenIfRegistered(this, token)
    }
}

object RingNotification {
    private const val CHANNEL = "doorbell_calls"
    private const val LOOP_CHANNEL = "doorbell_calls_looping"
    const val ID = 1
    const val RING_TIMEOUT_MS = 30_000L
    @Volatile private var channelsCreated = false

    fun createChannel(context: Context) {
        if (channelsCreated) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "Doorbell calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming doorbell calls"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
            )
            enableVibration(true)
        }
        val loopChannel = NotificationChannel(LOOP_CHANNEL, "Doorbell calls (ringing)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming doorbell calls"
            setSound(null, null)
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
        nm.createNotificationChannel(loopChannel)
        channelsCreated = true
    }

    fun show(context: Context, doorbellName: String = "Doorbell", cameraEntityId: String? = null) {
        try {
            context.startForegroundService(
                Intent(context, RingtoneService::class.java)
                    .putExtra("doorbell_name", doorbellName)
                    .putExtra("camera_entity_id", cameraEntityId)
            )
        } catch (e: RuntimeException) {
            Log.w("DoorbellCompanion", "Unable to start ringtone service", e)
            cameraEntityId?.let { HomeAssistantPrewarm.start(context, it) }
            context.getSystemService(NotificationManager::class.java)
                .notify(ID, build(context, false, doorbellName, cameraEntityId))
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, RingtoneService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun build(
        context: Context,
        looping: Boolean,
        doorbellName: String = "Doorbell",
        cameraEntityId: String? = null,
        thumbnail: Bitmap? = null,
        publicVersion: Notification? = null,
    ): Notification {
        createChannel(context)
        val intent = Intent(context, CallActivity::class.java)
            .putExtra("doorbell_name", doorbellName)
            .putExtra("camera_entity_id", cameraEntityId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().apply {
            pendingIntentCreatorBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }.toBundle() else null
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(context, 0, Intent(intent).putExtra("answer", false), flags, options)
        val answer = PendingIntent.getActivity(context, 1, Intent(intent).putExtra("answer", true), flags, options)
        val decline = PendingIntent.getBroadcast(context, 2, Intent(context, DeclineReceiver::class.java), flags)
        val builder = Notification.Builder(context, if (looping) LOOP_CHANNEL else CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_doorbell)
            .setColor(0xFF2F6BFF.toInt())
            .setContentTitle("$doorbellName ringing")
            .setContentText("Someone is at the door")
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setOngoing(true)
        if (thumbnail != null) {
            builder.setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setOnlyAlertOnce(true)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val caller = Person.Builder().setName(doorbellName).setImportant(true)
            thumbnail?.let { caller.setIcon(Icon.createWithBitmap(it)) }
            builder.setStyle(Notification.CallStyle.forIncomingCall(caller.build(), decline, answer))
        } else {
            thumbnail?.let { builder.setLargeIcon(it) }
            builder.addAction(R.drawable.ic_call_end, "Decline", decline)
            builder.addAction(R.drawable.ic_call, "Answer", answer)
        }
        return builder.build()
    }
}

class RingtoneService : Service() {
    private companion object {
        const val THUMBNAIL_SIZE_PX = 160
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrating = false
    private var ringingStartId: Int? = null
    private val autoStopRunnable = Runnable {
        ringingStartId = null
        HomeAssistantPrewarm.cancel()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val doorbellName = intent?.getStringExtra("doorbell_name")?.takeIf { it.isNotBlank() } ?: "Doorbell"
        val cameraEntityId = intent?.getStringExtra("camera_entity_id")?.takeIf { it.isNotBlank() }
        val notification = RingNotification.build(this, true, doorbellName, cameraEntityId)
        ringingStartId = startId
        if (cameraEntityId != null) {
            HomeAssistantPrewarm.start(this, cameraEntityId) { snapshot ->
                val thumbnail = ThumbnailUtils.extractThumbnail(snapshot, THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX)
                main.post {
                    if (ringingStartId != startId || getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                        return@post
                    }
                    val manager = getSystemService(NotificationManager::class.java)
                    if (manager.activeNotifications.none { it.id == RingNotification.ID }) return@post
                    val updated = RingNotification.build(
                        this, true, doorbellName, cameraEntityId, thumbnail, publicVersion = notification,
                    )
                    manager.notify(RingNotification.ID, updated)
                }
            }
        }
        startForeground(RingNotification.ID, notification)

        // Auto-stop unanswered rings so sound/vibration never loop indefinitely.
        main.removeCallbacks(autoStopRunnable)
        main.postDelayed(autoStopRunnable, RingNotification.RING_TIMEOUT_MS)

        val ringerMode = getSystemService(AudioManager::class.java).ringerMode
        if (player == null && ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            if (uri != null) {
                try {
                    player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                        )
                        setDataSource(this@RingtoneService, uri)
                        isLooping = true
                        setOnPreparedListener { it.start() }
                        setOnErrorListener { mp, _, _ ->
                            mp.release()
                            if (player === mp) player = null
                            true
                        }
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    Log.w("DoorbellCompanion", "Unable to start ringtone: ${e.message}")
                }
            }
        }
        if (!vibrating && ringerMode != AudioManager.RINGER_MODE_SILENT) {
            val vib = vibrator()
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 800), 0)
            if (Build.VERSION.SDK_INT >= 33) {
                vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            }
            vibrating = true
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ringingStartId = null
        main.removeCallbacks(autoStopRunnable)
        player?.release()
        player = null
        vibrator().cancel()
        super.onDestroy()
    }
}

class DeclineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HomeAssistantPrewarm.cancel()
        RingNotification.stop(context)
    }
}
