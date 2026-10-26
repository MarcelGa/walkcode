package dev.walkcode.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.walkcode.app.data.AppSettings
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.monitor.Battery
import dev.walkcode.app.data.UsageTotal
import dev.walkcode.app.voice.RealtimeProtocol
import dev.walkcode.app.voice.UsageFormat
import java.time.LocalDate

private val QuietChoices = listOf(5, 10, 15, 30, 60, 120)

@Composable
fun ColumnScope.SettingsScreen(repo: WalkcodeRepository, settings: AppSettings, onOpenVoice: () -> Unit, onOpenAgents: () -> Unit) {
    val context = LocalContext.current
    val quietSec by settings.quietAfterSec.collectAsState()
    val quietOn by settings.quietAlerts.collectAsState()
    val savedKey by settings.openAiKey.collectAsState()
    val model by settings.realtimeModel.collectAsState()
    val usage by repo.voiceUsage.collectAsState(emptyList())
    val monthUsd = UsageTotal.of(usage, LocalDate.now().withDayOfMonth(1).toString()).usd

    // The exemption is granted in a system dialog, so re-read it whenever we come back.
    var unrestricted by remember { mutableStateOf(Battery.isUnrestricted(context)) }
    DisposableEffect(context) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) unrestricted = Battery.isUnrestricted(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    TopBar("Settings")
    // weight: take only the space above the tab bar, otherwise a long list pushes the bar off screen.
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        SectionLabel("Alerts")
        ListRow(
            title = "Quiet alerts",
            subtitle = "Notify when a watched session has unread output and goes silent",
            subtitleLines = 3,
            trailing = { Switch(checked = quietOn, onCheckedChange = settings::setQuietAlerts) },
        )
        Text("Quiet after", color = WC.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (s in (QuietChoices + quietSec).distinct().sorted()) {
                Pill("${s}s", hot = s == quietSec, onClick = { settings.setQuietAfterSec(s) })
            }
        }

        // Dictation and the walk-mode assistant (model, voice, key) live on their own screen.
        SectionLabel("Voice")
        ListRow(
            title = "Dictation and walk mode",
            subtitle = if (savedKey == null) "Walk mode needs an OpenAI API key"
            else "Walk mode on ${RealtimeProtocol.modelLabel(model)} · ${UsageFormat.usd(monthUsd)} this month",
            subtitleLines = 2,
            onClick = onOpenVoice,
            trailing = { Text("›", color = WC.Muted, fontSize = 20.sp) },
        )

        // Each agent is a command a workspace types to start it, so a second account gets its own.
        SectionLabel("Agents")
        ListRow(
            title = "Agent commands",
            subtitle = "What a workspace starts, e.g. claude or claude-work for another account",
            subtitleLines = 2,
            onClick = onOpenAgents,
            trailing = { Text("›", color = WC.Muted, fontSize = 20.sp) },
        )

        SectionLabel("Background")
        ListRow(
            title = "Battery optimisation",
            subtitle = if (unrestricted) "Unrestricted — monitoring can run while the screen is off"
            else "Optimised — Android may suspend monitoring while the screen is off",
            subtitleLines = 3,
            trailing = { if (!unrestricted) Pill("Allow", onClick = { context.startActivity(Battery.requestIntent(context)) }) },
        )

        BackupSection(repo, settings)
    }
}
