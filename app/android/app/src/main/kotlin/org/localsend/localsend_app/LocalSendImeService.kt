package org.localsend.localsend_app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * TV keyboard input method: shows a QR code panel when any text field is focused and
 * hosts a lightweight HTTP server (port [KEYBOARD_PORT]) so a phone browser or the
 * LocalSend app can type into the focused editor over the LAN.
 *
 * Runs entirely on the Android side; the Flutter engine and the Rust transfer server
 * are not involved. Settings are read directly from FlutterSharedPreferences:
 * - flutter.ls_tv_keyboard (bool, default true): gate for the HTTP server
 * - flutter.ls_receive_pin (string?): optional PIN for /kb/* endpoints
 */
class LocalSendImeService : InputMethodService() {

    companion object {
        private const val TAG = "LocalSendIme"
        private const val PREF_NAME = "FlutterSharedPreferences"
        private const val PREF_KEY_ENABLED = "flutter.ls_tv_keyboard"
        private const val PREF_KEY_PIN = "flutter.ls_receive_pin"
        const val KEYBOARD_PORT = 53318

        private val ENTER_ACTIONS = setOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var httpServer: KeyboardHttpServer? = null

    // panel views (main thread only)
    private var qrView: ImageView? = null
    private var statusView: TextView? = null
    private var passwordWarning: TextView? = null

    // editor state, read from HTTP threads (volatile)
    @Volatile
    var editorVisible: Boolean = false
        private set

    @Volatile
    var isPasswordEditor: Boolean = false
        private set

    private var editorActionId = EditorInfo.IME_ACTION_UNSPECIFIED
    private var editorNoEnterAction = false

    override fun onCreate() {
        super.onCreate()
        startServerIfNeeded()
    }

    override fun onDestroy() {
        httpServer?.stop()
        httpServer = null
        super.onDestroy()
    }

    // region settings

    fun isKeyboardEnabled(): Boolean =
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(PREF_KEY_ENABLED, true)

    fun readPin(): String? =
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .getString(PREF_KEY_PIN, null)
            ?.takeIf { it.isNotEmpty() }

    private fun startServerIfNeeded() {
        if (httpServer != null || !isKeyboardEnabled()) {
            return
        }
        try {
            val server = KeyboardHttpServer(this, KEYBOARD_PORT)
            server.start(NanoHTTPD_SOCKET_READ_TIMEOUT, false)
            httpServer = server
            Log.i(TAG, "keyboard http server started on port $KEYBOARD_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start keyboard http server", e)
        }
    }

    // endregion

    // region panel

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.ime_panel, null)
        qrView = view.findViewById(R.id.ime_qr)
        statusView = view.findViewById(R.id.ime_status)
        passwordWarning = view.findViewById(R.id.ime_password_warning)

        val enabled = isKeyboardEnabled()
        val pin = readPin()
        val pinBadge = view.findViewById<TextView>(R.id.ime_pin_badge)
        pinBadge.visibility = if (pin != null) View.VISIBLE else View.GONE
        if (!enabled) {
            qrView?.visibility = View.GONE
            statusView?.text = getString(R.string.ime_status_disabled)
        } else {
            val url = buildKeyboardUrl(pin)
            view.findViewById<TextView>(R.id.ime_url).text = url
            renderQr(url)
        }

