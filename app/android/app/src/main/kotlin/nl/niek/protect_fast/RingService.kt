package nl.niek.protect_fast

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
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
        Log.i("ProtectFast", "Doorbell push received")

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
                // Start WebRTC negotiation immediately at t=0ms before CallActivity opens
                HomeAssistantPrewarm.start(this, camId)
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
            Log.w("ProtectFast", "Unable to start ringtone service", e)
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$doorbellName ringing")
            .setContentText("Someone is at the door")
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 31) {
            val caller = Person.Builder().setName(doorbellName).setImportant(true).build()
            builder.setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline)
            builder.addAction(android.R.drawable.ic_menu_call, "Answer", answer)
        }
        return builder.build()
    }
}

class RingtoneService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrating = false
    private val autoStopRunnable = Runnable {
        HomeAssistantPrewarm.cancel()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val doorbellName = intent?.getStringExtra("doorbell_name")?.takeIf { it.isNotBlank() } ?: "Doorbell"
        val cameraEntityId = intent?.getStringExtra("camera_entity_id")?.takeIf { it.isNotBlank() }
        startForeground(RingNotification.ID, RingNotification.build(this, true, doorbellName, cameraEntityId))

        // Auto-stop unanswered rings so sound/vibration never loop indefinitely.
        main.removeCallbacks(autoStopRunnable)
        main.postDelayed(autoStopRunnable, 40_000L)

        if (player == null) {
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
                    Log.w("ProtectFast", "Unable to start ringtone: ${e.message}")
                }
            }
        }
        if (!vibrating) {
            val vibrator = getSystemService(Vibrator::class.java)
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 800), 0)
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            }
            vibrating = true
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(autoStopRunnable)
        player?.release()
        player = null
        getSystemService(Vibrator::class.java).cancel()
        super.onDestroy()
    }
}

class DeclineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HomeAssistantPrewarm.cancel()
        RingNotification.stop(context)
    }
}
