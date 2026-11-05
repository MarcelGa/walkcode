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
import android.provider.Settings
import dev.walkcode.app.MainActivity
import dev.walkcode.app.R
import dev.walkcode.app.data.WalkcodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
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
        clearOldRows()
        val repo = WalkcodeRepository.get(this)
        scope.launch {
            combine(repo.allSessions, repo.workspaces, repo.projects, monitor.messages, SessionRows::of)
                .collect { showRows(it) }
        }
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

    /** An alert is the session's row posted again, this time making a sound. */
    @SuppressLint("MissingPermission")
    private fun notifyAlert(a: HostAlert) {
        val repeat = a.isRepeatOf(lastAlert.put(a.session, a))
        val row = rowsShown.firstOrNull { it.session == a.session }?.copy(message = a.text()) ?: return
        rowsShown = rowsShown.map { if (it.sessionId == row.sessionId) row else it }
        nm.notify(TAG_SESSION, row.sessionId.toInt(), rowNotification(row, alerting = !repeat))
        nm.notify(TAG_WORKSPACE, row.workspaceId.toInt(), summaryNotification(rowsShown.filter { it.workspaceId == row.workspaceId }))
    }

    private val lastAlert = HashMap<String, HostAlert>()
    private var rowsShown: List<SessionRow> = emptyList()

    /** Brings the shade in line with [rows], touching only what changed so a swiped row stays away. */
    @SuppressLint("MissingPermission")
    private fun showRows(rows: List<SessionRow>) {
        val before = rowsShown.associateBy { it.sessionId }
        val groupsBefore = rowsShown.groupBy { it.workspaceId }
        val groups = rows.groupBy { it.workspaceId }
        rowsShown = rows
        for (r in rows) if (before[r.sessionId] != r) nm.notify(TAG_SESSION, r.sessionId.toInt(), rowNotification(r, alerting = false))
        for (id in before.keys - rows.map { it.sessionId }.toSet()) nm.cancel(TAG_SESSION, id.toInt())
        for ((id, g) in groups) if (groupsBefore[id] != g) nm.notify(TAG_WORKSPACE, id.toInt(), summaryNotification(g))
        for (id in groupsBefore.keys - groups.keys) nm.cancel(TAG_WORKSPACE, id.toInt())
    }

    /**
     * Rows stay on the alert channel so a workspace's group never splits between the shade's
     * alerting and silent sections; [alerting] decides per post whether this one makes a sound.
     * GROUP_ALERT_SUMMARY mutes a child outright, also on its first post, where only-alert-once
     * has no effect: without it, pressing Watch would ring.
     */
    private fun rowNotification(r: SessionRow, alerting: Boolean): Notification =
        Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(r.label)
            .setContentText(r.message)
            .setSubText(r.workspace)
            .setStyle(Notification.BigTextStyle().bigText(r.message))
            .setGroup(groupKey(r.workspaceId))
            .setGroupAlertBehavior(if (alerting) Notification.GROUP_ALERT_ALL else Notification.GROUP_ALERT_SUMMARY)
            .setOnlyAlertOnce(!alerting)
            .setContentIntent(openSession(r.sessionId))
            .build()

    /** Android needs a summary to draw a group; it never alerts, the rows do. */
    private fun summaryNotification(rows: List<SessionRow>): Notification {
        val style = Notification.InboxStyle()
        rows.forEach { style.addLine("${it.label}: ${it.message}") }
        return Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(rows.first().workspace)
            .setContentText("${rows.size} watched session${if (rows.size == 1) "" else "s"}")
            .setSubText(rows.first().workspace)
            .setStyle(style)
            .setGroup(groupKey(rows.first().workspaceId))
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            // A tap on a collapsed group lands here whichever line it hit, and Android does not
            // say which: the only honest targets are the one session or the workspace's list.
            .setContentIntent(rows.singleOrNull()?.let { openSession(it.sessionId) } ?: openWorkspace(rows.first().workspaceId))
            .build()
    }

    private fun groupKey(workspaceId: Long) = "workspace-$workspaceId"

    /** Rows of a previous process would otherwise stay until their session next changes. */
    private fun clearOldRows() {
        nm.activeNotifications.filter { it.tag == TAG_SESSION || it.tag == TAG_WORKSPACE }.forEach { nm.cancel(it.tag, it.id) }
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
        showRows(emptyList())
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

    private fun openWorkspace(workspaceId: Long): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_WORKSPACE_ID, workspaceId)
        // Negative: request codes of openSession and openApp are not.
        return PendingIntent.getActivity(this, -1 - workspaceId.toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_WORKSPACE_ID = "workspaceId"
        const val EXTRA_RESUME = "resumeMonitoring"
        const val CHANNEL_ALERTS = "agent_alerts"
        const val CHANNEL_STATUS = "connection_status"
        private const val FOREGROUND_ID = 1
        private const val PAUSED_ID = 2
        private const val STATUS_BASE = 1000
        // Tags keep row and summary ids (session and workspace row ids) apart from the fixed ids above.
        private const val TAG_SESSION = "session"
        private const val TAG_WORKSPACE = "workspace"

        /** Two channels: alerts interrupt, connection status stays quiet. */
        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Agent alerts", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW))
        }

        /**
         * Android owns a channel's sound once it exists, so the app can only read it. The user may
         * have silenced it, or notifications as a whole, in system settings.
         */
        fun alertSoundOn(context: Context): Boolean {
            createChannels(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = nm.getNotificationChannel(CHANNEL_ALERTS)
            return nm.areNotificationsEnabled() && channel.importance >= NotificationManager.IMPORTANCE_DEFAULT && channel.sound != null
        }

        /** The system screen where the alert sound is picked or muted. */
        fun alertSoundSettings(context: Context): Intent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ALERTS)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
