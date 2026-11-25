// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.pinterest

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import org.json.JSONObject

/**
 * Modal auth/account-picker Activity sharing the app's WebView CookieManager so
 * Google Sign-In popups, Android's built-in Google account chooser, and Android
 * Autofill / Password Manager work natively with full window focus.
 */
class PinterestAuthActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var bannerView: View
    private lateinit var bannerText: TextView

    private var pickerOnlyMode = false
    private var selectedEmail: String? = null
    private var accountPickerLaunched = false

    private val accountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrBlank()) {
                selectedEmail = accountName
                PinterestAuthSession.lastSelectedEmail = accountName
                if (pickerOnlyMode) {
                    PinterestAuthSession.pendingFillEmail = accountName
                } else {
                    fillEmailInCurrentPage(accountName)
                }
            }
        }
        if (pickerOnlyMode) {
            PinterestAuthSession.markReopenForDrawer()
            finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        pickerOnlyMode = intent.getBooleanExtra(EXTRA_PICKER_ONLY, false)
        if (pickerOnlyMode) {
            setTheme(R.style.TransparentActivityTheme)
        }
        super.onCreate(savedInstanceState)

        if (pickerOnlyMode) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            launchAndroidAccountPicker()
            return
        }

        setContentView(R.layout.activity_pinterest_auth)

        titleView = findViewById(R.id.pinterest_auth_title)
        progressBar = findViewById(R.id.pinterest_auth_progress)
        bannerView = findViewById(R.id.pinterest_auth_banner)
        bannerText = findViewById(R.id.pinterest_auth_banner_text)
        webView = findViewById(R.id.pinterest_auth_webview)

        findViewById<ImageButton>(R.id.pinterest_auth_close).setOnClickListener {
            finishAuth(completed = false)
        }
        findViewById<Button>(R.id.pinterest_auth_pick_account).setOnClickListener {
            launchAndroidAccountPicker()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finishAuth(completed = false)
                }
            }
        })

        configureWebView()

        val popupMsg = PinterestAuthSession.consumePendingPopupMessage()
        val startUrl = intent.getStringExtra(EXTRA_AUTH_URL)
        val autoPickAccount = intent.getBooleanExtra(EXTRA_AUTO_PICK_ACCOUNT, true)

        if (popupMsg != null) {
            val transport = popupMsg.obj as? WebView.WebViewTransport
            if (transport != null) {
                transport.webView = webView
                popupMsg.sendToTarget()
            } else {
                webView.loadUrl(startUrl ?: PINTEREST_LOGIN_URL)
            }
        } else {
            webView.loadUrl(startUrl ?: PINTEREST_LOGIN_URL)
        }

        if (autoPickAccount) {
            launchAndroidAccountPicker()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.userAgentString = settings.userAgentString.replace("; wv", "")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(AuthJsBridge(), "PinterestAuthBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.isVisible = newProgress in 1..99
                progressBar.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    titleView.text = title
                }
            }

            override fun onCloseWindow(window: WebView?) {
                finishAuth(completed = true)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = webView
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleUrl(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return handleUrl(request?.url?.toString())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                runCatching { CookieManager.getInstance().flush() }
                if (url.isNullOrBlank()) return

                view?.evaluateJavascript(
                    """
                    (function() {
                        var txt = (document.body && document.body.innerText) ? document.body.innerText : '';
                        if (txt.indexOf('disallowed_useragent') >= 0 ||
                            (location.hostname.indexOf('accounts.google.') >= 0 && txt.indexOf('403.') >= 0)) {
                            window.PinterestAuthBridge.onGoogleBlocked();
                            return;
                        }
                    })();
                    """.trimIndent(),
                    null
                )

                selectedEmail?.let { fillEmailInCurrentPage(it) }

                val parsed = Uri.parse(url)
                val host = parsed.host?.lowercase() ?: ""
                val path = parsed.path?.lowercase() ?: ""
                if (host.endsWith("pinterest.com") &&
                    !path.startsWith("/login") &&
                    !path.startsWith("/auth") &&
                    !path.startsWith("/password") &&
                    !path.startsWith("/business/login")
                ) {
                    val cookies = CookieManager.getInstance().getCookie("https://www.pinterest.com/").orEmpty()
                    if (cookies.contains("_pinterest_sess") || cookies.contains("_auth=1")) {
                        finishAuth(completed = true)
                    }
                }
            }
        }
    }

    private fun handleUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return true
        val scheme = Uri.parse(rawUrl).scheme?.lowercase() ?: return true
        return scheme != "http" && scheme != "https"
    }

    private fun launchAndroidAccountPicker() {
        accountPickerLaunched = true
        val intent = createGoogleAccountChooserIntent()
        runCatching {
            accountPickerLauncher.launch(intent)
        }.onFailure {
            Log.e(TAG, "Account picker failed", it)
            if (pickerOnlyMode) finish()
        }
    }

    private fun createGoogleAccountChooserIntent(): Intent {
        @Suppress("DEPRECATION")
        return AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf("com.google"),
            null,
            null,
            null,
            null
        )
    }

    private fun fillEmailInCurrentPage(email: String) {
        if (!::webView.isInitialized) return
        val quoted = JSONObject.quote(email)
        webView.evaluateJavascript(
            """
            (function(email) {
                function setNativeValue(el, val) {
                    var proto = HTMLInputElement.prototype;
                    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                    if (desc && desc.set) desc.set.call(el, val); else el.value = val;
                    el.dispatchEvent(new InputEvent('input', { bubbles: true, data: val, inputType: 'insertText' }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }
                var candidates = document.querySelectorAll('input#identifierId, input[type="email"], input[name="id"], input[name="email"]');
                var input = null;
                for (var i = 0; i < candidates.length; i++) {
                    if (candidates[i].offsetParent !== null) {
                        input = candidates[i];
                        break;
                    }
                }
                if (input) {
                    input.focus();
                    setNativeValue(input, email);
                    if (location.hostname.indexOf('accounts.google.') >= 0 && !window.__pinGoogleEmailSubmitted) {
                        window.__pinGoogleEmailSubmitted = true;
                        var nextBtn = document.querySelector('#identifierNext button, #identifierNext');
                        if (nextBtn) setTimeout(function() { nextBtn.click(); }, 180);
                    }
                }
            })($quoted);
            """.trimIndent(),
            null
        )
    }

    private fun finishAuth(completed: Boolean) {
        runCatching { CookieManager.getInstance().flush() }
        if (completed) {
            PinterestAuthSession.pendingReloadHome = true
        }
        PinterestAuthSession.markReopenForDrawer()
        finish()
    }

    override fun onDestroy() {
        runCatching {
            CookieManager.getInstance().flush()
            if (::webView.isInitialized) {
                webView.stopLoading()
                webView.destroy()
            }
        }
        super.onDestroy()
    }

    private inner class AuthJsBridge {
        @JavascriptInterface
        fun onGoogleBlocked() {
            runOnUiThread {
                bannerView.isVisible = true
                bannerText.text = getString(R.string.pinterest_google_webview_fallback)
                if (webView.url?.contains("pinterest.com/login") != true) {
                    webView.loadUrl(PINTEREST_LOGIN_URL)
                }
                if (!accountPickerLaunched && selectedEmail == null) {
                    launchAndroidAccountPicker()
                }
            }
        }
    }

    companion object {
        private const val TAG = "PinterestAuthActivity"
        const val EXTRA_AUTH_URL = "extra_auth_url"
        const val EXTRA_AUTO_PICK_ACCOUNT = "extra_auto_pick_account"
        const val EXTRA_PICKER_ONLY = "extra_picker_only"
        private const val PINTEREST_LOGIN_URL = "https://www.pinterest.com/login/"
    }
}

