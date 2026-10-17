package dev.walkcode.app.monitor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.walkcode.app.MainActivity
import dev.walkcode.app.R
import dev.walkcode.app.data.WalkcodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that keeps [Monitor] alive in the background and turns
 * its alerts into notifications. Android caps dataSync services (6 h per 24 h); when the
 * limit hits, [onTimeout] stops monitoring and posts a "paused" notice that resumes on tap.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var monitor: Monitor
    private lateinit var nm: NotificationManager
    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        createChannels(this)
        monitor = Monitor.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FOREGROUND_ID, foregroundNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        nm.cancel(PAUSED_ID)
        monitor.start()
        // START_STICKY / repeated startForegroundService calls re-enter here; collect once.
        if (collecting) return START_STICKY
        collecting = true
        scope.launch {
            monitor.alerts.collect { notifyAlert(it) }
        }
        scope.launch {
            monitor.status.collect { statuses -> onStatus(statuses) }
        }
        scope.launch {
            monitor.watchedByHost.collect { watched ->
                if (watched == null) return@collect
                if (watched.isEmpty()) stopSelf() else update(watched.values.sumOf { it.size })
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun notifyAlert(a: HostAlert) {
        val text = when (a.kind) {
            HostAlert.Kind.ATTENTION -> "Needs your attention"
            HostAlert.Kind.QUIET -> "No output for ${a.idleSec}s — may be waiting for input"
        }
        val sessionId = withContext(Dispatchers.IO) { WalkcodeRepository.get(this@MonitorService).sessionByName(a.session)?.id }
        val n = Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${a.hostName} · ${a.session}")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openSession(sessionId))
            .build()
        nm.notify(a.session.hashCode(), n)
    }

    /** One connection-status notification per host, shown only while it is down. */
    private fun onStatus(statuses: Map<Long, LinkStatus>) {
        for ((hostId, s) in statuses) {
            val id = STATUS_BASE + hostId.toInt()
            if (s is LinkStatus.Reconnecting && s.attempt >= 2) {
                val n = Notification.Builder(this, CHANNEL_STATUS)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Connection lost")
                    .setContentText("Reconnecting… (${s.reason})")
                    .setOngoing(true)
                    .setContentIntent(openApp())
                    .build()
                nm.notify(id, n)
            } else {
                nm.cancel(id)
            }
        }
    }

    private fun update(watchedCount: Int) {
        nm.notify(FOREGROUND_ID, foregroundNotification("Watching $watchedCount session${if (watchedCount == 1) "" else "s"}"))
    }

    // Called by Android 15+ when the dataSync time limit is reached; must stop promptly.
    @Suppress("NewApi")
    override fun onTimeout(startId: Int, fgsType: Int) {
        monitor.stop(byOs = true)
        val n = Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Monitoring paused")
            .setContentText("Android limits background monitoring — tap to resume")
            .setAutoCancel(true)
            .setContentIntent(openApp(resume = true))
            .build()
        nm.notify(PAUSED_ID, n)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        // No service, no background monitoring; keep the paused flag the OS timeout may have set.
        monitor.stop(byOs = monitor.paused.value)
        super.onDestroy()
    }

    private fun foregroundNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Walkcode")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp(resume: Boolean = false): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (resume) i.putExtra(EXTRA_RESUME, true)
        return PendingIntent.getActivity(this, if (resume) 1 else 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openSession(sessionId: Long?): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (sessionId != null) i.putExtra(EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getActivity(this, (sessionId ?: 0L).toInt() + 100, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_RESUME = "resumeMonitoring"
        const val CHANNEL_ALERTS = "agent_alerts"
        const val CHANNEL_STATUS = "connection_status"
        private const val FOREGROUND_ID = 1
        private const val PAUSED_ID = 2
        private const val STATUS_BASE = 1000

        /** Two channels: alerts interrupt, connection status stays quiet. */
        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Agent alerts", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW))
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
