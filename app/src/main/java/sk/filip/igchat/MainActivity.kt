package sk.filip.igchat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

/**
 * IG Chat – zobrazí iba Instagram Direct (správy).
 * Feed, Reels, Explore, Stories a profily sú zablokované.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var lastToast = 0L

    companion object {
        private const val INBOX = "https://www.instagram.com/direct/inbox/"
        private const val FILE_REQ = 1001

        // Cesty na instagram.com, ktoré sú povolené (chat + prihlásenie)
        private val ALLOWED_PREFIXES = listOf(
            "/direct",
            "/accounts",        // login, odhlásenie, onetap, reset hesla
            "/challenge",       // overenie účtu
            "/two_factor",
            "/auth_platform",
            "/consent",
            "/privacy/checks",
            "/api/",
            "/ajax/"
        )

        // Skryje odkazy na feed, Reels, Explore, Stories, vytváranie príspevkov a reklamy na apku
        private const val HIDE_CSS = """
            a[href="/"], a[href="/explore/"], a[href^="/explore"],
            a[href^="/reels"], a[href^="/reel/"], a[href^="/stories"],
            a[href*="/create/"], a[href="/notifications/"],
            a[href*="apps.apple.com"], a[href*="play.google.com"],
            a[href*="instagram.com/download"] { display: none !important; }
        """
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this)
        root.addView(
            web,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        // Odsadenie od status baru, navigačnej lišty a klávesnice
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Mobilný Chrome user-agent (bez príznaku WebView)
            userAgentString = WebSettings.getDefaultUserAgent(this@MainActivity)
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme ?: return true

                if (scheme != "http" && scheme != "https") {
                    if (scheme == "mailto" || scheme == "tel") openExternal(uri)
                    return true // intent://, instagram:// a pod. ignorujeme
                }

                if (isInstagram(uri)) {
                    if (isAllowed(uri)) return false
                    if (isRoot(uri)) {
                        view.loadUrl(INBOX) // po prihlásení rovno do správ
                    } else {
                        blockedToast()
                    }
                    return true
                }

                if (isFacebook(uri)) return false // "Prihlásiť sa cez Facebook"

                openExternal(uri) // ostatné odkazy z chatu -> bežný prehliadač
                return true
            }

            // Instagram je SPA – prechody medzi stránkami idú cez history API,
            // takže ich kontrolujeme aj tu.
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val uri = url?.let(Uri::parse) ?: return
                if (!isInstagram(uri) || isAllowed(uri)) return

                if (isRoot(uri)) {
                    view.loadUrl(INBOX)
                } else {
                    blockedToast()
                    if (view.canGoBack()) view.goBack() else view.loadUrl(INBOX)
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                injectCss(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                injectCss(view)
                CookieManager.getInstance().flush()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // Posielanie fotiek / súborov v chate
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), FILE_REQ)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(INBOX)
        }
    }

    private fun injectCss(view: WebView) {
        val css = HIDE_CSS.replace("\n", " ").replace("'", "\\'")
        val js = """
            (function() {
              if (document.getElementById('igchat-style')) return;
              var s = document.createElement('style');
              s.id = 'igchat-style';
              s.textContent = '$css';
              (document.head || document.documentElement).appendChild(s);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun isInstagram(uri: Uri): Boolean {
        val h = uri.host ?: return false
        return h == "instagram.com" || h.endsWith(".instagram.com")
    }

    private fun isFacebook(uri: Uri): Boolean {
        val h = uri.host ?: return false
        return h == "facebook.com" || h.endsWith(".facebook.com")
    }

    private fun isRoot(uri: Uri): Boolean {
        val p = uri.path
        return p.isNullOrEmpty() || p == "/"
    }

    private fun isAllowed(uri: Uri): Boolean {
        val p = uri.path ?: return false
        return ALLOWED_PREFIXES.any { p.startsWith(it) }
    }

    private fun blockedToast() {
        val now = System.currentTimeMillis()
        if (now - lastToast > 2000) {
            lastToast = now
            Toast.makeText(this, "Tu je iba chat 🙂", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_REQ) {
            val cb = fileCallback ?: return
            fileCallback = null
            if (resultCode != RESULT_OK || data == null) {
                cb.onReceiveValue(null)
                return
            }
            val clip = data.clipData
            val result: Array<Uri>? = if (clip != null && clip.itemCount > 0) {
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            } else {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            }
            cb.onReceiveValue(result)
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) {
            web.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }
}
