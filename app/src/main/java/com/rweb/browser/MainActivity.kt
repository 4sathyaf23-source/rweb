package com.rweb.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.util.Xml
import java.net.HttpURLConnection
import java.net.URL
import org.xmlpull.v1.XmlPullParser

class MainActivity : Activity() {

    private class Tab(val web: WebView, val priv: Boolean = false) {
        var title = "New tab"
        var url = ""
        var desktop = false
        var lastHistoryUrl = ""
        var pendingUrl: String? = null
    }

    private val BG = Color.parseColor("#2A201B")
    private val BAR = Color.parseColor("#372A24")
    private val ROSE = Color.parseColor("#E8909F")
    private val INK = Color.parseColor("#F3E9E4")
    private val MUTE = Color.parseColor("#A89690")

    private val tabs = mutableListOf<Tab>()
    private var cur = -1
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var tabBtn: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var mobileUa: String
    private var pendingMedia: PermissionRequest? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var topBar: LinearLayout
    private lateinit var fullscreenHolder: FrameLayout
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private val PRIV = Color.parseColor("#33283A")
    private var newsHtml: String? = null
    private var newsTime = 0L
    private var newsLoading = false
    private val feeds = listOf(
        Pair("BBC News", "https://feeds.bbci.co.uk/news/rss.xml"),
        Pair("Google News", "https://news.google.com/rss"),
        Pair("NPR", "https://feeds.npr.org/1001/rss.xml"),
        Pair("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml")
    )
    private val engines = listOf(
        Pair("Google", "https://www.google.com/search?q="),
        Pair("DuckDuckGo", "https://duckduckgo.com/?q="),
        Pair("Bing", "https://www.bing.com/search?q=")
    )
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCb: GeolocationPermissions.Callback? = null

    companion object {
        const val PRIVATE_PROFILE = "rwebprivate"
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val HEAD_A = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;padding:24px 0;box-sizing:border-box;background:#FFFFFF;color:#202124;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh}h1{color:#D93025;font-size:44px;margin:0 0 24px}form{width:86%;max-width:520px}input{width:100%;box-sizing:border-box;padding:14px 18px;border-radius:26px;border:1px solid #DADCE0;background:#F1F3F4;color:#202124;font-size:16px}.grid{display:flex;flex-wrap:wrap;justify-content:center;gap:16px;margin-top:28px;width:90%;max-width:520px}.tile{width:72px;text-decoration:none;color:#202124;text-align:center;font-size:12px}.ic{position:relative;overflow:hidden;display:flex;align-items:center;justify-content:center;width:56px;height:56px;margin:0 auto 6px;border-radius:50%;background:#F1F3F4;color:#D93025;font-size:22px;font-weight:700;border:1px solid #E8EAED;box-sizing:border-box}.ic img{position:absolute;top:0;left:0;width:100%;height:100%;box-sizing:border-box;padding:12px;background:#FFFFFF;object-fit:contain}.nm{display:block;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}#news{width:90%;max-width:520px;margin-top:28px}.nh{font-size:12px;color:#5F6368;margin-bottom:8px;text-transform:uppercase;letter-spacing:.08em}.nw{display:flex;align-items:center;gap:12px;padding:12px 14px;margin-bottom:8px;background:#F8F9FA;border:1px solid #E8EAED;border-radius:14px;color:#202124;text-decoration:none;font-size:14px;line-height:1.35}.nt{flex:1}.ni{width:88px;height:64px;object-fit:cover;border-radius:10px;flex-shrink:0}</style></head><body><h1>rweb</h1><form action=""""
        const val HEAD_B = """" method="get"><input name="q" placeholder="Search the web" autocomplete="off"></form><div class="grid">"""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rweb", MODE_PRIVATE)
        mobileUa = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        window.statusBarColor = BG
        window.navigationBarColor = BAR
        wipePrivate()
        buildUi()
        if (savedInstanceState != null) restoreTabs(savedInstanceState)
        if (tabs.isEmpty()) newTab(intent?.data?.toString())
    }

    private fun currentOrNull(): Tab? = tabs.getOrNull(cur)

