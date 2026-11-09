package com.rweb.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
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

class MainActivity : Activity() {

    private class Tab(val web: WebView) {
        var title = "New tab"
        var url = ""
    }

    private val BG = Color.parseColor("#2A201B")
    private val BAR = Color.parseColor("#372A24")
    private val ROSE = Color.parseColor("#E8909F")
    private val INK = Color.parseColor("#F3E9E4")
    private val MUTE = Color.parseColor("#A89690")

    private val tabs = mutableListOf<Tab>()
    private var cur = -1
    private var desktop = false
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var tabBtn: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var mobileUa: String
    private var pendingMedia: PermissionRequest? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCb: GeolocationPermissions.Callback? = null

    companion object {
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val START_HTML = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;background:#2A201B;color:#F3E9E4;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh}h1{color:#E8909F;font-size:44px;margin:0 0 24px}form{width:86%;max-width:520px}input{width:100%;box-sizing:border-box;padding:14px 18px;border-radius:26px;border:0;background:#372A24;color:#F3E9E4;font-size:16px}</style></head><body><h1>rweb</h1><form action="https://www.google.com/search" method="get"><input name="q" placeholder="Search the web" autocomplete="off"></form></body></html>"""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rweb", MODE_PRIVATE)
        mobileUa = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        window.statusBarColor = BG
        window.navigationBarColor = BAR
        buildUi()
        newTab(intent?.data?.toString())
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

        val top = LinearLayout(this)
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
        setContentView(root)
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
        val target = UrlUtil.toNavigationTarget(text)
        if (target.isEmpty()) return
        current().web.loadUrl(target)
    }

    private fun loadStart(web: WebView) {
        web.loadDataWithBaseURL(null, START_HTML, "text/html", "utf-8", null)
    }

    private fun newTab(url: String?) {
        val tab = Tab(WebView(this))
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
        tabBtn.text = tabs.size.toString()
        progress.visibility = View.GONE
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        content.removeView(t.web)
        t.web.destroy()
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
        s.userAgentString = if (desktop) DESKTOP_UA else mobileUa
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

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
                    addHistory(tab.title, tab.url)
                } else {
                    tab.title = "New tab"
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

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mimeType)
                req.addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
                req.setTitle(name)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Downloading " + name)
            } catch (e: Exception) {
                toast("Download failed")
            }
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
            (if (i == cur) "\u25CF " else "    ") + (if (t.title.isBlank()) "New tab" else t.title)
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
            "Bookmark this page",
            "Bookmarks",
            "History",
            if (desktop) "Desktop site: ON" else "Desktop site: OFF",
            "Share page",
            "Clear browsing data"
        )
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> newTab(null)
                1 -> addBookmark()
                2 -> showList("Bookmarks", "bookmarks")
                3 -> showList("History", "history")
                4 -> toggleDesktop()
                5 -> sharePage()
                6 -> clearData()
            }
        }.show()
    }

    private fun toggleDesktop() {
        desktop = !desktop
        for (t in tabs) t.web.settings.userAgentString = if (desktop) DESKTOP_UA else mobileUa
        current().web.reload()
        toast(if (desktop) "Desktop site on" else "Desktop site off")
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
        for (t in tabs) t.web.clearCache(true)
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        saveList("history", emptyList())
        toast("Browsing data cleared")
    }

    // ---- lifecycle ----

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
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
        for (t in tabs) t.web.destroy()
        super.onDestroy()
    }
}
