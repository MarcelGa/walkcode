package dev.walkcode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette taken from mockup/index.html. */
object WC {
    val Bg = Color(0xFF0B0F14)
    val Panel = Color(0xFF121820)
    val Panel2 = Color(0xFF0E141B)
    val Line = Color(0xFF1E2733)
    val Fg = Color(0xFFE6EDF3)
    val Muted = Color(0xFF8B98A5)
    val Accent = Color(0xFF3DDC84)
    val Warn = Color(0xFFFFB020)
    val Blue = Color(0xFF6AA9FF)
    val Off = Color(0xFF4A5765)
    val Danger = Color(0xFFFF6B6B)
}

@Composable
fun WalkcodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = WC.Accent, onPrimary = Color(0xFF04120A),
            background = WC.Panel2, surface = WC.Panel2, onSurface = WC.Fg, onBackground = WC.Fg,
            surfaceVariant = WC.Panel, onSurfaceVariant = WC.Muted, outline = WC.Line,
            error = WC.Danger,
        ),
        content = content,
    )
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().background(WC.Panel).heightIn(min = 52.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.weight(1f).let { if (onBack != null) it.clickable(onClick = onBack) else it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) Text("←  ", color = WC.Muted, fontSize = 16.sp)
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                color = if (onBack != null) WC.Muted else WC.Fg, fontSize = 16.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), content = actions)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(WC.Line))
}

@Composable
fun BarAction(label: String, onClick: () -> Unit) {
    Text(
        label, color = WC.Accent, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
}

@Composable
fun UnreadDot() {
    Box(Modifier.size(8.dp).clip(CircleShape).background(WC.Accent))
}

@Composable
fun Badge(count: Int, dim: Boolean = false) {
    Box(
        Modifier.heightIn(min = 20.dp).clip(RoundedCornerShape(10.dp))
            .background(if (dim) Color(0xFF243040) else WC.Accent).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", color = if (dim) WC.Fg else Color(0xFF04120A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Pill(text: String, onClick: (() -> Unit)? = null, hot: Boolean = false) {
    Text(
        text, color = if (hot) WC.Warn else WC.Muted, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .background(WC.Panel).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** A list row: leading content, a title with up to two subtitle lines, trailing content. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    path: String? = null,
    subtitleLines: Int = 1,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = WC.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = WC.Muted, fontSize = 12.sp, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
            if (path != null) Text(path, color = WC.Blue, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(), color = WC.Muted, fontSize = 11.sp, letterSpacing = 0.9.sp,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
fun Card(onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp)).background(WC.Panel).clickable(onClick = onClick).padding(14.dp),
    ) { content() }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, ghost: Boolean = false) {
    Text(
        text, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold,
        color = if (ghost) WC.Accent else Color(0xFF04120A),
        modifier = Modifier.fillMaxWidth().padding(14.dp).clip(RoundedCornerShape(12.dp))
            .background(if (ghost) WC.Panel else if (enabled) WC.Accent else WC.Off)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(12.dp),
    )
}

@Composable
fun EmptyHint(text: String) {
    Text(text, color = WC.Muted, modifier = Modifier.padding(24.dp))
}
