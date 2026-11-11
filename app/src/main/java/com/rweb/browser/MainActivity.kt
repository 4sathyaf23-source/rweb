package com.rweb.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcel
import android.text.InputType
import android.text.TextUtils
import android.util.Xml
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private class Tab(val web: WebView, val priv: Boolean = false) {
        var title = "New tab"
        var url = ""
        var desktop = false
        var loading = false
        var lastHistoryUrl = ""
        var pendingUrl: String? = null
        var savedState: Bundle? = null
    }

    private val BG = Color.parseColor("#3A0A14")
    private val BAR = Color.parseColor("#7A1022")
    private val ROSE = Color.parseColor("#FF6B7D")
    private val INK = Color.parseColor("#FFF1F3")
    private val MUTE = Color.parseColor("#E3A9B1")
    private val PRIV = Color.parseColor("#1A1230")

    private val tabs = mutableListOf<Tab>()
    private var cur = -1
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var tabBtn: TextView
    private lateinit var reloadBtn: ImageView
    private lateinit var lockIcon: ImageView
    private lateinit var bottomBg: GradientDrawable
    private lateinit var prefs: SharedPreferences
    private lateinit var mobileUa: String
    private lateinit var desktopUa: String
    private lateinit var topBar: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var statusSpacer: View
    private lateinit var navSpacer: View
    private lateinit var fullscreenHolder: FrameLayout
    private var lastPrivUi: Boolean? = null
    private var pendingMedia: PermissionRequest? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var newsHtml: String? = null
    private var newsTime = 0L
    private var newsLoading = false
    private var newsItems: List<Triple<String, String, String>>? = null
    private var newsName = ""
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCb: GeolocationPermissions.Callback? = null

    // background audio
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlaying = false
    private var inBackground = false
    private var serviceOn = false
    private val mediaPoll = object : Runnable {
        override fun run() {
            checkMedia()
            handler.postDelayed(this, 3000)
        }
    }

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

    companion object {
        const val PRIVATE_PROFILE = "rwebprivate"
        const val DESKTOP_UA_FALLBACK = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val AUDIO_JS = "(function(){var m=document.querySelectorAll('audio,video');for(var i=0;i<m.length;i++){var e=m[i];if(!e.paused&&!e.ended&&e.readyState>2&&!e.muted&&e.volume>0)return true}return false})()"
        const val HEAD_A = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>:root{--bg:#FFFFFF;--fg:#202124;--mute:#5F6368;--card:#F8F9FA;--fld:#F1F3F4;--acc:#C41E3A;--sh:0 4px 14px rgba(0,0,0,.08)}html.dark{--bg:#3A0A14;--fg:#FFF1F3;--mute:#E3A9B1;--card:#7A1022;--fld:#7A1022;--acc:#FF6B7D;--sh:0 4px 14px rgba(0,0,0,.35)}body{margin:0;padding:28px 16px;box-sizing:border-box;background:var(--bg);color:var(--fg);font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh}h1{color:var(--acc);font-size:44px;margin:0 0 28px}form{width:100%;max-width:520px}input{width:100%;box-sizing:border-box;padding:16px 22px;border-radius:28px;border:0;background:var(--fld);color:var(--fg);font-size:16px;box-shadow:var(--sh);outline:none}.grid{display:flex;flex-wrap:wrap;justify-content:center;gap:20px 16px;margin-top:32px;width:100%;max-width:520px}.tile{width:72px;text-decoration:none;color:var(--fg);text-align:center;font-size:12px}.ic{position:relative;overflow:hidden;display:flex;align-items:center;justify-content:center;width:56px;height:56px;margin:0 auto 8px;border-radius:50%;background:var(--fld);color:var(--acc);font-size:22px;font-weight:700;box-shadow:var(--sh)}.ic img{position:absolute;top:0;left:0;width:100%;height:100%;box-sizing:border-box;padding:13px;background:#FFFFFF;object-fit:contain}.nm{display:block;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}#news{width:100%;max-width:520px;margin-top:32px}.nh{font-size:12px;color:var(--mute);text-transform:uppercase;letter-spacing:.08em;margin-bottom:8px}summary.nh{list-style:none;cursor:pointer;display:flex;justify-content:space-between;align-items:center;padding:4px 4px 12px;margin:0}summary.nh::-webkit-details-marker{display:none}summary.nh::after{content:"\25BE";font-size:14px}details[open] summary.nh::after{content:"\25B4"}.nw{display:flex;align-items:center;gap:14px;padding:14px 16px;margin-bottom:12px;background:var(--card);border-radius:18px;box-shadow:var(--sh);color:var(--fg);text-decoration:none;font-size:14px;line-height:1.4}.nt{flex:1}.ni{width:88px;height:64px;object-fit:cover;border-radius:12px;flex-shrink:0}</style></head><body><h1>rweb</h1><form action=""""
        const val HEAD_B = """" method="get"><input name="q" placeholder="Search the web" autocomplete="off"></form><div class="grid">"""
    }

    // ---- lifecycle ----

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rweb", MODE_PRIVATE)
        mobileUa = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        desktopUa = buildDesktopUa()
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = BG
            window.navigationBarColor = BAR
        }
        wipePrivate()
        cleanPrivateDownloads()
        buildUi()
        registerBackHandler()
        restoreSavedTabs()
        val link = intent?.data?.toString()
        if (link != null) newTab(link) else if (tabs.isEmpty()) newTab(null)
    }

    private fun buildDesktopUa(): String {
        val v = Regex("Chrome/(\\d+)").find(mobileUa)?.groupValues?.get(1)
        return if (v != null) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + v + ".0.0.0 Safari/537.36"
        } else {
            DESKTOP_UA_FALLBACK
        }
    }

    private fun currentOrNull(): Tab? = tabs.getOrNull(cur)

    // ---- tab saving (normal tabs only, including their back/forward history) ----

    private fun saveTabs() {
        try {
            val file = File(filesDir, "tabs.bin")
            val normal = tabs.filter { !it.priv }
            if (normal.isEmpty()) {
                file.delete()
                return
            }
            val b = Bundle()
            b.putInt("n", normal.size)
            val c = currentOrNull()
            b.putInt("cur", if (c != null && !c.priv) normal.indexOf(c) else 0)
            for (i in normal.indices) {
                val t = normal[i]
                b.putString("t" + i, t.title)
                b.putString("u" + i, t.url)
                b.putBoolean("d" + i, t.desktop)
                // Tabs showing the start page have no page history worth keeping.
                if (t.url.isNotEmpty() && t.savedState == null) {
                    val st = Bundle()
                    if (t.web.saveState(st) != null) b.putBundle("s" + i, st)
                } else if (t.savedState != null) {
                    b.putBundle("s" + i, t.savedState)
                }
            }
            val p = Parcel.obtain()
            try {
                p.writeBundle(b)
                file.writeBytes(p.marshall())
            } finally {
                p.recycle()
            }
        } catch (e: Exception) {
        }
    }

    private fun restoreSavedTabs() {
        try {
            val file = File(filesDir, "tabs.bin")
            if (!file.exists()) return
            val bytes = file.readBytes()
            val p = Parcel.obtain()
            val loaded: Bundle? = try {
                p.unmarshall(bytes, 0, bytes.size)
                p.setDataPosition(0)
                p.readBundle(MainActivity::class.java.classLoader)
            } finally {
                p.recycle()
            }
            if (loaded == null) return
            val b: Bundle = loaded
            val n = b.getInt("n")
            for (i in 0 until n) {
                val web = try {
                    createWebView(false)
                } catch (e: Exception) {
                    continue
                }
                val tab = Tab(web, false)
                tab.title = b.getString("t" + i) ?: "New tab"
                tab.url = b.getString("u" + i) ?: ""
                tab.desktop = b.getBoolean("d" + i)
                val st = b.getBundle("s" + i)
                if (st != null) tab.savedState = st else tab.pendingUrl = tab.url
                setupWeb(tab)
                tabs.add(tab)
            }
            if (tabs.isNotEmpty()) switchTo(b.getInt("cur").coerceIn(0, tabs.size - 1))
        } catch (e: Exception) {
        }
    }

    // Private downloads are tracked and removed when the last private tab closes.
    private fun cleanPrivateDownloads() {
        try {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val ids = prefs.getStringSet("priv_dl", emptySet()) ?: emptySet()
            for (id in ids) {
                id.toLongOrNull()?.let { dm.remove(it) }
            }
            prefs.edit().remove("priv_dl").apply()
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            dir?.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.data?.let { newTab(it.toString()) }
    }

    // ---- background audio ----
    // While the app is open we check every few seconds whether the current page is playing audio or video.
    // If it is when you leave the app, a foreground service keeps the process alive and the page is not paused.

    private fun checkMedia() {
        val web = currentOrNull()?.web ?: return
        try {
            web.evaluateJavascript(AUDIO_JS) { r ->
                val playing = r == "true"
                mediaPlaying = playing
                if (playing && !inBackground) maybeAskNotifications()
                if (!playing && inBackground && serviceOn) {
                    stopPlaybackService()
                    try {
                        web.onPause()
                    } catch (e: Exception) {
                    }
                    handler.removeCallbacks(mediaPoll)
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun startPlaybackService() {
        serviceOn = try {
            startForegroundService(Intent(this, PlaybackService::class.java))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun stopPlaybackService() {
        try {
            stopService(Intent(this, PlaybackService::class.java))
        } catch (e: Exception) {
        }
        serviceOn = false
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT < 33 || prefs.getBoolean("asked_notif", false) ||
            has(Manifest.permission.POST_NOTIFICATIONS)
        ) return
        prefs.edit().putBoolean("asked_notif", true).apply()
        dlg()
            .setTitle("Background audio")
            .setMessage("Allow notifications so rweb can show when audio keeps playing in the background?")
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onPause() {
        inBackground = true
        handler.removeCallbacks(mediaPoll)
        if (mediaPlaying && !isFinishing) {
            startPlaybackService()
            if (serviceOn) {
                handler.postDelayed(mediaPoll, 3000)
            } else {
                try {
                    currentOrNull()?.web?.onPause()
                } catch (e: Exception) {
                }
            }
        } else {
            try {
                currentOrNull()?.web?.onPause()
            } catch (e: Exception) {
            }
        }
        saveTabs()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        inBackground = false
        stopPlaybackService()
        try {
            currentOrNull()?.web?.onResume()
        } catch (e: Exception) {
        }
        handler.removeCallbacks(mediaPoll)
        handler.post(mediaPoll)
    }

    override fun onDestroy() {
        handler.removeCallbacks(mediaPoll)
        stopPlaybackService()
        val hadPrivate = tabs.any { it.priv }
        for (t in tabs) {
            try {
                t.web.destroy()
            } catch (e: Exception) {
            }
        }
        if (hadPrivate) {
            wipePrivate()
            cleanPrivateDownloads()
        }
        super.onDestroy()
    }

    // ---- back handling (works with predictive back on Android 13+) ----

    private fun registerBackHandler() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                handleBack()
            }
        }
    }

    private fun handleBack() {
        if (customView != null) {
            exitFullscreen()
            return
        }
        val t = currentOrNull()
        if (t == null) {
            finish()
            return
        }
        if (t.web.canGoBack()) {
            t.web.goBack()
        } else if (tabs.size > 1) {
            closeTab(cur)
        } else {
            finish()
        }
    }

    // Only called on Android 12 and below. Newer versions use the callback above.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    // ---- UI ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun btn(label: String, desc: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.contentDescription = desc
        t.textSize = 20f
        t.setTextColor(INK)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun setSpacer(v: View, h: Int) {
        val lp = v.layoutParams
        if (lp.height != h) {
            lp.height = h
            v.layoutParams = lp
        }
    }

    private fun buildUi() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        statusSpacer = View(this)
        statusSpacer.setBackgroundColor(BAR)
        navSpacer = View(this)
        navSpacer.setBackgroundColor(BG)

        topBar = LinearLayout(this)
        val top = topBar
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(BAR)
        top.setPadding(dp(12), dp(8), dp(12), dp(10))

        // Address pill: [security icon] [address] [reload / stop]
        val pillBox = LinearLayout(this)
        pillBox.orientation = LinearLayout.HORIZONTAL
        pillBox.gravity = Gravity.CENTER_VERTICAL
        val pill = GradientDrawable()
        pill.setColor(BG)
        pill.cornerRadius = dp(24).toFloat()
        pillBox.background = pill

        lockIcon = icon("search", MUTE)
        val lockLp = LinearLayout.LayoutParams(dp(18), dp(18))
        lockLp.leftMargin = dp(14)
        pillBox.addView(lockIcon, lockLp)

        address = EditText(this)
        address.background = null
        address.setSingleLine()
        address.hint = "Search or enter address"
        address.setHintTextColor(MUTE)
        address.setTextColor(INK)
        address.textSize = 15f
        address.setPadding(dp(10), dp(12), dp(6), dp(12))
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
        pillBox.addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        reloadBtn = icon("reload", INK)
        reloadBtn.setPadding(dp(10), dp(10), dp(10), dp(10))
        reloadBtn.contentDescription = "Reload"
        reloadBtn.background = ripple()
        reloadBtn.setOnClickListener { reloadOrStop() }
        val reloadLp = LinearLayout.LayoutParams(dp(42), dp(42))
        reloadLp.rightMargin = dp(4)
        pillBox.addView(reloadBtn, reloadLp)

        top.addView(pillBox, LinearLayout.LayoutParams(0, dp(46), 1f))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(ROSE)
        progress.visibility = View.GONE

        content = FrameLayout(this)

        // Floating bottom bar: back, forward, home, tabs, menu
        bottomBg = GradientDrawable()
        bottomBg.setColor(BAR)
        bottomBg.cornerRadius = dp(28).toFloat()
        val island = LinearLayout(this)
        island.orientation = LinearLayout.HORIZONTAL
        island.gravity = Gravity.CENTER_VERTICAL
        island.background = bottomBg
        island.elevation = dp(8).toFloat()
        island.setPadding(dp(6), dp(4), dp(6), dp(4))

        island.addView(
            cell(icon("back", INK), 24, "Back") { currentOrNull()?.web?.let { if (it.canGoBack()) it.goBack() } },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        island.addView(
            cell(icon("forward", INK), 24, "Forward") { currentOrNull()?.web?.let { if (it.canGoForward()) it.goForward() } },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        island.addView(
            cell(icon("home", INK), 24, "Home") { currentOrNull()?.let { loadStart(it.web) } },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        tabBtn = TextView(this)
        tabBtn.text = "1"
        tabBtn.textSize = 12f
        tabBtn.setTypeface(tabBtn.typeface, Typeface.BOLD)
        tabBtn.setTextColor(INK)
        tabBtn.gravity = Gravity.CENTER
        val tabBox = GradientDrawable()
        tabBox.setColor(Color.TRANSPARENT)
        tabBox.setStroke(dp(2), INK)
        tabBox.cornerRadius = dp(7).toFloat()
        tabBtn.background = tabBox
        island.addView(cell(tabBtn, 26, "Tabs") { showTabs() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        island.addView(
            cell(icon("menu", INK), 24, "Menu") { showMenu() },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val islandWrap = FrameLayout(this)
        islandWrap.clipChildren = false
        islandWrap.clipToPadding = false
        islandWrap.setPadding(dp(14), dp(6), dp(14), dp(8))
        islandWrap.addView(island, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(statusSpacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        root.addView(top)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(islandWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(navSpacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))

        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreenHolder = FrameLayout(this)
        fullscreenHolder.setBackgroundColor(Color.BLACK)
        fullscreenHolder.visibility = View.GONE
        frame.addView(fullscreenHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Edge-to-edge. Required on Android 15+, and consistent on 11+.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            frame.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                setSpacer(statusSpacer, bars.top)
                setSpacer(navSpacer, maxOf(bars.bottom, ime.bottom))
                root.setPadding(bars.left, 0, bars.right, 0)
                WindowInsets.CONSUMED
            }
        }
        setContentView(frame)
        if (Build.VERSION.SDK_INT >= 30) {
            // Bars are dark, so use light icons.
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
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
        currentOrNull()?.web?.loadUrl(target)
    }

    private fun updateReloadButton() {
        val loading = currentOrNull()?.loading == true
        reloadBtn.setImageDrawable(IconDrawable(if (loading) "close" else "reload", INK))
        reloadBtn.contentDescription = if (loading) "Stop" else "Reload"
        updateLock()
    }

    private fun updateLock() {
        val t = currentOrNull() ?: return
        var color = MUTE
        val kind = when {
            t.url.startsWith("https://") -> "lock"
            t.url.startsWith("http://") -> {
                color = Color.parseColor("#FFB74D")
                "warning"
            }
            t.priv -> "shield"
            else -> "search"
        }
        lockIcon.setImageDrawable(IconDrawable(kind, color))
    }

    private fun reloadOrStop() {
        val t = currentOrNull() ?: return
        if (t.loading) {
            t.web.stopLoading()
            t.loading = false
            updateReloadButton()
        } else {
            t.web.reload()
        }
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

    private fun thirdPartyCookies(): Boolean = prefs.getBoolean("tpc", true)

    private fun createWebView(priv: Boolean): WebView {
        val web = WebView(this)
        if (priv) {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    throw IllegalStateException("Private profiles are not supported")
                }
                val profile = ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
                WebViewCompat.setProfile(web, PRIVATE_PROFILE)
                web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                profile.cookieManager.setAcceptCookie(true)
                profile.cookieManager.setAcceptThirdPartyCookies(web, false)
            } catch (e: Exception) {
                web.destroy()
                throw e
            }
        } else {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, thirdPartyCookies())
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
        val prev = tabs.getOrNull(cur)
        cur = i
        content.removeAllViews()
        val t = tabs[i]
        if (prev != null && prev !== t) {
            try {
                prev.web.onPause()
            } catch (e: Exception) {
            }
        }
        try {
            t.web.onResume()
        } catch (e: Exception) {
        }
        content.addView(t.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        address.setText(displayUrl(t.url))
        address.clearFocus()
        val barColor = if (t.priv) PRIV else BAR
        topBar.setBackgroundColor(barColor)
        statusSpacer.setBackgroundColor(barColor)
        bottomBg.setColor(barColor)
        address.hint = if (t.priv) "Private tab: search or enter address" else "Search or enter address"
        if (lastPrivUi != t.priv) {
            lastPrivUi = t.priv
            address.imeOptions = if (t.priv) {
                EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            } else {
                EditorInfo.IME_ACTION_GO
            }
            address.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                (if (t.priv) InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else 0)
        }
        tabBtn.text = tabs.size.toString()
        progress.visibility = View.GONE
        updateReloadButton()
        if (t.priv) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        t.savedState?.let { st ->
            t.savedState = null
            if (t.web.restoreState(st) == null) {
                if (t.url.isEmpty()) loadStart(t.web) else t.web.loadUrl(t.url)
            }
        }
        t.pendingUrl?.let {
            t.pendingUrl = null
            if (it.isEmpty()) loadStart(t.web) else t.web.loadUrl(it)
        }
    }

    private fun closeTab(i: Int) {
        if (i !in 0 until tabs.size) return
        val wasCurrent = i == cur
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
        if (t.priv && tabs.none { it.priv }) {
            wipePrivate()
            cleanPrivateDownloads()
        }
        when {
            tabs.isEmpty() -> {
                cur = -1
                newTab(null)
            }
            wasCurrent -> switchTo(minOf(i, tabs.size - 1))
            else -> {
                if (i < cur) cur--
                tabBtn.text = tabs.size.toString()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb(tab: Tab) {
        val web = tab.web
        web.setBackgroundColor(Color.WHITE)
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
        s.userAgentString = if (tab.desktop) desktopUa else mobileUa

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tab.loading = true
                if (tabs.getOrNull(cur) === tab) updateReloadButton()
                val u = url ?: return
                tab.url = displayUrl(u)
                if (tabs.getOrNull(cur) === tab && !address.hasFocus()) address.setText(tab.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tab.loading = false
                if (tabs.getOrNull(cur) === tab) updateReloadButton()
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
                if (tabs.getOrNull(cur) === tab && !address.hasFocus()) address.setText(tab.url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                val u = url ?: return
                tab.url = displayUrl(u)
                if (tabs.getOrNull(cur) === tab && !address.hasFocus()) address.setText(tab.url)
                if (!tab.priv && !isReload && tab.url.startsWith("http") && tab.url != tab.lastHistoryUrl) {
                    addHistory(tab.title, tab.url)
                    tab.lastHistoryUrl = tab.url
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val req = request ?: return false
                val u = req.url
                val scheme = u.scheme ?: return false
                when (scheme) {
                    "http", "https", "about", "data", "blob", "javascript" -> return false
                }
                if (!req.isForMainFrame || !req.hasGesture()) return true
                try {
                    val i = if (scheme == "intent") {
                        val parsed = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
                        parsed.component = null
                        parsed.selector = null
                        parsed
                    } else {
                        Intent(Intent.ACTION_VIEW, u)
                    }
                    startActivity(i)
                } catch (e: Exception) {
                    val fallback: String? = if (scheme == "intent") {
                        try {
                            Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                        } catch (x: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    if (fallback != null && isHttp(fallback)) view?.loadUrl(fallback) else toast("No app can open this link")
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
                    dlg()
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
                        if (idx < cur) cur--
                        if (wasCurrent) switchTo(minOf(idx, tabs.size - 1)) else tabBtn.text = tabs.size.toString()
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
                if (!title.isNullOrBlank() && !title.startsWith("about:")) {
                    tab.title = title
                    if (!tab.priv && tab.url.startsWith("http") && tab.url == tab.lastHistoryUrl) updateHistoryTitle(tab.url, title)
                }
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
        dlg()
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
        pendingMedia?.let { if (it !== req) it.deny() }
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
        dlg()
            .setTitle("Allow location?")
            .setMessage(Uri.parse(origin).host.toString() + " wants to know your location.")
            .setPositiveButton("Allow") { _, _ ->
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    cb.invoke(origin, true, false)
                } else {
                    pendingGeoCb?.invoke(pendingGeoOrigin ?: origin, false, false)
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
        dlg()
            .setTitle("Search engine")
            .setSingleChoiceItems(names, idx) { d, i ->
                prefs.edit().putString("search", engines[i].second).apply()
                toast(names[i])
                d.dismiss()
            }.show()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun siteIcons(): Boolean = prefs.getBoolean("icons", true)

    // Tiles show a letter first. With site icons on, the icon (from Google's favicon service) loads on top of it.
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
        val icons = siteIcons()
        val sb = StringBuilder()
        sb.append(HEAD_A.replaceFirst("<html>", if (isDark()) "<html class=\"dark\">" else "<html>"))
        sb.append(esc(searchBase().substringBefore("?q="))).append(HEAD_B)
        for (t in tiles) {
            val letter = t.first.trim().take(1).uppercase()
            sb.append("<a class=\"tile\" href=\"").append(esc(t.second)).append("\">")
            val host = Uri.parse(t.second).host ?: ""
            sb.append("<span class=\"ic\">").append(esc(letter))
            if (icons && host.isNotEmpty()) {
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
        val dlg = dlg()
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
        return HEAD_A.replaceFirst("<html>", "<html class=\"dark\">") + esc(searchBase().substringBefore("?q=")) + HEAD_B +
            "</div><div id=\"news\" style=\"text-align:center;color:#E3A9B1;font-size:14px;line-height:1.5\">" +
            "<b style=\"color:#FF6B7D\">Private tab</b><br>History, cookies and site data from private tabs are deleted when you close the last private tab.<br>" +
            "Bookmarks can't be saved from private tabs. Downloads from private tabs are deleted when you close the last private tab.</div></body></html>"
    }

    private fun isDark(): Boolean = prefs.getString("home_theme", "light") == "dark"

    private fun reloadStartIfShown() {
        val t = currentOrNull() ?: return
        if (t.url.isEmpty() && !t.priv) loadStart(t.web)
    }

    private fun toggleHomeTheme() {
        val dark = !isDark()
        prefs.edit().putString("home_theme", if (dark) "dark" else "light").apply()
        reloadStartIfShown()
        toast(if (dark) "Homepage: dark" else "Homepage: light")
    }

    private fun toggleIcons() {
        val on = !siteIcons()
        prefs.edit().putBoolean("icons", on).apply()
        reloadStartIfShown()
        toast(if (on) "Site icons on" else "Site icons off")
    }

    private fun toggleNewsFold() {
        val open = !prefs.getBoolean("news_open", true)
        prefs.edit().putBoolean("news_open", open).apply()
        val items = newsItems
        if (items != null) newsHtml = buildNews(newsName, items)
        reloadStartIfShown()
        toast(if (open) "News expanded" else "News collapsed")
    }

    private fun toggleThirdPartyCookies() {
        val on = !thirdPartyCookies()
        prefs.edit().putBoolean("tpc", on).apply()
        for (t in tabs) {
            if (!t.priv) CookieManager.getInstance().setAcceptThirdPartyCookies(t.web, on)
        }
        toast(if (on) "Third-party cookies allowed" else "Third-party cookies blocked")
    }

    // deleteProfile throws while the profile is still in use, so retry shortly after the last private WebView is destroyed.
    private fun wipePrivate(retries: Int = 3) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE)
            }
        } catch (e: Exception) {
            if (retries > 0 && !isFinishing) {
                Handler(Looper.getMainLooper()).postDelayed({ wipePrivate(retries - 1) }, 500)
            }
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
        val open = prefs.getBoolean("news_open", true)
        val sb = StringBuilder()
        sb.append("<details class=\"nd\"").append(if (open) " open" else "").append(">")
        sb.append("<summary class=\"nh\"><span>Top news \u00B7 ").append(esc(name)).append("</span></summary>")
        for (n in items) {
            sb.append("<a class=\"nw\" href=\"").append(esc(n.second)).append("\">")
            sb.append("<span class=\"nt\">").append(esc(n.first)).append("</span>")
            if (n.third.startsWith("http")) {
                sb.append("<img class=\"ni\" referrerpolicy=\"no-referrer\" src=\"").append(esc(n.third))
                sb.append("\" onerror=\"this.style.display='none'\">")
            }
            sb.append("</a>")
        }
        sb.append("</details>")
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
            val items: List<Triple<String, String, String>>? = try {
                val l = fetchNews(feed)
                if (l.isEmpty()) null else l
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                newsLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (feed != feedUrl()) {
                    refreshNews()
                } else {
                    val show: String
                    if (items != null) {
                        show = buildNews(name, items)
                        newsItems = items
                        newsName = name
                        newsHtml = show
                        newsTime = System.currentTimeMillis()
                    } else {
                        show = "<div class=\"nh\">Couldn't load news. Check your connection or pick another source in the menu.</div>"
                    }
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
        dlg()
            .setTitle("News source")
            .setSingleChoiceItems(names, idx) { d, i ->
                prefs.edit().putString("news", feeds[i].second).apply()
                newsHtml = null
                newsItems = null
                newsTime = 0L
                d.dismiss()
                val t = currentOrNull()
                if (t != null && t.url.isEmpty() && !t.priv) loadStart(t.web)
            }.show()
    }

    // ---- fullscreen video, long-press menu, downloads ----

    private fun setFullscreenMode(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController
            if (c != null) {
                if (on) {
                    c.hide(WindowInsets.Type.systemBars())
                    c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    c.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
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
        dlg()
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
            val referer = currentOrNull()?.url
            if (referer != null && isHttp(referer)) req.addRequestHeader("Referer", referer)
            val cm: CookieManager = if (priv) {
                ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE).cookieManager
            } else {
                CookieManager.getInstance()
            }
            cm.getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            req.setTitle(name)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (priv) {
                // App-scoped storage: not shown in the public Downloads folder or the gallery.
                req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
            } else {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            val id = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            if (priv) {
                val set = (prefs.getStringSet("priv_dl", emptySet()) ?: emptySet()).toMutableSet()
                set.add(id.toString())
                prefs.edit().putStringSet("priv_dl", set).apply()
            }
            toast(if (priv) "Downloading " + name + " (deleted when private tabs close)" else "Downloading " + name)
        } catch (e: Exception) {
            toast("Download failed")
        }
    }

    private fun confirm(title: String, msg: String, action: () -> Unit) {
        dlg()
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Clear") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- UI building blocks ----

    private val SHEET = Color.parseColor("#2A0710")

    private fun dlg(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun ripple(): Drawable = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)

    private fun icon(kind: String, color: Int): ImageView {
        val iv = ImageView(this)
        iv.setImageDrawable(IconDrawable(kind, color))
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        return iv
    }

    // A tappable cell for the bottom bar. The child is centred at sizeDp x sizeDp.
    private fun cell(child: View, sizeDp: Int, desc: String, onClick: () -> Unit): FrameLayout {
        val c = FrameLayout(this)
        c.contentDescription = desc
        c.background = ripple()
        c.setOnClickListener { onClick() }
        c.addView(child, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER))
        return c
    }

    private fun newSheet(heightPx: Int): Pair<Dialog, LinearLayout> {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(SHEET)
        val r = dp(24).toFloat()
        bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        box.background = bg
        box.setPadding(dp(16), dp(10), dp(16), dp(20))
        d.setContentView(box)
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
            w.setGravity(Gravity.BOTTOM)
        }
        return Pair(d, box)
    }

    private fun sheetHandle(box: LinearLayout) {
        val h = View(this)
        val bg = GradientDrawable()
        bg.setColor(0x4DFFFFFF)
        bg.cornerRadius = dp(2).toFloat()
        h.background = bg
        val lp = LinearLayout.LayoutParams(dp(40), dp(4))
        lp.gravity = Gravity.CENTER_HORIZONTAL
        lp.bottomMargin = dp(14)
        box.addView(h, lp)
    }

    private fun quickAction(kind: String, label: String, onClick: () -> Unit): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setOnClickListener { onClick() }
        val circle = FrameLayout(this)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(BAR)
        circle.background = bg
        circle.addView(icon(kind, INK), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        col.addView(circle, LinearLayout.LayoutParams(dp(54), dp(54)))
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 12f
        tv.setTextColor(MUTE)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(6), 0, 0)
        col.addView(tv)
        return col
    }

    private fun sheetRow(label: String, value: String?, danger: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(48)
        row.setPadding(dp(4), 0, dp(4), 0)
        row.background = ripple()
        row.setOnClickListener { onClick() }
        val l = TextView(this)
        l.text = label
        l.textSize = 15f
        l.setTextColor(if (danger) ROSE else INK)
        row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            val v = TextView(this)
            v.text = value
            v.textSize = 13f
            v.setTextColor(MUTE)
            row.addView(v)
        }
        return row
    }

    private fun pillButton(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.textSize = 14f
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.gravity = Gravity.CENTER
        b.setTextColor(if (primary) BG else INK)
        val bg = GradientDrawable()
        bg.setColor(if (primary) ROSE else BAR)
        bg.cornerRadius = dp(23).toFloat()
        b.background = bg
        b.setOnClickListener { onClick() }
        return b
    }

    private fun tabRow(t: Tab, isCur: Boolean, onSelect: () -> Unit, onClose: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val bg = GradientDrawable()
        bg.setColor(if (t.priv) PRIV else BAR)
        bg.cornerRadius = dp(16).toFloat()
        if (isCur) bg.setStroke(dp(2), ROSE)
        row.background = bg
        row.setPadding(dp(12), dp(10), dp(4), dp(10))
        row.setOnClickListener { onSelect() }

        val badge = TextView(this)
        badge.gravity = Gravity.CENTER
        badge.textSize = 15f
        badge.setTextColor(INK)
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(BG)
        badge.background = circle
        badge.text = if (t.priv) "P" else t.title.trim().take(1).uppercase().ifEmpty { "N" }
        row.addView(badge, LinearLayout.LayoutParams(dp(36), dp(36)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        val name = TextView(this)
        name.text = if (t.title.isBlank()) "New tab" else t.title
        name.textSize = 14f
        name.setTextColor(INK)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        val sub = TextView(this)
        sub.text = (if (t.priv) "Private \u00B7 " else "") + (Uri.parse(t.url).host ?: "Start page")
        sub.textSize = 12f
        sub.setTextColor(MUTE)
        sub.maxLines = 1
        sub.ellipsize = TextUtils.TruncateAt.END
        col.addView(name)
        col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val x = icon("close", INK)
        x.setPadding(dp(12), dp(12), dp(12), dp(12))
        x.contentDescription = "Close tab"
        x.background = ripple()
        x.setOnClickListener { onClose() }
        row.addView(x, LinearLayout.LayoutParams(dp(44), dp(44)))
        return row
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

    private fun updateHistoryTitle(url: String, title: String) {
        val l = loadList("history")
        val idx = l.indexOfFirst { it.second == url }
        if (idx >= 0 && l[idx].first != title) {
            l[idx] = Pair(title, url)
            saveList("history", l)
        }
    }

    private fun addBookmark() {
        val t = current()
        if (t.priv) {
            toast("Can't bookmark in a private tab")
            return
        }
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
        val b = dlg().setTitle(title)
            .setItems(labels) { _, i -> pickItem(key, l[i]) }
        if (key == "history") {
            b.setNeutralButton("Clear all") { _, _ ->
                confirm("Clear all history?", "This can't be undone.") {
                    saveList(key, emptyList())
                    toast("History cleared")
                }
            }
        }
        b.setNegativeButton("Close", null).show()
    }

    private fun pickItem(key: String, item: Pair<String, String>) {
        dlg()
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
        val screenH = resources.displayMetrics.heightPixels
        val (d, box) = newSheet((screenH * 0.7).toInt())
        sheetHandle(box)
        val title = TextView(this)
        title.textSize = 16f
        title.setTextColor(INK)
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setPadding(dp(4), dp(4), dp(4), dp(10))
        box.addView(title)

        val scroll = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun render() {
            list.removeAllViews()
            title.text = "Tabs (" + tabs.size + ")"
            for (i in tabs.indices) {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                list.addView(
                    tabRow(tabs[i], i == cur, {
                        d.dismiss()
                        switchTo(i)
                    }, {
                        closeTab(i)
                        render()
                    }),
                    lp
                )
            }
        }
        render()

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        val leftLp = LinearLayout.LayoutParams(0, dp(46), 1f)
        leftLp.rightMargin = dp(8)
        actions.addView(pillButton("New tab", true) {
            d.dismiss()
            newTab(null)
        }, leftLp)
        actions.addView(pillButton("New private tab", false) {
            d.dismiss()
            newTab(null, true)
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        val actionsLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        actionsLp.topMargin = dp(8)
        box.addView(actions, actionsLp)
        d.show()
    }

    private class MenuRow(val label: String, val value: String?, val danger: Boolean, val action: () -> Unit)

    private fun showMenu() {
        val t = current()
        val rows = listOf(
            MenuRow("Bookmarks", null, false) { showList("Bookmarks", "bookmarks") },
            MenuRow("History", null, false) { showList("History", "history") },
            MenuRow("Downloads", null, false) { openDownloads() },
            MenuRow("Find in page", null, false) { findInPage() },
            MenuRow("Search engine", engines.first { it.second == searchBase() }.first, false) { chooseEngine() },
            MenuRow("News source", feedName(), false) { chooseNews() },
            MenuRow("Homepage news", if (prefs.getBoolean("news_open", true)) "Expanded" else "Collapsed", false) { toggleNewsFold() },
            MenuRow("Homepage theme", if (isDark()) "Dark" else "Light", false) { toggleHomeTheme() },
            MenuRow("Homepage site icons", if (siteIcons()) "On" else "Off", false) { toggleIcons() },
            MenuRow("Third-party cookies", if (thirdPartyCookies()) "Allowed" else "Blocked", false) { toggleThirdPartyCookies() },
            MenuRow("Desktop site", if (t.desktop) "On" else "Off", false) { toggleDesktop() },
            MenuRow("Copy link", null, false) { copyLink() },
            MenuRow("Clear browsing data", null, true) {
                confirm(
                    "Clear browsing data?",
                    "This deletes history, cookies, cache and saved site data, and signs you out of websites."
                ) { clearData() }
            }
        )
        val screenH = resources.displayMetrics.heightPixels
        val h = minOf(dp(170 + rows.size * 49), (screenH * 0.85).toInt())
        val (d, box) = newSheet(h)
        sheetHandle(box)

        val quick = LinearLayout(this)
        quick.orientation = LinearLayout.HORIZONTAL
        quick.setPadding(0, dp(4), 0, dp(12))
        val qLp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        quick.addView(quickAction("plus", "New tab") {
            d.dismiss()
            newTab(null)
        }, qLp())
        quick.addView(quickAction("shield", "Private") {
            d.dismiss()
            newTab(null, true)
        }, qLp())
        quick.addView(quickAction("star", "Bookmark") {
            d.dismiss()
            addBookmark()
        }, qLp())
        quick.addView(quickAction("share", "Share") {
            d.dismiss()
            sharePage()
        }, qLp())
        box.addView(quick)

        val scroll = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        for (r in rows) {
            val divider = View(this)
            divider.setBackgroundColor(0x1AFFFFFF)
            list.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            list.addView(sheetRow(r.label, r.value, r.danger) {
                d.dismiss()
                r.action()
            })
        }
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        d.show()
    }

    private fun toggleDesktop() {
        val t = current()
        t.desktop = !t.desktop
        t.web.settings.userAgentString = if (t.desktop) desktopUa else mobileUa
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
            if (!t.priv) t.web.clearHistory()
        }
        File(filesDir, "tabs.bin").delete()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        GeolocationPermissions.getInstance().clearAll()
        saveList("history", emptyList())
        toast("Browsing data cleared")
    }
}
