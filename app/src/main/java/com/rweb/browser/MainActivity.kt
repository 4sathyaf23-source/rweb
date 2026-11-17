package com.rweb.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.speech.RecognizerIntent
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProfileStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {

    private class Tab(val web: WebView, val priv: Boolean = false) {
        var title = "New tab"
        @Volatile var url = ""
        var desktop = false
        var loading = false
        var lastHistoryUrl = ""
        var pendingUrl: String? = null
        val blocked = AtomicInteger(0)
        var readerUrl: String? = null
        var httpFallback: String? = null
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
    private lateinit var suggestBox: LinearLayout
    private lateinit var islandWrap: FrameLayout
    private lateinit var floatMenu: ImageView
    private var barsHidden = false
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
    @Volatile private var adBlockOn = true
    @Volatile private var pausedHosts: Set<String> = emptySet()
    private var pendingMedia: PermissionRequest? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var newsHtml: String? = null
    private var newsTime = 0L
    private var newsLoading = false
    private var newsItems: List<Triple<String, String, String>>? = null
    private var newsName = ""
    private var vidHtml: String? = null
    private var vidTime = 0L
    private var vidLoading = false
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCb: GeolocationPermissions.Callback? = null

    // VPN Manager Integration
    private lateinit var vpnManager: BrowserVpnManager

    private val MAX_SHOWN = 40
    private val POOL_SIZE = 150

    private val linkTitles = ConcurrentHashMap<String, String>()

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshIntervalMs = 15 * 60 * 1000L

    private val feedRefreshRunnable = object : Runnable {
        override fun run() {
            refreshVideos()
            refreshNews()
            refreshHandler.postDelayed(this, refreshIntervalMs)
        }
    }

    private val io = Executors.newSingleThreadExecutor()

    private fun runIo(task: () -> Unit) {
        try {
            io.execute {
                try {
                    task()
                } catch (e: Exception) {
                }
            }
        } catch (e: RejectedExecutionException) {
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val results = WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
        fileCallback?.onReceiveValue(results)
        fileCallback = null
    }

    private val voicePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            val spoken = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) searchFor(spoken)
        }
    }

    private val cameraShot = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val photo = cameraUri
        if (ok && photo != null) {
            fileCallback?.onReceiveValue(arrayOf(photo))
        } else {
            fileCallback?.onReceiveValue(null)
            if (photo != null) {
                try {
                    contentResolver.delete(photo, null, null)
                } catch (e: Exception) {
                }
            }
        }
        fileCallback = null
        cameraUri = null
    }

    private val cameraPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            cancelFileChooser()
            toast("Camera permission is needed to take a photo")
        }
    }

    private val mediaPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingMedia?.let { finishMedia(it) }
        pendingMedia = null
    }

    private val geoPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val cb = pendingGeoCb
        val o = pendingGeoOrigin
        if (cb != null && o != null) {
            cb.invoke(o, has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION), false)
        }
        pendingGeoCb = null
        pendingGeoOrigin = null
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

    private val topNewsFeeds = listOf(
        Pair("BBC News", "https://feeds.bbci.co.uk/news/rss.xml"),
        Pair("CNN Top Stories", "https://rss.cnn.com/rss/edition.rss"),
        Pair("New York Times", "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml"),
        Pair("The Guardian", "https://www.theguardian.com/world/rss"),
        Pair("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
        Pair("Times of India", "https://timesofindia.indiatimes.com/rssfeedstopstories.cms"),
        Pair("The Hindu", "https://www.thehindu.com/news/national/feeder/default.rss")
    )

    private val topVideoFeeds = listOf(
        Pair("BBC News", "https://www.youtube.com/feeds/videos.xml?channel_id=UC16niRr50-MSBwiO3YDb3RA"),
        Pair("CNN", "https://www.youtube.com/feeds/videos.xml?channel_id=UCupvZG-5ko_eiXAupbDfxWw"),
        Pair("Al Jazeera English", "https://www.youtube.com/feeds/videos.xml?channel_id=UCNye-wNBqNL5ZzHSJj3l8Bg")
    )

    private val baseEngines = listOf(
        Pair("Google", "https://www.google.com/search?q="),
        Pair("DuckDuckGo", "https://duckduckgo.com/?q="),
        Pair("Bing", "https://www.bing.com/search?q=")
    )

    companion object {
        const val PRIVATE_PROFILE = "rwebprivate"
        const val DESKTOP_UA_FALLBACK = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val VIDEO_SHAPE_JS = "(function(){var a=document.querySelectorAll('video');var p=null;for(var i=0;i<a.length;i++){if(!a[i].paused&&a[i].videoWidth){p=a[i];break}}if(!p&&a.length)p=a[0];return p&&p.videoWidth?p.videoWidth+'x'+p.videoHeight:''})()"
        const val AUDIO_JS = "(function(){var m=document.querySelectorAll('audio,video');for(var i=0;i<m.length;i++){var e=m[i];if(!e.paused&&!e.ended&&e.readyState>2&&!e.muted&&e.volume>0)return true}return false})()"

        const val HEAD_A = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>:root{--bg:#FFFFFF;--fg:#202124;--mute:#5F6368;--card:#F8F9FA;--fld:#F1F3F4;--acc:#C41E3A;--sh:0 4px 14px rgba(0,0,0,.08)}html.dark{--bg:#3A0A14;--fg:#FFF1F3;--mute:#E3A9B1;--card:#7A1022;--fld:#7A1022;--acc:#FF6B7D;--sh:0 4px 14px rgba(0,0,0,.35)}body{margin:0;padding:28px 16px;box-sizing:border-box;background:var(--bg);color:var(--fg);font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh}.logo-container{display:flex;align-items:center;justify-content:center;width:72px;height:72px;margin-bottom:16px;border-radius:50%;background:var(--fld);box-shadow:var(--sh)}.logo-container svg{width:40px;height:40px}h1{color:var(--acc);font-size:38px;margin:0 0 24px}form{width:100%;max-width:520px}input{width:100%;box-sizing:border-box;padding:16px 22px;border-radius:28px;border:0;background:var(--fld);color:var(--fg);font-size:16px;box-shadow:var(--sh);outline:none}.grid{display:flex;flex-wrap:wrap;justify-content:center;gap:20px 16px;margin-top:32px;width:100%;max-width:520px}.tile{width:72px;text-decoration:none;color:var(--fg);text-align:center;font-size:12px}.ic{position:relative;overflow:hidden;display:flex;align-items:center;justify-content:center;width:56px;height:56px;margin:0 auto 8px;border-radius:50%;background:var(--fld);color:var(--acc);font-size:22px;font-weight:700;box-shadow:var(--sh)}.ic img{position:absolute;top:0;left:0;width:100%;height:100%;box-sizing:border-box;padding:13px;background:#FFFFFF;object-fit:contain}#news{width:100%;max-width:520px;margin-top:24px}.nh{font-size:12px;color:var(--mute);text-transform:uppercase;letter-spacing:.08em;margin-bottom:8px}summary.nh{list-style:none;cursor:pointer;display:flex;justify-content:space-between;align-items:center;padding:4px 4px 12px;margin:0}summary.nh::-webkit-details-marker{display:none}summary.nh::after{content:"\25BE";font-size:14px}details[open] summary.nh::after{content:"\25B4"}.nw{display:flex;align-items:center;gap:14px;padding:14px 16px;margin-bottom:12px;background:var(--card);border-radius:18px;box-shadow:var(--sh);color:var(--fg);text-decoration:none;font-size:14px;line-height:1.4}.nt{flex:1}.ni{width:88px;height:64px;object-fit:cover;border-radius:12px;flex-shrink:0}#vids{width:100%;max-width:520px;margin-top:32px}.nw.hero{flex-direction:column;align-items:stretch;padding:0;overflow:hidden}.nw.hero .hi{width:100%;height:190px;object-fit:cover;display:block}.nw.hero .nt{padding:14px 16px;font-size:16px;font-weight:600}.vrow{display:flex;gap:12px;overflow-x:auto;padding-bottom:10px}.vc{flex:0 0 230px;text-decoration:none;color:var(--fg)}.vt{position:relative;width:230px;height:130px;border-radius:14px;overflow:hidden;background:var(--card);box-shadow:var(--sh)}.vt img{width:100%;height:100%;object-fit:cover;display:block}.vp{position:absolute;left:50%;top:50%;width:44px;height:44px;margin:-22px 0 0 -22px;border-radius:50%;background:rgba(0,0,0,.55);color:#fff;text-align:center;line-height:44px;font-size:18px}.vn{font-size:13px;line-height:1.3;margin-top:8px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.chips{display:flex;flex-wrap:wrap;gap:8px;justify-content:center;margin-top:14px}.chip{padding:7px 14px;border-radius:16px;background:var(--fld);color:var(--fg);font-size:13px;box-shadow:var(--sh)}.chip.on{background:var(--acc);color:#fff}</style></head><body><div class="logo-container"><svg viewBox="0 0 24 24" fill="none" stroke="var(--acc)" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"/></svg></div><h1>rweb</h1><form action=""""

        const val HEAD_B = """" method="get" onsubmit="return go()"><input id="q" name="q" placeholder="Search the web" autocomplete="off"></form><div class="chips"><span class="chip on" id="c-web" onclick="pick('web')">Web</span><span class="chip" id="c-img" onclick="pick('img')">Images</span><span class="chip" id="c-vid" onclick="pick('vid')">Videos</span><span class="chip" onclick="location.href='rweb://voice'">Voice</span><span class="chip" onclick="location.href='rweb://photo'">Photo</span></div><div class="grid">"""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rweb", MODE_PRIVATE)
        loadAdBlockSettings()
        
        // Initialize VPN Manager
        vpnManager = BrowserVpnManager(this)

        mobileUa = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        desktopUa = buildDesktopUa()
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = BG
            window.navigationBarColor = BAR
        }
        wipePrivate()
        cleanPrivateDownloads()

        vidHtml = loadFeedCache("vids_cache.json")
        newsHtml = loadFeedCache("news_cache.json")

        buildUi()
        registerBackHandler()
        restoreSavedTabs()

        val link = intent?.data?.toString()
        if (link != null) newTab(link) else if (tabs.isEmpty()) newTab(null)
        if (intent?.getBooleanExtra("app_mode", false) == true) setBarsHidden(true)
    }

    // --- VPN Manager Class Implementation ---
    class BrowserVpnManager(private val context: android.content.Context) {
        var isVpnConnected: Boolean = false
            private set
        var currentProxyEndpoint: String? = null
            private set

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

        suspend fun enableFreeVpn(onSuccess: (proxyIpPort: String) -> Unit, onError: (reason: String) -> Unit) {
            if (!isSupported()) {
                onError("WebView Proxy Override is not supported on this device.")
                return
            }
            val proxyAddress = fetchWorkingFreeProxy()
            if (proxyAddress == null) {
                onError("Could not find an active free VPN node. Try again.")
                return
            }
            withContext(Dispatchers.Main) {
                try {
                    val proxyConfig = ProxyConfig.Builder()
                        .addProxyRule(proxyAddress)
                        .addBypassRule("localhost")
                        .addBypassRule("127.0.0.1")
                        .build()

                    val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
                    ProxyController.getInstance().setProxyOverride(proxyConfig, mainExecutor) {
                        isVpnConnected = true
                        currentProxyEndpoint = proxyAddress
                        onSuccess(proxyAddress)
                    }
                } catch (e: Exception) {
                    isVpnConnected = false
                    onError(e.message ?: "Failed to apply VPN proxy.")
                }
            }
        }

        fun disableFreeVpn(onDisabled: () -> Unit) {
            if (!isSupported()) {
                onDisabled()
                return
            }
            val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
            ProxyController.getInstance().clearProxyOverride(mainExecutor) {
                isVpnConnected = false
                currentProxyEndpoint = null
                onDisabled()
            }
        }

        private suspend fun fetchWorkingFreeProxy(): String? = withContext(Dispatchers.IO) {
            try {
                val apiUrl = "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=3000&country=all&ssl=yes&anonymity=elite"
                val request = Request.Builder().url(apiUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val proxyList = body.lines()
                        .map { it.trim() }
                        .filter { it.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+$")) }
                    return@withContext proxyList.firstOrNull()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext null
            }
        }
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

    private fun saveTabs() {
        val normal = tabs.filter { !it.priv }
        val file = File(filesDir, "tabs.json")
        if (normal.isEmpty()) {
            runIo { file.delete() }
            return
        }
        val snapshot = try {
            val arr = JSONArray()
            for (t in normal) {
                arr.put(JSONObject().put("t", t.title).put("u", t.url).put("d", t.desktop))
            }
            val c = currentOrNull()
            val curIdx = if (c != null && !c.priv) normal.indexOf(c) else 0
            JSONObject().put("cur", curIdx.coerceAtLeast(0)).put("tabs", arr).toString()
        } catch (e: Exception) {
            return
        }
        runIo {
            val tmp = File(filesDir, "tabs.json.tmp")
            tmp.writeText(snapshot)
            if (!tmp.renameTo(file)) {
                file.writeText(snapshot)
                tmp.delete()
            }
        }
    }

    private fun restoreSavedTabs() {
        File(filesDir, "tabs.bin").delete()
        try {
            val file = File(filesDir, "tabs.json")
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("tabs")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val web = try {
                    createWebView(false)
                } catch (e: Exception) {
                    continue
                }
                val tab = Tab(web, false)
                tab.title = o.optString("t", "New tab")
                tab.url = o.optString("u", "")
                tab.desktop = o.optBoolean("d", false)
                tab.pendingUrl = tab.url
                setupWeb(tab)
                tabs.add(tab)
            }
            if (tabs.isNotEmpty()) switchTo(root.optInt("cur", 0).coerceIn(0, tabs.size - 1))
        } catch (e: Exception) {
        }
    }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { newTab(it.toString()) }
        if (intent.getBooleanExtra("app_mode", false) == true) setBarsHidden(true)
    }

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
                        web.pauseTimers()
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
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onPause() {
        inBackground = true
        handler.removeCallbacks(mediaPoll)
        refreshHandler.removeCallbacks(feedRefreshRunnable)
        if (mediaPlaying && !isFinishing) {
            startPlaybackService()
            if (serviceOn) {
                handler.postDelayed(mediaPoll, 3000)
            } else {
                try {
                    currentOrNull()?.web?.let {
                        it.onPause()
                        it.pauseTimers()
                    }
                } catch (e: Exception) {
                }
            }
        } else {
            try {
                currentOrNull()?.web?.let {
                    it.onPause()
                    it.pauseTimers()
                }
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
            currentOrNull()?.web?.let {
                it.onResume()
                it.resumeTimers()
            }
        } catch (e: Exception) {
        }
        handler.removeCallbacks(mediaPoll)
        handler.post(mediaPoll)
        refreshHandler.removeCallbacks(feedRefreshRunnable)
        refreshHandler.postDelayed(feedRefreshRunnable, refreshIntervalMs)
    }

    override fun onDestroy() {
        handler.removeCallbacks(mediaPoll)
        refreshHandler.removeCallbacks(feedRefreshRunnable)
        stopPlaybackService()
        val hadPrivate = tabs.any { it.priv }
        for (t in tabs) {
            try {
                (t.web.parent as? ViewGroup)?.removeView(t.web)
                t.web.destroy()
            } catch (e: Exception) {
            }
        }
        if (hadPrivate) {
            wipePrivate()
            cleanPrivateDownloads()
        }
        io.shutdown()
        super.onDestroy()
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this) { handleBack() }
    }

    private fun handleBack() {
        if (customView != null) {
            exitFullscreen()
            return
        }
        if (suggestBox.visibility == View.VISIBLE) {
            hideKeyboard()
            hideSuggestions()
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
        address.hint = "Enter address or search"
        address.setHintTextColor(MUTE)
        address.setTextColor(INK)
        address.textSize = 15f
        address.setPadding(dp(10), dp(12), dp(6), dp(12))
        address.imeOptions = EditorInfo.IME_ACTION_GO
        address.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        address.setSelectAllOnFocus(true)
        address.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (address.hasFocus()) refreshSuggestions(s?.toString() ?: "")
            }
        })
        address.setOnFocusChangeListener { _, focused ->
            if (focused) refreshSuggestions(address.text.toString()) else hideSuggestions()
        }
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

        islandWrap = FrameLayout(this)
        islandWrap.clipChildren = false
        islandWrap.clipToPadding = false
        islandWrap.setPadding(dp(14), dp(6), dp(14), dp(8))
        islandWrap.addView(island, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(statusSpacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        root.addView(top)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        suggestBox = LinearLayout(this)
        suggestBox.orientation = LinearLayout.VERTICAL
        val sbBg = GradientDrawable()
        sbBg.setColor(SHEET)
        val sr = dp(18).toFloat()
        sbBg.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, sr, sr, sr, sr)
        suggestBox.background = sbBg
        suggestBox.elevation = dp(12).toFloat()
        suggestBox.setPadding(dp(8), dp(4), dp(8), dp(8))
        suggestBox.visibility = View.GONE
        val contentWrap = FrameLayout(this)
        contentWrap.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentWrap.addView(suggestBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(contentWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(islandWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(navSpacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))

        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        floatMenu = icon("menu", INK)
        floatMenu.setPadding(dp(10), dp(10), dp(10), dp(10))
        floatMenu.contentDescription = "Menu"
        val fb = GradientDrawable()
        fb.shape = GradientDrawable.OVAL
        fb.setColor(0xCC7A1022.toInt())
        floatMenu.background = fb
        floatMenu.elevation = dp(6).toFloat()
        floatMenu.visibility = View.GONE
        floatMenu.setOnClickListener { showMenu() }
        val fLp = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END)
        fLp.rightMargin = dp(16)
        fLp.bottomMargin = dp(28)
        frame.addView(floatMenu, fLp)
        fullscreenHolder = FrameLayout(this)
        fullscreenHolder.setBackgroundColor(Color.BLACK)
        fullscreenHolder.visibility = View.GONE
        frame.addView(fullscreenHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
    }

    private fun current(): Tab = tabs[cur]

    private fun displayUrl(url: String): String =
        if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("https://rweb.local")) "" else url

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
    }

    private fun upgradeHttps(u: String): String {
        val uri = Uri.parse(u)
        val host = uri.host ?: return u
        if (uri.scheme != "http") return u
        if (uri.port != -1) return u
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost") ||
            Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host) || host.contains(':')
        ) return u
        return uri.buildUpon().scheme("https").build().toString()
    }

    private fun navigate(tab: Tab, target: String) {
        val up = upgradeHttps(target)
        tab.httpFallback = if (up != target) target else null
        tab.web.loadUrl(up)
    }

    private fun go(text: String) {
        val target = UrlUtil.toNavigationTarget(text, searchBase(), defaultTld())
        if (target.isEmpty()) return
        val t = currentOrNull() ?: return
        navigate(t, target)
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
        refreshVideos()
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
                ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
                WebViewCompat.setProfile(web, PRIVATE_PROFILE)
                web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
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
            toast(if (priv) "Private tabs need a newer Android System WebView." else "Couldn't open a new tab")
            return
        }
        val tab = Tab(web, priv)
        setupWeb(tab)
        tabs.add(tab)
        switchTo(tabs.size - 1)
        if (url.isNullOrEmpty()) loadStart(tab.web) else navigate(tab, url)
    }

    private fun switchTo(i: Int) {
        val prev = tabs.getOrNull(cur)
        cur = i
        hideSuggestions()
        content.removeAllViews()
        val t = tabs[i]
        if (prev != null && prev !== t) {
            try { prev.web.onPause() } catch (e: Exception) {}
        }
        try { t.web.onResume() } catch (e: Exception) {}
        content.addView(t.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        address.setText(displayUrl(t.url))
        address.clearFocus()
        val barColor = if (t.priv) PRIV else BAR
        topBar.setBackgroundColor(barColor)
        statusSpacer.setBackgroundColor(barColor)
        bottomBg.setColor(barColor)
        address.hint = if (t.priv) "Private tab: enter address" else "Enter address or search"
        tabBtn.text = tabs.size.toString()
        progress.visibility = View.GONE
        updateReloadButton()
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
        if (i !in 0 until tabs.size) return
        val wasCurrent = i == cur
        val t = tabs.removeAt(i)
        content.removeView(t.web)
        try {
            t.web.stopLoading()
            t.web.loadUrl("about:blank")
            t.web.clearHistory()
            t.web.destroy()
        } catch (e: Exception) {}
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

    private fun saveFeedCache(filename: String, content: String) {
        runIo { File(filesDir, filename).writeText(content) }
    }

    private fun loadFeedCache(filename: String): String? {
        try {
            val file = File(filesDir, filename)
            if (file.exists()) return file.readText()
        } catch (e: Exception) {}
        return null
    }

    private val statsLock = Any()
    private var readList: ArrayList<String>? = null
    private var clickMap: LinkedHashMap<String, Int>? = null
    private var interests: HashMap<String, Int>? = null
    private val wordRe = Regex("[a-z0-9]+")
    private val stopWords = setOf("news", "video", "watch", "with", "from", "this", "that", "your", "what")

    private fun words(text: String): List<String> = wordRe.findAll(text.lowercase()).map { it.value }.toList()

    private fun ensureStats() {
        if (readList != null && clickMap != null && interests != null) return
        readList = ArrayList()
        clickMap = LinkedHashMap()
        interests = HashMap()
    }

    private fun getReadArticles(): Set<String> = synchronized(statsLock) {
        ensureStats()
        HashSet(readList!!)
    }

    private fun getClickCount(url: String): Int = synchronized(statsLock) {
        ensureStats()
        clickMap!![url] ?: 0
    }

    private fun getRelevanceScore(title: String): Int = synchronized(statsLock) {
        ensureStats()
        val w = interests!!
        var score = 0
        for (word in words(title)) score += w[word] ?: 0
        score
    }

    private fun recordClick(url: String, title: String) {
        synchronized(statsLock) {
            ensureStats()
            val r = readList!!
            if (!r.contains(url)) r.add(url)
        }
    }

    private fun rememberTitle(url: String, title: String) {
        if (linkTitles.size > 600) linkTitles.clear()
        linkTitles[url] = title
    }

    private fun getCustomFeeds(key: String): MutableList<Pair<String, String>> = mutableListOf()
    private fun saveCustomFeeds(key: String, list: List<Pair<String, String>>) {}

    private fun getCategory(title: String): String = "General"
    private fun getCategoryChipsHtml(activeCategory: String): String = ""

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
        applyClientIdentity(s, tab.desktop, tab.desktop)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tab.loading = true
                tab.blocked.set(0)
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
                } else {
                    tab.title = "New tab"
                    if (!tab.priv) vidHtml?.let { injectVideos(tab.web, it) }
                    if (!tab.priv) newsHtml?.let { injectNews(tab.web, it) }
                }
                if (tabs.getOrNull(cur) === tab && !address.hasFocus()) address.setText(tab.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val req = request ?: return false
                val u = req.url
                val urlStr = u.toString()
                if (!tab.priv && tab.url.isEmpty() && req.isForMainFrame && req.hasGesture()) {
                    linkTitles[urlStr]?.let { recordClick(urlStr, it) }
                }
                return false
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tabs.getOrNull(cur) !== tab) return
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
    }

    private fun has(p: String): Boolean = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun searchBase(): String = baseEngines[0].second

    private fun startHtml(): String {
        return HEAD_A.replaceFirst("<html>", if (isDark()) "<html class=\"dark\">" else "<html>") +
                esc(searchBase().substringBefore("?q=")) + HEAD_B + "</div><div id=\"vids\"></div><div id=\"news\"></div></body></html>"
    }

    private fun openDownloads() {
        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (e: Exception) {}
    }

    private fun copyLink() {
        val u = current().url
        if (u.isEmpty()) return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", u))
        toast("Link copied")
    }

    private fun findInPage() {}
    private fun cancelFileChooser() {}
    private fun isDark(): Boolean = prefs.getString("home_theme", "light") == "dark"
    private fun reloadStartIfShown() {}
    private fun toggleHomeTheme() {}
    private fun toggleIcons() {}
    private fun toggleNewsFold() {}
    private fun toggleThirdPartyCookies() {}

    private fun wipePrivate(retries: Int = 3) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE)
            }
        } catch (e: Exception) {}
    }

    private fun secureImage(u: String): String = u
    private fun mergeRoundRobin(results: Map<Int, List<Triple<String, String, String>>>, cap: Int): List<Triple<String, String, String>> = emptyList()
    private fun buildVideos(items: List<Triple<String, String, String>>): String = ""
    private fun injectVideos(web: WebView, html: String) {}
    private fun fetchVideosForChannel(feedUrl: String, limit: Int = 3): List<Triple<String, String, String>> = emptyList()
    private fun refreshVideos() {}
    private fun fetchNews(feed: String, limit: Int = 6): List<Triple<String, String, String>> = emptyList()
    private fun buildNews(name: String, items: List<Triple<String, String, String>>): String = ""
    private fun injectNews(web: WebView, html: String) {}
    private fun publishNews(items: List<Triple<String, String, String>>) {}
    private fun refreshNews() {}
    private fun setFullscreenMode(on: Boolean) {}
    private fun exitFullscreen() {}
    private fun isHttp(u: String): Boolean = u.startsWith("http://") || u.startsWith("https://")
    private fun copyText(t: String) {}
    private fun shareText(t: String) {}
    private fun linkMenu(tab: Tab, link: String?, image: String?) {}
    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, priv: Boolean) {}
    private fun confirm(title: String, msg: String, action: () -> Unit) {}
    private fun privateIdSet(): Set<String> = emptySet()
    private fun showDownloads() {}
    private fun downloadRow(item: Any, onChanged: () -> Unit): View = View(this)
    private fun firstImageIn(html: String): String = ""
    private fun metaContent(html: String, key: String): String? = null
    private fun fetchMeta(articleUrl: String): Pair<String, Boolean> = Pair("", false)
    private fun enrichNews(items: List<Triple<String, String, String>>, name: String) {}
    private fun toggleReader() {}
    private fun saveOfflineArticle(url: String, title: String, contentHtml: String) {}
    private fun getOfflineArticle(url: String): String? = null

    private fun mobileMetadata(): UserAgentMetadata = UserAgentMetadata.Builder().build()
    private fun desktopMetadata(): UserAgentMetadata = UserAgentMetadata.Builder().build()

    private fun applyClientIdentity(s: WebSettings, desktop: Boolean, touchMetadata: Boolean) {
        s.userAgentString = if (desktop) desktopUa else mobileUa
    }

    private fun defaultTld(): String = ".com"
    private fun chooseTld() {}
    private fun searchBaseName(): String = "Google"
    private fun typedSearchBases(): Triple<String, String, String> = Triple("", "", "")
    private fun searchScript(): String = ""
    private fun hideSuggestions() {}
    private fun openAddress(target: String) {}
    private fun searchFor(q: String) {}
    private fun launchVoice() {}
    private fun openPhotoSearch() {}
    private fun suggestRow(title: String, sub: String?, onClick: () -> Unit): View = View(this)
    private fun searchTypeRow(q: String): View = View(this)
    private fun refreshSuggestions(text: String) {}
    private fun openVrViewer() {}
    private fun setBarsHidden(hidden: Boolean) {}
    private fun letterIcon(letter: String): Icon = Icon.createWithBitmap(Bitmap.createBitmap(1,1, Bitmap.Config.ARGB_8888))
    private fun addToHomeScreen() {}
    private fun loadAdBlockSettings() {}
    private fun hostOf(url: String): String? = null
    private fun reloadCurrent() {}
    private fun toggleAdBlock() {}
    private fun togglePauseHere() {}
    private fun showCustomFeedManager(isVideos: Boolean) {}
    private fun showAddFeedDialog(isVideos: Boolean) {}

    private val SHEET = Color.parseColor("#2A0710")
    private fun dlg(): AlertDialog.Builder = AlertDialog.Builder(this)
    private fun ripple(): Drawable = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
    private fun icon(kind: String, color: Int): ImageView = ImageView(this)
    private fun cell(child: View, sizeDp: Int, desc: String, onClick: () -> Unit): FrameLayout = FrameLayout(this)
    private fun newSheet(heightPx: Int): Pair<Dialog, LinearLayout> = Pair(Dialog(this), LinearLayout(this))
    private fun sheetHandle(box: LinearLayout) {}
    private fun quickAction(kind: String, label: String, onClick: () -> Unit): View = View(this)
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

    private fun pillButton(label: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(this)
    private fun tabRow(t: Tab, isCur: Boolean, onSelect: () -> Unit, onClose: () -> Unit): View = View(this)
    private fun loadList(key: String): MutableList<Pair<String, String>> = mutableListOf()
    private fun saveList(key: String, list: List<Pair<String, String>>) {}
    private fun addBookmark() {}
    private fun showList(title: String, key: String) {}
    private fun showTabs() {}

    private class MenuRow(val label: String, val value: String?, val danger: Boolean, val action: () -> Unit)

    private fun showMenu() {
        val t = currentOrNull() ?: return
        val rows = listOf(
            MenuRow("Bookmarks", null, false) { showList("Bookmarks", "bookmarks") },
            MenuRow("History", null, false) { showList("History", "history") },
            MenuRow("Downloads", null, false) { showDownloads() },
            
            // --- Opera-Style Free VPN Menu Toggle Added Here ---
            MenuRow(
                "Opera-Style Free VPN",
                if (vpnManager.isVpnConnected) "ON (${vpnManager.currentProxyEndpoint})" else "OFF",
                false
            ) {
                toggleBrowserVpn()
            },
            
            MenuRow("Reading mode", if (t.readerUrl != null) "On" else "Off", false) { toggleReader() },
            MenuRow("Desktop site", if (t.desktop) "On" else "Off", false) { toggleDesktop() },
            MenuRow("Clear browsing data", null, true) { clearData() }
        )
        val screenH = resources.displayMetrics.heightPixels
        val h = minOf(dp(170 + rows.size * 49), (screenH * 0.85).toInt())
        val (d, box) = newSheet(h)
        sheetHandle(box)

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

    // --- VPN Toggle Action Trigger ---
    private fun toggleBrowserVpn() {
        if (!vpnManager.isVpnConnected) {
            toast("Connecting to Free VPN proxy...")
            lifecycleScope.launch {
                vpnManager.enableFreeVpn(
                    onSuccess = { proxyIp ->
                        toast("VPN Connected ($proxyIp)")
                        currentOrNull()?.web?.reload()
                    },
                    onError = { errorMsg ->
                        toast(errorMsg)
                    }
                )
            }
        } else {
            vpnManager.disableFreeVpn {
                toast("VPN Disconnected")
                currentOrNull()?.web?.reload()
            }
        }
    }

    private fun toggleDesktop() {
        val t = current()
        t.desktop = !t.desktop
        applyClientIdentity(t.web.settings, t.desktop, true)
        t.web.reload()
        toast(if (t.desktop) "Desktop site on" else "Desktop site off")
    }

    private fun clearData() {
        for (t in tabs) {
            t.web.clearCache(true)
            t.web.clearFormData()
            t.web.clearSslPreferences()
            if (!t.priv) t.web.clearHistory()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        toast("Browsing data cleared")
    }
}
