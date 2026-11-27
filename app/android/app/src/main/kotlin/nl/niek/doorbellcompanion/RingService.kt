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
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

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
    private const val MISSED_CHANNEL = "doorbell_missed_calls"
    const val ID = 1
    const val RING_TIMEOUT_MS = 30_000L
    const val EXTRA_RING_ID = "ring_id"
    internal const val THUMBNAIL_SIZE_PX = 160
    private const val BIG_PICTURE_MAX_PX = 400
    @Volatile private var channelsCreated = false

    private val main = Handler(Looper.getMainLooper())
    private val ringSequence = AtomicLong(0L)
    private val missedNotificationId = AtomicInteger(((System.currentTimeMillis() % 1_000_000L).toInt()) + 1000)

    data class ActiveRing(val ringId: Long, val doorbellName: String, val cameraEntityId: String?)

    private var activeRingId: Long = 0L
    private var activeDoorbellName: String = "Doorbell"
    private var activeCameraEntityId: String? = null
    private var activeSnapshot: Bitmap? = null
    private var activeCallActivity: WeakReference<CallActivity>? = null
    private var activeCallActivityRingId: Long = 0L
    private var ringTimeoutRunnable: Runnable? = null

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
        val missedChannel = NotificationChannel(
            MISSED_CHANNEL,
            "Missed doorbell calls",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Missed doorbell rings"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
        nm.createNotificationChannel(loopChannel)
        nm.createNotificationChannel(missedChannel)
        channelsCreated = true
    }

    fun show(context: Context, doorbellName: String = "Doorbell", cameraEntityId: String? = null) {
        val appContext = context.applicationContext
        val ringId = ringSequence.incrementAndGet()
        val existingActivity: CallActivity?
        synchronized(this) {
            ringTimeoutRunnable?.let(main::removeCallbacks)
            activeRingId = ringId
            activeDoorbellName = doorbellName
            activeCameraEntityId = cameraEntityId
            activeSnapshot = null
            existingActivity = activeCallActivity?.get()
            if (existingActivity != null) {
                activeCallActivityRingId = ringId
            }
            val runnable = Runnable { handleRingTimeout(appContext, ringId) }
            ringTimeoutRunnable = runnable
            main.postDelayed(runnable, RING_TIMEOUT_MS)
        }
        existingActivity?.let { activity ->
            main.post { activity.updateUnansweredRing(ringId, doorbellName) }
        }
        try {
            context.startForegroundService(
                Intent(context, RingtoneService::class.java)
                    .putExtra("doorbell_name", doorbellName)
                    .putExtra("camera_entity_id", cameraEntityId)
                    .putExtra(EXTRA_RING_ID, ringId)
            )
        } catch (e: RuntimeException) {
            Log.w("DoorbellCompanion", "Unable to start ringtone service", e)
            cameraEntityId?.let {
                HomeAssistantPrewarm.start(context, it) { snapshot ->
                    recordSnapshot(ringId, snapshot)
                }
            }
            context.getSystemService(NotificationManager::class.java)
                .notify(ID, build(context, false, doorbellName, cameraEntityId, ringId = ringId))
        }
    }

    fun recordSnapshot(ringId: Long, snapshot: Bitmap) {
        if (ringId == 0L || !isRingActive(ringId)) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            HomeAssistantAuth.ioExecutor.execute { storeScaledSnapshot(ringId, snapshot) }
        } else {
            storeScaledSnapshot(ringId, snapshot)
        }
    }

    private fun storeScaledSnapshot(ringId: Long, snapshot: Bitmap) {
        if (!isRingActive(ringId)) return
        val scaled = scaleForNotification(snapshot, BIG_PICTURE_MAX_PX)
        synchronized(this) {
            if (activeRingId == ringId) {
                activeSnapshot = scaled
            }
        }
    }

    fun isRingActive(ringId: Long): Boolean = synchronized(this) {
        ringId != 0L && activeRingId == ringId
    }

    fun hasActiveRing(): Boolean = synchronized(this) {
        activeRingId != 0L
    }

    fun currentRing(): ActiveRing? = synchronized(this) {
        if (activeRingId == 0L) null else ActiveRing(activeRingId, activeDoorbellName, activeCameraEntityId)
    }

    fun attachUnansweredCall(ringId: Long, activity: CallActivity): Boolean = synchronized(this) {
        if (ringId != 0L && activeRingId != ringId) return false
        activeCallActivity = WeakReference(activity)
        activeCallActivityRingId = ringId
        true
    }

    fun detachCall(activity: CallActivity) {
        synchronized(this) {
            if (activeCallActivity?.get() === activity) {
                activeCallActivity = null
                activeCallActivityRingId = 0L
            }
        }
    }

    fun stop(context: Context) {
        synchronized(this) {
            ringTimeoutRunnable?.let(main::removeCallbacks)
            ringTimeoutRunnable = null
            activeRingId = 0L
            activeCameraEntityId = null
            activeSnapshot = null
        }
        context.stopService(Intent(context, RingtoneService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun stopIfCurrent(context: Context, ringId: Long): Boolean {
        if (ringId == 0L) return false
        val stopped = synchronized(this) {
            if (activeRingId != ringId) return@synchronized false
            ringTimeoutRunnable?.let(main::removeCallbacks)
            ringTimeoutRunnable = null
            activeRingId = 0L
            activeCameraEntityId = null
            activeSnapshot = null
            true
        }
        if (!stopped) return false
        context.stopService(Intent(context, RingtoneService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(ID)
        return true
    }

    fun decline(context: Context, onlyIfRingId: Long? = null, activity: CallActivity? = null) {
        var activityToDismiss: CallActivity? = null
        val shouldStop = synchronized(this) {
            val matchesAttached = activity != null && activeCallActivity?.get() === activity && activeRingId != 0L
            val matchesRingId = onlyIfRingId == null || (onlyIfRingId != 0L && activeRingId == onlyIfRingId)
            if (!matchesAttached && !matchesRingId) {
                return@synchronized false
            }
            ringTimeoutRunnable?.let(main::removeCallbacks)
            ringTimeoutRunnable = null
            activeRingId = 0L
            activeCameraEntityId = null
            activeSnapshot = null
            activityToDismiss = activeCallActivity?.get()
            activeCallActivity = null
            activeCallActivityRingId = 0L
            true
        }
        if (!shouldStop) return
        HomeAssistantPrewarm.cancel()
        context.stopService(Intent(context, RingtoneService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(ID)
        activityToDismiss?.let { target ->
            if (target !== activity) {
                main.post { target.finishFromRingEnd() }
            }
        }
    }

    private fun handleRingTimeout(context: Context, ringId: Long) {
        val doorbellName: String
        val snapshot: Bitmap?
        val activityToDismiss: CallActivity?
        synchronized(this) {
            if (activeRingId != ringId) return
            doorbellName = activeDoorbellName
            snapshot = activeSnapshot
            activityToDismiss = if (activeCallActivityRingId == ringId) activeCallActivity?.get() else null
            ringTimeoutRunnable = null
            activeRingId = 0L
            activeCameraEntityId = null
            activeSnapshot = null
            if (activeCallActivityRingId == ringId) {
                activeCallActivity = null
                activeCallActivityRingId = 0L
            }
        }
        Log.i("DoorbellCompanion", "Doorbell ring timed out unanswered")
        HomeAssistantPrewarm.cancel()
        context.stopService(Intent(context, RingtoneService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(ID)
        activityToDismiss?.finishFromRingEnd()
        showMissedCall(context, doorbellName, snapshot)
    }

    fun showMissedCall(context: Context, doorbellName: String = "Doorbell", snapshot: Bitmap? = null) {
        createChannel(context)
        val openApp = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(context, 3, openApp, flags)
        val now = System.currentTimeMillis()

        fun baseBuilder() = Notification.Builder(context, MISSED_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_doorbell)
            .setColor(0xFF2F6BFF.toInt())
            .setContentTitle(doorbellName)
            .setContentText("Missed doorbell")
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setWhen(now)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        val nm = context.getSystemService(NotificationManager::class.java)
        val notificationId = missedNotificationId.incrementAndGet()
        if (snapshot != null) {
            val publicVersion = baseBuilder()
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
            val thumbnail = ThumbnailUtils.extractThumbnail(snapshot, THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX)
            val rich = baseBuilder()
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setLargeIcon(thumbnail)
                .setStyle(
                    Notification.BigPictureStyle()
                        .bigPicture(snapshot)
                        .bigLargeIcon(null as Bitmap?)
                )
                .build()
            try {
                nm.notify(notificationId, rich)
                return
            } catch (e: RuntimeException) {
                Log.w("DoorbellCompanion", "Unable to post missed doorbell snapshot: ${e.message}")
            }
        }
        nm.notify(
            notificationId,
            baseBuilder().setVisibility(Notification.VISIBILITY_PUBLIC).build(),
        )
    }

    private fun scaleForNotification(bitmap: Bitmap, maxDimensionPx: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        val scaled = if (largest > maxDimensionPx && largest > 0) {
            val scale = maxDimensionPx.toFloat() / largest
            val targetWidth = max(1, (bitmap.width * scale).roundToInt())
            val targetHeight = max(1, (bitmap.height * scale).roundToInt())
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } else {
            bitmap
        }
        if (scaled.config == Bitmap.Config.RGB_565) return scaled
        val compact = scaled.copy(Bitmap.Config.RGB_565, false)
        if (compact != null) {
            if (scaled !== bitmap) scaled.recycle()
            return compact
        }
        return scaled
    }

    fun build(
        context: Context,
        looping: Boolean,
        doorbellName: String = "Doorbell",
        cameraEntityId: String? = null,
        thumbnail: Bitmap? = null,
        publicVersion: Notification? = null,
        ringId: Long = 0L,
    ): Notification {
        createChannel(context)
        val intent = Intent(context, CallActivity::class.java)
            .putExtra("doorbell_name", doorbellName)
            .putExtra("camera_entity_id", cameraEntityId)
            .putExtra(EXTRA_RING_ID, ringId)
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
            .setTimeoutAfter(RING_TIMEOUT_MS)
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
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrating = false
    private var ringingStartId: Int? = null

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
        val ringId = intent?.getLongExtra(RingNotification.EXTRA_RING_ID, 0L) ?: 0L
        val notification = RingNotification.build(this, true, doorbellName, cameraEntityId, ringId = ringId)
        ringingStartId = startId
        if (cameraEntityId != null) {
            HomeAssistantPrewarm.start(this, cameraEntityId) { snapshot ->
                RingNotification.recordSnapshot(ringId, snapshot)
                val thumbnail = ThumbnailUtils.extractThumbnail(
                    snapshot,
                    RingNotification.THUMBNAIL_SIZE_PX,
                    RingNotification.THUMBNAIL_SIZE_PX,
                )
                main.post {
                    if (
                        ringingStartId != startId ||
                        !RingNotification.isRingActive(ringId) ||
                        getSystemService(KeyguardManager::class.java).isKeyguardLocked
                    ) {
                        return@post
                    }
                    val manager = getSystemService(NotificationManager::class.java)
                    if (manager.activeNotifications.none { it.id == RingNotification.ID }) return@post
                    val updated = RingNotification.build(
                        this, true, doorbellName, cameraEntityId, thumbnail, publicVersion = notification, ringId = ringId,
                    )
                    manager.notify(RingNotification.ID, updated)
                }
            }
        }
        startForeground(RingNotification.ID, notification)

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
        player?.release()
        player = null
        vibrator().cancel()
        super.onDestroy()
    }
}

class DeclineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RingNotification.decline(context)
    }
}
