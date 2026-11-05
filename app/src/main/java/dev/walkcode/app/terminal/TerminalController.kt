package dev.walkcode.app.terminal

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Glue between a [TerminalView], its [TerminalSession] and the special-key bar.
 * The Ctrl/Alt buttons arm a one-shot modifier that the view consumes on the next key.
 */
class TerminalController(
    private val context: Context,
    val view: TerminalView,
    private val listener: Listener,
) : TerminalViewClient, TerminalSessionClient {

    interface Listener {
        fun onTitleChanged(title: String?) {}
        fun onBell() {}
        fun onScreenChanged() {}
        fun onFinished(session: TerminalSession) {}
        fun onModifiersChanged(ctrl: Boolean, alt: Boolean) {}
        fun onScrollbackChanged(open: Boolean) {}
    }

    var ctrlArmed = false
        private set
    var altArmed = false
        private set

    /** Set to let swipes read the pane's history; without it they stay arrow keys. */
    var historySource: PaneHistorySource? = null

    /** The session on the link, also while the view shows the history in its place. */
    val liveSession: TerminalSession? get() = scrollback?.live ?: view.currentSession

    private class Scrollback(val live: TerminalSession, val history: TerminalSession)

    private enum class SwipeMode { LOADING, ARROW_KEYS, IGNORED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scrollback: Scrollback? = null
    private var closedHistory: TerminalSession? = null
    private var swipeDownTime = -1L
    private var swipeMode = SwipeMode.IGNORED
    private var loadToken = 0
    private var loadStarted = 0L
    private var pendingRows = 0
    private var pendingFling: Pair<MotionEvent, Float>? = null

    init {
        view.setTerminalViewClient(this)
        view.setTextSize(spToPx(DEFAULT_TEXT_SP))
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        // A view made in code has no theme scrollbar, so nothing would be drawn without a thumb.
        view.scrollBarSize = spToPx(SCROLLBAR_DP)
        view.verticalScrollbarThumbDrawable = GradientDrawable().apply {
            setColor(SCROLLBAR_COLOR)
            cornerRadius = spToPx(SCROLLBAR_DP).toFloat()
        }
    }

    /** Leaves the history view for the live screen; also drops a history still being fetched. */
    fun closeScrollback() {
        loadToken++
        swipeMode = SwipeMode.IGNORED
        pendingFling = null
        val open = scrollback ?: return
        scrollback = null
        if (view.isSelectingText) view.stopTextSelectionMode()
        view.isScrollbarFadingEnabled = true
        view.attachSession(open.live)
        view.onScreenUpdated()
        closedHistory = open.history
        open.history.finishIfRunning()
        listener.onScrollbackChanged(false)
    }

    private fun loadHistory(source: PaneHistorySource) {
        val token = ++loadToken
        loadStarted = SystemClock.uptimeMillis()
        swipeMode = SwipeMode.LOADING
        pendingRows = 0
        pendingFling = null
        scope.launch {
            val fullScreen = runCatching { source.isFullScreen() }.getOrDefault(false)
            if (token != loadToken) return@launch
            if (fullScreen) {
                swipeMode = SwipeMode.ARROW_KEYS
                val key = if (pendingRows < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
                repeat(abs(pendingRows)) { view.handleKeyCode(key, 0) }
                return@launch
            }
            // Below the history's end already: nothing to show, and no keys for the agent.
            if (pendingRows >= 0) {
                swipeMode = SwipeMode.IGNORED
                return@launch
            }
            val text = runCatching { source.history() }
                .onFailure { Log.w(TAG, "Could not read the pane history", it) }
                .getOrNull()
            if (token != loadToken) return@launch
            swipeMode = SwipeMode.IGNORED
            if (text != null) openScrollback(text)
        }
    }

    private fun openScrollback(text: String) {
        val live = view.currentSession ?: return
        val liveEmulator = live.emulator ?: return
        if (!live.isRunning) return
        val transport = PaneHistoryTransport { bytes, offset, count ->
            // Straight into the live session's queue, on this thread, so keys stay in order.
            live.write(bytes, offset, count)
            view.post { closeScrollback() }
        }
        val history = TerminalSession(transport, PaneHistory.ROWS, this)
        view.attachSession(history)
        val emulator = history.emulator
        if (emulator != null) {
            val bytes = PaneHistory.toTerminalBytes(text, liveEmulator.isCursorKeysApplicationMode, liveEmulator.isBracketedPasteMode)
            emulator.append(bytes, bytes.size)
        }
        val rows = emulator?.screen?.activeTranscriptRows ?: 0
        if (rows == 0) {
            // All of it fits on the screen: there is nothing to scroll to.
            view.attachSession(live)
            view.onScreenUpdated()
            closedHistory = history
            history.finishIfRunning()
            return
        }
        scrollback = Scrollback(live, history)
        view.onScreenUpdated(true)
        view.setTopRow(maxOf(-rows, pendingRows))
        view.isScrollbarFadingEnabled = false
        view.invalidate()
        pendingFling?.let { (event, velocity) -> view.startFling(event, velocity) }
        pendingFling = null
        listener.onScrollbackChanged(true)
    }

    fun toggleCtrl() { ctrlArmed = !ctrlArmed; modifiersChanged() }
    fun toggleAlt() { altArmed = !altArmed; modifiersChanged() }

    /** Sends a key the way a hardware key press would (respects cursor application mode). */
    fun sendKey(keyCode: Int) {
        val session = view.currentSession ?: return
        if (!session.isRunning) return
        var mod = 0
        if (consumeCtrl()) mod = mod or com.termux.terminal.KeyHandler.KEYMOD_CTRL
        if (consumeAlt()) mod = mod or com.termux.terminal.KeyHandler.KEYMOD_ALT
        view.handleKeyCode(keyCode, mod)
    }

    fun sendBytes(bytes: ByteArray) {
        view.currentSession?.write(bytes, 0, bytes.size)
    }

    /** Types synthetic text (dictation); [submit] follows separately as a raw CR, which hosts accept as Enter. */
    fun sendText(text: String) = sendBytes(text.toByteArray(Charsets.UTF_8))

    fun submit() = sendBytes(byteArrayOf(13))

    fun showKeyboard() {
        view.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(view, 0)
    }

    private fun consumeCtrl(): Boolean = ctrlArmed.also { if (it) { ctrlArmed = false; modifiersChanged() } }
    private fun consumeAlt(): Boolean = altArmed.also { if (it) { altArmed = false; modifiersChanged() } }
    private fun modifiersChanged() = listener.onModifiersChanged(ctrlArmed, altArmed)

    private fun spToPx(sp: Float): Int =
        (sp * context.resources.displayMetrics.density * context.resources.configuration.fontScale).toInt()

    // --- TerminalViewClient ---------------------------------------------------------------

    override fun onScale(scale: Float): Float = 1f
    override fun onSingleTapUp(e: MotionEvent) {
        val url = linkAt(e)
        if (url != null) { openLink(url); return }
        // A tap means typing is next, and that belongs on the live screen.
        closeScrollback()
        showKeyboard()
    }

    override fun onAlternateBufferScroll(event: MotionEvent, rowsDown: Int): Boolean {
        val source = historySource ?: return false
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        if (view.isSelectingText) return true
        if (event.downTime != swipeDownTime) {
            swipeDownTime = event.downTime
            // A swipe joins a fetch still running, unless that one looks stuck on a dead link.
            val running = swipeMode == SwipeMode.LOADING && SystemClock.uptimeMillis() - loadStarted < LOAD_PATIENCE_MS
            if (!running) loadHistory(source)
        }
        return when (swipeMode) {
            SwipeMode.ARROW_KEYS -> false
            SwipeMode.IGNORED -> true
            SwipeMode.LOADING -> { pendingRows += rowsDown; true }
        }
    }

    override fun onFlingStart(event: MotionEvent, velocityY: Float): Boolean {
        // A quick flick ends before the history arrives; it runs once the history is on screen.
        if (swipeMode != SwipeMode.LOADING) return false
        pendingFling = event to velocityY
        return true
    }

    override fun onTranscriptScrolled(topRow: Int, rowsDown: Int) {
        if (scrollback != null && topRow == 0 && rowsDown > 0) closeScrollback()
    }

    /**
     * The URL under a tap, if any. A long URL wraps onto the rows below it, so the whole wrapped
     * line is read and the tapped cell is located by its offset from the first row.
     */
    private fun linkAt(e: MotionEvent): String? {
        val emulator = view.currentSession?.emulator ?: return null
        val screen = emulator.screen
        val columns = emulator.mColumns
        val lastRow = emulator.mRows - 1
        val (tappedColumn, row) = view.getColumnAndRow(e, true).let { it[0] to it[1] }
        if (row < -screen.activeTranscriptRows || row > lastRow) return null

        var first = row
        while (first > -screen.activeTranscriptRows && screen.getLineWrap(first - 1)) first--
        var last = row
        while (last < lastRow && screen.getLineWrap(last)) last++

        // Rows first..last-1 are wrapped, so the text has no line breaks and each full row is
        // exactly `columns` characters. Wide characters would shift this offset; URLs rarely have them.
        val text = screen.getSelectedText(0, first, columns - 1, last)
        val offset = (row - first) * columns + tappedColumn.coerceIn(0, columns - 1)
        return TerminalLinks.urlAt(text, offset)
    }

    private fun openLink(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app to open $url", e)
        }
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = consumeCtrl()
    override fun readAltKey(): Boolean = consumeAlt()
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}

    // --- TerminalSessionClient ------------------------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession) {
        // Live output must not pull a history being read back to its bottom.
        if (changedSession === view.currentSession) view.onScreenUpdated()
        if (changedSession === liveSession) listener.onScreenChanged()
    }
    override fun onTitleChanged(changedSession: TerminalSession) = listener.onTitleChanged(changedSession.title)
    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (finishedSession !== closedHistory) listener.onFinished(finishedSession)
    }
    override fun onBell(session: TerminalSession) = listener.onBell()
    override fun onColorsChanged(session: TerminalSession) = view.invalidate()
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        session?.emulator?.paste(text)
    }

    // --- Logging (both interfaces) ----------------------------------------------------------

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "", e) }

    private companion object {
        const val TAG = "WalkcodeTerm"
        const val DEFAULT_TEXT_SP = 12f
        const val SCROLLBAR_DP = 5f
        const val SCROLLBAR_COLOR = 0xCCC9D1D9.toInt()
        const val LOAD_PATIENCE_MS = 5000L
    }
}

/** Keys shown above the system keyboard. */
enum class ExtraKey(val label: String) {
    CTRL("Ctrl"), ALT("Alt"), ESC("Esc"), TAB("Tab"), CTRL_C("^C"),
    LEFT("←"), UP("↑"), DOWN("↓"), RIGHT("→"), ENTER("⏎");

    fun press(c: TerminalController) = when (this) {
        CTRL -> c.toggleCtrl()
        ALT -> c.toggleAlt()
        ESC -> c.sendKey(KeyEvent.KEYCODE_ESCAPE)
        TAB -> c.sendKey(KeyEvent.KEYCODE_TAB)
        CTRL_C -> c.sendBytes(byteArrayOf(3))
        LEFT -> c.sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
        UP -> c.sendKey(KeyEvent.KEYCODE_DPAD_UP)
        DOWN -> c.sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
        RIGHT -> c.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        ENTER -> c.sendKey(KeyEvent.KEYCODE_ENTER)
    }
}