object PinterestAuthSession {
    private const val REOPEN_TIMEOUT_MS = 120_000L

    var pendingPopupMessage: Message? = null
    var sourcePackageName: String? = null
    var lastSelectedEmail: String? = null
    var pendingFillEmail: String? = null
    var pendingReloadHome: Boolean = false

    private var reopenTargetPackage: String? = null
    private var reopenRequestedAtMs: Long = 0L

    fun setPendingPopup(message: Message?, editorPackageName: String?) {
        pendingPopupMessage = message
        sourcePackageName = editorPackageName
    }

    fun consumePendingPopupMessage(): Message? {
        val msg = pendingPopupMessage
        pendingPopupMessage = null
        return msg
    }

    fun markReopenForDrawer() {
        reopenTargetPackage = sourcePackageName
        reopenRequestedAtMs = SystemClock.elapsedRealtime()
    }

    fun consumeReopenForPackage(currentPackageName: String?): Boolean {
        val target = reopenTargetPackage ?: return false
        val age = SystemClock.elapsedRealtime() - reopenRequestedAtMs
        reopenTargetPackage = null
        reopenRequestedAtMs = 0L
        if (age > REOPEN_TIMEOUT_MS) return false
        return !currentPackageName.isNullOrBlank() && currentPackageName == target
    }

    fun clearReopen() {
        reopenTargetPackage = null
        reopenRequestedAtMs = 0L
    }
}
