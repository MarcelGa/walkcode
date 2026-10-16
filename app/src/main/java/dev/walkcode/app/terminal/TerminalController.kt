package dev.walkcode.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

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
        fun onFinished(session: TerminalSession) {}
        fun onModifiersChanged(ctrl: Boolean, alt: Boolean) {}
    }

    var ctrlArmed = false
        private set
    var altArmed = false
        private set

    init {
        view.setTerminalViewClient(this)
        view.setTextSize(spToPx(DEFAULT_TEXT_SP))
        view.isFocusable = true
        view.isFocusableInTouchMode = true
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
    override fun onSingleTapUp(e: MotionEvent) = showKeyboard()
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

    override fun onTextChanged(changedSession: TerminalSession) = view.onScreenUpdated()
    override fun onTitleChanged(changedSession: TerminalSession) = listener.onTitleChanged(changedSession.title)
    override fun onSessionFinished(finishedSession: TerminalSession) = listener.onFinished(finishedSession)
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
        const val DEFAULT_TEXT_SP = 12f
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
