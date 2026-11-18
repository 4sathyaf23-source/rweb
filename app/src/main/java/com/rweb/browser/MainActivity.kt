package com.rweb.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val P = 0xFF7B0E24.toInt()
    private val PD = 0xFF560A19.toInt()
    private val BG = 0xFFFBF7F8.toInt()
    private val TXT = 0xFF211216.toInt()
    private val SUB = 0xFF7B6369.toInt()
    private val W = 0xFFFFFFFF.toInt()
    private val HOME = "rweb://home"

    private lateinit var root: LinearLayout
    private lateinit var webHost: FrameLayout
    private lateinit var urlBox: LinearLayout
    private lateinit var lockIcon: ImageView
    private lateinit var urlInput: EditText
    private lateinit var reloadBtn: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var backBtn: ImageView
    private lateinit var fwdBtn: ImageView
    private lateinit var homeBtn: ImageView
    private lateinit var tabBtn: TextView
    private lateinit var menuBtn: ImageView

    private data class Tab(
        val id: Int,
        val web: WebView,
        var url: String = "rweb://home",
        var title: String = "New Tab",
        var desktop: Boolean = false,
        var forcedDesktopForImage: Boolean = false
    )

    private val tabs = mutableListOf<Tab>()
    private var cur = 0
    private var nextId = 1
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var defaultUa = ""
    private var desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val uiHandler = Handler(Looper.getMainLooper())
    private val bgExec = Executors.newCachedThreadPool()
    private var feedsCache: String? = null
    private var feedsLoading = false
    private var camPhotoFile: File? = null

    private val prefs by lazy { getSharedPreferences("rweb_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PD
        window.navigationBarColor = PD
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        buildUi()
        setContentView(root)

        val incoming = intent?.dataString
        if (!incoming.isNullOrBlank()) {
            newTab(incoming)
        } else {
            newTab(HOME)
        }
        refreshFeeds()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val u = intent?.dataString
        if (!u.isNullOrBlank()) newTab(u)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(P)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        urlBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(0xFF470815.toInt(), dp(22))
            setPadding(dp(12), dp(6), dp(10), dp(6))
        }

        lockIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_search_w)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        urlBox.addView(lockIcon, LinearLayout.LayoutParams(dp(16), dp(16)).apply {
            marginEnd = dp(8)
        })

        urlInput = EditText(this).apply {
            hint = "Enter address or search"
            setHintTextColor(0xB3FFFFFF.toInt())
            setTextColor(W)
            textSize = 14f
            background = null
            setSingleLine(true)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(0, dp(2), 0, dp(2))
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    val q = text.toString().trim()
                    if (q.isNotEmpty()) loadQuery(q)
                    hideKeyboard()
                    clearFocus()
                    true
                } else false
            }
            setOnFocusChangeListener { _, hasFocus ->
                val t = currentTab() ?: return@setOnFocusChangeListener
                if (hasFocus) {
                    if (t.url == HOME) setText("") else setText(t.url)
                    selectAll()
                } else {
                    syncUrlBar(t)
                }
            }
        }
        urlBox.addView(urlInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        reloadBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_reload_w)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(4), dp(4), dp(4))
            setOnClickListener {
                val t = currentTab() ?: return@setOnClickListener
                if (t.url == HOME) {
                    t.web.loadDataWithBaseURL(HOME, startHtml(), "text/html", "UTF-8", HOME)
                    refreshFeeds(force = true)
                } else {
                    t.web.reload()
                }
            }
        }
        urlBox.addView(reloadBtn, LinearLayout.LayoutParams(dp(26), dp(26)))

        topBar.addView(urlBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressDrawable.setColorFilter(P, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        webHost = FrameLayout(this).apply {
            setBackgroundColor(BG)
        }
        root.addView(webHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val navWrap = FrameLayout(this).apply {
            setBackgroundColor(BG)
            setPadding(dp(12), dp(6), dp(12), dp(8))
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundRect(P, dp(26))
            elevation = dp(6).toFloat()
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        backBtn = navIcon(R.drawable.ic_back_w) {
            val t = currentTab() ?: return@navIcon
            if (t.web.canGoBack()) t.web.goBack()
        }
        fwdBtn = navIcon(R.drawable.ic_fwd_w) {
            val t = currentTab() ?: return@navIcon
            if (t.web.canGoForward()) t.web.goForward()
        }
        homeBtn = navIcon(R.drawable.ic_home_w) {
            val t = currentTab() ?: return@navIcon
            resetDesktopIfForced(t)
            t.url = HOME
            t.web.loadDataWithBaseURL(HOME, startHtml(), "text/html", "UTF-8", HOME)
            refreshFeeds()
            syncUrlBar(t)
        }

        val tabWrap = FrameLayout(this).apply {
            setOnClickListener { showTabsDialog() }
        }
        tabBtn = TextView(this).apply {
            text = "1"
            setTextColor(W)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = strokeRect(W, dp(2), dp(6))
        }
        tabWrap.addView(tabBtn, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))

        menuBtn = navIcon(R.drawable.ic_menu_w) { showMenuDialog() }

        pill.addView(backBtn, LinearLayout.LayoutParams(0, dp(36), 1f))
        pill.addView(fwdBtn, LinearLayout.LayoutParams(0, dp(36), 1f))
        pill.addView(homeBtn, LinearLayout.LayoutParams(0, dp(36), 1f))
        pill.addView(tabWrap, LinearLayout.LayoutParams(0, dp(36), 1f))
        pill.addView(menuBtn, LinearLayout.LayoutParams(0, dp(36), 1f))

        navWrap.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(navWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun navIcon(res: Int, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(res)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { onClick() }
        }

    private fun roundRect(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun strokeRect(color: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(stroke, color)
            cornerRadius = radius.toFloat()
        }

    private fun currentTab(): Tab? = tabs.getOrNull(cur)

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String) {
        val wv = WebView(this)
        val tab = Tab(nextId++, wv, url)
        setupWeb(tab)
        tabs.add(tab)
        switchTo(tabs.size - 1)
        if (url == HOME) {
            wv.loadDataWithBaseURL(HOME, startHtml(), "text/html", "UTF-8", HOME)
            refreshFeeds()
        } else {
            loadQuery(url)
        }
    }

    private fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        cur = index
        webHost.removeAllViews()
        val t = tabs[cur]
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        webHost.addView(t.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabBtn.text = tabs.size.toString()
        syncUrlBar(t)
        updateNavButtons(t)
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) {
            val t = tabs[0]
            resetDesktopIfForced(t)
            t.url = HOME
            t.title = "New Tab"
            t.web.loadDataWithBaseURL(HOME, startHtml(), "text/html", "UTF-8", HOME)
            syncUrlBar(t)
            return
        }
        val removed = tabs.removeAt(index)
        removed.web.destroy()
        val next = when {
            cur >= tabs.size -> tabs.size - 1
            index < cur -> cur - 1
            else -> cur
        }
        switchTo(next)
    }

    private fun resetDesktopIfForced(t: Tab) {
        if (t.forcedDesktopForImage) {
            t.forcedDesktopForImage = false
            if (!t.desktop) {
                t.web.settings.userAgentString = defaultUa
            }
        }
    }

    private fun enableDesktopForImageSearch(t: Tab) {
        if (!t.desktop) {
            t.forcedDesktopForImage = true
            t.web.settings.userAgentString = desktopUa
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb(tab: Tab) {
        val w = tab.web
        val s = w.settings
        if (defaultUa.isEmpty()) defaultUa = s.userAgentString
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setGeolocationEnabled(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = if (tab.desktop || tab.forcedDesktopForImage) desktopUa else defaultUa

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.addJavascriptInterface(RwebBridge(), "Rweb")

        w.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return null
                val host = (u.host ?: "").lowercase()
                if (prefs.getBoolean("adblock", true) && isAdHost(host)) {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val str = u.toString()
                if (str == HOME) {
                    resetDesktopIfForced(tab)
                    tab.url = HOME
                    view?.loadDataWithBaseURL(HOME, startHtml(), "text/html", "UTF-8", HOME)
                    refreshFeeds()
                    return true
                }
                if (str == "rweb://voice") {
                    startVoiceSearch()
                    return true
                }
                if (str == "rweb://photo" || str == "rweb://images") {
                    enableDesktopForImageSearch(tab)
                    tab.web.loadUrl("https://www.google.com/imghp?hl=en")
                    return true
                }
                val scheme = (u.scheme ?: "").lowercase()
                if (scheme == "http" || scheme == "https") {
                    return false
                }
                return try {
                    val intent = if (scheme == "intent") {
                        Intent.parseUri(str, Intent.URI_INTENT_SCHEME)
                    } else {
                        Intent(Intent.ACTION_VIEW, u)
                    }
                    startActivity(intent)
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val u = url ?: return
                tab.url = u
                if (tab == currentTab()) {
                    syncUrlBar(tab)
                    progress.visibility = View.VISIBLE
                    progress.progress = 15
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val u = url ?: return
                tab.url = u
                val t = view?.title?.takeIf { it.isNotBlank() } ?: u
                tab.title = if (u == HOME) "New Tab" else t
                if (u == HOME) {
                    injectFeeds(view)
                } else {
                    addHistory(tab.title, u)
                }
                if (tab == currentTab()) {
                    syncUrlBar(tab)
                    updateNavButtons(tab)
                    progress.visibility = View.GONE
                }
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tab == currentTab()) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank() && tab.url != HOME) tab.title = title
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = filePathCallback
                return try {
                    val i = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    startActivityForResult(i, 1001)
                    true
                } catch (_: Exception) {
                    fileCb = null
                    false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        1002
                    )
                }
                callback?.invoke(origin, true, false)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    val res = request?.resources ?: return@runOnUiThread
                    val need = mutableListOf<String>()
                    if (res.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    ) need.add(Manifest.permission.CAMERA)
                    if (res.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                    ) need.add(Manifest.permission.RECORD_AUDIO)
                    if (need.isNotEmpty()) {
                        ActivityCompat.requestPermissions(this@MainActivity, need.toTypedArray(), 1003)
                    }
                    request.grant(res)
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCb = callback
                (window.decorView as FrameLayout).addView(
                    view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
            }

            override fun onHideCustomView() {
                val v = customView ?: return
                (window.decorView as FrameLayout).removeView(v)
                customView = null
                customCb?.onCustomViewHidden()
                customCb = null
            }
        }
    }

    private fun startVoiceSearch() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search")
            }
            startActivityForResult(i, 1004)
        } catch (_: Exception) {
            toast("Voice input not available on this device")
        }
    }

    private fun startPhotoSearch() {
        val t = currentTab() ?: return
        enableDesktopForImageSearch(t)
        t.web.loadUrl("https://www.google.com/imghp?hl=en")
    }

    private fun startImageSearch() {
        val t = currentTab() ?: return
        enableDesktopForImageSearch(t)
        t.web.loadUrl("https://www.google.com/imghp?hl=en")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            val cb = fileCb ?: return
            fileCb = null
            val res = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            cb.onReceiveValue(res)
        } else if (requestCode == 1004 && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) loadQuery(spoken)
        } else if (requestCode == 1005) {
            val f = camPhotoFile
            camPhotoFile = null
            if (resultCode == RESULT_OK && f != null && f.exists()) {
                val t = currentTab() ?: return
                enableDesktopForImageSearch(t)
                t.web.loadUrl("https://www.google.com/imghp?hl=en")
            }
        }
    }

    private fun loadQuery(raw: String) {
        val t = currentTab() ?: return
        val q = raw.trim()
        if (q.equals(HOME, true)) {
            resetDesktopIfForced(t)
            t.url = HOME
            t.web.loadDataWithBaseURL(HOME, startHtml(), "text/html", "UTF-8", HOME)
            refreshFeeds()
            syncUrlBar(t)
            return
        }
        val target = when {
            q.startsWith("http://", true) || q.startsWith("https://", true) -> q
            !q.contains(" ") && q.contains(".") && !q.startsWith(".") && !q.endsWith(".") -> "https://$q"
            else -> searchBase() + URLEncoder.encode(q, "UTF-8")
        }

        // Restore normal user-agent if navigating away from forced-desktop image search
        if (!target.contains("google.com/imghp") && !target.contains("lens.google.com")) {
            resetDesktopIfForced(t)
        }

        t.url = target
        t.web.loadUrl(target)
        syncUrlBar(t)
    }

    private fun searchBase(): String =
        when (prefs.getString("engine", "google")) {
            "ddg" -> "https://duckduckgo.com/?q="
            "bing" -> "https://www.bing.com/search?q="
            else -> "https://www.google.com/search?q="
        }

    private fun typedSearchBases(): Triple<String, String, String> {
        val web = searchBase()
        return when {
            web.startsWith("https://duckduckgo.com") -> Triple(web, "https://duckduckgo.com/?iax=images&ia=images&q=", "https://duckduckgo.com/?iax=videos&ia=videos&q=")
            web.startsWith("https://www.bing.com") -> Triple(web, "https://www.bing.com/images/search?q=", "https://www.bing.com/videos/search?q=")
            else -> Triple(web, "https://www.google.com/search?udm=2&q=", "https://www.google.com/search?tbm=vid&q=")
        }
    }

    private fun syncUrlBar(t: Tab) {
        if (urlInput.hasFocus()) return
        if (t.url == HOME) {
            lockIcon.setImageResource(R.drawable.ic_search_w)
            urlInput.setText("")
        } else {
            lockIcon.setImageResource(if (t.url.startsWith("https://")) R.drawable.ic_lock_w else R.drawable.ic_search_w)
            urlInput.setText(t.url)
        }
    }

    private fun updateNavButtons(t: Tab) {
        backBtn.alpha = if (t.web.canGoBack()) 1f else 0.4f
        fwdBtn.alpha = if (t.web.canGoForward()) 1f else 0.4f
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    override fun onBackPressed() {
        if (customView != null) {
            tabs.getOrNull(cur)?.web?.webChromeClient?.onHideCustomView()
            return
        }
        val t = currentTab()
        if (t != null && t.web.canGoBack()) {
            t.web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun showTabsDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        var dlg: AlertDialog? = null
        tabs.forEachIndexed { i, tab ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundRect(if (i == cur) 0xFFF2DFE3.toInt() else 0xFFF7F2F3.toInt(), dp(12))
                setPadding(dp(12), dp(10), dp(8), dp(10))
                setOnClickListener {
                    switchTo(i)
                    dlg?.dismiss()
                }
            }
            val title = TextView(this).apply {
                text = tab.title
                setTextColor(TXT)
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val close = TextView(this).apply {
                text = "✕"
                setTextColor(SUB)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener {
                    closeTab(i)
                    dlg?.dismiss()
                }
            }
            row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(close)
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        val scroll = ScrollView(this).apply { addView(box) }
        dlg = styledDialog("Tabs (${tabs.size})", scroll, "New tab", { newTab(HOME) }, "Close", null)
        dlg.show()
    }

    private fun showMenuDialog() {
        val t = currentTab() ?: return
        val items = arrayOf(
            "New tab",
            "Bookmark this page",
            "Bookmarks",
            "History",
            "Share page",
            "Find on page",
            if (t.desktop) "Desktop site: ON" else "Desktop site: OFF",
            if (prefs.getBoolean("adblock", true)) "Ad blocker: ON" else "Ad blocker: OFF",
            "Search engine",
            "Clear browsing data"
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(6))
        }
        var dlg: AlertDialog? = null
        items.forEachIndexed { idx, label ->
            val tv = TextView(this).apply {
                text = label
                setTextColor(TXT)
                textSize = 15f
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setOnClickListener {
                    dlg?.dismiss()
                    handleMenu(idx)
                }
            }
            box.addView(tv)
        }
        val scroll = ScrollView(this).apply { addView(box) }
        dlg = styledDialog("rweb", scroll, null, null, "Close", null)
        dlg.show()
    }

    private fun handleMenu(which: Int) {
        val t = currentTab() ?: return
        when (which) {
            0 -> newTab(HOME)
            1 -> {
                if (t.url == HOME) { toast("Open a site first"); return }
                addBookmark(t.title, t.url)
                toast("Bookmarked")
            }
            2 -> showListDialog("Bookmarks", loadList("bookmarks"), clearable = false)
            3 -> showListDialog("History", loadList("history"), clearable = true)
            4 -> {
                if (t.url == HOME) return
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, t.title)
                    putExtra(Intent.EXTRA_TEXT, t.url)
                }
                startActivity(Intent.createChooser(i, "Share"))
            }
            5 -> showFindDialog()
            6 -> {
                t.desktop = !t.desktop
                t.forcedDesktopForImage = false
                t.web.settings.userAgentString = if (t.desktop) desktopUa else defaultUa
                if (t.url != HOME) t.web.reload()
                toast(if (t.desktop) "Desktop site on" else "Desktop site off")
            }
            7 -> {
                val next = !prefs.getBoolean("adblock", true)
                prefs.edit().putBoolean("adblock", next).apply()
                if (t.url != HOME) t.web.reload()
                toast(if (next) "Ad blocker on" else "Ad blocker off")
            }
            8 -> showEngineDialog()
            9 -> {
                t.web.clearCache(true)
                t.web.clearHistory()
                CookieManager.getInstance().removeAllCookies(null)
                prefs.edit().remove("history").apply()
                toast("Browsing data cleared")
            }
        }
    }

    private fun showFindDialog() {
        val t = currentTab() ?: return
        val input = EditText(this).apply {
            hint = "Text to find"
            setTextColor(TXT)
            setHintTextColor(SUB)
            setSingleLine(true)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(6))
            addView(input)
        }
        styledDialog("Find on page", wrap, "Find", {
            val q = input.text.toString()
            if (q.isNotEmpty()) t.web.findAllAsync(q)
        }, "Clear", { t.web.clearMatches() }).show()
    }

    private fun showEngineDialog() {
        val names = arrayOf("Google", "DuckDuckGo", "Bing")
        val keys = arrayOf("google", "ddg", "bing")
        val curKey = prefs.getString("engine", "google")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        var dlg: AlertDialog? = null
        names.forEachIndexed { idx, name ->
            val selected = keys[idx] == curKey
            val tv = TextView(this).apply {
                text = if (selected) "●  $name" else "○  $name"
                setTextColor(if (selected) P else TXT)
                textSize = 15f
                if (selected) typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setOnClickListener {
                    prefs.edit().putString("engine", keys[idx]).apply()
                    val t = currentTab()
                    if (t != null && t.url == HOME) {
                        t.web.loadDataWithBaseURL(HOME, startHtml(), "text/html", "UTF-8", HOME)
                        refreshFeeds()
                    }
                    toast("Search engine: $name")
                    dlg?.dismiss()
                }
            }
            box.addView(tv)
        }
        dlg = styledDialog("Search engine", box, null, null, "Close", null)
        dlg.show()
    }

    private fun showListDialog(title: String, items: MutableList<Pair<String, String>>, clearable: Boolean) {
        if (items.isEmpty()) {
            toast("No $title yet")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        var dlg: AlertDialog? = null
        items.forEachIndexed { idx, (t, u) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setOnClickListener {
                    dlg?.dismiss()
                    loadQuery(u)
                }
            }
            val tv1 = TextView(this).apply {
                text = t
                setTextColor(TXT)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val tv2 = TextView(this).apply {
                text = u
                setTextColor(SUB)
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(tv1)
            textCol.addView(tv2)
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (!clearable) {
                val del = TextView(this).apply {
                    text = "✕"
                    setTextColor(SUB)
                    textSize = 15f
                    setPadding(dp(10), dp(4), dp(8), dp(4))
                    setOnClickListener {
                        items.removeAt(idx)
                        saveList("bookmarks", items)
                        dlg?.dismiss()
                        showListDialog(title, items, false)
                    }
                }
                row.addView(del)
            }
            box.addView(row)
        }
        val scroll = ScrollView(this).apply { addView(box) }
        dlg = styledDialog(
            title,
            scroll,
            if (clearable) "Clear all" else null,
            if (clearable) ({ prefs.edit().remove("history").apply(); toast("History cleared") }) else null,
            "Close",
            null
        )
        dlg.show()
    }

    private fun styledDialog(
        titleText: String,
        body: View,
        posLabel: String?,
        onPos: (() -> Unit)?,
        negLabel: String?,
        onNeg: (() -> Unit)?
    ): AlertDialog {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(W, dp(22))
            setPadding(dp(6), dp(14), dp(6), dp(10))
        }
        val head = TextView(this).apply {
            text = titleText
            setTextColor(TXT)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        card.addView(head)
        card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(6))
        }
        var dlg: AlertDialog? = null
        fun makeBtn(label: String, filled: Boolean, action: (() -> Unit)?): TextView =
            TextView(this).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (filled) W else P)
                background = if (filled) roundRect(P, dp(20)) else strokeRect(P, dp(1), dp(20))
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener {
                    dlg?.dismiss()
                    action?.invoke()
                }
            }
        if (posLabel != null) {
            btns.addView(makeBtn(posLabel, true, onPos), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (negLabel != null) marginEnd = dp(8)
            })
        }
        if (negLabel != null) {
            btns.addView(makeBtn(negLabel, false, onNeg), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        card.addView(btns)

        dlg = AlertDialog.Builder(this).setView(card).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dlg
    }

    private fun startDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) addRequestHeader("cookie", cookies)
                addRequestHeader("User-Agent", userAgent)
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            toast("Downloading...")
        } catch (_: Exception) {
            toast("Could not start download")
        }
    }

    private fun addBookmark(title: String, url: String) {
        val l = loadList("bookmarks")
        l.removeAll { it.second == url }
        l.add(0, Pair(title, url))
        saveList("bookmarks", l.take(100))
    }

    private fun addHistory(title: String, url: String) {
        // Skip saving giant internal Google Lens / session query URLs
        if (url.contains("vsrid=") || url.contains("gsessionid=") || url.contains("vsint=")) return

        val l = loadList("history")
        l.removeAll { it.second == url }
        l.add(0, Pair(title, url))
        saveList("history", l.take(200))
    }

    private fun loadList(key: String): MutableList<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val raw = prefs.getString(key, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.optString("t"), o.optString("u")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveList(key: String, items: List<Pair<String, String>>) {
        val arr = JSONArray()
        items.forEach { (t, u) ->
            arr.put(JSONObject().put("t", t).put("u", u))
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun isAdHost(host: String): Boolean {
        if (host.isEmpty()) return false
        val blocked = arrayOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "adnxs.com",
            "adsrvr.org",
            "rubiconproject.com",
            "pubmatic.com",
            "openx.net",
            "criteo.com",
            "taboola.com",
            "outbrain.com",
            "scorecardresearch.com",
            "moatads.com",
            "amazon-adsystem.com",
            "advertising.com",
            "zedo.com",
            "popads.net",
            "propellerads.com"
        )
        return blocked.any { host == it || host.endsWith(".$it") }
    }

    inner class RwebBridge {
        @JavascriptInterface
        fun open(url: String) {
            runOnUiThread { loadQuery(url) }
        }

        @JavascriptInterface
        fun openImages() {
            runOnUiThread { startImageSearch() }
        }

        @JavascriptInterface
        fun voice() {
            runOnUiThread { startVoiceSearch() }
        }

        @JavascriptInterface
        fun photo() {
            runOnUiThread { startPhotoSearch() }
        }

        @JavascriptInterface
        fun share(title: String, url: String) {
            runOnUiThread {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, "$title\n$url")
                }
                startActivity(Intent.createChooser(i, "Share"))
            }
        }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("rweb", text))
                toast("Link copied")
            }
        }
    }

    private fun refreshFeeds(force: Boolean = false) {
        if (feedsLoading) return
        if (!force && feedsCache != null) return
        feedsLoading = true
        bgExec.execute {
            val html = buildFeeds()
            uiHandler.post {
                feedsLoading = false
                feedsCache = html
                tabs.forEach { t ->
                    if (t.url == HOME) injectFeeds(t.web)
                }
            }
        }
    }

    private fun injectFeeds(wv: WebView?) {
        val html = feedsCache ?: return
        val b64 = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        wv?.evaluateJavascript(
            "(function(){var e=document.getElementById('vids');if(e){e.innerHTML=decodeURIComponent(escape(atob('$b64')));}})();",
            null
        )
    }

    private fun buildFeeds(): String {
        data class Cat(val id: String, val label: String, val url: String)
        data class Item(val id: String, val cat: String, val title: String, val ch: String, val whenTxt: String)

        val cats = listOf(
            Cat("tech", "Tech", "https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ"),
            Cat("sports", "Sports", "https://www.youtube.com/feeds/videos.xml?channel_id=UCqZQlzSHbVJrwrn5XvzrzcA"),
            Cat("biz", "Business", "https://www.youtube.com/feeds/videos.xml?channel_id=UCvJJ_dzjViJCoLf5uKUTwoA"),
            Cat("sci", "Science", "https://www.youtube.com/feeds/videos.xml?channel_id=UCsXVk37bltHxD1rDPwtNM8Q"),
            Cat("edu", "Learn", "https://www.youtube.com/feeds/videos.xml?channel_id=UCsooa4yRKGN_zEE8iknghZA"),
            Cat("ent", "Entertainment", "https://www.youtube.com/feeds/videos.xml?channel_id=UCi8e0iOVk1fEOogdfu4YgfA")
        )
        val perCat = linkedMapOf<String, MutableList<Item>>()
        cats.forEach { c ->
            val xml = fetchText(c.url)
            val list = mutableListOf<Item>()
            if (xml != null) {
                var pos = 0
                while (list.size < 4) {
                    val s = xml.indexOf("<entry>", pos)
                    if (s < 0) break
                    val e = xml.indexOf("</entry>", s)
                    if (e < 0) break
                    val chunk = xml.substring(s, e + 8)
                    pos = e + 8
                    val id = tagText(chunk, "yt:videoId")
                    val title = tagText(chunk, "title")
                    val ch = tagText(chunk, "name")
                    val pub = tagText(chunk, "published")
                    if (id.isNotEmpty() && title.isNotEmpty()) {
                        list.add(Item(id, c.id, title, ch, relTime(pub)))
                    }
                }
            }
            perCat[c.id] = list
        }

        val all = mutableListOf<Item>()
        for (i in 0 until 4) {
            cats.forEach { c ->
                perCat[c.id]?.getOrNull(i)?.let { all.add(it) }
            }
        }
        val items = all.take(16)
        if (items.isEmpty()) {
            return "<div class=\"empty\">Could not load videos right now. Pull down or tap reload to retry.</div>"
        }

        val sb = StringBuilder()
        sb.append("<div class=\"sec-hd\">TRENDING VIDEOS</div>")
        sb.append("<div class=\"cats\">")
        sb.append("<button class=\"cat on\" data-c=\"all\" onclick=\"setCat('all',this)\">All</button>")
        cats.forEach { c ->
            if (!perCat[c.id].isNullOrEmpty()) {
                sb.append("<button class=\"cat\" data-c=\"").append(c.id).append("\" onclick=\"setCat('")
                    .append(c.id).append("',this)\">").append(esc(c.label)).append("</button>")
            }
        }
        sb.append("</div><div class=\"vlist\">")
        items.forEach { it ->
            val u = "https://www.youtube.com/watch?v=${it.id}"
            val thumb = "https://i.ytimg.com/vi/${it.id}/hqdefault.jpg"
            val qUrl = jsStr(u)
            val qTitle = jsStr(it.title)
            sb.append("<div class=\"vcard\" data-c=\"").append(it.cat).append("\">")
            sb.append("<a class=\"vthumb\" href=\"").append(u).append("\">")
            sb.append("<img src=\"").append(thumb).append("\" alt=\"\" loading=\"lazy\">")
            sb.append("<span class=\"vplay\">&#9654;</span></a>")
            sb.append("<div class=\"vbody\">")
            sb.append("<a class=\"vtitle\" href=\"").append(u).append("\">").append(esc(it.title)).append("</a>")
            sb.append("<div class=\"vmeta\">").append(esc(it.ch))
            if (it.whenTxt.isNotEmpty()) sb.append(" &middot; ").append(esc(it.whenTxt))
            sb.append("</div>")
            sb.append("<div class=\"vacts\">")
            sb.append("<button class=\"vbtn\" onclick=\"Rweb.open(").append(qUrl).append(")\">Watch</button>")
            sb.append("<button class=\"vbtn\" onclick=\"Rweb.share(").append(qTitle).append(",").append(qUrl).append(")\">Share</button>")
            sb.append("<button class=\"vbtn\" onclick=\"Rweb.copy(").append(qUrl).append(")\">Copy</button>")
            sb.append("</div></div></div>")
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun fetchText(url: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun tagText(xml: String, tag: String): String {
        val open = "<$tag>"
        val close = "</$tag>"
        val s = xml.indexOf(open)
        if (s < 0) return ""
        val e = xml.indexOf(close, s + open.length)
        if (e < 0) return ""
        return unescXml(xml.substring(s + open.length, e).trim())
    }

    private fun unescXml(s: String): String =
        s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")

    private fun relTime(iso: String): String {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val clean = iso.substringBefore("+").substringBefore("Z")
            val t = sdf.parse(clean)?.time ?: return ""
            val diff = System.currentTimeMillis() - t
            val hrs = diff / 3600000L
            when {
                hrs < 1 -> "Just now"
                hrs < 24 -> "${hrs}h ago"
                hrs < 24 * 7 -> "${hrs / 24}d ago"
                else -> "${hrs / (24 * 7)}w ago"
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun jsStr(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "") + "'"

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun searchScript(): String {
        val (web, img, vid) = typedSearchBases()
        val jWeb = jsStr(web)
        val jImg = jsStr(img)
        val jVid = jsStr(vid)
        return """
        var mode='web';
        var bases={web:$jWeb,images:$jImg,videos:$jVid};
        function setMode(m,el){
          if(m==='images'){Rweb.openImages();return;}
          if(m==='voice'){Rweb.voice();return;}
          if(m==='photo'){Rweb.photo();return;}
          mode=m;
          var ps=document.querySelectorAll('.pill');
          for(var i=0;i<ps.length;i++)ps[i].classList.remove('on');
          el.classList.add('on');
        }
        function doSearch(){
          var v=document.getElementById('q').value.trim();
          if(!v)return;
          if(mode==='web'&&!/\s/.test(v)&&v.indexOf('.')>0&&v.indexOf('.')<v.length-1){
            Rweb.open(/^https?:\/\//i.test(v)?v:'https://'+v);
          }else{
            Rweb.open(bases[mode]+encodeURIComponent(v));
          }
        }
        function setCat(c,el){
          var bs=document.querySelectorAll('.cat');
          for(var i=0;i<bs.length;i++)bs[i].classList.remove('on');
          el.classList.add('on');
          var cs=document.querySelectorAll('.vcard');
          for(var j=0;j<cs.length;j++){
            cs[j].style.display=(c==='all'||cs[j].getAttribute('data-c')===c)?'flex':'none';
          }
        }
        """.trimIndent()
    }

    private fun startHtml(): String {
        val tiles = listOf(
            arrayOf("YouTube", "https://m.youtube.com", "https://www.youtube.com/favicon.ico", "#E53935", "YT"),
            arrayOf("ChatGPT", "https://chatgpt.com", "https://chatgpt.com/favicon.ico", "#10A37F", "AI"),
            arrayOf("Facebook", "https://m.facebook.com", "https://www.facebook.com/favicon.ico", "#1877F2", "f"),
            arrayOf("Instagram", "https://www.instagram.com", "https://www.instagram.com/favicon.ico", "#E1306C", "IG"),
            arrayOf("Weather", "https://weather.com", "https://weather.com/favicon.ico", "#0288D1", "W"),
            arrayOf("Amazon", "https://www.amazon.in", "https://www.amazon.in/favicon.ico", "#FF9900", "a"),
            arrayOf("Google", "https://www.google.com", "https://www.google.com/favicon.ico", "#4285F4", "G"),
            arrayOf("Translate", "https://translate.google.com", "https://translate.google.com/favicon.ico", "#1A73E8", "Tr"),
            arrayOf("WhatsApp", "https://web.whatsapp.com", "https://web.whatsapp.com/favicon.ico", "#25D366", "WA"),
            arrayOf("Gmail", "https://mail.google.com", "https://ssl.gstatic.com/ui/v1/icons/mail/rfr/gmail.ico", "#EA4335", "M")
        )
        val sb = StringBuilder()
        sb.append(HEAD_A)
        sb.append(searchScript())
        sb.append(HEAD_B)
        tiles.forEach { t ->
            sb.append("<a class=\"tile\" href=\"").append(t[1]).append("\">")
            sb.append("<span class=\"ic\" style=\"background:").append(t[3]).append("\">")
            sb.append("<span class=\"fb\">").append(t[4]).append("</span>")
            sb.append("<img src=\"").append(t[2]).append("\" alt=\"\" onerror=\"this.style.display='none'\">")
            sb.append("</span><span class=\"lb\">").append(t[0]).append("</span></a>")
        }
        sb.append("</div><div id=\"vids\">")
        sb.append(feedsCache ?: "<div class=\"loading\">Loading trending videos&hellip;</div>")
        sb.append("</div>")
        sb.append(TAIL)
        return sb.toString()
    }

    companion object {
        private const val HEAD_A = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;padding:12px 16px 32px;background:#FBF7F8;color:#211216;font-family:-apple-system,Roboto,sans-serif;display:flex;flex-direction:column;align-items:center}
.sbox{width:100%;max-width:440px;display:flex;align-items:center;background:#FFFFFF;border:1.5px solid #EAD7DC;border-radius:26px;padding:4px 6px 4px 16px;box-shadow:0 3px 12px rgba(123,14,36,.08);margin:0 0 10px}
.sbox input{flex:1;border:none;outline:none;background:transparent;font-size:15px;color:#211216;padding:8px 4px;min-width:0}
.sbox input::placeholder{color:#8E757B}
.sbtn{border:none;background:#7B0E24;color:#FFFFFF;width:36px;height:36px;border-radius:18px;display:flex;align-items:center;justify-content:center;flex-shrink:0}
.sbtn:active{background:#560A19}
.pills{width:100%;max-width:440px;display:flex;flex-wrap:wrap;justify-content:center;gap:8px;margin-bottom:20px}
.pill{border:none;background:#F2EAEC;color:#5D474D;font-size:13px;font-weight:600;padding:8px 16px;border-radius:18px}
.pill.on{background:#7B0E24;color:#FFFFFF}
.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:18px 10px;width:100%;max-width:420px;margin-bottom:22px}
.tile{display:flex;flex-direction:column;align-items:center;text-decoration:none;color:#211216}
.tile:nth-child(9){grid-column:2}
.tile:nth-child(10){grid-column:3}
.ic{width:54px;height:54px;border-radius:50%;position:relative;display:flex;align-items:center;justify-content:center;color:#FFFFFF;font-weight:700;font-size:20px;box-shadow:0 3px 10px rgba(123,14,36,.12);margin-bottom:7px;overflow:hidden;border:1px solid #EFE4E7}
.ic .fb{position:relative;z-index:1;pointer-events:none}
.ic img{position:absolute;top:0;left:0;width:100%;height:100%;box-sizing:border-box;padding:13px;background:#FFFFFF;object-fit:contain;z-index:2;pointer-events:none}
.tile:active .ic{transform:scale(.94)}
.lb{font-size:12px;color:#473238;text-align:center;max-width:74px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#vids{width:100%;max-width:440px}
.sec-hd{font-size:11px;font-weight:700;letter-spacing:.9px;color:#7B6369;margin:0 0 10px 4px}
.cats{display:flex;gap:8px;overflow-x:auto;padding-bottom:6px;margin-bottom:12px;scrollbar-width:none}
.cats::-webkit-scrollbar{display:none}
.cat{border:none;background:#F2EAEC;color:#5D474D;font-size:12px;font-weight:600;padding:7px 14px;border-radius:16px;white-space:nowrap;flex-shrink:0}
.cat.on{background:#7B0E24;color:#FFFFFF}
.vlist{display:flex;flex-direction:column;gap:12px}
.vcard{display:flex;flex-direction:column;background:#FFFFFF;border-radius:16px;overflow:hidden;box-shadow:0 3px 12px rgba(123,14,36,.08);border:1px solid #EFE4E7}
.vthumb{position:relative;display:block;width:100%;aspect-ratio:16/9;background:#1A0D10;overflow:hidden}
.vthumb img{width:100%;height:100%;object-fit:cover;display:block}
.vplay{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:48px;height:48px;border-radius:24px;background:rgba(123,14,36,.92);color:#FFFFFF;display:flex;align-items:center;justify-content:center;font-size:17px;padding-left:3px}
.vbody{padding:11px 13px 12px}
.vtitle{display:block;text-decoration:none;color:#211216;font-size:14px;font-weight:700;line-height:1.35;margin-bottom:4px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.vmeta{font-size:12px;color:#7B6369;margin-bottom:10px}
.vacts{display:flex;gap:8px}
.vbtn{border:none;background:#F5EBEE;color:#7B0E24;font-size:12px;font-weight:700;padding:7px 13px;border-radius:14px}
.vbtn:active{background:#E8D3D8}
.loading,.empty{text-align:center;color:#7B6369;font-size:13px;padding:22px 12px}
</style><script>"""

        private const val HEAD_B = """</script></head><body>
<div class="pills">
  <button class="pill on" onclick="setMode('web',this)">Web</button>
  <button class="pill" onclick="setMode('images',this)">Images</button>
  <button class="pill" onclick="setMode('videos',this)">Videos</button>
  <button class="pill" onclick="setMode('voice',this)">Voice</button>
  <button class="pill" onclick="setMode('photo',this)">Photo</button>
</div>
<div class="grid">"""

        private const val TAIL = "</body></html>"
    }
}
