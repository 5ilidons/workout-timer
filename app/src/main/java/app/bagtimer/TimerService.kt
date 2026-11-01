package app.bagtimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.bagtimer.engine.formatClock

/**
 * Keeps the process alive while a workout runs: foreground notification plus a partial wake lock,
 * so beeps keep coming with the screen off.
 */
class TimerService : Service(), Session.Listener {
    companion object {
        const val CHANNEL = "session"
        const val NOTIF_ID = 1
        const val ACTION_TOGGLE = "app.bagtimer.TOGGLE"
        const val ACTION_SKIP = "app.bagtimer.SKIP"
        const val ACTION_STOP = "app.bagtimer.STOP"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText = ""
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Workout in progress", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val notif = build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bagtimer:session")
            .apply { setReferenceCounted(false) }
        syncWakeLock()
        Session.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> Session.toggle()
            ACTION_SKIP -> Session.skip()
            ACTION_STOP -> Session.stop()
        }
        if (Session.state == Session.State.IDLE) stopSelf()
        return START_NOT_STICKY
    }

    /** Hold the CPU awake only while the clock is running. */
    private fun syncWakeLock() {
        val wl = wakeLock ?: return
        if (Session.state == Session.State.RUNNING) {
            if (!wl.isHeld) wl.acquire(3 * 60 * 60 * 1000L)
        } else if (wl.isHeld) {
            wl.release()
        }
    }

    override fun onTick() {
        if (Session.state == Session.State.IDLE) {
            stopSelf()
            return
        }
        syncWakeLock()
        val n = build()
        if (lastText != currentText) {
            lastText = currentText
            nm.notify(NOTIF_ID, n)
        }
    }

    private var currentText = ""

    private fun build(): Notification {
        val p = Session.position()
        val t = Session.timeline
        val title: String
        val text: String
        if (p == null || t == null) {
            title = "Bag Timer"
            text = ""
        } else if (p.finished) {
            title = "Done"
            text = "${t.workout.name} complete"
        } else {
            val paused = if (Session.state == Session.State.PAUSED) " · Paused" else ""
            title = "${p.segment.kind.label}  ${formatClock(p.remainingMs)}$paused"
            text = if (p.segment.round > 0) "Round ${p.segment.round} of ${t.workout.rounds} · ${formatClock(p.totalRemainingMs)} left"
            else t.workout.name
        }
        currentText = title + text

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(a: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, TimerService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggleLabel = if (Session.state == Session.State.RUNNING) "Pause" else "Resume"
        val toggleIcon = if (Session.state == Session.State.RUNNING) R.drawable.ic_pause else R.drawable.ic_play
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, toggleIcon), toggleLabel, action(ACTION_TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_skip), "Skip", action(ACTION_SKIP, 2)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Stop", action(ACTION_STOP, 3)).build())
            .build()
    }

    override fun onDestroy() {
        Session.removeListener(this)
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