        view.findViewById<Button>(R.id.ime_btn_switch).setOnClickListener {
            switchToNextInputMethod()
        }
        view.findViewById<Button>(R.id.ime_btn_settings).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        view.findViewById<Button>(R.id.ime_btn_close).setOnClickListener {
            requestHideSelf(0)
        }
        // D-pad focus lands on the switch button first, so the panel is operable
        // even on ROMs without a visible IME switcher.
        view.findViewById<Button>(R.id.ime_btn_switch).requestFocus()
        return view
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        editorVisible = true
        applyEditorInfo(editorInfo)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        editorVisible = false
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        applyEditorInfo(attribute)
    }

    private fun applyEditorInfo(editorInfo: EditorInfo?) {
        if (editorInfo == null) {
            return
        }
        editorActionId = editorInfo.actionId
        editorNoEnterAction =
            (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val password = isPasswordInput(editorInfo.inputType)
        isPasswordEditor = password
        passwordWarning?.visibility = if (password) View.VISIBLE else View.GONE
    }

    private fun isPasswordInput(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val textPassword = cls == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val numberPassword = cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return textPassword || numberPassword
    }

    private fun buildKeyboardUrl(pin: String?): String {
        val base = "http://${findLanIp() ?: "0.0.0.0"}:$KEYBOARD_PORT/kb"
        return if (pin != null) "$base?pin=$pin" else base
    }

    private fun renderQr(content: String) {
        try {
            val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
            val matrix = QRCodeWriter()
                .encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
            val size = matrix.width
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            qrView?.setImageBitmap(Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565))
        } catch (e: Exception) {
            Log.e(TAG, "failed to render QR", e)
        }
    }

    private fun findLanIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces.asIterable()) {
                for (addr in intf.inetAddresses.asIterable()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to find LAN IP", e)
        }
        return null
    }

    private fun switchToNextInputMethod() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val token = window?.window?.attributes?.token
        if (token == null || !imm.switchToNextInputMethod(token, false)) {
            imm.showInputMethodPicker()
        }
    }

    // endregion

    // region input operations (called from HTTP threads)

    /**
     * Runs [block] on the main thread and waits briefly for the result, so HTTP
     * handlers get accurate `applied` flags while InputConnection stays on its
     * owning thread. Returns null on timeout.
     */
    fun <T> callOnMainThread(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "input op failed", e)
                null
            }
        }
        val latch = CountDownLatch(1)
        val ref = AtomicReference<T?>()
        mainHandler.post {
            try {
                ref.set(block())
            } catch (e: Exception) {
                Log.e(TAG, "input op failed", e)
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(1, TimeUnit.SECONDS)) ref.get() else null
    }

    fun applyInsert(text: String): Boolean = callOnMainThread {
        val ic = currentInputConnection ?: return@callOnMainThread false
        ic.commitText(text, 1)
    } ?: false

    fun applyDelete(before: Int, after: Int): Boolean = callOnMainThread {
        val ic = currentInputConnection ?: return@callOnMainThread false
        ic.deleteSurroundingText(before.coerceAtLeast(0), after.coerceAtLeast(0))
    } ?: false

    fun applySelection(a: Int, b: Int): Boolean = callOnMainThread {
        val ic = currentInputConnection ?: return@callOnMainThread false
        val extracted = try {
            ic.getExtractedText(ExtractedTextRequest(), 0)
        } catch (e: Exception) {
            null
        }
        if (extracted?.text != null) {
            val len = extracted.text.length
            ic.setSelection(a.coerceIn(0, len), b.coerceIn(0, len))
        } else {
            // editor does not expose full text; best effort
            ic.setSelection(a, b)
        }
        true
    } ?: false

    fun applyReplaceAll(text: String): Boolean = callOnMainThread {
        val ic = currentInputConnection ?: return@callOnMainThread false
        ic.finishComposingText()
        val extracted = try {
            ic.getExtractedText(ExtractedTextRequest(), 0)
        } catch (e: Exception) {
            null
        }
        if (extracted?.text != null && extracted.text.isNotEmpty()) {
            ic.setSelection(0, extracted.text.length)
            ic.commitText(text, 1)
        } else {
            ic.deleteSurroundingText(EXTRACT_ALL_LIMIT, EXTRACT_ALL_LIMIT)
            ic.commitText(text, 1)
        }
        true
    } ?: false

    fun applyRelativeCursorMove(steps: Int): Boolean = callOnMainThread {
        if (steps == 0) {
            return@callOnMainThread true
        }
        val ic = currentInputConnection ?: return@callOnMainThread false
        val extracted = try {
            ic.getExtractedText(ExtractedTextRequest(), 0)
        } catch (e: Exception) {
            null
        }
        if (extracted != null) {
            val len = extracted.text?.length ?: 0
            val target = (extracted.selectionEnd + steps).coerceIn(0, len)
            ic.setSelection(target, target)
        } else {
            // fall back to key events, e.g. custom editors without text extraction
            val code = if (steps > 0) 22 else 21 // KEYCODE_DPAD_RIGHT / KEYCODE_DPAD_LEFT
            repeat(kotlin.math.abs(steps)) {
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
            }
        }
        true
    } ?: false

    fun applyEnter(): Boolean = callOnMainThread {
        val ic = currentInputConnection ?: return@callOnMainThread false
        val usesAction = !editorNoEnterAction && editorActionId in ENTER_ACTIONS
        if (usesAction) {
            ic.performEditorAction(editorActionId)
        } else {
            ic.commitText("\n", 1)
        }
        true
    } ?: false

    fun applyTab(): Boolean = callOnMainThread {
        val ic = currentInputConnection ?: return@callOnMainThread false
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_TAB))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_TAB))
        true
    } ?: false

    fun applyContextMenuAction(action: String): Boolean = callOnMainThread {
        val ic = currentInputConnection ?: return@callOnMainThread false
        val id = when (action) {
            "selectAll" -> android.R.id.selectAll
            "cut" -> android.R.id.cut
            "copy" -> android.R.id.copy
            "paste" -> android.R.id.paste
            else -> return@callOnMainThread false
        }
        ic.performContextMenuAction(id)
    } ?: false

    // endregion
}

private const val NanoHTTPD_SOCKET_READ_TIMEOUT = 5000 // ms, matches NanoHTTPD default
private const val EXTRACT_ALL_LIMIT = 100000
