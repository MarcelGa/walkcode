package dev.walkcode.app.assistant

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

/**
 * Foreground service (type microphone) that keeps walk mode listening with the screen off. It holds
 * no state: [WalkAssistant] owns the conversation and starts and stops this with it. The
 * notification's Stop ends walk mode.
 */
class WalkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WalkAssistant.get(this).stop()   // which stops this service through WalkAssistant.on
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Walk mode", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WalkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Walk mode")
            .setContentText("Listening — talk about your workspaces")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        // Fails when the app is not in the foreground or the mic permission is missing; walk mode then
        // still runs while the app is open, it just stops listening when the screen goes off.
        runCatching { startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "walk_mode"
        private const val FOREGROUND_ID = 3
        private const val ACTION_STOP = "dev.walkcode.app.WALK_STOP"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WalkService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WalkService::class.java))
        }
    }
}
