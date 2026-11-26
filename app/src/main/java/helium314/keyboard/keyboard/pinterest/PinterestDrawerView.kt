// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.pinterest

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ResourceUtils
import org.json.JSONObject

class PinterestDrawerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private lateinit var controlBar: View
    private lateinit var btnBack: ImageButton
    private lateinit var btnHome: ImageButton
    private lateinit var titleView: TextView
    private lateinit var btnSend: ImageButton
    private lateinit var btnHeight: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var webViewContainer: FrameLayout
    private var webView: WebView? = null

    private lateinit var imeContainer: View
    private lateinit var imeBar: View
    private lateinit var imeLabel: TextView
    private lateinit var imeAccountBtn: ImageButton
    private lateinit var imePasteBtn: ImageButton
    private lateinit var imeHideBtn: ImageButton
    private lateinit var miniKeyboardView: MainKeyboardView

    private data class MiniLayoutKey(val width: Int, val height: Int, val subtype: String)

    private var latinIME: LatinIME? = null
    private var viewsBound = false
    private var initialized = false
    private var lastVisitedUrl: String? = null
    private var isExpandedHeight = false
    private var isImeVisible = false
    private var isSymbolsMode = false
    private var isShiftedMode = false
    private var isCapsLocked = false
    private var miniLayoutSet: KeyboardLayoutSet? = null
    private var miniLayoutKey: MiniLayoutKey? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideImeRunnable: Runnable? = null
    private val idleUnloadRunnable = Runnable {
        if (!this@PinterestDrawerView.isVisible) {
            unloadWebView(preserveUrl = true)
        }
    }

    private var lastCapturedKey: String? = null
    private var lastCapturedTimeMs: Long = 0L

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val settingsValues = Settings.getValues()
        val width = ResourceUtils.getKeyboardWidth(context, settingsValues) + paddingLeft + paddingRight
        val standardHeight = ResourceUtils.getSecondaryKeyboardHeight(resources, settingsValues)
        val screenHeight = resources.displayMetrics.heightPixels
        val expandedHeight = (screenHeight * EXPANDED_HEIGHT_FRACTION).toInt().coerceAtLeast(standardHeight)
        val typingHeight = (screenHeight * TYPING_HEIGHT_FRACTION).toInt().coerceAtLeast(expandedHeight)
        val targetHeight = when {
            isImeVisible -> typingHeight
            isExpandedHeight -> expandedHeight
            else -> standardHeight
        } + paddingTop + paddingBottom

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, targetHeight)
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun ensureInitialized() {
        if (!viewsBound) {
            viewsBound = true
            controlBar = findViewById(R.id.pinterest_control_bar)
            btnBack = findViewById(R.id.pinterest_btn_back)
            btnHome = findViewById(R.id.pinterest_btn_home)
            titleView = findViewById(R.id.pinterest_title)
            btnSend = findViewById(R.id.pinterest_btn_send)
            btnHeight = findViewById(R.id.pinterest_btn_height)
            btnClose = findViewById(R.id.pinterest_btn_close)
            progressBar = findViewById(R.id.pinterest_progress)
            webViewContainer = findViewById(R.id.pinterest_webview_container)

            imeContainer = findViewById(R.id.pinterest_ime_container)
            imeBar = findViewById(R.id.pinterest_ime_bar)
            imeLabel = findViewById(R.id.pinterest_ime_label)
            imeAccountBtn = findViewById(R.id.pinterest_ime_account)
            imePasteBtn = findViewById(R.id.pinterest_ime_paste)
            imeHideBtn = findViewById(R.id.pinterest_ime_hide)
            miniKeyboardView = findViewById(R.id.pinterest_mini_keyboard)

            applyThemeColors()
            setupControls()
            setupMiniKeyboard()
        }
        ensureWebView()
        initialized = true
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val existing = findViewById<WebView?>(R.id.pinterest_webview)
        val wv = existing ?: WebView(context).apply {
            id = R.id.pinterest_webview
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
            webViewContainer.addView(this)
        }
        webView = wv
        setupWebView(wv)
        return wv
    }

    private fun applyThemeColors() {
        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.MAIN_BACKGROUND)
        colors.setBackground(controlBar, ColorType.STRIP_BACKGROUND)
        colors.setBackground(imeBar, ColorType.STRIP_BACKGROUND)
        titleView.setTextColor(colors.get(ColorType.SUGGESTED_WORD))
        imeLabel.setTextColor(colors.get(ColorType.SUGGESTED_WORD))

        listOf(btnBack, btnHome, btnSend, btnHeight, btnClose, imeAccountBtn, imePasteBtn, imeHideBtn).forEach { btn ->
            colors.setColor(btn, ColorType.TOOL_BAR_KEY)
            colors.setBackground(btn, ColorType.STRIP_BACKGROUND)
        }
    }

    private fun setupControls() {
        btnBack.setOnClickListener {
            performKeyFeedback(it)
            val wv = webView
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                closePanel()
            }
        }
        btnHome.setOnClickListener {
            performKeyFeedback(it)
            lastVisitedUrl = PINTEREST_HOME_URL
            ensureWebView().loadUrl(PINTEREST_HOME_URL)
        }
        btnSend.setOnClickListener {
            performKeyFeedback(it)
            triggerManualSendCurrentPin()
        }
        btnHeight.setOnClickListener {
            performKeyFeedback(it)
            toggleHeight()
        }
        btnClose.setOnClickListener {
            performKeyFeedback(it)
            closePanel()
        }
        imeHideBtn.setOnClickListener {
            performKeyFeedback(it)
            setMiniKeyboardVisible(false)
            webView?.evaluateJavascript("document.activeElement && document.activeElement.blur && document.activeElement.blur();", null)
        }
        imeAccountBtn.setOnClickListener {
            performKeyFeedback(it)
            launchAccountPickerOnly()
        }
        imePasteBtn.setOnClickListener {
            performKeyFeedback(it)
            pasteClipboardIntoWebView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView(targetWebView: WebView) {
        val settings = targetWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.userAgentString = settings.userAgentString.replace("; wv", "")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(targetWebView, true)

        targetWebView.addJavascriptInterface(PinterestJsBridge(), JS_BRIDGE_NAME)

        targetWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.isVisible = newProgress in 1..99
                progressBar.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    titleView.text = title
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(TAG, "WebView JS error (${consoleMessage.lineNumber()}): ${consoleMessage.message()}")
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (resultMsg == null) return false
                launchAuthActivity(popupMessage = resultMsg, authUrl = null, autoPickAccount = true)
                return true
            }
        }

        targetWebView.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleUrlLoading(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return handleUrlLoading(request?.url?.toString())
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                setMiniKeyboardVisible(false)
                if (view != null) injectHooks(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view != null) injectHooks(view)
                runCatching { CookieManager.getInstance().flush() }
            }
        }

        targetWebView.setDownloadListener { url, userAgent, _, mimetype, _ ->
            if (url.isNullOrBlank()) return@setDownloadListener
            handleDownloadIntercepted(url, userAgent, mimetype)
        }
    }

    private fun handleUrlLoading(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return true
        val uri = Uri.parse(rawUrl)
        val scheme = uri.scheme?.lowercase() ?: return true
        if (scheme == "http" || scheme == "https") {
            val host = uri.host?.lowercase() ?: ""
            if (host == "accounts.google.com" || host.endsWith(".accounts.google.com")) {
                launchAuthActivity(popupMessage = null, authUrl = rawUrl, autoPickAccount = true)
                return true
            }
            return false // keep navigation inside WebView
        }
        // Block/ignore intent://, pinterest://, market://, etc.
        Log.d(TAG, "Blocked external redirect: $rawUrl")
        return true
    }

    private fun launchAuthActivity(popupMessage: Message?, authUrl: String?, autoPickAccount: Boolean) {
        val pkg = latinIME?.currentInputEditorInfo?.packageName
        PinterestAuthSession.setPendingPopup(popupMessage, pkg)
        val intent = Intent(context, PinterestAuthActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(PinterestAuthActivity.EXTRA_AUTO_PICK_ACCOUNT, autoPickAccount)
            if (authUrl != null) putExtra(PinterestAuthActivity.EXTRA_AUTH_URL, authUrl)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Log.e(TAG, "Failed to launch PinterestAuthActivity", it)
        }
    }

    private fun launchAccountPickerOnly() {
        PinterestAuthSession.sourcePackageName = latinIME?.currentInputEditorInfo?.packageName
        val intent = Intent(context, PinterestAuthActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(PinterestAuthActivity.EXTRA_PICKER_ONLY, true)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Log.e(TAG, "Failed to launch Google account picker", it)
        }
    }

    private fun injectHooks(view: WebView) {
        view.evaluateJavascript(JS_HOOK_SCRIPT, null)
    }

    private fun handleDownloadIntercepted(url: String, userAgent: String?, mimeHint: String?) {
        val ime = latinIME ?: return
        if (isDuplicateCapture(url)) return
        markCaptured(url)
        setMiniKeyboardVisible(false)
        PinMediaSender.showToast(ime, R.string.sending_pin)

        when {
            url.startsWith("blob:", ignoreCase = true) -> {
                val escaped = JSONObject.quote(url)
                webView?.evaluateJavascript("window.__pinReadBlobUrl($escaped, '');", null)
            }
            url.startsWith("data:", ignoreCase = true) -> {
                PinMediaSender.sendFromDataOrBlob(ime, url, mimeHint, null) {}
            }
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> {
                val effectiveMime = if (url.contains("/videos/thumbnails/", ignoreCase = true)) {
                    "video/mp4"
                } else {
                    mimeHint
                }
                PinMediaSender.sendFromUrl(
                    latinIME = ime,
                    url = url,
                    mimeHint = effectiveMime,
                    userAgent = userAgent ?: webView?.settings?.userAgentString,
                    preferOriginals = true
                ) {}
            }
        }
    }

    private fun triggerManualSendCurrentPin() {
        val ime = latinIME ?: return
        setMiniKeyboardVisible(false)
        PinMediaSender.showToast(ime, R.string.sending_pin)
        webView?.evaluateJavascript("window.__pinExtractCurrentMedia();", null)
    }

    private val miniKeyboardListener = MiniKeyboardListener()

    private fun setupMiniKeyboard() {
        miniKeyboardView.setHardwareAcceleratedDrawingEnabled(true)
        // Inflating miniKeyboardView (a MainKeyboardView) initializes PointerTracker statics for itself;
        // switch immediately back to the primary keyboard until a WebView input actually gains focus.
        restoreMainKeyboardPointerTracker()
    }

    private fun loadMiniKeyboardLayout() {
        val sv = Settings.getValues()
        val width = ResourceUtils.getKeyboardWidth(context, sv)
        val height = ResourceUtils.getKeyboardHeight(context.resources, sv)
        val subtype = RichInputMethodManager.getInstance().currentSubtype
        val currentKey = MiniLayoutKey(width, height, subtype.toString())
        if (miniLayoutSet != null && miniLayoutKey == currentKey) {
            applyMiniKeyboardElement()
            return
        }
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val builder = KeyboardLayoutSet.Builder(context, editorInfo, sv)
        builder.setKeyboardGeometry(width, height)
        builder.setSubtype(subtype)
        miniLayoutSet = builder.build()
        miniLayoutKey = currentKey
        applyMiniKeyboardElement()
    }

    private fun applyMiniKeyboardElement() {
        val layoutSet = miniLayoutSet ?: return
        val element = when {
            isSymbolsMode && isShiftedMode -> KeyboardElement.SYMBOLS_SHIFTED
            isSymbolsMode -> KeyboardElement.SYMBOLS
            isCapsLocked -> KeyboardElement.ALPHABET_SHIFT_LOCKED
            isShiftedMode -> KeyboardElement.ALPHABET_MANUAL_SHIFTED
            else -> KeyboardElement.ALPHABET
        }
        miniKeyboardView.setKeyboard(layoutSet.getKeyboard(element))
    }

    private fun setMiniKeyboardVisible(visible: Boolean) {
        hideImeRunnable?.let { mainHandler.removeCallbacks(it) }
        if (isImeVisible == visible) {
            if (!visible) restoreMainKeyboardPointerTracker()
            return
        }
        isImeVisible = visible
        imeContainer.isVisible = visible
        if (visible) {
            isSymbolsMode = false
            isShiftedMode = false
            isCapsLocked = false
            PointerTracker.cancelAllPointerTrackers()
            PointerTracker.switchTo(miniKeyboardView)
            miniKeyboardView.setKeyboardActionListener(miniKeyboardListener)
            loadMiniKeyboardLayout()
        } else {
            restoreMainKeyboardPointerTracker()
        }
        refreshLayoutAndInsets()
    }

    private fun restoreMainKeyboardPointerTracker() {
        PointerTracker.cancelAllPointerTrackers()
        val mainView = KeyboardSwitcher.getInstance().mainKeyboardView ?: return
        PointerTracker.switchTo(mainView)
        latinIME?.let { mainView.setKeyboardActionListener(it.mKeyboardActionListener) }
        mainView.keyboard?.let { mainView.setKeyboard(it) }
    }

    private fun toggleHeight() {
        isExpandedHeight = !isExpandedHeight
        btnHeight.setImageResource(
            if (isExpandedHeight) R.drawable.ic_pinterest_collapse else R.drawable.ic_pinterest_expand
        )
        refreshLayoutAndInsets()
    }

    private fun refreshLayoutAndInsets() {
        requestLayout()
        invalidate()
        post { KeyboardSwitcher.getInstance().updateSoftInputWindowLayoutParameters() }
    }

    fun startPinterestDrawer(latinIme: LatinIME) {
        latinIME = latinIme
        mainHandler.removeCallbacks(idleUnloadRunnable)
        ensureInitialized()
        applyThemeColors()
        setMiniKeyboardVisible(false)
        val wv = ensureWebView()
        wv.onResume()
        if (PinterestAuthSession.pendingReloadHome) {
            PinterestAuthSession.pendingReloadHome = false
            lastVisitedUrl = PINTEREST_HOME_URL
            wv.loadUrl(PINTEREST_HOME_URL)
        } else if (wv.url.isNullOrBlank()) {
            wv.loadUrl(lastVisitedUrl ?: PINTEREST_HOME_URL)
        }
        val fillEmail = PinterestAuthSession.pendingFillEmail
        if (!fillEmail.isNullOrBlank()) {
            PinterestAuthSession.pendingFillEmail = null
            postDelayed({ fillEmailInDrawerWebView(fillEmail) }, 250L)
        }
        refreshLayoutAndInsets()
    }

    private fun fillEmailInDrawerWebView(email: String) {
        val wv = webView ?: return
        if (!initialized) return
        val quoted = JSONObject.quote(email)
        wv.evaluateJavascript(
            """
            (function(email) {
                var el = document.activeElement;
                if (!el || el.tagName !== 'INPUT') {
                    el = document.querySelector('input[type="email"], input[name="id"], input[name="email"]');
                }
                if (!el) return;
                el.focus();
                var desc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
                if (desc && desc.set) desc.set.call(el, email); else el.value = email;
                el.dispatchEvent(new InputEvent('input', { bubbles: true, data: email, inputType: 'insertText' }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            })($quoted);
            """.trimIndent(),
            null
        )
    }

    fun stopPinterestDrawer() {
        if (!viewsBound) return
        setMiniKeyboardVisible(false)
        webView?.let { wv ->
            runCatching {
                wv.evaluateJavascript(
                    "if (document.activeElement && document.activeElement.blur) document.activeElement.blur();",
                    null
                )
                wv.clearFocus()
                wv.onPause()
                CookieManager.getInstance().flush()
            }
            mainHandler.removeCallbacks(idleUnloadRunnable)
            mainHandler.postDelayed(idleUnloadRunnable, IDLE_UNLOAD_DELAY_MS)
        }
        restoreMainKeyboardPointerTracker()
    }

    fun onHideWindow() {
        stopPinterestDrawer()
    }

    fun trimMemory() {
        miniLayoutSet = null
        miniLayoutKey = null
        if (!this.isVisible && webView != null) {
            unloadWebView(preserveUrl = true)
        }
    }

    private fun unloadWebView(preserveUrl: Boolean) {
        mainHandler.removeCallbacks(idleUnloadRunnable)
        hideImeRunnable?.let { mainHandler.removeCallbacks(it) }
        val wv = webView ?: return
        runCatching {
            if (preserveUrl) {
                lastVisitedUrl = wv.url?.takeIf { it.startsWith("http", ignoreCase = true) } ?: lastVisitedUrl
            }
            CookieManager.getInstance().flush()
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearCache(false)
            if (viewsBound) {
                webViewContainer.removeView(wv)
            }
            wv.destroy()
        }
        webView = null
        initialized = false
    }

    fun destroyPinterestDrawer() {
        unloadWebView(preserveUrl = false)
    }

    fun handleBackPressed(): Boolean {
        if (!initialized) return false
        if (isImeVisible) {
            setMiniKeyboardVisible(false)
            return true
        }
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return true
        }
        closePanel()
        return true
    }

    private fun closePanel() {
        setMiniKeyboardVisible(false)
        KeyboardSwitcher.getInstance().closePinterestDrawer()
    }

    private fun performKeyFeedback(view: View) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
            KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS
        )
    }

    private fun pasteClipboardIntoWebView() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        if (text.isNotEmpty()) {
            commitTextToWebView(text)
        }
    }

    private fun commitTextToWebView(text: String) {
        val quoted = JSONObject.quote(text)
        webView?.evaluateJavascript("window.__pinInsertText($quoted);", null)
    }

    private fun backspaceInWebView() {
        webView?.evaluateJavascript("window.__pinBackspace();", null)
    }

    private fun submitEnterInWebView() {
        val now = SystemClock.uptimeMillis()
        webView?.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        webView?.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        webView?.evaluateJavascript("window.__pinSubmitEnter();", null)
        setMiniKeyboardVisible(false)
    }

    @Synchronized
    private fun isDuplicateCapture(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        return lastCapturedKey == key && (now - lastCapturedTimeMs) < 1500L
    }

    @Synchronized
    private fun markCaptured(key: String) {
        lastCapturedKey = key
        lastCapturedTimeMs = SystemClock.elapsedRealtime()
    }

    private inner class MiniKeyboardListener : KeyboardActionListener.Adapter() {
        override fun onPressKey(primaryCode: Int, repeatCount: Int, pointerCount: Int, hapticEvent: HapticEvent) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                primaryCode, miniKeyboardView, hapticEvent
            )
        }

        override fun onTextInput(text: String?) {
            if (!this@PinterestDrawerView.isVisible || !this@PinterestDrawerView.isShown || !isImeVisible) {
                restoreMainKeyboardPointerTracker()
                return
            }
            if (!text.isNullOrEmpty()) {
                commitTextToWebView(text)
            }
        }

        override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
            if (!this@PinterestDrawerView.isVisible || !this@PinterestDrawerView.isShown || !isImeVisible) {
                restoreMainKeyboardPointerTracker()
                return
            }
            when (primaryCode) {
                KeyCode.DELETE -> backspaceInWebView()
                KeyCode.SHIFT -> {
                    if (isSymbolsMode) {
                        isShiftedMode = !isShiftedMode
                    } else {
                        isShiftedMode = !isShiftedMode
                        isCapsLocked = false
                    }
                    applyMiniKeyboardElement()
                }
                KeyCode.CAPS_LOCK -> {
                    if (!isSymbolsMode) {
                        isCapsLocked = !isCapsLocked
                        isShiftedMode = isCapsLocked
                        applyMiniKeyboardElement()
                    }
                }
                KeyCode.SYMBOL, KeyCode.SYMBOL_ALPHA -> {
                    isSymbolsMode = !isSymbolsMode
                    isShiftedMode = false
                    isCapsLocked = false
                    applyMiniKeyboardElement()
                }
                KeyCode.ALPHA -> {
                    isSymbolsMode = false
                    isShiftedMode = false
                    isCapsLocked = false
                    applyMiniKeyboardElement()
                }
                Constants.CODE_ENTER, KeyCode.ACTION_NEXT -> submitEnterInWebView()
                KeyCode.CLIPBOARD_PASTE -> pasteClipboardIntoWebView()
                else -> {
                    if (primaryCode > 0) {
                        val str = String(Character.toChars(primaryCode))
                        commitTextToWebView(str)
                        if (!isSymbolsMode && isShiftedMode && !isCapsLocked) {
                            isShiftedMode = false
                            applyMiniKeyboardElement()
                        }
                    }
                }
            }
        }
    }

    private inner class PinterestJsBridge {
        @JavascriptInterface
        fun onMediaUrlCaptured(url: String?, filename: String?) {
            if (url.isNullOrBlank()) return
            mainHandler.post {
                handleDownloadIntercepted(url, webView?.settings?.userAgentString, null)
            }
        }

        @JavascriptInterface
        fun onBlobCaptured(dataUrl: String?, mimeType: String?, filename: String?) {
            if (dataUrl.isNullOrBlank()) return
            val dedupeKey = "blob:${dataUrl.length}:${dataUrl.take(48)}"
            mainHandler.post {
                val ime = latinIME ?: return@post
                if (isDuplicateCapture(dedupeKey)) return@post
                markCaptured(dedupeKey)
                setMiniKeyboardVisible(false)
                PinMediaSender.showToast(ime, R.string.sending_pin)
                PinMediaSender.sendFromDataOrBlob(ime, dataUrl, mimeType, filename) {}
            }
        }

        @JavascriptInterface
        fun onExtractedPinMedia(url: String?, mimeHint: String?, filename: String?) {
            mainHandler.post {
                val ime = latinIME ?: return@post
                if (url.isNullOrBlank()) {
                    PinMediaSender.showToast(ime, R.string.pinterest_no_media_found)
                    return@post
                }
                when {
                    url.startsWith("blob:", ignoreCase = true) -> {
                        val escaped = JSONObject.quote(url)
                        webView?.evaluateJavascript("window.__pinReadBlobUrl($escaped, '');", null)
                    }
                    url.startsWith("data:", ignoreCase = true) -> {
                        PinMediaSender.sendFromDataOrBlob(ime, url, mimeHint, filename) {}
                    }
                    else -> {
                        PinMediaSender.sendFromUrl(
                            latinIME = ime,
                            url = url,
                            mimeHint = mimeHint,
                            userAgent = webView?.settings?.userAgentString,
                            preferOriginals = true
                        ) {}
                    }
                }
            }
        }

        @JavascriptInterface
        fun onVideoMenuDownloadClicked() {
            mainHandler.post {
                val ime = latinIME ?: return@post
                setMiniKeyboardVisible(false)
                PinMediaSender.showToast(ime, R.string.sending_pin)
                webView?.evaluateJavascript("window.__pinExtractCurrentMedia(true);", null)
            }
        }

        @JavascriptInterface
        fun onDebugLog(message: String?) {
            if (!message.isNullOrBlank()) {
                Log.d(TAG, "JS: $message")
            }
        }

        @JavascriptInterface
        fun onWebInputFocused(focused: Boolean, inputType: String?) {
            mainHandler.post {
                hideImeRunnable?.let { mainHandler.removeCallbacks(it) }
                if (!this@PinterestDrawerView.isVisible || !this@PinterestDrawerView.isShown) {
                    setMiniKeyboardVisible(false)
                    return@post
                }
                if (focused) {
                    setMiniKeyboardVisible(true)
                } else {
                    val runnable = Runnable { setMiniKeyboardVisible(false) }
                    hideImeRunnable = runnable
                    mainHandler.postDelayed(runnable, 180L)
                }
            }
        }
    }

    companion object {
        private const val TAG = "PinterestDrawerView"
        private const val PINTEREST_HOME_URL = "https://www.pinterest.com/"
        private const val JS_BRIDGE_NAME = "PinterestBridge"
        private const val EXPANDED_HEIGHT_FRACTION = 0.55f
        private const val TYPING_HEIGHT_FRACTION = 0.64f
        private const val IDLE_UNLOAD_DELAY_MS = 180_000L

        private val JS_HOOK_SCRIPT = """
            (function() {
                if (window.__pinEnsureSheetObservers) {
                    try { window.__pinEnsureSheetObservers(); } catch (e) {}
                }
                if (window.__pinTryInjectVideoMenu) {
                    try { window.__pinTryInjectVideoMenu(); } catch (e) {}
                }
                if (window.__pinKeyboardHooked) return;
                window.__pinKeyboardHooked = true;
                window.__pinBlobMap = new Map();
                window.__pinVideoHashMap = new Map();
                var VIDEO_HASH_RE = /([0-9a-f]{2}\/[0-9a-f]{2}\/[0-9a-f]{2}\/[0-9a-f]{16,40})/i;

                function scoreMp4Quality(url) {
                    var l = url.toLowerCase();
                    if (l.indexOf('1080') >= 0) return 1080;
                    if (l.indexOf('720p') >= 0 || l.indexOf('_720w') >= 0) return 720;
                    if (l.indexOf('540') >= 0) return 540;
                    if (l.indexOf('480') >= 0) return 480;
                    if (l.indexOf('360') >= 0) return 360;
                    return 400;
                }

                function recordMp4Url(url) {
                    if (!url || url.indexOf('.mp4') < 0 || url.indexOf('pinimg.com/videos/') < 0) return;
                    var clean = url.replace(/\\\//g, '/');
                    var m = clean.match(VIDEO_HASH_RE);
                    if (!m) return;
                    var hash = m[1].toLowerCase();
                    var prev = window.__pinVideoHashMap.get(hash);
                    if (!prev || scoreMp4Quality(clean) >= scoreMp4Quality(prev)) {
                        if (window.__pinVideoHashMap.size >= 40 && !window.__pinVideoHashMap.has(hash)) {
                            var oldest = window.__pinVideoHashMap.keys().next().value;
                            window.__pinVideoHashMap.delete(oldest);
                        }
                        window.__pinVideoHashMap.set(hash, clean);
                    }
                }

                function recordVideoText(txt) {
                    if (!txt || txt.indexOf('pinimg.com') < 0 || txt.indexOf('.mp4') < 0) return;
                    var norm = txt.replace(/\\\//g, '/');
                    var matches = norm.match(/https:\/\/v\d*\.pinimg\.com\/videos\/[^"'\s\\<>]+\.mp4/g);
                    if (matches) {
                        for (var i = 0; i < matches.length; i++) {
                            recordMp4Url(matches[i]);
                        }
                    }
                }

                if (window.fetch) {
                    var origFetch = window.fetch;
                    window.fetch = function() {
                        var p = origFetch.apply(this, arguments);
                        try {
                            var req = arguments[0];
                            var u = typeof req === 'string' ? req : (req && req.url) || '';
                            if (u.indexOf('/resource/') >= 0 || u.indexOf('/graphql') >= 0) {
                                p.then(function(resp) {
                                    if (resp && resp.clone) {
                                        resp.clone().text().then(recordVideoText).catch(function() {});
                                    }
                                }).catch(function() {});
                            }
                        } catch (e) {}
                        return p;
                    };
                }

                if (window.XMLHttpRequest && XMLHttpRequest.prototype) {
                    var origXhrOpen = XMLHttpRequest.prototype.open;
                    var origXhrSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__pinUrl = typeof url === 'string' ? url : '';
                        return origXhrOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        try {
                            var u = this.__pinUrl || '';
                            if (u.indexOf('/resource/') >= 0 || u.indexOf('/graphql') >= 0) {
                                this.addEventListener('load', function() {
                                    try {
                                        if (typeof this.responseText === 'string') {
                                            recordVideoText(this.responseText);
                                        }
                                    } catch (e) {}
                                });
                            }
                        } catch (e) {}
                        return origXhrSend.apply(this, arguments);
                    };
                }

                function storeBlob(url, blob) {
                    try {
                        if (window.__pinBlobMap.size >= 3) {
                            var first = window.__pinBlobMap.keys().next().value;
                            window.__pinBlobMap.delete(first);
                        }
                        window.__pinBlobMap.set(url, blob);
                    } catch (e) {}
                }

                var origCreateObjectURL = URL.createObjectURL;
                URL.createObjectURL = function(obj) {
                    var result = origCreateObjectURL.apply(this, arguments);
                    try {
                        if (obj instanceof Blob) storeBlob(result, obj);
                    } catch (e) {}
                    return result;
                };

                window.__pinReadBlobUrl = function(blobUrl, filename) {
                    var cached = window.__pinBlobMap.get(blobUrl);
                    var sendBlob = function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            if (reader.result) {
                                window.PinterestBridge.onBlobCaptured(reader.result, blob.type || '', filename || '');
                            }
                        };
                        reader.readAsDataURL(blob);
                    };
                    if (cached) {
                        window.__pinBlobMap.delete(blobUrl);
                        sendBlob(cached);
                        return;
                    }
                    fetch(blobUrl).then(function(r) { return r.blob(); }).then(sendBlob).catch(function() {
                        window.PinterestBridge.onExtractedPinMedia('', '', '');
                    });
                };

                function handleAnchorDownload(anchor, event) {
                    if (!anchor) return false;
                    var href = anchor.getAttribute('href') || anchor.href || '';
                    if (!href) return false;
                    var hasDownload = anchor.hasAttribute('download') || href.indexOf('blob:') === 0;
                    if (!hasDownload) return false;
                    if (event) {
                        try { event.preventDefault(); event.stopPropagation(); } catch (e) {}
                    }
                    var filename = anchor.getAttribute('download') || '';
                    if (href.indexOf('blob:') === 0) {
                        window.__pinReadBlobUrl(href, filename);
                    } else if (href.indexOf('data:') === 0) {
                        window.PinterestBridge.onBlobCaptured(href, '', filename);
                    } else if (href.indexOf('http://') === 0 || href.indexOf('https://') === 0) {
                        window.PinterestBridge.onMediaUrlCaptured(href, filename);
                    }
                    return true;
                }

                var origAnchorClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    if (handleAnchorDownload(this, null)) return;
                    return origAnchorClick.apply(this, arguments);
                };

                document.addEventListener('click', function(e) {
                    var target = e.target;
                    var anchor = target && target.closest ? target.closest('a') : null;
                    if (anchor && anchor.hasAttribute('download')) {
                        handleAnchorDownload(anchor, e);
                    }
                }, true);

                function isTextEditable(el) {
                    if (!el) return false;
                    if (el.isContentEditable) return true;
                    if (el.tagName === 'TEXTAREA') return !el.readOnly && !el.disabled;
                    if (el.tagName === 'INPUT') {
                        var t = (el.type || 'text').toLowerCase();
                        return ['text','search','email','password','url','tel','number'].indexOf(t) >= 0 && !el.readOnly && !el.disabled;
                    }
                    return false;
                }

                document.addEventListener('focusin', function(e) {
                    if (isTextEditable(e.target)) {
                        window.PinterestBridge.onWebInputFocused(true, e.target.type || 'text');
                        setTimeout(function() {
                            try { e.target.scrollIntoView({ behavior: 'smooth', block: 'center' }); } catch (err) {}
                        }, 200);
                    }
                }, true);

                document.addEventListener('focusout', function() {
                    setTimeout(function() {
                        if (!isTextEditable(document.activeElement)) {
                            window.PinterestBridge.onWebInputFocused(false, '');
                        }
                    }, 50);
                }, true);

                window.__pinInsertText = function(text) {
                    var el = document.activeElement;
                    if (!el || !isTextEditable(el)) return false;
                    try { el.scrollIntoView({ block: 'nearest' }); } catch (err) {}
                    if (el.isContentEditable) {
                        document.execCommand('insertText', false, text);
                        return true;
                    }
                    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                    var start = el.selectionStart != null ? el.selectionStart : el.value.length;
                    var end = el.selectionEnd != null ? el.selectionEnd : el.value.length;
                    var next = el.value.slice(0, start) + text + el.value.slice(end);
                    if (desc && desc.set) desc.set.call(el, next); else el.value = next;
                    try { el.setSelectionRange(start + text.length, start + text.length); } catch (e) {}
                    el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, data: text, inputType: 'insertText' }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                };

                window.__pinBackspace = function() {
                    var el = document.activeElement;
                    if (!el || !isTextEditable(el)) return false;
                    if (el.isContentEditable) {
                        document.execCommand('delete', false, null);
                        return true;
                    }
                    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                    var start = el.selectionStart != null ? el.selectionStart : el.value.length;
                    var end = el.selectionEnd != null ? el.selectionEnd : el.value.length;
                    if (start === 0 && end === 0) return true;
                    var delStart = start === end ? Math.max(0, start - 1) : start;
                    var next = el.value.slice(0, delStart) + el.value.slice(end);
                    if (desc && desc.set) desc.set.call(el, next); else el.value = next;
                    try { el.setSelectionRange(delStart, delStart); } catch (e) {}
                    el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, inputType: 'deleteContentBackward' }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                };

                window.__pinSubmitEnter = function() {
                    var el = document.activeElement;
                    if (!el) return;
                    try {
                        el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
                        el.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
                        el.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                        if (el.form && typeof el.form.requestSubmit === 'function') {
                            el.form.requestSubmit();
                        } else if (el.form && typeof el.form.submit === 'function') {
                            el.form.submit();
                        }
                    } catch (e) {}
                };

                function pickBestSrcFromSrcset(img) {
                    var srcset = img.getAttribute('srcset') || '';
                    if (!srcset) return img.currentSrc || img.src || '';
                    var bestUrl = '';
                    var bestScore = -1;
                    srcset.split(',').forEach(function(part) {
                        var tokens = part.trim().split(/\s+/);
                        if (!tokens[0]) return;
                        var score = 0;
                        if (tokens[1]) {
                            var num = parseFloat(tokens[1]);
                            if (!isNaN(num)) score = num;
                        }
                        if (tokens[0].indexOf('/originals/') >= 0) score += 10000;
                        if (score >= bestScore) {
                            bestScore = score;
                            bestUrl = tokens[0];
                        }
                    });
                    return bestUrl || img.currentSrc || img.src || '';
                }

                function findBestVisibleVideo() {
                    var videos = Array.from(document.querySelectorAll('video'));
                    if (videos.length === 0) return null;
                    var best = null;
                    var bestScore = -1;
                    var vw = window.innerWidth || 400;
                    var vh = window.innerHeight || 600;
                    videos.forEach(function(v) {
                        var r = v.getBoundingClientRect();
                        if (r.width < 40 || r.height < 40) return;
                        var visH = Math.min(r.bottom, vh) - Math.max(r.top, 0);
                        var visW = Math.min(r.right, vw) - Math.max(r.left, 0);
                        var bonus = 0;
                        if (visH > 10 && visW > 10) bonus += 100000;
                        try {
                            if (!v.paused && !v.ended) bonus += 1000000;
                            if (v.closest && v.closest('[data-test-id*="closeup"], [data-test-id*="story-pin"]')) bonus += 500000;
                        } catch (e) {}
                        var area = r.width * r.height + bonus;
                        if (area > bestScore) {
                            bestScore = area;
                            best = v;
                        }
                    });
                    return best || videos[0];
                }

                function collectVideoFromObject(rootObj) {
                    if (!rootObj) return '';
                    var seen = new Set();
                    var queue = [{ o: rootObj, d: 0 }];
                    var bestMp4 = '';
                    var bestMp4Score = -1;
                    var bestHls = '';
                    var count = 0;
                    while (queue.length > 0 && count < 150) {
                        var item = queue.shift();
                        var o = item.o;
                        var d = item.d;
                        if (!o || typeof o !== 'object' || seen.has(o)) continue;
                        seen.add(o);
                        count++;
                        var vl = o.video_list;
                        if (vl && typeof vl === 'object') {
                            var pref = ['V_EXP7', 'V_720P', 'V_EXP6', 'V_480P', 'V_EXP5', 'V_360P', 'V_HLSV4', 'V_HLSV3_MOBILE', 'V_HLSV3_WEB'];
                            var allKeys = pref.concat(Object.keys(vl));
                            for (var k = 0; k < allKeys.length; k++) {
                                var entry = vl[allKeys[k]];
                                var u = entry && entry.url;
                                if (typeof u !== 'string') continue;
                                if (u.indexOf('.mp4') >= 0) {
                                    recordMp4Url(u);
                                    var sc = scoreMp4Quality(u);
                                    if (sc > bestMp4Score) {
                                        bestMp4Score = sc;
                                        bestMp4 = u;
                                    }
                                } else if (!bestHls && u.indexOf('pinimg.com/videos/') >= 0 && u.indexOf('.m3u8') >= 0) {
                                    bestHls = u;
                                }
                            }
                            if (bestMp4) return bestMp4;
                        }
                        if (d < 6) {
                            var keys = Array.isArray(o) ? o : Object.keys(o);
                            var limit = Array.isArray(o) ? Math.min(o.length, 16) : Math.min(keys.length, 30);
                            for (var i = 0; i < limit; i++) {
                                var val = Array.isArray(o) ? o[i] : o[keys[i]];
                                if (typeof val === 'string') {
                                    if (val.indexOf('pinimg.com/videos/') >= 0) {
                                        if (val.indexOf('.mp4') >= 0) {
                                            recordMp4Url(val);
                                            var vs = scoreMp4Quality(val);
                                            if (vs > bestMp4Score) {
                                                bestMp4Score = vs;
                                                bestMp4 = val;
                                            }
                                        } else if (!bestHls && val.indexOf('.m3u8') >= 0) {
                                            bestHls = val;
                                        }
                                    }
                                } else if (val && typeof val === 'object') {
                                    queue.push({ o: val, d: d + 1 });
                                }
                            }
                        }
                    }
                    return bestMp4 || bestHls;
                }

                function findVideoInFiber(videoEl) {
                    try {
                        var el = videoEl;
                        var bestHls = '';
                        for (var up = 0; up < 3 && el; up++) {
                            var keys = Object.keys(el);
                            for (var i = 0; i < keys.length; i++) {
                                var key = keys[i];
                                if (key.indexOf('__reactProps') === 0) {
                                    var found = collectVideoFromObject(el[key]);
                                    if (found && found.indexOf('.mp4') >= 0) return found;
                                    if (!bestHls && found && found.indexOf('.m3u8') >= 0) bestHls = found;
                                }
                                if (key.indexOf('__reactFiber') === 0 || key.indexOf('__reactInternalInstance') === 0) {
                                    var f = el[key];
                                    for (var steps = 0; steps < 9 && f; steps++) {
                                        var p = f.memoizedProps || f.pendingProps;
                                        if (p && typeof p.src === 'string' && p.src.indexOf('pinimg.com/videos/') >= 0) {
                                            if (p.src.indexOf('.mp4') >= 0) return p.src;
                                            if (!bestHls && p.src.indexOf('.m3u8') >= 0) bestHls = p.src;
                                        }
                                        if (p && typeof p.hlsUrl === 'string' && p.hlsUrl.indexOf('pinimg.com/videos/') >= 0) {
                                            if (!bestHls && p.hlsUrl.indexOf('.m3u8') >= 0) bestHls = p.hlsUrl;
                                        }
                                        if (!p || (!p.relatedPins && !p.storyPinData)) {
                                            var pFound = collectVideoFromObject(p);
                                            if (pFound && pFound.indexOf('.mp4') >= 0) return pFound;
                                            if (!bestHls && pFound && pFound.indexOf('.m3u8') >= 0) bestHls = pFound;
                                        }
                                        f = f.return;
                                    }
                                }
                            }
                            el = el.parentElement;
                        }
                        if (bestHls) {
                            var hm = bestHls.match(VIDEO_HASH_RE);
                            if (hm && window.__pinVideoHashMap.has(hm[1].toLowerCase())) {
                                return window.__pinVideoHashMap.get(hm[1].toLowerCase());
                            }
                            return bestHls;
                        }
                    } catch (e) {}
                    return '';
                }

                function findVideoHash(videoEl) {
                    // 1. Live network performance entries strictly matching pinimg.com/videos/
                    try {
                        if (window.performance && performance.getEntriesByType) {
                            var entries = performance.getEntriesByType('resource');
                            var fallbackPerfHash = '';
                            for (var j = entries.length - 1; j >= 0 && j >= entries.length - 100; j--) {
                                var name = entries[j].name || '';
                                if (name.indexOf('pinimg.com/videos/') >= 0) {
                                    var rm = name.match(VIDEO_HASH_RE);
                                    if (rm) {
                                        if (name.indexOf('.mp4') >= 0 || name.indexOf('.m3u8') >= 0) {
                                            return rm[1].toLowerCase();
                                        }
                                        if (!fallbackPerfHash) fallbackPerfHash = rm[1].toLowerCase();
                                    }
                                }
                            }
                            if (fallbackPerfHash) return fallbackPerfHash;
                        }
                    } catch (e) {}
                    // 2. Video element sources & strictly /videos/ thumbnail URLs (never 736x cover images)
                    var srcs = [];
                    if (videoEl) {
                        if (videoEl.currentSrc && videoEl.currentSrc.indexOf('pinimg.com/videos/') >= 0) srcs.push(videoEl.currentSrc);
                        if (videoEl.src && videoEl.src.indexOf('pinimg.com/videos/') >= 0) srcs.push(videoEl.src);
                        if (videoEl.poster && videoEl.poster.indexOf('/videos/') >= 0) srcs.push(videoEl.poster);
                    }
                    for (var i = 0; i < srcs.length; i++) {
                        var m = srcs[i].match(VIDEO_HASH_RE);
                        if (m) return m[1].toLowerCase();
                    }
                    return '';
                }

                function extractPinIdForVideo(videoEl) {
                    var pm = location.pathname.match(/\/pin\/(?:[^\/]+--)?(\d{8,24})/);
                    if (pm) return pm[1];
                    if (videoEl && videoEl.closest) {
                        var link = videoEl.closest('a[href*="/pin/"]');
                        if (link) {
                            var lm = (link.getAttribute('href') || '').match(/\/pin\/(?:[^\/]+--)?(\d{8,24})/);
                            if (lm) return lm[1];
                        }
                    }
                    return '';
                }

                function scanScriptsForVideo(hash) {
                    var scripts = Array.from(document.querySelectorAll('script[type="application/ld+json"], script#__PWS_DATA__, script[data-relay-response], script'));
                    var fallbackHls = '';
                    for (var j = 0; j < scripts.length; j++) {
                        var txt = scripts[j].textContent || '';
                        if (txt.indexOf('pinimg.com/videos/') < 0) continue;
                        if (txt.indexOf('.mp4') >= 0) recordVideoText(txt);
                        if (hash && window.__pinVideoHashMap.has(hash)) {
                            return window.__pinVideoHashMap.get(hash);
                        }
                        if (!hash && txt.indexOf('.mp4') >= 0) {
                            var norm = txt.replace(/\\\//g, '/');
                            var m = norm.match(/https:\/\/v\d*\.pinimg\.com\/videos\/[^"'\s\\<>]+\.mp4/);
                            if (m && m[0]) return m[0];
                        }
                        if (!fallbackHls && txt.indexOf('.m3u8') >= 0) {
                            var hnorm = txt.replace(/\\\//g, '/');
                            if (hash && hnorm.indexOf(hash) >= 0) {
                                fallbackHls = 'https://v1.pinimg.com/videos/iht/hls/' + hash + '.m3u8';
                            } else if (!hash) {
                                var hm = hnorm.match(/https:\/\/v\d*\.pinimg\.com\/videos\/[^"'\s\\<>]+\.m3u8/);
                                if (hm && hm[0]) fallbackHls = hm[0];
                            }
                        }
                    }
                    if (hash && window.__pinVideoHashMap.has(hash)) return window.__pinVideoHashMap.get(hash);
                    return fallbackHls;
                }

                function replaceMenuLabelText(rootEl, newText) {
                    var walker = document.createTreeWalker(rootEl, NodeFilter.SHOW_TEXT, null);
                    var node;
                    var replaced = false;
                    while ((node = walker.nextNode())) {
                        if ((node.nodeValue || '').trim().length > 0) {
                            if (!replaced) {
                                node.nodeValue = newText;
                                replaced = true;
                            } else {
                                node.nodeValue = '';
                            }
                        }
                    }
                    if (!replaced) {
                        rootEl.textContent = newText;
                    }
                }

                var lastMenuDbg = '';
                function dbgMenu(msg) {
                    if (!msg || msg === lastMenuDbg) return;
                    lastMenuDbg = msg;
                    try {
                        if (window.PinterestBridge && window.PinterestBridge.onDebugLog) {
                            window.PinterestBridge.onDebugLog(msg);
                        }
                    } catch (e) {}
                }

                // Inject a "Download video" option into Pinterest's ... bottom sheet on video pins
                function tryInjectVideoMenuOption() {
                    var elPrimary = null;
                    var elSecondary = null;

                    function findVisibleTestId(tid) {
                        var nodes = document.querySelectorAll('[data-test-id="' + tid + '"]');
                        var fallback = null;
                        for (var ni = nodes.length - 1; ni >= 0; ni--) {
                            var n = nodes[ni];
                            if (n.closest && n.closest('[aria-hidden="true"]')) continue;
                            var r = n.getBoundingClientRect();
                            var cs = window.getComputedStyle(n);
                            if (r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none') {
                                if (parseFloat(cs.opacity || '1') > 0) return n;
                                if (!fallback) fallback = n;
                            }
                        }
                        return fallback;
                    }

                    // Fast exact data-test-id match used by Pinterest mobile context menus
                    var tidCands = [
                        findVisibleTestId('more-options-open-app'),
                        findVisibleTestId('more-options-see-more'),
                        findVisibleTestId('more-options-hide-pin'),
                        findVisibleTestId('more-options-report-pin'),
                        findVisibleTestId('more-options-save'),
                        findVisibleTestId('more-options-share')
                    ].filter(function(el) { return !!el; });
                    if (tidCands.length >= 2) {
                        elPrimary = tidCands[0];
                        elSecondary = tidCands[1];
                    }

                    // Fallback visible-label scan (do not filter out items below innerHeight when drawer is short)
                    if (!elPrimary || !elSecondary) {
                        var leafMap = {};
                        var allEls = document.querySelectorAll('div, span, p, a, button, h1, h2, h3, h4, li, [role="button"], [role="menuitem"]');
                        for (var i = 0; i < allEls.length; i++) {
                            var el = allEls[i];
                            if (el.closest && el.closest('[aria-hidden="true"]')) continue;
                            var hasBlockChild = false;
                            for (var ci = 0; ci < el.children.length; ci++) {
                                var ctag = (el.children[ci].tagName || '').toLowerCase();
                                if (ctag !== 'svg' && ctag !== 'path') {
                                    hasBlockChild = true;
                                    break;
                                }
                            }
                            if (hasBlockChild) continue;
                            var t = (el.textContent || '').trim();
                            if (t !== 'Open in app' && t !== 'See more' && t !== 'See less' &&
                                t !== 'Report Pin' && t !== 'Save image' && t !== 'Share' &&
                                t !== 'Copy link' && t !== 'Hide Pin') {
                                continue;
                            }
                            var r = el.getBoundingClientRect();
                            var cs = window.getComputedStyle(el);
                            if (r.width <= 10 || r.height <= 8 || cs.visibility === 'hidden' || cs.display === 'none') continue;
                            leafMap[t] = el;
                        }
                        var labelOrder = ['Open in app', 'Save image', 'See more', 'See less', 'Hide Pin', 'Copy link', 'Report Pin', 'Share'];
                        var foundLeaves = [];
                        for (var li = 0; li < labelOrder.length; li++) {
                            if (leafMap[labelOrder[li]]) foundLeaves.push(leafMap[labelOrder[li]]);
                        }
                        if (foundLeaves.length >= 2) {
                            elPrimary = foundLeaves[0];
                            elSecondary = foundLeaves[1];
                        }
                    }

                    if (!elPrimary || !elSecondary) return;

                    // Find the exact common ancestor container holding both visible menu items
                    var listParent = elPrimary.parentElement;
                    while (listParent && !listParent.contains(elSecondary)) {
                        listParent = listParent.parentElement;
                    }
                    if (!listParent || listParent === document.body || listParent === document.documentElement) {
                        dbgMenu('skip_bad_parent url=' + location.pathname);
                        return;
                    }

                    // If this active menu list already has our injected item visible, keep it
                    var prevItem = document.getElementById('__pinSendVideoMenuItem');
                    if (prevItem && listParent.contains(prevItem)) {
                        var pr = prevItem.getBoundingClientRect();
                        if (pr.width > 0 && pr.height > 0) return;
                    }
                    if (/download\s*video/i.test(listParent.innerText || '')) return;

                    if (document.querySelectorAll('video').length === 0) {
                        dbgMenu('skip_no_video url=' + location.pathname + ' items=' + (listParent.innerText || '').replace(/\s+/g, '|').slice(0, 80));
                        return;
                    }

                    if (prevItem && prevItem.parentElement) {
                        prevItem.parentElement.removeChild(prevItem);
                    }

                    // Climb from elPrimary and elSecondary to their direct child rows inside listParent
                    var rowPrimary = elPrimary;
                    while (rowPrimary.parentElement && rowPrimary.parentElement !== listParent) {
                        rowPrimary = rowPrimary.parentElement;
                    }
                    var rowSecondary = elSecondary;
                    while (rowSecondary.parentElement && rowSecondary.parentElement !== listParent) {
                        rowSecondary = rowSecondary.parentElement;
                    }
                    if (rowPrimary.parentElement !== listParent || rowPrimary === rowSecondary) {
                        dbgMenu('skip_same_row url=' + location.pathname);
                        return;
                    }

                    var sheetRoot = listParent;
                    for (var sp = 0; sp < 8 && sheetRoot.parentElement && sheetRoot.parentElement !== document.body; sp++) {
                        if (sheetRoot.getAttribute && sheetRoot.getAttribute('role') === 'dialog') break;
                        if (sheetRoot.querySelector && sheetRoot.querySelector('button[aria-label*="Close" i], button svg')) break;
                        sheetRoot = sheetRoot.parentElement;
                    }

                    // Clone rowPrimary (typically "Open in app") so height, left alignment, fonts, and margins match 1:1
                    var desiredGap = 0;
                    try {
                        var refNext = rowPrimary.nextElementSibling;
                        if (refNext) {
                            desiredGap = Math.round(refNext.getBoundingClientRect().top - rowPrimary.getBoundingClientRect().bottom);
                        } else {
                            desiredGap = Math.round(rowSecondary.getBoundingClientRect().top - rowPrimary.getBoundingClientRect().bottom);
                        }
                    } catch (e) {}

                    var clone = rowPrimary.cloneNode(true);
                    clone.id = '__pinSendVideoMenuItem';
                    if (clone.removeAttribute) clone.removeAttribute('data-test-id');
                    clone.querySelectorAll('[data-test-id]').forEach(function(n) { n.removeAttribute('data-test-id'); });
                    clone.querySelectorAll('svg').forEach(function(svg) { svg.remove(); });
                    replaceMenuLabelText(clone, 'Download video');
                    clone.style.cursor = 'pointer';

                    var insertBeforeNode = rowPrimary;
                    if (!insertBeforeNode) return;

                    (function(targetSheet) {
                        clone.addEventListener('click', function(ev) {
                            ev.preventDefault();
                            ev.stopPropagation();
                            if (ev.stopImmediatePropagation) ev.stopImmediatePropagation();
                            try {
                                var closeBtn = targetSheet.querySelector('button[aria-label*="Close" i], button[aria-label*="close" i], [data-test-id*="close"] button');
                                if (!closeBtn) {
                                    var btns = targetSheet.querySelectorAll('button');
                                    for (var bi = 0; bi < btns.length; bi++) {
                                        if (!(btns[bi].innerText || '').trim()) { closeBtn = btns[bi]; break; }
                                    }
                                }
                                if (closeBtn) closeBtn.click();
                            } catch (err) {}
                            window.PinterestBridge.onVideoMenuDownloadClicked();
                        }, true);
                    })(sheetRoot);

                    listParent.insertBefore(clone, insertBeforeNode);
                    try {
                        if (desiredGap > 4 && desiredGap < 60) {
                            var actualGap = Math.round(insertBeforeNode.getBoundingClientRect().top - clone.getBoundingClientRect().bottom);
                            if (actualGap < desiredGap - 2) {
                                var curMb = parseFloat(window.getComputedStyle(clone).marginBottom || '0') || 0;
                                clone.style.marginBottom = (curMb + (desiredGap - actualGap)) + 'px';
                            }
                        }
                    } catch (e) {}
                    dbgMenu('injected url=' + location.pathname + ' gap=' + desiredGap + ' top=' + Math.round(clone.getBoundingClientRect().top));
                }

                window.__pinTryInjectVideoMenu = tryInjectVideoMenuOption;

                var sheetTimer = null;
                function scheduleSheetInject() {
                    if (sheetTimer) return;
                    sheetTimer = setTimeout(function() {
                        sheetTimer = null;
                        tryInjectVideoMenuOption();
                    }, 60);
                }

                function scheduleBurstInject() {
                    setTimeout(tryInjectVideoMenuOption, 80);
                    setTimeout(tryInjectVideoMenuOption, 220);
                    setTimeout(tryInjectVideoMenuOption, 480);
                    setTimeout(tryInjectVideoMenuOption, 850);
                }

                window.__pinEnsureSheetObservers = function() {
                    try {
                        var root = document.documentElement || document.body;
                        if (!root) {
                            if (!window.__pinDomReadyHooked) {
                                window.__pinDomReadyHooked = true;
                                document.addEventListener('DOMContentLoaded', function() {
                                    window.__pinEnsureSheetObservers();
                                    tryInjectVideoMenuOption();
                                }, { once: true });
                            }
                            return;
                        }
                        if (window.__pinSheetObsAttached) return;
                        var obs = new MutationObserver(scheduleSheetInject);
                        obs.observe(root, {
                            childList: true,
                            subtree: true,
                            attributes: true,
                            attributeFilter: ['style', 'class', 'hidden', 'aria-hidden']
                        });
                        document.addEventListener('click', scheduleBurstInject, true);
                        document.addEventListener('touchend', scheduleBurstInject, true);
                        window.__pinSheetObsAttached = true;
                        dbgMenu('observer_attached url=' + location.pathname);
                    } catch (e) {
                        dbgMenu('observer_err ' + (e && e.message ? e.message : e));
                    }
                };
                window.__pinEnsureSheetObservers();
                tryInjectVideoMenuOption();

                window.__pinExtractCurrentMedia = function(videoOnly) {
                    try {
                        // 1. Check active visible <video> element or explicit video request
                        var activeVideo = findBestVisibleVideo();
                        var videoList = activeVideo ? [activeVideo] : Array.from(document.querySelectorAll('video'));
                        for (var i = 0; i < videoList.length; i++) {
                            var v = videoList[i];
                            var vSrc = v.currentSrc || v.src || '';
                            if (vSrc && vSrc.indexOf('.mp4') >= 0) {
                                window.PinterestBridge.onExtractedPinMedia(vSrc, 'video/mp4', '');
                                return;
                            }
                            var sources = v.querySelectorAll('source');
                            for (var s = 0; s < sources.length; s++) {
                                var sSrc = sources[s].src || '';
                                if (sSrc && sSrc.indexOf('.mp4') >= 0) {
                                    window.PinterestBridge.onExtractedPinMedia(sSrc, 'video/mp4', '');
                                    return;
                                }
                            }
                        }

                        var ogVideo = document.querySelector('meta[property="og:video"], meta[property="og:video:secure_url"]');
                        if (ogVideo && ogVideo.content && ogVideo.content.indexOf('.mp4') >= 0) {
                            window.PinterestBridge.onExtractedPinMedia(ogVideo.content, 'video/mp4', '');
                            return;
                        }

                        var hash = findVideoHash(activeVideo || videoList[0]);
                        var fallbackVideoUrl = hash ? ('https://v1.pinimg.com/videos/iht/hls/' + hash + '.m3u8') : '';
                        if (activeVideo) {
                            var fiberVid = findVideoInFiber(activeVideo);
                            if (fiberVid) {
                                if (fiberVid.indexOf('.mp4') >= 0) {
                                    window.PinterestBridge.onExtractedPinMedia(fiberVid, 'video/mp4', '');
                                    return;
                                }
                                // Keep exact stream URL/hash bound to this <video> element
                                var fhm = fiberVid.match(VIDEO_HASH_RE);
                                if (fhm) hash = fhm[1].toLowerCase();
                                fallbackVideoUrl = fiberVid;
                            }
                            if (hash && window.__pinVideoHashMap.has(hash)) {
                                window.PinterestBridge.onExtractedPinMedia(window.__pinVideoHashMap.get(hash), 'video/mp4', '');
                                return;
                            }
                            if (hash) {
                                var scriptVid = scanScriptsForVideo(hash);
                                if (scriptVid && scriptVid.indexOf('.mp4') >= 0) {
                                    window.PinterestBridge.onExtractedPinMedia(scriptVid, 'video/mp4', '');
                                    return;
                                }
                            }
                        } else if (videoOnly || location.pathname.indexOf('/pin/') >= 0) {
                            var pageVid = hash ? scanScriptsForVideo(hash) : '';
                            if (pageVid && pageVid.indexOf('.mp4') >= 0) {
                                window.PinterestBridge.onExtractedPinMedia(pageVid, 'video/mp4', '');
                                return;
                            }
                        }

                        // Async resolution via Pinterest's PinResource endpoint & CDN video hash fallback
                        if (activeVideo || videoOnly) {
                            var pinId = extractPinIdForVideo(activeVideo);
                            if (pinId) {
                                var payload = encodeURIComponent(JSON.stringify({
                                    options: { field_set_key: 'unauth_react_main_pin', id: pinId }
                                }));
                                fetch('/resource/PinResource/get/?data=' + payload, {
                                    credentials: 'include',
                                    headers: {
                                        'X-Pinterest-PWS-Handler': 'www/[username].js',
                                        'Accept': 'application/json'
                                    }
                                }).then(function(r) { return r.json(); }).then(function(data) {
                                    var root = data && data.resource_response && data.resource_response.data;
                                    var vidUrl = collectVideoFromObject(root);
                                    if (vidUrl && vidUrl.indexOf('.mp4') >= 0 && (!hash || vidUrl.toLowerCase().indexOf(hash) >= 0)) {
                                        window.PinterestBridge.onExtractedPinMedia(vidUrl, 'video/mp4', '');
                                    } else if (fallbackVideoUrl) {
                                        window.PinterestBridge.onExtractedPinMedia(fallbackVideoUrl, 'video/mp4', '');
                                    } else if (vidUrl) {
                                        window.PinterestBridge.onExtractedPinMedia(vidUrl, 'video/mp4', '');
                                    } else {
                                        window.PinterestBridge.onExtractedPinMedia('pinid://' + pinId, 'video/mp4', '');
                                    }
                                }).catch(function() {
                                    if (fallbackVideoUrl) {
                                        window.PinterestBridge.onExtractedPinMedia(fallbackVideoUrl, 'video/mp4', '');
                                    } else {
                                        window.PinterestBridge.onExtractedPinMedia('pinid://' + pinId, 'video/mp4', '');
                                    }
                                });
                                return;
                            }
                            if (fallbackVideoUrl) {
                                window.PinterestBridge.onExtractedPinMedia(fallbackVideoUrl, 'video/mp4', '');
                                return;
                            }
                            window.PinterestBridge.onExtractedPinMedia('', '', '');
                            return;
                        }

                        // 2. Hero closeup image selectors or largest visible pinimg image
                        var heroSelectors = [
                            '[data-test-id="pin-closeup-image"] img',
                            '[data-test-id="story-pin-image"] img',
                            '[data-test-id="visual-content-container"] img',
                            '[data-test-id="closeup-image"] img'
                        ];
                        for (var h = 0; h < heroSelectors.length; h++) {
                            var heroImg = document.querySelector(heroSelectors[h]);
                            if (heroImg) {
                                var heroUrl = pickBestSrcFromSrcset(heroImg);
                                if (heroUrl) {
                                    window.PinterestBridge.onExtractedPinMedia(heroUrl, '', '');
                                    return;
                                }
                            }
                        }

                        var imgs = Array.from(document.querySelectorAll('img'));
                        var bestImgUrl = '';
                        var bestArea = 0;
                        imgs.forEach(function(img) {
                            var src = pickBestSrcFromSrcset(img);
                            if (!src || src.indexOf('pinimg.com') < 0) return;
                            if (src.indexOf('30x30') >= 0 || src.indexOf('75x75') >= 0) return;
                            var rect = img.getBoundingClientRect();
                            var area = rect.width * rect.height;
                            if (rect.width >= 90 && rect.height >= 90 && area > bestArea) {
                                bestArea = area;
                                bestImgUrl = src;
                            }
                        });
                        if (bestImgUrl) {
                            window.PinterestBridge.onExtractedPinMedia(bestImgUrl, '', '');
                            return;
                        }

                        var ogImg = document.querySelector('meta[property="og:image"], link[rel="preload"][as="image"]');
                        var fallbackUrl = ogImg ? (ogImg.content || ogImg.href || '') : '';
                        window.PinterestBridge.onExtractedPinMedia(fallbackUrl, '', '');
                    } catch (err) {
                        window.PinterestBridge.onExtractedPinMedia('', '', '');
                    }
                };
            })();
        """.trimIndent()
    }
}
