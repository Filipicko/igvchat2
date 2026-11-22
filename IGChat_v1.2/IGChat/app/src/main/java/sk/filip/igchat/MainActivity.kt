package sk.filip.igchat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
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

    // Stav pripojenia – keď stránka zlyhá (bez internetu / DNS), po návrate siete sa sama obnoví
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var offlineShown = false
    private var failedUrl: String? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        /** Či je apka práve na obrazovke – vtedy kontrola na pozadí nenotifikuje. */
        @Volatile
        @JvmStatic
        var isVisible = false

        private const val NOTIF_PERM_REQ = 2001
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
                if (url != null && url.startsWith("https://")) offlineShown = false
                injectCss(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val url = request.url.toString()
                failedUrl = if (isInstagram(request.url) && isAllowed(request.url)) url else INBOX
                showOffline(view, error.description?.toString() ?: "")
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
            web.loadUrl(urlFromIntent(intent) ?: INBOX)
        }

        // Notifikácie: kanály, plánovanie kontroly, povolenia
        MessageChecker.createChannels(this)
        MessageChecker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 && !MessageChecker.hasNotificationPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERM_REQ)
        } else {
            askBatteryOnce()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIF_PERM_REQ) askBatteryOnce()
    }

    /** Raz požiada o vypnutie optimalizácie batérie, aby Android neuspal kontrolu správ. */
    @SuppressLint("BatteryLife")
    private fun askBatteryOnce() {
        val prefs = getSharedPreferences("igchat_ui", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
        }
    }

    private fun urlFromIntent(i: Intent?): String? {
        val url = i?.getStringExtra(MessageChecker.EXTRA_URL) ?: return null
        val uri = Uri.parse(url)
        return if (isInstagram(uri) && isAllowed(uri)) url else null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        urlFromIntent(intent)?.let { web.loadUrl(it) }
    }

    private fun showOffline(view: WebView, detail: String) {
        offlineShown = true
        val safe = detail.replace("<", "&lt;").replace(">", "&gt;")
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{background:#000;color:#fff;font-family:sans-serif;display:flex;flex-direction:column;
                   align-items:center;justify-content:center;height:90vh;margin:0;text-align:center;padding:0 24px}
              .b{margin-top:24px;padding:14px 28px;border-radius:12px;background:#C13584;color:#fff;
                 text-decoration:none;font-size:17px;font-weight:600}
              small{color:#888;margin-top:16px}
            </style></head><body>
              <div style="font-size:48px">📡</div>
              <h2>Nedá sa pripojiť k Instagramu</h2>
              <div>Skontroluj internet. Keď sa pripojenie vráti, chat sa načíta sám.</div>
              <a class="b" href="$INBOX">Skúsiť znova</a>
              <small>$safe</small>
            </body></html>
        """.trimIndent()
        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        registerNetworkWatch()
    }

    private fun retryNow() {
        if (!offlineShown) return
        offlineShown = false
        web.loadUrl(failedUrl ?: INBOX)
    }

    private fun registerNetworkWatch() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // chvíľu počkaj, kým sa nastaví DNS
                mainHandler.postDelayed({ retryNow() }, 1500)
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(req, cb)
            netCallback = cb
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        netCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
            }
        }
        netCallback = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // po návrate do apky skús znova, ak predtým nebolo pripojenie
        if (offlineShown) mainHandler.postDelayed({ retryNow() }, 500)
        isVisible = true
        Notifier.clearAll(this)
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
        isVisible = false
        CookieManager.getInstance().flush()
        // Zapamätaj si, čo už bolo videné, aby neprišli notifikácie na prečítané správy
        val cookies = MessageChecker.readCookies()
        val app = applicationContext
        Thread {
            try {
                MessageChecker.check(app, cookies, notify = false)
            } catch (e: Exception) {
            }
        }.start()
    }
}
