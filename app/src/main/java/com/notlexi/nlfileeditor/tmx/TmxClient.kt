package com.notlexi.nlfileeditor.tmx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Glue between Termux's terminal emulator (real PTY, real escape-sequence handling) and the app.
 * Holds the sticky CTRL / ALT state of the on-screen key row. A sticky key applies to the next key
 * and then releases (tap twice to lock).
 */
class TmxClient(private val context: Context) : TerminalViewClient, TerminalSessionClient {
    var view: TerminalView? = null
    var onFinished: ((Int) -> Unit)? = null

    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var ctrlLocked by mutableStateOf(false)
    var altLocked by mutableStateOf(false)
    var finished by mutableStateOf(false)

    var textSizePx: Int = (context.resources.displayMetrics.scaledDensity * 14f).toInt()
        private set

    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // ------------------------------------------------------------------ view client

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val step = (context.resources.displayMetrics.scaledDensity * 1f).toInt().coerceAtLeast(1)
            val min = (context.resources.displayMetrics.scaledDensity * 8f).toInt()
            val max = (context.resources.displayMetrics.scaledDensity * 32f).toInt()
            textSizePx = (textSizePx + if (scale > 1f) step else -step).coerceIn(min, max)
            view?.setTextSize(textSizePx)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        showKeyboard()
    }

    fun showKeyboard() {
        val v = view ?: return
        v.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean {
        val v = ctrl
        if (v && !ctrlLocked) ctrl = false
        return v
    }

    override fun readAltKey(): Boolean {
        val v = alt
        if (v && !altLocked) alt = false
        return v
    }

    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}

    // ------------------------------------------------------------------ on-screen keys

    fun toggleCtrl() {
        if (!ctrl) { ctrl = true; ctrlLocked = false } else if (!ctrlLocked) ctrlLocked = true else { ctrl = false; ctrlLocked = false }
    }

    fun toggleAlt() {
        if (!alt) { alt = true; altLocked = false } else if (!altLocked) altLocked = true else { alt = false; altLocked = false }
    }

    /** ESC, TAB, arrows, Home, End, PgUp, PgDn ... as a real key press (respects application-cursor mode, CTRL, ALT). */
    fun sendKey(keyCode: Int) {
        val v = view ?: return
        v.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        v.onKeyUp(keyCode, KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /** A typed character (affected by sticky CTRL / ALT: CTRL + 'c' sends ^C). */
    fun sendChar(c: Char) {
        view?.inputCodePoint(-1, c.code, false, false)
    }

    /** Raw control byte, e.g. 3 = ^C, 4 = ^D, 26 = ^Z. */
    fun sendByte(b: Int) {
        val s = view?.mTermSession ?: return
        s.write(byteArrayOf(b.toByte()), 0, 1)
    }

    /**
     * Runs a multi-line script: every line is typed into the shell followed by Enter, exactly as if pasted.
     * (A real shell then handles quoting, here-docs and loops itself.)
     */
    fun runScript(text: String) {
        val s = view?.mTermSession ?: return
        val body = text.replace("\r\n", "\n").trimEnd('\n') + "\n"
        val bytes = body.replace("\n", "\r").toByteArray(Charsets.UTF_8)
        s.write(bytes, 0, bytes.size)
    }

    fun pasteFromClipboard() {
        val s = view?.mTermSession ?: return
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        s.emulator?.paste(text)
    }

    // ------------------------------------------------------------------ session client

    override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        finished = true
        onFinished?.invoke(finishedSession.exitStatus)
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (!text.isNullOrEmpty()) clipboard.setPrimaryClip(ClipData.newPlainText("NL TMX", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) { pasteFromClipboard() }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { view?.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message ?: "", e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "error", e) }
}
