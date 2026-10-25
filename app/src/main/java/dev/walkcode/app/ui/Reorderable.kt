package dev.walkcode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A column whose rows can be reordered by holding one and dragging it. Rows keep their own
 * click handling: a plain tap still opens them, only a long press starts a drag.
 *
 * Works inside a `verticalScroll` column (the lists here are short, so no LazyColumn).
 * [onReorder] gets the keys in the new order once, on release, and only if it changed.
 * The dropped order stays on screen until [items] catches up, so there is no flicker
 * while the database write is in flight.
 */
@Composable
fun <T, K : Any> ReorderableColumn(
    items: List<T>,
    keyOf: (T) -> K,
    onReorder: (List<K>) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (item: T) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var dragged by remember { mutableStateOf<K?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    // Key order chosen by the user and not yet reflected in [items].
    var local by remember { mutableStateOf<List<K>?>(null) }
    val heights = remember { mutableStateMapOf<K, Int>() }

    val itemKeys = items.map(keyOf)
    LaunchedEffect(itemKeys) { if (dragged == null && local == itemKeys) local = null }

    val shown = local?.let { order ->
        val byKey = items.associateBy(keyOf)
        order.mapNotNull { byKey[it] } + items.filter { keyOf(it) !in order }
    } ?: items
    val shownKeys = shown.map(keyOf)
    // The gesture coroutines outlive recompositions; they read the latest order through this.
    val current by rememberUpdatedState(shownKeys)
    val reorder by rememberUpdatedState(onReorder)

    /** Moves [k] by [dy] pixels within [from] and returns the resulting key order. */
    fun drag(k: K, from: List<K>, dy: Float): List<K> {
        val order = from.toMutableList()
        offset += dy
        var i = order.indexOf(k)
        // Swap past a neighbour once the row is more than halfway over it. The swap moves the
        // row's layout slot by the neighbour's height, so the offset drops by the same amount.
        while (offset > 0 && i < order.lastIndex) {
            val h = heights[order[i + 1]] ?: break
            if (offset < h / 2f) break
            order[i] = order[i + 1].also { order[i + 1] = order[i] }
            offset -= h; i++
        }
        while (offset < 0 && i > 0) {
            val h = heights[order[i - 1]] ?: break
            if (-offset < h / 2f) break
            order[i] = order[i - 1].also { order[i - 1] = order[i] }
            offset += h; i--
        }
        return order
    }

    Column(modifier) {
        for (item in shown) {
            val k = keyOf(item)
            key(k) {
                val isDragged = dragged == k
                Box(
                    Modifier.fillMaxWidth()
                        .onSizeChanged { heights[k] = it.height }
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragged) offset else 0f
                            if (isDragged) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12f }
                        }
                        .alpha(if (isDragged) 0.92f else 1f)
                        // List rows are transparent; the lifted one needs a fill to cover the rows below.
                        .background(if (isDragged) WC.Panel else Color.Transparent, RoundedCornerShape(12.dp))
                        .pointerInput(k) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (!awaitLongPress(down)) return@awaitEachGesture
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                val before = current
                                var order = before
                                local = order
                                dragged = k
                                offset = 0f
                                // Consume in the Initial pass so neither the row's click nor the
                                // scroll container sees this gesture once the drag has started.
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) { change.consume(); break }
                                    order = drag(k, order, change.positionChange().y)
                                    local = order
                                    change.consume()
                                }
                                dragged = null
                                offset = 0f
                                if (order != before) reorder(order) else local = null
                            }
                        },
                ) { row(item) }
            }
        }
    }
}

/**
 * Waits out the long-press timeout without consuming anything, so taps and scrolls behave as
 * usual. False if the finger lifts or moves past the touch slop first.
 */
private suspend fun AwaitPointerEventScope.awaitLongPress(down: PointerInputChange): Boolean {
    // withTimeoutOrNull is null only when the timeout fired, i.e. the finger was held still.
    return withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed || change.isConsumed) break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
        }
    } == null
}