    // Private tabs are never saved.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val normal = tabs.filter { !it.priv }
        outState.putStringArray("tab_urls", normal.map { it.url }.toTypedArray())
        outState.putStringArray("tab_titles", normal.map { it.title }.toTypedArray())
        outState.putBooleanArray("tab_desktops", normal.map { it.desktop }.toBooleanArray())
        val c = currentOrNull()
        outState.putInt("cur_tab", if (c != null && !c.priv) normal.indexOf(c) else 0)
    }

    private fun restoreTabs(b: Bundle) {
        val urls = b.getStringArray("tab_urls") ?: return
        val titles = b.getStringArray("tab_titles")
        val desktops = b.getBooleanArray("tab_desktops")
        for (i in urls.indices) {
            val web = try {
                createWebView(false)
            } catch (e: Exception) {
                continue
            }
            val tab = Tab(web, false)
            tab.url = urls[i]
            tab.title = titles?.getOrNull(i) ?: "New tab"
            tab.desktop = desktops?.getOrNull(i) ?: false
            tab.pendingUrl = urls[i]
            setupWeb(tab)
            tabs.add(tab)
        }
        if (tabs.isNotEmpty()) switchTo(b.getInt("cur_tab", 0).coerceIn(0, tabs.size - 1))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.data?.let { newTab(it.toString()) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun btn(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 20f
        t.setTextColor(INK)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        topBar = LinearLayout(this)
        val top = topBar
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(BAR)
        top.setPadding(dp(8), dp(6), dp(4), dp(6))

        address = EditText(this)
        val pill = GradientDrawable()
        pill.setColor(BG)
        pill.cornerRadius = dp(22).toFloat()
        address.background = pill
        address.setSingleLine()
        address.hint = "Search or enter address"
        address.setHintTextColor(MUTE)
        address.setTextColor(INK)
        address.textSize = 15f
        address.setPadding(dp(16), dp(10), dp(16), dp(10))
        address.imeOptions = EditorInfo.IME_ACTION_GO
        address.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        address.setSelectAllOnFocus(true)
        address.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                go(address.text.toString())
                hideKeyboard()
                true
            } else {
                false
            }
        }
        top.addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        tabBtn = btn("1") { showTabs() }
        tabBtn.textSize = 16f
        top.addView(tabBtn)
        top.addView(btn("\u22EE") { showMenu() })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(ROSE)
        progress.visibility = View.GONE

        content = FrameLayout(this)

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.setBackgroundColor(BAR)
        val w = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bottom.addView(btn("\u25C0") { if (current().web.canGoBack()) current().web.goBack() }, w)
        bottom.addView(btn("\u25B6") { if (current().web.canGoForward()) current().web.goForward() }, w)
        bottom.addView(btn("\u27F3") { current().web.reload() }, w)
        bottom.addView(btn("\u2302") { loadStart(current().web) }, w)

        root.addView(top)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottom)
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreenHolder = FrameLayout(this)
        fullscreenHolder.setBackgroundColor(Color.BLACK)
        fullscreenHolder.visibility = View.GONE
        frame.addView(fullscreenHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
    }

    private fun current(): Tab = tabs[cur]

    private fun displayUrl(url: String): String =
        if (url.startsWith("about:") || url.startsWith("data:")) "" else url

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
    }

    private fun go(text: String) {
        val target = UrlUtil.toNavigationTarget(text, searchBase())
        if (target.isEmpty()) return
        current().web.loadUrl(target)
    }

    private fun loadStart(web: WebView) {
        val tab = tabs.firstOrNull { it.web === web }
        if (tab != null && tab.priv) {
            web.loadDataWithBaseURL(null, privateHtml(), "text/html", "utf-8", null)
            return
        }
        web.loadDataWithBaseURL(null, startHtml(), "text/html", "utf-8", null)
        refreshNews()
    }

    private fun createWebView(priv: Boolean): WebView {
        val web = WebView(this)
        if (priv) {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    throw IllegalStateException("Private profiles are not supported")
                }
                val profile = ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
                WebViewCompat.setProfile(web, PRIVATE_PROFILE)
                profile.cookieManager.setAcceptCookie(true)
                profile.cookieManager.setAcceptThirdPartyCookies(web, false)
            } catch (e: Exception) {
                web.destroy()
                throw e
            }
        } else {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        }
        return web
    }

    private fun newTab(url: String?, priv: Boolean = false) {
        val web = try {
            createWebView(priv)
        } catch (e: Exception) {
            toast(if (priv) "Private tabs need a newer Android System WebView. Update it in the Play Store." else "Couldn't open a new tab")
            return
        }
        val tab = Tab(web, priv)
        setupWeb(tab)
        tabs.add(tab)
        switchTo(tabs.size - 1)
        if (url.isNullOrEmpty()) loadStart(tab.web) else tab.web.loadUrl(url)
    }

    private fun switchTo(i: Int) {
        cur = i
        content.removeAllViews()
        val t = tabs[i]
        content.addView(t.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        address.setText(displayUrl(t.url))
        address.clearFocus()
        topBar.setBackgroundColor(if (t.priv) PRIV else BAR)
        address.hint = if (t.priv) "Private tab: search or enter address" else "Search or enter address"
        tabBtn.text = tabs.size.toString()
        progress.visibility = View.GONE
        if (t.priv) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        t.pendingUrl?.let {
            t.pendingUrl = null
            if (it.isEmpty()) loadStart(t.web) else t.web.loadUrl(it)
        }
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        content.removeView(t.web)
        try {
            t.web.stopLoading()
            t.web.loadUrl("about:blank")
            t.web.clearHistory()
            t.web.removeAllViews()
            t.web.destroy()
        } catch (e: Exception) {
        }
        if (t.priv && tabs.none { it.priv }) wipePrivate()
        if (tabs.isEmpty()) {
            cur = -1
            newTab(null)
        } else {
            switchTo(minOf(i, tabs.size - 1))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb(tab: Tab) {
        val web = tab.web
        web.setBackgroundColor(BG)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.safeBrowsingEnabled = true
        s.setSupportMultipleWindows(true)
        s.userAgentString = if (tab.desktop) DESKTOP_UA else mobileUa

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val u = url ?: return
                tab.url = displayUrl(u)
                if (tabs.getOrNull(cur) === tab) address.setText(tab.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val u = url ?: return
                tab.url = displayUrl(u)
                if (tab.url.isNotEmpty()) {
                    val t = view?.title
                    if (!t.isNullOrBlank()) tab.title = t
                    if (!tab.priv && tab.url.startsWith("http") && tab.url != tab.lastHistoryUrl) {
                        addHistory(tab.title, tab.url)
                        tab.lastHistoryUrl = tab.url
                    }
                } else {
                    tab.title = "New tab"
                    if (!tab.priv) newsHtml?.let { injectNews(tab.web, it) }
                }
                if (tabs.getOrNull(cur) === tab) address.setText(tab.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val scheme = u.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: Exception) {
                    toast("No app can open this link")
                }
                return true
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.cancel()
                val failing = error?.url
                if (failing != null && failing == view?.url) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Connection not secure")
                        .setMessage("This site's security certificate isn't valid, so rweb blocked it.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val idx = tabs.indexOf(tab)
                if (idx < 0) return true
                val wasCurrent = idx == cur
                val crashedUrl = tab.url
                try {
                    (view?.parent as? ViewGroup)?.removeView(view)
                    view?.destroy()
                } catch (e: Exception) {
                }
                val newWeb = try {
                    createWebView(tab.priv)
                } catch (e: Exception) {
                    tabs.removeAt(idx)
                    if (tabs.isEmpty()) {
                        cur = -1
                        newTab(null)
                    } else {
                        switchTo(minOf(idx, tabs.size - 1))
                    }
                    toast("A page crashed and was closed")
                    return true
                }
                val fresh = Tab(newWeb, tab.priv)
                fresh.desktop = tab.desktop
                fresh.url = crashedUrl
                fresh.pendingUrl = crashedUrl
                setupWeb(fresh)
                tabs[idx] = fresh
                if (wasCurrent) switchTo(idx)
                toast("Page crashed and was reloaded")
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tabs.getOrNull(cur) !== tab) return
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank() && !title.startsWith("about:")) tab.title = title
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val params = fileChooserParams ?: return false
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(params.createIntent(), 200)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    toast("Can't open the file picker")
                    false
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCb = callback
                fullscreenHolder.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                fullscreenHolder.visibility = View.VISIBLE
                setFullscreenMode(true)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (!isUserGesture || resultMsg == null) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val popup = try {
                    createWebView(tab.priv)
                } catch (e: Exception) {
                    return false
                }
                val pt = Tab(popup, tab.priv)
                pt.desktop = tab.desktop
                setupWeb(pt)
                tabs.add(pt)
                switchTo(tabs.size - 1)
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                val idx = tabs.indexOfFirst { it.web === window }
                if (idx >= 0) closeTab(idx)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                runOnUiThread { askMediaPermission(req) }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (pendingMedia === request) pendingMedia = null
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                val cb = callback ?: return
                val o = origin ?: return
                runOnUiThread { askLocation(o, cb) }
            }
        }

        web.setOnLongClickListener {
            val r = web.hitTestResult
            val extra = r.extra
            when (r.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    if (extra != null) linkMenu(tab, extra, null)
                    true
                }
                WebView.HitTestResult.IMAGE_TYPE -> {
                    if (extra != null) linkMenu(tab, null, extra)
                    true
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val msg = Handler(Looper.getMainLooper()) { m ->
                        val link = m.data.getString("url")
                        val src = m.data.getString("src")
                        if (link != null || src != null) linkMenu(tab, link, src)
                        true
                    }.obtainMessage()
                    web.requestFocusNodeHref(msg)
                    true
                }
                else -> false
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType, tab.priv)
        }
    }

    // ---- site permissions (camera, microphone, location) ----

    private fun has(p: String): Boolean =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun askMediaPermission(req: PermissionRequest) {
        val cam = req.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val mic = req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        if (!cam && !mic) {
            req.deny()
            return
        }
        val what = if (cam && mic) "camera and microphone" else if (cam) "camera" else "microphone"
        AlertDialog.Builder(this)
            .setTitle("Allow " + what + "?")
            .setMessage(req.origin.host + " wants to use your " + what + ".")
            .setPositiveButton("Allow") { _, _ -> grantMedia(req, cam, mic) }
            .setNegativeButton("Block") { _, _ -> req.deny() }
            .setOnCancelListener { req.deny() }
            .show()
    }

    private fun grantMedia(req: PermissionRequest, cam: Boolean, mic: Boolean) {
        val needed = mutableListOf<String>()
        if (cam && !has(Manifest.permission.CAMERA)) needed.add(Manifest.permission.CAMERA)
        if (mic && !has(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
        if (needed.isEmpty()) {
            finishMedia(req)
            return
        }
        pendingMedia = req
        requestPermissions(needed.toTypedArray(), 100)
    }

    private fun finishMedia(req: PermissionRequest) {
        val ok = mutableListOf<String>()
        for (r in req.resources) {
            if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE && has(Manifest.permission.CAMERA)) ok.add(r)
            if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE && has(Manifest.permission.RECORD_AUDIO)) ok.add(r)
        }
        if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
    }

    private fun askLocation(origin: String, cb: GeolocationPermissions.Callback) {
        AlertDialog.Builder(this)
            .setTitle("Allow location?")
            .setMessage(Uri.parse(origin).host.toString() + " wants to know your location.")
            .setPositiveButton("Allow") { _, _ ->
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    cb.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCb = cb
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 101)
                }
            }
            .setNegativeButton("Block") { _, _ -> cb.invoke(origin, false, false) }
            .setOnCancelListener { cb.invoke(origin, false, false) }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            pendingMedia?.let { finishMedia(it) }
            pendingMedia = null
        } else if (requestCode == 101) {
            val cb = pendingGeoCb
            val o = pendingGeoOrigin
            if (cb != null && o != null) {
                cb.invoke(o, has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION), false)
            }
            pendingGeoCb = null
            pendingGeoOrigin = null
        }
    }

    // ---- start page, search engine, extra tools ----

    private fun searchBase(): String {
        val saved = prefs.getString("search", null)
        return if (saved != null && engines.any { it.second == saved }) saved else engines[0].second
    }

    private fun chooseEngine() {
        val names = engines.map { it.first }.toTypedArray()
        val idx = engines.indexOfFirst { it.second == searchBase() }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Search engine")
            .setSingleChoiceItems(names, idx) { d, i ->
                prefs.edit().putString("search", engines[i].second).apply()
                toast(names[i])
                d.dismiss()
            }.show()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun startHtml(): String {
        val tiles = mutableListOf(
            Pair("Google", "https://www.google.com"),
            Pair("YouTube", "https://www.youtube.com"),
            Pair("Wikipedia", "https://www.wikipedia.org"),
            Pair("GitHub", "https://github.com")
        )
        for (b in loadList("bookmarks").take(8)) {
            if (tiles.none { it.second == b.second }) {
                tiles.add(Pair(if (b.first.isBlank()) b.second else b.first, b.second))
            }
        }
        val sb = StringBuilder()
        sb.append(HEAD_A).append(esc(searchBase().substringBefore("?q="))).append(HEAD_B)
        for (t in tiles) {
            val letter = t.first.trim().take(1).uppercase()
            sb.append("<a class=\"tile\" href=\"").append(esc(t.second)).append("\">")
            val host = Uri.parse(t.second).host ?: ""
            sb.append("<span class=\"ic\">").append(esc(letter))
            if (host.isNotEmpty()) {
                sb.append("<img referrerpolicy=\"no-referrer\" src=\"https://www.google.com/s2/favicons?sz=128&amp;domain=")
                sb.append(esc(Uri.encode(host))).append("\" onerror=\"this.style.display='none'\">")
            }
            sb.append("</span>")
            sb.append("<span class=\"nm\">").append(esc(t.first.take(14))).append("</span></a>")
        }
        sb.append("</div><div id=\"news\">")
        sb.append(newsHtml ?: "<div class=\"nh\">Loading news...</div>")
        sb.append("</div></body></html>")
        return sb.toString()
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            toast("Can't open Downloads")
        }
    }

    private fun copyLink() {
        val u = current().url
        if (u.isEmpty()) {
            toast("Open a page first")
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", u))
        toast("Link copied")
    }

    private fun findInPage() {
        val web = current().web
        val input = EditText(this)
        input.setSingleLine()
        input.hint = "Find in page"
        val dlg = AlertDialog.Builder(this)
            .setTitle("Find in page")
            .setView(input)
            .setPositiveButton("Find", null)
            .setNeutralButton("Next", null)
            .setNegativeButton("Close", null)
            .create()
        dlg.setOnDismissListener {
            web.clearMatches()
            web.setFindListener(null)
        }
        dlg.show()
        web.setFindListener { active, total, _ ->
            dlg.setTitle(if (total == 0) "No matches" else "Match " + (active + 1) + " of " + total)
        }
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { web.findAllAsync(input.text.toString()) }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { web.findNext(true) }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
    }

    // ---- private tabs and news ----

    private fun privateHtml(): String {
        return HEAD_A + esc(searchBase().substringBefore("?q=")) + HEAD_B +
            "</div><style>body{background:#2A201B;color:#F3E9E4}h1{color:#E8909F}input{background:#372A24;color:#F3E9E4;border-color:#372A24}</style>" +
            "<div id=\"news\" style=\"text-align:center;color:#A89690;font-size:14px;line-height:1.5\">" +
            "<b style=\"color:#E8909F\">Private tab</b><br>History, cookies and site data from private tabs are deleted when you close the last private tab.</div></body></html>"
    }

    private fun wipePrivate() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE)
            }
        } catch (e: Exception) {
        }
    }

    private fun feedUrl(): String {
        val saved = prefs.getString("news", null)
        return if (saved != null && feeds.any { it.second == saved }) saved else feeds[0].second
    }

    private fun feedName(): String = feeds.first { it.second == feedUrl() }.first

    private fun fetchNews(feed: String): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        val conn = URL(feed).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) rweb")
        try {
            val parser = Xml.newPullParser()
            parser.setInput(conn.inputStream, null)
            var inItem = false
            var title = ""
            var link = ""
            var image = ""
            var tag = ""
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT && out.size < 8) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = parser.name
                    if (tag == "item" || tag == "entry") {
                        inItem = true
                        title = ""
                        link = ""
                        image = ""
                    } else if (inItem && tag == "link") {
                        val rel = parser.getAttributeValue(null, "rel")
                        val href = parser.getAttributeValue(null, "href")
                        if (href != null && link.isEmpty() && (rel == null || rel == "alternate")) link = href
                    } else if (inItem && (tag == "media:thumbnail" || tag == "media:content" || tag == "enclosure")) {
                        val u = parser.getAttributeValue(null, "url")
                        val type = parser.getAttributeValue(null, "type") ?: ""
                        val medium = parser.getAttributeValue(null, "medium") ?: ""
                        if (u != null && image.isEmpty() && (tag == "media:thumbnail" || type.startsWith("image") || medium == "image")) {
                            image = u
                        }
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (inItem) {
                        if (tag == "title") title += parser.text
                        else if (tag == "link" && link.isEmpty()) link += parser.text
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (parser.name == "item" || parser.name == "entry") {
                        inItem = false
                        val l = link.trim()
                        if (title.isNotBlank() && (l.startsWith("https://") || l.startsWith("http://"))) {
                            out.add(Triple(title.trim(), l, image.trim()))
                        }
                    }
                    tag = ""
                }
                ev = parser.next()
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    private fun buildNews(name: String, items: List<Triple<String, String, String>>): String {
        val sb = StringBuilder()
        sb.append("<div class=\"nh\">Top news \u00B7 ").append(esc(name)).append("</div>")
        for (n in items) {
            sb.append("<a class=\"nw\" href=\"").append(esc(n.second)).append("\">")
            sb.append("<span class=\"nt\">").append(esc(n.first)).append("</span>")
            if (n.third.startsWith("http")) {
                sb.append("<img class=\"ni\" referrerpolicy=\"no-referrer\" src=\"").append(esc(n.third))
                sb.append("\" onerror=\"this.style.display='none'\">")
            }
            sb.append("</a>")
        }
        return sb.toString()
    }

    private fun injectNews(web: WebView, html: String) {
        web.evaluateJavascript(
            "(function(){var e=document.getElementById('news');if(e){e.innerHTML=" + JSONObject.quote(html) + ";}})()",
            null
        )
    }

    private fun refreshNews() {
        if (newsLoading) return
        if (newsHtml != null && System.currentTimeMillis() - newsTime < 10 * 60 * 1000) return
        newsLoading = true
        val feed = feedUrl()
        val name = feedName()
        Thread {
            val html: String? = try {
                val items = fetchNews(feed)
                if (items.isEmpty()) null else buildNews(name, items)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                newsLoading = false
                if (feed != feedUrl()) {
                    refreshNews()
                } else {
                    if (html != null) {
                        newsHtml = html
                        newsTime = System.currentTimeMillis()
                    }
                    val show = html
                        ?: "<div class=\"nh\">Couldn't load news. Check your connection or pick another source in the menu.</div>"
                    for (t in tabs) {
                        if (!t.priv && t.url.isEmpty()) injectNews(t.web, show)
                    }
                }
            }
        }.start()
    }

    private fun chooseNews() {
        val names = feeds.map { it.first }.toTypedArray()
        val idx = feeds.indexOfFirst { it.second == feedUrl() }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("News source")
            .setSingleChoiceItems(names, idx) { d, i ->
                prefs.edit().putString("news", feeds[i].second).apply()
                newsHtml = null
                newsTime = 0L
                d.dismiss()
                val t = current()
                if (t.url.isEmpty() && !t.priv) loadStart(t.web)
            }.show()
    }

    // ---- fullscreen video, long-press menu, downloads ----

    private fun setFullscreenMode(on: Boolean) {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        fullscreenHolder.removeView(v)
        fullscreenHolder.visibility = View.GONE
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        setFullscreenMode(false)
    }

    private fun isHttp(u: String): Boolean = u.startsWith("http://") || u.startsWith("https://")

    private fun copyText(t: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", t))
        toast("Copied")
    }

    private fun shareText(t: String) {
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, t)
        startActivity(Intent.createChooser(send, "Share"))
    }

    private fun linkMenu(tab: Tab, link: String?, image: String?) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (link != null) {
            if (isHttp(link)) {
                labels.add("Open in new tab")
                actions.add { newTab(link, tab.priv) }
            }
            labels.add("Copy link")
            actions.add { copyText(link) }
            if (isHttp(link)) {
                labels.add("Share link")
                actions.add { shareText(link) }
            }
        }
        if (image != null && isHttp(image)) {
            labels.add("Open image in new tab")
            actions.add { newTab(image, tab.priv) }
            labels.add("Download image")
            actions.add { startDownload(image, null, null, null, tab.priv) }
            labels.add("Copy image address")
            actions.add { copyText(image) }
        }
        if (labels.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle((link ?: image ?: "").take(60))
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .show()
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, priv: Boolean) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url))
            if (mimeType != null) req.setMimeType(mimeType)
            req.addRequestHeader("User-Agent", userAgent ?: mobileUa)
            val cm: CookieManager = if (priv) {
                ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE).cookieManager
            } else {
                CookieManager.getInstance()
            }
            cm.getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            req.setTitle(name)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Downloading " + name)
        } catch (e: Exception) {
            toast("Download failed")
        }
    }

    // ---- storage (history and bookmarks) ----

    private fun loadList(key: String): MutableList<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val arr = JSONArray(prefs.getString(key, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.getString("t"), o.getString("u")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun saveList(key: String, list: List<Pair<String, String>>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("t", p.first).put("u", p.second))
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun addHistory(title: String, url: String) {
        val l = loadList("history")
        l.removeAll { it.second == url }
        l.add(0, Pair(title, url))
        saveList("history", l.take(200))
    }

    private fun addBookmark() {
        val t = current()
        if (t.url.isEmpty()) {
            toast("Open a page first")
            return
        }
        val l = loadList("bookmarks")
        if (l.any { it.second == t.url }) {
            toast("Already bookmarked")
            return
        }
        l.add(0, Pair(t.title, t.url))
        saveList("bookmarks", l)
        toast("Bookmarked")
    }

    // ---- dialogs ----

    private fun showList(title: String, key: String) {
        val l = loadList(key)
        if (l.isEmpty()) {
            toast("Nothing here yet")
            return
        }
        val labels = l.map { (if (it.first.isBlank()) it.second else it.first) + "\n" + it.second }.toTypedArray()
        val b = AlertDialog.Builder(this).setTitle(title)
            .setItems(labels) { _, i -> pickItem(key, l[i]) }
        if (key == "history") {
            b.setNeutralButton("Clear all") { _, _ ->
                saveList(key, emptyList())
                toast("History cleared")
            }
        }
        b.setNegativeButton("Close", null).show()
    }

    private fun pickItem(key: String, item: Pair<String, String>) {
        AlertDialog.Builder(this)
            .setTitle(item.first.take(40))
            .setItems(arrayOf("Open", "Open in new tab", "Delete")) { _, which ->
                when (which) {
                    0 -> current().web.loadUrl(item.second)
                    1 -> newTab(item.second)
                    2 -> {
                        val l = loadList(key)
                        l.removeAll { it.second == item.second }
                        saveList(key, l)
                        toast("Deleted")
                    }
                }
            }.show()
    }

    private fun showTabs() {
        val labels = tabs.mapIndexed { i, t ->
            (if (i == cur) "\u25CF " else "    ") + (if (t.priv) "(Private) " else "") + (if (t.title.isBlank()) "New tab" else t.title)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tabs (" + tabs.size + ")")
            .setItems(labels) { _, i -> switchTo(i) }
            .setPositiveButton("New tab") { _, _ -> newTab(null) }
            .setNeutralButton("Close this tab") { _, _ -> closeTab(cur) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMenu() {
        val items = arrayOf(
            "New tab",
            "New private tab",
            "Bookmark this page",
            "Bookmarks",
            "History",
            "Downloads",
            "Find in page",
            "Search engine: " + engines.first { it.second == searchBase() }.first,
            "News source: " + feedName(),
            if (current().desktop) "Desktop site: ON" else "Desktop site: OFF",
            "Copy link",
            "Share page",
            "Clear browsing data"
        )
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> newTab(null)
                1 -> newTab(null, true)
                2 -> addBookmark()
                3 -> showList("Bookmarks", "bookmarks")
                4 -> showList("History", "history")
                5 -> openDownloads()
                6 -> findInPage()
                7 -> chooseEngine()
                8 -> chooseNews()
                9 -> toggleDesktop()
                10 -> copyLink()
                11 -> sharePage()
                12 -> clearData()
            }
        }.show()
    }

    private fun toggleDesktop() {
        val t = current()
        t.desktop = !t.desktop
        t.web.settings.userAgentString = if (t.desktop) DESKTOP_UA else mobileUa
        t.web.reload()
        toast(if (t.desktop) "Desktop site on" else "Desktop site off")
    }

    private fun sharePage() {
        val u = current().url
        if (u.isEmpty()) {
            toast("Open a page first")
            return
        }
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, u)
        startActivity(Intent.createChooser(send, "Share page"))
    }

    private fun clearData() {
        for (t in tabs) {
            t.web.clearCache(true)
            t.web.clearFormData()
            t.web.clearSslPreferences()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        saveList("history", emptyList())
        toast("Browsing data cleared")
    }

    // ---- lifecycle ----

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            exitFullscreen()
            return
        }
        val t = current()
        if (t.web.canGoBack()) {
            t.web.goBack()
        } else if (tabs.size > 1) {
            closeTab(cur)
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        val hadPrivate = tabs.any { it.priv }
        for (t in tabs) {
            try {
                t.web.destroy()
            } catch (e: Exception) {
            }
        }
        if (hadPrivate) wipePrivate()
        super.onDestroy()
    }
}
