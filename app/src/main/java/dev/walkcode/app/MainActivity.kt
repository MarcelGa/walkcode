package dev.walkcode.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.walkcode.app.monitor.Monitor
import dev.walkcode.app.monitor.MonitorService
import dev.walkcode.app.ui.WalkcodeApp

class MainActivity : ComponentActivity() {
    /** Session to open, set when the user taps an alert notification. */
    private var pendingSession by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The UI is always dark, so force light system-bar icons regardless of the device theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        handle(intent)
        setContent { WalkcodeApp(pendingSession, onSessionOpened = { pendingSession = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(MonitorService.EXTRA_RESUME, false)) Monitor.get(this).resume()
        val id = intent.getLongExtra(MonitorService.EXTRA_SESSION_ID, -1L)
        if (id >= 0) pendingSession = id
    }
}
