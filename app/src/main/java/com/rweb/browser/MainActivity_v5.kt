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
import androidx.webkit.ProfileStore
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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

    private val MAX_SHOWN = 40
    private val POOL_SIZE = 150

    // link -> title for stories currently on the start page, so a tap can be recorded without
    // exposing a JavaScript bridge to every website
    private val linkTitles = ConcurrentHashMap<String, String>()

    // on-device search engine (index + crawler), see LocalSearch.kt
    private lateinit var localSearch: LocalSearch
    private val crawler: LocalCrawler by lazy { LocalCrawler(localSearch) }
    @Volatile private var crawlStatus = ""

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshIntervalMs = 15 * 60 * 1000L // 15 minutes

    private val feedRefreshRunnable = object : Runnable {
        override fun run() {
            refreshVideos()
            refreshNews()
            refreshHandler.postDelayed(this, refreshIntervalMs)
        }
    }

    private val io = Executors.newSingleThreadExecutor()

    /** Runs [task] on the IO thread; ignored if the executor is already shut down (activity closing). */
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
        Pair("The Hindu", "https://www.thehindu.com/news/national/feeder/default.rss"),
        Pair("NPR", "https://feeds.npr.org/1001/rss.xml"),
        Pair("BBC India", "https://feeds.bbci.co.uk/news/world/asia/india/rss.xml"),
        Pair("BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml"),
        Pair("Fox News", "https://moxie.foxnews.com/feedburner/latest.xml"),
        Pair("Washington Post", "https://feeds.washingtonpost.com/rss/world"),
        Pair("Wall Street Journal", "https://feeds.a.dj.com/rss/RSSWorldNews.xml"),
        Pair("USA Today", "https://rssfeeds.usatoday.com/usatoday-NewsTopStories"),
        Pair("Sky News", "https://feeds.skynews.com/feeds/rss/home.xml"),
        Pair("NBC News", "https://feeds.nbcnews.com/nbcnews/public/news"),
        Pair("BBC Technology", "https://feeds.bbci.co.uk/news/technology/rss.xml"),
        Pair("TechCrunch", "https://techcrunch.com/feed/"),
        Pair("The Verge", "https://www.theverge.com/rss/index.xml"),
        Pair("Wired", "https://www.wired.com/feed/rss"),
        Pair("Ars Technica", "https://arstechnica.com/feed/"),
        Pair("Engadget", "https://www.engadget.com/rss.xml"),
        Pair("CNET", "https://www.cnet.com/rss/all/"),
        Pair("Mashable", "https://mashable.com/feed.rss"),
        Pair("VentureBeat", "https://venturebeat.com/feed/"),
        Pair("Gizmodo", "https://gizmodo.com/rss"),
        Pair("Bloomberg", "https://feeds.bloomberg.com/markets/news.rss"),
        Pair("Financial Times", "https://www.ft.com/rss/home"),
        Pair("Forbes", "https://www.forbes.com/real-time/feed2/"),
        Pair("CNBC", "https://www.cnbc.com/id/100003114/device/rss/rss.html"),
        Pair("Yahoo Finance", "https://finance.yahoo.com/news/rssindex"),
        Pair("ESPN", "https://www.espn.com/espn/rss/news"),
        Pair("BBC Sport", "https://feeds.bbci.co.uk/sport/rss.xml"),
        Pair("Sky Sports", "https://www.skysports.com/rss/12040"),
        Pair("Variety", "https://variety.com/feed/"),
        Pair("Hollywood Reporter", "https://www.hollywoodreporter.com/feed/"),
        Pair("Rolling Stone", "https://www.rollingstone.com/feed/"),
        Pair("Billboard", "https://www.billboard.com/feed/"),
        Pair("E! Online", "https://www.eonline.com/syndication/feeds/rssfeeds/topstories.xml"),
        Pair("Nature", "https://www.nature.com/nature.rss"),
        Pair("ScienceDaily", "https://www.sciencedaily.com/rss/top.xml"),
        Pair("National Geographic", "https://www.nationalgeographic.com/pages/topic/index.rss"),
        Pair("Space.com", "https://www.space.com/feeds/all"),
        Pair("Live Science", "https://www.livescience.com/feeds/all"),
        Pair("Politico", "https://politico.com/rss/politicopulse.xml"),
        Pair("The Hill", "https://thehill.com/rss/syndication/1"),
        Pair("Axios", "https://api.axios.com/feed/"),
        Pair("BBC Science", "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml"),
        Pair("CBC News", "https://www.cbc.ca/webfeed/rss/rss-topstories")
    )

    private val topVideoFeeds = listOf(
        Pair("BBC News", "https://www.youtube.com/feeds/videos.xml?channel_id=UC16niRr50-MSBwiO3YDb3RA"),
        Pair("CNN", "https://www.youtube.com/feeds/videos.xml?channel_id=UCupvZG-5ko_eiXAupbDfxWw"),
        Pair("Al Jazeera English", "https://www.youtube.com/feeds/videos.xml?channel_id=UCNye-wNBqNL5ZzHSJj3l8Bg"),
        Pair("NBC News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCeY0bbntWzzVIaj2z3QigXg"),
        Pair("ABC News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCBi2mrWuNuyYy4gbM6fU18Q"),
        Pair("CBS News", "https://www.youtube.com/feeds/videos.xml?channel_id=UC8p1vwvWtl6T73JiExfWs1g"),
        Pair("MSNBC", "https://www.youtube.com/feeds/videos.xml?channel_id=UCaXkIU1QidjPwiAYu6GcHjg"),
        Pair("Sky News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCoMdktPbSTixAyNGwb-UYkQ"),
        Pair("Fox News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCXIJgqnII2ZOINSWNOGFThA"),
        Pair("DW News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCknLrEdhRCp1aegoMqRaCZg"),
        Pair("France 24 English", "https://www.youtube.com/feeds/videos.xml?channel_id=UCQfwfsi5VrQ8yKZ-UWmAEFg"),
        Pair("Reuters", "https://www.youtube.com/feeds/videos.xml?channel_id=UChqUTb7kYRX8-EiaN3XFrSQ"),
        Pair("Associated Press", "https://www.youtube.com/feeds/videos.xml?channel_id=UC52X5wxOL_s5yw0dQk7NtgA"),
        Pair("Bloomberg", "https://www.youtube.com/feeds/videos.xml?channel_id=UCUMZ7gohGI9HcU9VNsr2FJQ"),
        Pair("CNBC", "https://www.youtube.com/feeds/videos.xml?channel_id=UCvJJ_dzjViJCoLf5uKUTwoA"),
        Pair("The Guardian", "https://www.youtube.com/feeds/videos.xml?channel_id=UCHpw8xwDNhU9gdohEcJu4aA"),
        Pair("New York Times", "https://www.youtube.com/feeds/videos.xml?channel_id=UCqnbDFdCpuN8CMEg0VuEBqA"),
        Pair("Washington Post", "https://www.youtube.com/feeds/videos.xml?channel_id=UCHd62-u_v4DvJ8TCFtpi4GA"),
        Pair("Wall Street Journal", "https://www.youtube.com/feeds/videos.xml?channel_id=UCK7tptUDHh-RYDsdxO1-5QQ"),
        Pair("The Economist", "https://www.youtube.com/feeds/videos.xml?channel_id=UC0p5jTq6Xx_DosDFxVXnWaQ"),
        Pair("Financial Times", "https://www.youtube.com/feeds/videos.xml?channel_id=UCoUxsWakJucWg46KW5RsvPw"),
        Pair("USA TODAY", "https://www.youtube.com/feeds/videos.xml?channel_id=UCP6HGa63sBC7-KHtkme-p-g"),
        Pair("TIME", "https://www.youtube.com/feeds/videos.xml?channel_id=UC8Su5vZCXWRag13H53zWVwA"),
        Pair("Newsweek", "https://www.youtube.com/feeds/videos.xml?channel_id=UC2a35q7eyzkfoIusBzdH4Hw"),
        Pair("VICE News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCZaT_X_mc0BI-djXOlfhqWQ"),
        Pair("Vox", "https://www.youtube.com/feeds/videos.xml?channel_id=UCLXo7UDZvByw2ixzpQCufnA"),
        Pair("POLITICO", "https://www.youtube.com/feeds/videos.xml?channel_id=UCgjtvMmHXbutALaw9XzRkAg"),
        Pair("CBC News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCuFFtHWoLl5fauMMD5Ww2jA"),
        Pair("Global News", "https://www.youtube.com/feeds/videos.xml?channel_id=UChLtXXpo4Ge1ReTEboVvTDg"),
        Pair("CTV News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCi7Zk9baY1tv03gL6n12p-w"),
        Pair("ABC News Australia", "https://www.youtube.com/feeds/videos.xml?channel_id=UCVgO39Bk5sMo66-6o6Spn6Q"),
        Pair("7NEWS Australia", "https://www.youtube.com/feeds/videos.xml?channel_id=UC5T7D-Dh1eDGtsAFCuwv_Sw"),
        Pair("9 News Australia", "https://www.youtube.com/feeds/videos.xml?channel_id=UCIYLOcEUX6TbBo7HQVF2PKA"),
        Pair("Sky News Australia", "https://www.youtube.com/feeds/videos.xml?channel_id=UCO0akufu9MOzyz3nvGIXAAw"),
        Pair("NDTV", "https://www.youtube.com/feeds/videos.xml?channel_id=UCZFMm1mMw0F81Z37aaEzTUA"),
        Pair("India Today", "https://www.youtube.com/feeds/videos.xml?channel_id=UCYPvAwZP8pZhSMW8qs7cVCw"),
        Pair("WION", "https://www.youtube.com/feeds/videos.xml?channel_id=UC_gUM8rL-Lrg6O3adPW9K1g"),
        Pair("Firstpost", "https://www.youtube.com/feeds/videos.xml?channel_id=UCz8QaiQxApLq8sLNcszYyJw"),
        Pair("Times Now", "https://www.youtube.com/feeds/videos.xml?channel_id=UC6RJ7-PaXg6TIH2BzZfTV7w"),
        Pair("Republic World", "https://www.youtube.com/feeds/videos.xml?channel_id=UCwqusr8YDwM-3mEYTDeJHzw"),
        Pair("CNN-News18", "https://www.youtube.com/feeds/videos.xml?channel_id=UCef1-8eOpJgud7szVPlZQAQ"),
        Pair("ANI News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCtFQDgA8J8_iiwc5-KoAQlg"),
        Pair("DD India", "https://www.youtube.com/feeds/videos.xml?channel_id=UCGDQNvybfDDeGTf4GtigXaw"),
        Pair("Aaj Tak", "https://www.youtube.com/feeds/videos.xml?channel_id=UCt4t-jeY85JegMlZ-E5UWtA"),
        Pair("ABP News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCRWFSbif-RFENbBrSiez1DA"),
        Pair("IndiaTV", "https://www.youtube.com/feeds/videos.xml?channel_id=UCttspZesZIDEwwpVIgoZtWQ"),
        Pair("Zee News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCIvaYmXn910QMdemBG3v1pQ"),
        Pair("News18 India", "https://www.youtube.com/feeds/videos.xml?channel_id=UCPP3etACgdUWvizcES1dJ8Q"),
        Pair("The Lallantop", "https://www.youtube.com/feeds/videos.xml?channel_id=UCx8Z14PpntdaxCt2hakbQLQ"),
        Pair("NDTV India", "https://www.youtube.com/feeds/videos.xml?channel_id=UC9CYT9gSNLevX5ey2_6CK0Q"),
        Pair("TRT World", "https://www.youtube.com/feeds/videos.xml?channel_id=UC7fWeaHhqgM4Ry-RMpM2YYw"),
        Pair("CNA", "https://www.youtube.com/feeds/videos.xml?channel_id=UC83jt4dlz1Gjl58fzQrrKZg"),
        Pair("South China Morning Post", "https://www.youtube.com/feeds/videos.xml?channel_id=UC4SUWiuTj6fnrkEhWCN40gw"),
        Pair("NHK WORLD-JAPAN", "https://www.youtube.com/feeds/videos.xml?channel_id=UCSPEjw8F2nQDtmUKPFNF7_A"),
        Pair("Arirang News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCzznO4xSV8BKnUBPyswtCUw"),
        Pair("KBS World News", "https://www.youtube.com/feeds/videos.xml?channel_id=UC5BMQOsAB8hKUyHu9KI6yig"),
        Pair("Euronews", "https://www.youtube.com/feeds/videos.xml?channel_id=UCSrZ3UV4jOidv8ppoVuvW9Q"),
        Pair("i24NEWS English", "https://www.youtube.com/feeds/videos.xml?channel_id=UCvHDpsWKADrDia0c99X37vg"),
        Pair("Channel 4 News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCTrQ7HXWRRxr76OsehR_2rg"),
        Pair("ITV News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCFQgi22MaCa4E4YNJUWOPGg"),
        Pair("The Telegraph", "https://www.youtube.com/feeds/videos.xml?channel_id=UCPgLNge0xqQHWM5B5EFH9Cg"),
        Pair("The Independent", "https://www.youtube.com/feeds/videos.xml?channel_id=UCshwRhftAwXZ8wTf73_60lA"),
        Pair("GBNews", "https://www.youtube.com/feeds/videos.xml?channel_id=UC05b5M9K9K-V2O69b8V3b-Q"),
        Pair("60 Minutes", "https://www.youtube.com/feeds/videos.xml?channel_id=UCsN32BtMd0IoByjJRNF12cw"),
        Pair("CBS Mornings", "https://www.youtube.com/feeds/videos.xml?channel_id=UC-SJ6nODDmufqBzPBwCvYvQ"),
        Pair("CBS Sunday Morning", "https://www.youtube.com/feeds/videos.xml?channel_id=UCVT1tPkR-fUVlO652EcO3ow"),
        Pair("TODAY", "https://www.youtube.com/feeds/videos.xml?channel_id=UChDKyKQ59fYz3JO2fl0Z6sg"),
        Pair("Good Morning America", "https://www.youtube.com/feeds/videos.xml?channel_id=UCH1oRy1dINbMVp3UFWrKP0w"),
        Pair("PBS NewsHour", "https://www.youtube.com/feeds/videos.xml?channel_id=UC6ZFN9Tx6xh-skXCuRHCDpQ"),
        Pair("FRONTLINE PBS", "https://www.youtube.com/feeds/videos.xml?channel_id=UC3ScyryU9rc9sezkjpLD9Tg"),
        Pair("The Verge", "https://www.youtube.com/feeds/videos.xml?channel_id=UCddiUEpeqJcYeBxX1IVBKvQ"),
        Pair("TechCrunch", "https://www.youtube.com/feeds/videos.xml?channel_id=UCCjyq_K1Xwfg8Lndy7lKMpA"),
        Pair("CNET", "https://www.youtube.com/feeds/videos.xml?channel_id=UCOmcA3f_RrH6b9NmcNa4tdg"),
        Pair("WIRED", "https://www.youtube.com/feeds/videos.xml?channel_id=UCftwRNsjfRo08xYE31tkiyw"),
        Pair("Engadget", "https://www.youtube.com/feeds/videos.xml?channel_id=UC-6012p_083P3w6mR127bSg"),
        Pair("Mashable", "https://www.youtube.com/feeds/videos.xml?channel_id=UC33_tB18qR26iR18vM90t_Q"),
        Pair("MIT Technology Review", "https://www.youtube.com/feeds/videos.xml?channel_id=UCgy4Mf_tlZGqesYNqPNxjPw"),
        Pair("Bloomberg Television", "https://www.youtube.com/feeds/videos.xml?channel_id=UCIALMKvObZNtJ6AmdCLP7Lg"),
        Pair("Bloomberg Technology", "https://www.youtube.com/feeds/videos.xml?channel_id=UCrM7B7SL_g1edFOnmj-SDKg"),
        Pair("Yahoo Finance", "https://www.youtube.com/feeds/videos.xml?channel_id=UCEAZeUIeJs0IjQiqTCdVSIg"),
        Pair("Forbes", "https://www.youtube.com/feeds/videos.xml?channel_id=UCmh7afjfKQMeY69261o2w8Q"),
        Pair("Fortune Magazine", "https://www.youtube.com/feeds/videos.xml?channel_id=UCP2gdh7HfjgCoi8lO50dA5w"),
        Pair("Business Insider", "https://www.youtube.com/feeds/videos.xml?channel_id=UCcyq283he07B7_KUX07mmtA"),
        Pair("ESPN", "https://www.youtube.com/feeds/videos.xml?channel_id=UCiWLfSweyRNmLpgEHekhoAg"),
        Pair("Sky Sports News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCNAf1k0yIjyGu3k9BwAg3lg"),
        Pair("CBS Sports", "https://www.youtube.com/feeds/videos.xml?channel_id=UCET00YnetHT7tOpu12v8jxg"),
        Pair("NBC Sports", "https://www.youtube.com/feeds/videos.xml?channel_id=UCqZQlzSHbVJrwrn5XvzrzcA"),
        Pair("FOX Sports", "https://www.youtube.com/feeds/videos.xml?channel_id=UCw05gJBvjg92964tL0M6xXw"),
        Pair("Variety", "https://www.youtube.com/feeds/videos.xml?channel_id=UCgRQHK8T60w-1yV6iW91p2w"),
        Pair("The Hollywood Reporter", "https://www.youtube.com/feeds/videos.xml?channel_id=UCZ8Sxmkweh65HetaZfR8YuA"),
        Pair("Entertainment Tonight", "https://www.youtube.com/feeds/videos.xml?channel_id=UCdtXPiqI2cLorKaPrfpKc4g"),
        Pair("Access Hollywood", "https://www.youtube.com/feeds/videos.xml?channel_id=UCiKGMZZmSjG-9GJ22_2_52g"),
        Pair("E! News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCjDsbbzHnTrXk4_26-85P9A"),
        Pair("National Geographic", "https://www.youtube.com/feeds/videos.xml?channel_id=UCpVm7bg6pXKo1Pr6k5kxG9A"),
        Pair("NASA", "https://www.youtube.com/feeds/videos.xml?channel_id=UCLA_DiR1FfKNvjuUpBHmylQ"),
        Pair("Smithsonian Channel", "https://www.youtube.com/feeds/videos.xml?channel_id=UC-266w47B78-p-4G8wP6kCg"),
        Pair("Discovery", "https://www.youtube.com/feeds/videos.xml?channel_id=UCqOoboPm3uhY_YXhvhmL-WA"),
        Pair("New Scientist", "https://www.youtube.com/feeds/videos.xml?channel_id=UC3-R2B9177M-X0S_w1o1Z3w"),
        Pair("Seeker", "https://www.youtube.com/feeds/videos.xml?channel_id=UCzWQYUVCpZqtN93H8RR44Qw"),
        Pair("Vox Borders", "https://www.youtube.com/feeds/videos.xml?channel_id=UC6107grRI4m0o2-emgoDnAA")
    )

    private val baseEngines = listOf(
        Pair("Google", "https://www.google.com/search?q="),
        Pair("DuckDuckGo", "https://duckduckgo.com/?q="),
        Pair("Bing", "https://www.bing.com/search?q="),
        Pair("rweb Search", "rweb://search?q=")
    )

    companion object {
        const val PRIVATE_PROFILE = "rwebprivate"
        const val DESKTOP_UA_FALLBACK = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val VIDEO_SHAPE_JS = "(function(){var a=document.querySelectorAll('video');var p=null;for(var i=0;i<a.length;i++){if(!a[i].paused&&a[i].videoWidth){p=a[i];break}}if(!p&&a.length)p=a[0];return p&&p.videoWidth?p.videoWidth+'x'+p.videoHeight:''})()"
        const val AUDIO_JS = "(function(){var m=document.querySelectorAll('audio,video');for(var i=0;i<m.length;i++){var e=m[i];if(!e.paused&&!e.ended&&e.readyState>2&&!e.muted&&e.volume>0)return true}return false})()"

        const val HEAD_A = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>:root{--bg:#FFFFFF;--fg:#202124;--mute:#5F6368;--card:#F8F9FA;--fld:#F1F3F4;--acc:#C41E3A;--sh:0 4px 14px rgba(0,0,0,.08)}html.dark{--bg:#3A0A14;--fg:#FFF1F3;--mute:#E3A9B1;--card:#7A1022;--fld:#7A1022;--acc:#FF6B7D;--sh:0 4px 14px rgba(0,0,0,.35)}body{margin:0;padding:28px 16px;box-sizing:border-box;background:var(--bg);color:var(--fg);font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh}.logo-container{display:flex;align-items:center;justify-content:center;width:72px;height:72px;margin-bottom:16px;border-radius:50%;background:var(--fld);box-shadow:var(--sh)}.logo-container svg{width:40px;height:40px}h1{color:var(--acc);font-size:38px;margin:0 0 24px}form{width:100%;max-width:520px}input{width:100%;box-sizing:border-box;padding:16px 22px;border-radius:28px;border:0;background:var(--fld);color:var(--fg);font-size:16px;box-shadow:var(--sh);outline:none}.grid{display:flex;flex-wrap:wrap;justify-content:center;gap:20px 16px;margin-top:32px;width:100%;max-width:520px}.tile{width:72px;text-decoration:none;color:var(--fg);text-align:center;font-size:12px}.ic{position:relative;overflow:hidden;display:flex;align-items:center;justify-content:center;width:56px;height:56px;margin:0 auto 8px;border-radius:50%;background:var(--fld);color:var(--acc);font-size:22px;font-weight:700;box-shadow:var(--sh)}.ic img{position:absolute;top:0;left:0;width:100%;height:100%;box-sizing:border-box;padding:13px;background:#FFFFFF;object-fit:contain}#news{width:100%;max-width:520px;margin-top:24px}.nh{font-size:12px;color:var(--mute);text-transform:uppercase;letter-spacing:.08em;margin-bottom:8px}summary.nh{list-style:none;cursor:pointer;display:flex;justify-content:space-between;align-items:center;padding:4px 4px 12px;margin:0}summary.nh::-webkit-details-marker{display:none}summary.nh::after{content:"\25BE";font-size:14px}details[open] summary.nh::after{content:"\25B4"}.nw{display:flex;align-items:center;gap:14px;padding:14px 16px;margin-bottom:12px;background:var(--card);border-radius:18px;box-shadow:var(--sh);color:var(--fg);text-decoration:none;font-size:14px;line-height:1.4}.nt{flex:1}.ni{width:88px;height:64px;object-fit:cover;border-radius:12px;flex-shrink:0}#vids{width:100%;max-width:520px;margin-top:32px}.nw.hero{flex-direction:column;align-items:stretch;padding:0;overflow:hidden}.nw.hero .hi{width:100%;height:190px;object-fit:cover;display:block}.nw.hero .nt{padding:14px 16px;font-size:16px;font-weight:600}.vrow{display:flex;gap:12px;overflow-x:auto;padding-bottom:10px}.vc{flex:0 0 230px;text-decoration:none;color:var(--fg)}.vt{position:relative;width:230px;height:130px;border-radius:14px;overflow:hidden;background:var(--card);box-shadow:var(--sh)}.vt img{width:100%;height:100%;object-fit:cover;display:block}.vp{position:absolute;left:50%;top:50%;width:44px;height:44px;margin:-22px 0 0 -22px;border-radius:50%;background:rgba(0,0,0,.55);color:#fff;text-align:center;line-height:44px;font-size:18px}.vn{font-size:13px;line-height:1.3;margin-top:8px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.chips{display:flex;flex-wrap:wrap;gap:8px;justify-content:center;margin-top:14px}.chip{padding:7px 14px;border-radius:16px;background:var(--fld);color:var(--fg);font-size:13px;box-shadow:var(--sh)}.chip.on{background:var(--acc);color:#fff}.lf{margin-top:14px}.lf input{border:2px solid var(--acc);box-shadow:none}</style></head><body><div class="logo-container"><svg viewBox="0 0 24 24" fill="none" stroke="var(--acc)" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"/></svg></div><h1>rweb</h1><form action=""""

        const val HEAD_B = """" method="get" onsubmit="return go()"><input id="q" name="q" placeholder="Search the web" autocomplete="off"></form><div class="chips"><span class="chip on" id="c-web" onclick="pick('web')">Web</span><span class="chip" id="c-img" onclick="pick('img')">Images</span><span class="chip" id="c-vid" onclick="pick('vid')">Videos</span><span class="chip" onclick="location.href='rweb://voice'">Voice</span><span class="chip" onclick="location.href='rweb://photo'">Photo</span></div><div class="grid">"""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rweb", MODE_PRIVATE)
        localSearch = LocalSearch(this)
        loadAdBlockSettings()
        mobileUa = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        desktopUa = buildDesktopUa()
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = BG
            window.navigationBarColor = BAR
        }
        wipePrivate()
        cleanPrivateDownloads()

        // Load cached feeds for instant offline start
        vidHtml = loadFeedCache("vids_cache.json")
        newsHtml = loadFeedCache("news_cache.json")

        buildUi()
        registerBackHandler()
        restoreSavedTabs()

        val link = intent?.data?.toString()
        if (link != null) newTab(link) else if (tabs.isEmpty()) newTab(null)
        if (intent?.getBooleanExtra("app_mode", false) == true) setBarsHidden(true)
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
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
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
        if (target.startsWith("rweb://search")) { // our own engine: results are built in the app
            runLocalSearch(tab, Uri.parse(target).getQueryParameter("q") ?: "")
            return
        }
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
        lockIcon.contentDescription = when (kind) {
            "lock" -> "Secure connection"
            "warning" -> "Connection not secure"
            "shield" -> "Private tab"
            else -> "Search"
        }
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
        if (url.isNullOrEmpty()) loadStart(tab.web) else navigate(tab, url)
    }

    private fun switchTo(i: Int) {
        val prev = tabs.getOrNull(cur)
        cur = i
        hideSuggestions()
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
        address.hint = if (t.priv) "Private tab: enter address" else "Enter address or search"
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

    // --- Local Caching Helpers ---
    private fun saveFeedCache(filename: String, content: String) {
        runIo { File(filesDir, filename).writeText(content) }
    }

    private fun loadFeedCache(filename: String): String? {
        try {
            val file = File(filesDir, filename)
            if (file.exists()) return file.readText()
        } catch (e: Exception) {
        }
        return null
    }

    // --- Read state, click counts and interests (bounded, kept in memory, one prefs write per tap) ---
    private val statsLock = Any()
    private var readList: ArrayList<String>? = null              // oldest first, max 500
    private var clickMap: LinkedHashMap<String, Int>? = null     // oldest first, max 300
    private var interests: HashMap<String, Int>? = null          // max 400 words

    private val wordRe = Regex("[a-z0-9]+")
    private val stopWords = setOf("news", "video", "watch", "with", "from", "this", "that", "your", "what", "have", "will", "about", "after")

    private fun words(text: String): List<String> = wordRe.findAll(text.lowercase()).map { it.value }.toList()

    private fun ensureStats() {
        if (readList != null && clickMap != null && interests != null) return
        val r = ArrayList<String>()
        val c = LinkedHashMap<String, Int>()
        val w = HashMap<String, Int>()
        try {
            val arr = JSONArray(prefs.getString("read_urls", "[]"))
            for (i in 0 until arr.length()) r.add(arr.getString(i))
        } catch (e: Exception) {
        }
        try {
            val o = JSONObject(prefs.getString("click_counts", "{}"))
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                c[k] = o.optInt(k, 0)
            }
        } catch (e: Exception) {
        }
        try {
            val o = JSONObject(prefs.getString("user_interests", "{}"))
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                w[k] = o.optInt(k, 0)
            }
        } catch (e: Exception) {
        }
        readList = r
        clickMap = c
        interests = w
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
            if (!r.contains(url)) {
                r.add(url)
                while (r.size > 500) r.removeAt(0)
            }
            val c = clickMap!!
            val n = (c.remove(url) ?: 0) + 1
            c[url] = n
            while (c.size > 300) c.remove(c.keys.iterator().next())
            val w = interests!!
            for (word in words(title)) {
                if (word.length > 3 && word !in stopWords && (w.containsKey(word) || w.size < 400)) {
                    w[word] = (w[word] ?: 0) + 1
                }
            }
            prefs.edit()
                .putString("read_urls", JSONArray(r).toString())
                .putString("click_counts", JSONObject(c as Map<*, *>).toString())
                .putString("user_interests", JSONObject(w as Map<*, *>).toString())
                .apply()
        }
    }

    private fun rememberTitle(url: String, title: String) {
        if (linkTitles.size > 600) linkTitles.clear()
        linkTitles[url] = title
    }

    // --- Custom Feeds Management ---
    private fun getCustomFeeds(key: String): MutableList<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        try {
            val arr = JSONArray(prefs.getString(key, "[]"))
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Pair(obj.getString("name"), obj.getString("url")))
            }
        } catch (e: Exception) {
        }
        return list
    }

    private fun saveCustomFeeds(key: String, list: List<Pair<String, String>>) {
        try {
            val arr = JSONArray()
            for (item in list) {
                arr.put(JSONObject().put("name", item.first).put("url", item.second))
            }
            prefs.edit().putString(key, arr.toString()).apply()
        } catch (e: Exception) {
        }
    }

    // --- Categories (whole-word matching) ---
    private val techWords = setOf("tech", "technology", "ai", "software", "app", "apps", "phone", "iphone", "android", "apple", "google", "microsoft", "openai", "chip", "chips", "cyber", "robot", "robots", "internet", "gadget", "startup", "startups")
    private val sportsWords = setOf("sport", "sports", "football", "cricket", "match", "league", "nba", "nfl", "soccer", "tennis", "olympic", "olympics", "tournament", "goal", "coach", "ipl", "fifa", "championship")
    private val businessWords = setOf("business", "market", "markets", "economy", "economic", "stock", "stocks", "bank", "banks", "inflation", "trade", "tariff", "tariffs", "earnings", "shares", "investors", "finance")
    private val scienceWords = setOf("science", "space", "nasa", "study", "research", "scientists", "climate", "physics", "planet", "species", "vaccine", "astronomy", "telescope")

    private fun getCategory(title: String): String {
        val w = words(title).toSet()
        return when {
            w.any { it in techWords } -> "Tech"
            w.any { it in sportsWords } -> "Sports"
            w.any { it in businessWords } -> "Business"
            w.any { it in scienceWords } -> "Science"
            else -> "General"
        }
    }

    private fun getCategoryChipsHtml(activeCategory: String): String {
        val categories = listOf("All", "Tech", "Sports", "Business", "Science")
        val sb = StringBuilder("<div style=\"display:flex;gap:8px;overflow-x:auto;padding:12px 0;width:100%;max-width:520px;\">")
        for (cat in categories) {
            val isActive = cat == activeCategory
            val bg = if (isActive) "var(--acc)" else "var(--fld)"
            val fg = if (isActive) "#fff" else "var(--fg)"
            sb.append("<button onclick=\"filterCategory('$cat')\" style=\"padding:6px 14px;border-radius:14px;border:0;background:$bg;color:$fg;font-size:12px;cursor:pointer;box-shadow:var(--sh);white-space:nowrap;\">$cat</button>")
        }
        sb.append("</div>")
        return sb.toString()
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
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.safeBrowsingEnabled = true
        s.setSupportMultipleWindows(true)
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
                if (url?.startsWith("https://") == true) tab.httpFallback = null
                if (tab.readerUrl != null) {
                    view?.evaluateJavascript("(function(){return !!document.getElementById('rweb-reader')})()") { r ->
                        if (r != "true") tab.readerUrl = null
                    }
                }
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
                    if (!tab.priv && indexVisited() && tab.url.startsWith("http")) indexCurrentPage(view, tab.url)
                } else {
                    tab.title = "New tab"
                    if (!tab.priv) vidHtml?.let { injectVideos(tab.web, it) }
                    if (!tab.priv) newsHtml?.let { injectNews(tab.web, it) }
                }
                if (tabs.getOrNull(cur) === tab && !address.hasFocus()) address.setText(tab.url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                val req = request ?: return
                if (req.isForMainFrame) {
                    val url = req.url.toString()
                    val offlineHtml = getOfflineArticle(url)
                    if (offlineHtml != null) {
                        view?.loadDataWithBaseURL(url, offlineHtml, "text/html", "UTF-8", null)
                        return
                    }
                }
                if (!req.isForMainFrame || req.url.scheme != "https") return
                val fb = tab.httpFallback ?: return
                if (hostOf(fb) != req.url.host?.lowercase()) return
                tab.httpFallback = null
                view?.loadUrl(fb)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val req = request ?: return null
                if (!adBlockOn || req.isForMainFrame) return null
                val host = req.url.host?.lowercase() ?: return null
                val pageHost = hostOf(tab.url)
                if (pageHost != null) {
                    if (pausedHosts.contains(pageHost)) return null
                    if (host == pageHost || host.endsWith("." + pageHost) || pageHost.endsWith("." + host)) return null
                }
                if (!AdBlock.isBlocked(host)) return null
                tab.blocked.incrementAndGet()
                return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
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
                val urlStr = u.toString()

                // a tap on a story/video card on the start page: remember it (marks as read, trains interests)
                if (!tab.priv && tab.url.isEmpty() && req.isForMainFrame && req.hasGesture()) {
                    linkTitles[urlStr]?.let { recordClick(urlStr, it) }
                    if (isHttp(urlStr)) localSearch.visit(urlStr)
                }

                if (urlStr.contains("web.whatsapp.com")) {
                    view?.settings?.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                } else {
                    view?.settings?.userAgentString = if (tab.desktop) desktopUa else mobileUa
                }

                val scheme = u.scheme ?: return false
                if (scheme == "rweb") {
                    when (u.host) {
                        "voice" -> launchVoice()
                        "photo" -> openPhotoSearch()
                        "search" -> runLocalSearch(tab, u.getQueryParameter("q") ?: "")
                        "home" -> loadStart(tab.web)
                        "crawl" -> showSearchIndexMenu()
                    }
                    return true
                }
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
                val fb = tab.httpFallback
                if (fb != null && failing != null && hostOf(failing) == hostOf(fb)) {
                    tab.httpFallback = null
                    view?.loadUrl(fb)
                    return
                }
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
                chooseFiles(params)
                return true
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
                tab.web.evaluateJavascript(VIDEO_SHAPE_JS) { r ->
                    val parts = (r ?: "").replace("\"", "").split("x")
                    val w = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    val h = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    if (customView != null && w > 0 && h > 0) {
                        requestedOrientation = if (h > w) {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    }
                }
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
        mediaPerms.launch(needed.toTypedArray())
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
                    geoPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
            .setNegativeButton("Block") { _, _ -> cb.invoke(origin, false, false) }
            .setOnCancelListener { cb.invoke(origin, false, false) }
            .show()
    }

    /** Search URL of the user's own rweb-search server (ends with /search?q=), or null if not configured. */
    private fun rwebSearchBase(): String? = prefs.getString("rweb_search_url", null)?.takeIf { it.isNotBlank() }

    private fun allEngines(): List<Pair<String, String>> {
        return baseEngines
    }

    private fun searchBase(): String {
        val saved = prefs.getString("search", null)
        return if (saved != null && allEngines().any { it.second == saved }) saved else allEngines()[0].second
    }

    private fun chooseEngine() {
        val list = allEngines()
        val names = (list.map { it.first } + (if (rwebSearchBase() == null) "Add rweb Search server\u2026" else "Edit rweb Search server\u2026")).toTypedArray()
        val idx = list.indexOfFirst { it.second == searchBase() }.coerceAtLeast(0)
        dlg()
            .setTitle("Search engine")
            .setSingleChoiceItems(names, idx) { d, i ->
                d.dismiss()
                if (i < list.size) {
                    prefs.edit().putString("search", list[i].second).apply()
                    toast(names[i])
                } else {
                    askRwebServer()
                }
            }.show()
    }

    private fun askRwebServer() {
        val input = EditText(this)
        input.setSingleLine()
        input.hint = "https://search.example.com"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.setText(rwebSearchBase()?.substringBefore("/search?q=") ?: "")
        dlg()
            .setTitle("rweb Search server")
            .setMessage("Address of your rwebsearch server. Leave empty to remove it.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                var v = input.text.toString().trim().trimEnd('/')
                if (v.isEmpty()) {
                    prefs.edit().remove("rweb_search_url").apply()
                    toast("rweb Search removed")
                } else {
                    if (!isHttp(v)) v = "https://" + v
                    if (Uri.parse(v).host == null) {
                        toast("That address isn't valid")
                    } else {
                        val url = v + "/search?q="
                        prefs.edit().putString("rweb_search_url", url).putString("search", "rweb://search?q=").apply()
                        toast("rweb Search selected (this phone + your server)")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun siteIcons(): Boolean = prefs.getBoolean("icons", true)

    private fun startHtml(): String {
        val amazon = "https://www.amazon.com"
        val tiles = mutableListOf(
            Pair("YouTube", "https://www.youtube.com"),
            Pair("ChatGPT", "https://chatgpt.com"),
            Pair("Facebook", "https://www.facebook.com"),
            Pair("Instagram", "https://www.instagram.com"),
            Pair("Weather", "https://weather.com"),
            Pair("Amazon", amazon),
            Pair("Google", "https://www.google.com"),
            Pair("Translate", "https://translate.google.com"),
            Pair("WhatsApp", "https://web.whatsapp.com"),
            Pair("Gmail", "https://mail.google.com")
        )
        for (b in loadList("bookmarks").take(8)) {
            if (tiles.none { it.second == b.second }) {
                tiles.add(Pair(if (b.first.isBlank()) b.second else b.first, b.second))
            }
        }
        val icons = siteIcons()
        val sb = StringBuilder()
        sb.append(HEAD_A.replaceFirst("<html>", if (isDark()) "<html class=\"dark\">" else "<html>"))
        sb.append(esc(searchBase().substringBefore("?q="))).append(HEAD_B.replace("<div class=\"grid\">", localBoxHtml() + "<div class=\"grid\">"))
        for (t in tiles) {
            val letter = t.first.trim().take(1).uppercase()
            sb.append("<a class=\"tile\" href=\"").append(esc(t.second)).append("\">")
            val host = Uri.parse(t.second).host ?: ""
            sb.append("<span class=\"ic\">").append(esc(letter))
            if (icons && host.isNotEmpty()) {
                val iconSrc = when {
                    t.first.equals("Gmail", true) -> "https://ssl.gstatic.com/ui/v1/icons/mail/rfr/gmail.ico"
                    t.first.equals("WhatsApp", true) -> "https://upload.wikimedia.org/wikipedia/commons/6/6b/WhatsApp.svg"
                    else -> "https://www.google.com/s2/favicons?sz=128&domain=" + Uri.encode(host)
                }
                sb.append("<img referrerpolicy=\"no-referrer\" src=\"").append(esc(iconSrc)).append("\" onerror=\"this.style.display='none'\">")
            }
            sb.append("</span>")
            sb.append("<span class=\"nm\">").append(esc(t.first.take(14))).append("</span></a>")
        }
        sb.append("</div><div id=\"vids\">")
        sb.append(vidHtml ?: "<div class=\"nh\">Loading top news videos...</div>")
        sb.append("</div><div id=\"news\">")
        sb.append(newsHtml ?: "<div class=\"nh\">Loading aggregated news...</div>")
        sb.append("</div>").append(searchScript()).append("</body></html>")
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

    private fun cancelFileChooser() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
    }

    private fun startPicker(intent: Intent) {
        try {
            filePicker.launch(intent)
        } catch (e: Exception) {
            cancelFileChooser()
            toast("Can't open the picker")
        }
    }

    private fun chooseFiles(p: WebChromeClient.FileChooserParams) {
        val types = p.acceptTypes.filter { it.isNotBlank() }
        val hasImages = types.any { it.startsWith("image/") }
        val imagesOnly = hasImages && types.all { it.startsWith("image/") }
        val multiple = p.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        if (p.isCaptureEnabled && hasImages) {
            launchCamera()
        } else if (imagesOnly) {
            launchPhotos(multiple)
        } else if (hasImages) {
            dlg()
                .setTitle("Attach")
                .setItems(arrayOf("Photos", "Camera", "Files")) { _, i ->
                    when (i) {
                        0 -> launchPhotos(multiple)
                        1 -> launchCamera()
                        else -> startPicker(p.createIntent())
                    }
                }
                .setOnCancelListener { cancelFileChooser() }
                .show()
        } else {
            startPicker(p.createIntent())
        }
    }

    private fun launchPhotos(multiple: Boolean) {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            val pick = Intent(MediaStore.ACTION_PICK_IMAGES)
            if (multiple) pick.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
            pick
        } else {
            val get = Intent(Intent.ACTION_GET_CONTENT)
            get.addCategory(Intent.CATEGORY_OPENABLE)
            get.type = "image/*"
            if (multiple) get.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            get
        }
        startPicker(intent)
    }

    private fun launchCamera() {
        if (!has(Manifest.permission.CAMERA)) {
            cameraPerm.launch(Manifest.permission.CAMERA)
            return
        }
        try {
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "rweb_" + System.currentTimeMillis() + ".jpg")
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                cancelFileChooser()
                toast("Can't start the camera")
                return
            }
            cameraUri = uri
            cameraShot.launch(uri)
        } catch (e: Exception) {
            cancelFileChooser()
            toast("Can't start the camera")
        }
    }

    private fun privateHtml(): String {
        return HEAD_A.replaceFirst("<html>", "<html class=\"dark\">") + esc(searchBase().substringBefore("?q=")) + HEAD_B +
            "</div><div id=\"news\" style=\"text-align:center;color:#E3A9B1;font-size:14px;line-height:1.5\">" +
            "<b style=\"color:#FF6B7D\">Private tab</b><br>History, cookies and site data from private tabs are deleted when you close the last private tab.<br>" +
            "Bookmarks can't be saved from private tabs. Downloads from private tabs are deleted when you close the last private tab.</div>" + searchScript() + "</body></html>"
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
        if (items != null) {
            val html = buildNews(newsName, items)
            if (html.isNotEmpty()) newsHtml = html
        }
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

    private fun secureImage(u: String): String =
        if (u.startsWith("http://")) "https://" + u.removePrefix("http://") else u

    /** Round-robin across feeds so no single site dominates; drops duplicate links and titles. */
    private fun mergeRoundRobin(
        results: Map<Int, List<Triple<String, String, String>>>,
        cap: Int
    ): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        val seenLinks = HashSet<String>()
        val seenTitles = HashSet<String>()
        val keys = results.keys.sorted()
        var round = 0
        var any = true
        while (any && out.size < cap) {
            any = false
            for (k in keys) {
                val l = results[k] ?: continue
                if (round >= l.size) continue
                any = true
                val t = l[round]
                val titleKey = wordRe.findAll(t.first.lowercase()).joinToString("") { it.value }
                if (!seenLinks.add(t.second) || !seenTitles.add(titleKey)) continue
                out.add(t)
                if (out.size >= cap) break
            }
            round++
        }
        return out
    }

    // ---- Asynchronous Streaming Videos Aggregation ----

    /** Builds the video strip; returns "" when nothing with a thumbnail is available. */
    private fun buildVideos(items: List<Triple<String, String, String>>): String {
        val readSet = getReadArticles()
        val sorted = items
            .filter { !readSet.contains(it.second) && it.third.startsWith("http") }
            .sortedByDescending { getClickCount(it.second) + getRelevanceScore(it.first) * 3 }
            .take(MAX_SHOWN)
        if (sorted.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("<div class=\"nh\">Trending Videos</div>")
        sb.append(getCategoryChipsHtml("All"))
        sb.append("<div class=\"vrow\">")
        for (v in sorted) {
            rememberTitle(v.second, v.first)
            sb.append("<a class=\"vc\" data-cat=\"").append(getCategory(v.first)).append("\" href=\"").append(esc(v.second)).append("\">")
                .append("<div class=\"vt\"><img loading=\"lazy\" referrerpolicy=\"no-referrer\" src=\"").append(esc(secureImage(v.third)))
                .append("\" onerror=\"var c=this.closest('.vc');if(c)c.remove()\">")
                .append("<span class=\"vp\">\u25B6</span></div><div class=\"vn\">").append(esc(v.first)).append("</div></a>")
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun injectVideos(web: WebView, html: String) {
        web.evaluateJavascript(
            "(function(){var e=document.getElementById('vids');if(e){e.innerHTML=" + JSONObject.quote(html) + ";}})()",
            null
        )
    }

    /** Latest few videos of one YouTube channel feed: (title, link, thumbnail). */
    private fun fetchVideosForChannel(feedUrl: String, limit: Int = 3): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        val conn = URL(upgradeHttps(feedUrl)).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) rweb")
        try {
            val parser = Xml.newPullParser()
            parser.setInput(conn.inputStream, null)
            var inEntry = false
            var title = ""
            var link = ""
            var videoId = ""
            var thumbnail = ""
            var tag = ""
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT && out.size < limit) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = parser.name
                    if (tag == "entry") {
                        inEntry = true
                        title = ""
                        link = ""
                        videoId = ""
                        thumbnail = ""
                    } else if (inEntry && tag == "link") {
                        val rel = parser.getAttributeValue(null, "rel")
                        val href = parser.getAttributeValue(null, "href")
                        if (href != null && link.isEmpty() && (rel == null || rel == "alternate" || rel == "")) {
                            link = href
                        }
                    } else if (inEntry && (tag == "thumbnail" || tag == "media:thumbnail")) {
                        val u = parser.getAttributeValue(null, "url")
                        if (!u.isNullOrEmpty()) thumbnail = u
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (inEntry) {
                        if (tag == "title") title += parser.text
                        else if (tag == "link" && link.isEmpty()) link += parser.text
                        else if (tag == "videoId" || tag.endsWith("videoId")) videoId += parser.text
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (parser.name == "entry") {
                        inEntry = false
                        val cleanTitle = title.trim()
                        var cleanLink = link.trim()
                        videoId = videoId.trim()
                        if (videoId.isEmpty() && cleanLink.contains("v=")) {
                            videoId = cleanLink.substringAfter("v=").substringBefore("&")
                        } else if (videoId.isEmpty() && cleanLink.contains("youtu.be/")) {
                            videoId = cleanLink.substringAfter("youtu.be/").substringBefore("?")
                        }
                        if (videoId.isNotEmpty() && cleanTitle.isNotBlank()) {
                            if (cleanLink.isEmpty()) cleanLink = "https://www.youtube.com/watch?v=$videoId"
                            if (!thumbnail.startsWith("http")) thumbnail = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                            out.add(Triple(cleanTitle, cleanLink, thumbnail))
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

    private fun refreshVideos() {
        if (vidLoading) return
        vidLoading = true
        Thread {
            var merged: List<Triple<String, String, String>> = emptyList()
            try {
                val feeds = topVideoFeeds + getCustomFeeds("custom_video_feeds")
                val pool = Executors.newFixedThreadPool(16)
                val results = ConcurrentHashMap<Int, List<Triple<String, String, String>>>()
                val latch = CountDownLatch(feeds.size)
                for ((i, f) in feeds.withIndex()) {
                    pool.execute {
                        try {
                            results.put(i, fetchVideosForChannel(f.second))
                        } catch (e: Exception) {
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await(15, TimeUnit.SECONDS)
                pool.shutdownNow()
                merged = mergeRoundRobin(HashMap(results), POOL_SIZE)
            } catch (e: Exception) {
            }
            val finalList = merged
            runOnUiThread {
                vidLoading = false // always reset so the 15-minute refresh keeps working
                if (isFinishing || isDestroyed) return@runOnUiThread
                val html = buildVideos(finalList)
                if (html.isNotEmpty()) {
                    vidHtml = html
                    vidTime = System.currentTimeMillis()
                    saveFeedCache("vids_cache.json", html)
                    for (t in tabs) {
                        if (!t.priv && t.url.isEmpty()) injectVideos(t.web, html)
                    }
                }
            }
        }.start()
    }

    // ---- News Aggregation ----

    private fun fetchNews(feed: String, limit: Int = 6): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        val conn = URL(upgradeHttps(feed)).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) rweb")
        try {
            val parser = Xml.newPullParser()
            parser.setInput(conn.inputStream, null)
            var inItem = false
            var title = ""
            var link = ""
            var image = ""
            var desc = ""
            var tag = ""
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT && out.size < limit) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = parser.name
                    if (tag == "item" || tag == "entry") {
                        inItem = true
                        title = ""
                        link = ""
                        image = ""
                        desc = ""
                    } else if (inItem && tag == "link") {
                        val rel = parser.getAttributeValue(null, "rel")
                        val href = parser.getAttributeValue(null, "href")
                        if (href != null && link.isEmpty() && (rel == null || rel == "alternate")) link = href
                    } else if (inItem && (tag == "media:thumbnail" || tag == "media:content" || tag == "enclosure")) {
                        val u = parser.getAttributeValue(null, "url")
                        val type = parser.getAttributeValue(null, "type") ?: ""
                        val medium = parser.getAttributeValue(null, "medium") ?: ""
                        // media:content with no type/medium (e.g. The Guardian) is normally the image
                        if (u != null && image.isEmpty() && (tag == "media:thumbnail" || type.startsWith("image") || medium == "image" ||
                                (tag == "media:content" && type.isEmpty() && medium.isEmpty()))
                        ) {
                            image = u
                        }
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (inItem) {
                        if (tag == "title") title += parser.text
                        else if (tag == "link" && link.isEmpty()) link += parser.text
                        else if (tag == "description" || tag == "content:encoded" || tag == "summary" || tag == "content") desc += parser.text
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (parser.name == "item" || parser.name == "entry") {
                        inItem = false
                        val l = link.trim()
                        if (title.isNotBlank() && (l.startsWith("https://") || l.startsWith("http://"))) {
                            var pic = image.trim()
                            if (pic.isEmpty() && desc.isNotEmpty()) pic = firstImageIn(desc)
                            out.add(Triple(title.trim(), l, pic))
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

    /** Builds the news list; only stories with a thumbnail are shown. Returns "" when there are none yet. */
    private fun buildNews(name: String, items: List<Triple<String, String, String>>): String {
        val open = prefs.getBoolean("news_open", true)
        val readSet = getReadArticles()
        val sorted = items
            .filter { !readSet.contains(it.second) }
            .sortedByDescending { getClickCount(it.second) + getRelevanceScore(it.first) * 3 }

        val cards = StringBuilder()
        var shown = 0
        for (n in sorted) {
            if (shown >= MAX_SHOWN) break
            var pic = n.third
            var isVideo = false
            synchronized(ogCache) {
                if (!pic.startsWith("http")) pic = ogCache[n.second] ?: ""
                isVideo = ogVideo.contains(n.second)
            }
            if (!pic.startsWith("http") || isVideo) continue
            pic = secureImage(pic)

            val hero = shown == 0
            shown++
            rememberTitle(n.second, n.first)
            val broken = "var c=this.closest('.nw');if(c)c.remove()"
            cards.append("<a class=\"nw").append(if (hero) " hero" else "").append("\" data-cat=\"").append(getCategory(n.first))
                .append("\" href=\"").append(esc(n.second)).append("\">")
            if (hero) {
                cards.append("<img class=\"hi\" referrerpolicy=\"no-referrer\" src=\"").append(esc(pic)).append("\" onerror=\"").append(broken).append("\">")
                cards.append("<span class=\"nt\">").append(esc(n.first)).append("</span>")
            } else {
                cards.append("<span class=\"nt\">").append(esc(n.first)).append("</span>")
                cards.append("<img class=\"ni\" loading=\"lazy\" referrerpolicy=\"no-referrer\" src=\"").append(esc(pic)).append("\" onerror=\"").append(broken).append("\">")
            }
            cards.append("</a>")
        }
        if (shown == 0) return ""

        val sb = StringBuilder()
        sb.append("<details class=\"nd\"").append(if (open) " open" else "").append(">")
        sb.append("<summary class=\"nh\"><span>Latest News</span></summary>")
        sb.append(getCategoryChipsHtml("All"))
        sb.append(cards)
        sb.append("</details>")
        return sb.toString()
    }

    private fun injectNews(web: WebView, html: String) {
        web.evaluateJavascript(
            "(function(){var e=document.getElementById('news');if(e){e.innerHTML=" + JSONObject.quote(html) + ";}})()",
            null
        )
    }

    private fun publishNews(items: List<Triple<String, String, String>>) {
        val html = buildNews(newsName, items)
        if (html.isEmpty()) return
        newsHtml = html
        newsTime = System.currentTimeMillis()
        saveFeedCache("news_cache.json", html)
        for (t in tabs) {
            if (!t.priv && t.url.isEmpty()) injectNews(t.web, html)
        }
    }

    private fun refreshNews() {
        if (newsLoading) return
        newsLoading = true
        Thread {
            var merged: List<Triple<String, String, String>> = emptyList()
            try {
                val feeds = topNewsFeeds + getCustomFeeds("custom_news_feeds")
                val pool = Executors.newFixedThreadPool(16)
                val results = ConcurrentHashMap<Int, List<Triple<String, String, String>>>()
                val latch = CountDownLatch(feeds.size)
                for ((i, f) in feeds.withIndex()) {
                    pool.execute {
                        try {
                            results.put(i, fetchNews(f.second, 6))
                        } catch (e: Exception) {
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await(12, TimeUnit.SECONDS)
                pool.shutdownNow()
                // snapshot after shutdown, then interleave sources; thumbnails are filtered when rendering
                merged = mergeRoundRobin(HashMap(results), POOL_SIZE)
            } catch (e: Exception) {
            }
            val finalList = merged
            runOnUiThread {
                newsLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (finalList.isNotEmpty()) {
                    newsItems = finalList
                    newsName = "Top News Websites"
                    publishNews(finalList)
                    enrichNews(finalList, newsName)
                }
            }
        }.start()
    }

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
        if (!on) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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

    private fun privateIdSet(): Set<String> = prefs.getStringSet("priv_dl", emptySet()) ?: emptySet()

    private fun showDownloads() {
        val wantPrivate = currentOrNull()?.priv == true
        val screenH = resources.displayMetrics.heightPixels
        val (d, box) = newSheet((screenH * 0.7).toInt())
        sheetHandle(box)
        val title = TextView(this)
        title.text = if (wantPrivate) "Private downloads" else "Downloads"
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

        fun render(): Boolean {
            val items = Downloads.query(this, privateIdSet(), wantPrivate)
            list.removeAllViews()
            if (items.isEmpty()) {
                val empty = TextView(this)
                empty.text = "No downloads yet"
                empty.textSize = 14f
                empty.setTextColor(MUTE)
                empty.gravity = Gravity.CENTER
                empty.setPadding(0, dp(40), 0, dp(40))
                list.addView(empty)
                return false
            }
            for (item in items) {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                list.addView(downloadRow(item) { render() }, lp)
            }
            return items.any { it.running }
        }

        val tick = object : Runnable {
            override fun run() {
                if (render() && d.isShowing) handler.postDelayed(this, 1000)
            }
        }
        d.setOnDismissListener { handler.removeCallbacks(tick) }
        d.show()
        handler.post(tick)
    }

    private fun downloadRow(item: DownloadItem, onChanged: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val bg = GradientDrawable()
        bg.setColor(BAR)
        bg.cornerRadius = dp(16).toFloat()
        row.background = bg
        row.setPadding(dp(12), dp(10), dp(4), dp(10))
        row.setOnClickListener {
            if (item.done) {
                if (!Downloads.open(this, item.id)) toast("No app can open this file")
            }
        }

        val badge = TextView(this)
        badge.gravity = Gravity.CENTER
        badge.textSize = 11f
        badge.setTextColor(INK)
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(BG)
        badge.background = circle
        val ext = item.title.substringAfterLast('.', "").take(4).uppercase()
        badge.text = if (ext.isEmpty()) "FILE" else ext
        row.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        val name = TextView(this)
        name.text = item.title
        name.textSize = 14f
        name.setTextColor(INK)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        val sub = TextView(this)
        sub.text = Downloads.describe(item)
        sub.textSize = 12f
        sub.setTextColor(if (item.status == DownloadManager.STATUS_FAILED) ROSE else MUTE)
        col.addView(name)
        col.addView(sub)
        if (item.running && item.total > 0) {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            bar.max = 100
            bar.progress = item.percent
            bar.progressTintList = ColorStateList.valueOf(ROSE)
            val barLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
            barLp.topMargin = dp(6)
            col.addView(bar, barLp)
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val x = icon("close", INK)
        x.setPadding(dp(12), dp(12), dp(12), dp(12))
        x.contentDescription = if (item.running) "Cancel download" else "Delete download"
        x.background = ripple()
        x.setOnClickListener {
            if (item.running) {
                Downloads.remove(this, item.id)
                onChanged()
            } else {
                dlg()
                    .setTitle("Delete this file?")
                    .setMessage(item.title)
                    .setPositiveButton("Delete") { _, _ ->
                        Downloads.remove(this, item.id)
                        onChanged()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        row.addView(x, LinearLayout.LayoutParams(dp(44), dp(44)))
        return row
    }

    private val ogCache = HashMap<String, String>()
    private val ogVideo = HashSet<String>()

    private fun firstImageIn(html: String): String {
        val m = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html) ?: return ""
        var u = m.groupValues[1].replace("&amp;", "&").trim()
        if (u.startsWith("//")) u = "https:" + u
        return if (isHttp(u)) u else ""
    }

    private fun metaContent(html: String, key: String): String? {
        val keyRe = Regex("(?:property|name)\\s*=\\s*[\"']" + Regex.escape(key) + "[\"']", RegexOption.IGNORE_CASE)
        val contentRe = Regex("content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        for (m in Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            val t = m.value
            if (!keyRe.containsMatchIn(t)) continue
            val c = contentRe.find(t) ?: continue
            val v = c.groupValues[1].replace("&amp;", "&").trim()
            if (v.isNotEmpty()) return v
        }
        return null
    }

    private fun fetchMeta(articleUrl: String): Pair<String, Boolean> {
        val base = upgradeHttps(articleUrl)
        val conn = URL(base).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.setRequestProperty("User-Agent", mobileUa)
        try {
            val stream = conn.inputStream
            val buf = ByteArray(160 * 1024)
            var n = 0
            while (n < buf.size) {
                val r = stream.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
            val html = String(buf, 0, n, Charsets.UTF_8)
            var img = metaContent(html, "og:image") ?: metaContent(html, "twitter:image") ?: ""
            if (img.startsWith("//")) img = "https:" + img
            else if (img.startsWith("/")) img = try { URL(URL(base), img).toString() } catch (e: Exception) { "" }
            if (!isHttp(img)) img = ""
            val video = metaContent(html, "og:video") != null ||
                metaContent(html, "og:video:url") != null ||
                (metaContent(html, "og:type") ?: "").startsWith("video")
            return Pair(img, video)
        } finally {
            conn.disconnect()
        }
    }

    /** Looks up og:image for stories whose feed had no thumbnail, then refreshes the list. */
    private fun enrichNews(items: List<Triple<String, String, String>>, name: String) {
        val readSet = getReadArticles()
        val todo = items.filter {
            it.third.isEmpty() && isHttp(it.second) && !readSet.contains(it.second) &&
                synchronized(ogCache) { !ogCache.containsKey(it.second) }
        }.take(40)
        if (todo.isEmpty()) return
        Thread {
            val pool = Executors.newFixedThreadPool(6)
            val latch = CountDownLatch(todo.size)
            for (item in todo) {
                pool.execute {
                    try {
                        val m = fetchMeta(item.second)
                        synchronized(ogCache) {
                            ogCache[item.second] = m.first
                            if (m.second) ogVideo.add(item.second)
                        }
                    } catch (e: Exception) {
                        synchronized(ogCache) { ogCache[item.second] = "" }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(8, TimeUnit.SECONDS)
            pool.shutdownNow()
            runOnUiThread {
                if (isFinishing || isDestroyed || newsItems !== items) return@runOnUiThread
                publishNews(items)
            }
        }.start()
    }

    private fun toggleReader() {
        val t = current()
        val original = t.readerUrl
        if (original != null) {
            t.readerUrl = null
            t.web.loadUrl(original)
            return
        }
        if (!isHttp(t.url)) {
            toast("Open an article first")
            return
        }
        val pageUrl = t.url
        t.web.evaluateJavascript(ReaderMode.EXTRACT_JS) { raw ->
            val text = try {
                JSONTokener(raw ?: "null").nextValue() as? String
            } catch (e: Exception) {
                null
            }
            val obj = try {
                if (text != null) JSONObject(text) else null
            } catch (e: Exception) {
                null
            }
            if (obj == null || !obj.optBoolean("ok")) {
                toast("Couldn't find an article on this page")
            } else {
                t.readerUrl = pageUrl
                val html = ReaderMode.page(obj.optString("title"), obj.optString("site"), obj.optString("html"))
                saveOfflineArticle(pageUrl, obj.optString("title"), obj.optString("html"))
                t.web.loadDataWithBaseURL(pageUrl, html, "text/html", "utf-8", pageUrl)
            }
        }
    }

    private fun saveOfflineArticle(url: String, title: String, contentHtml: String) {
        runIo {
            val file = File(filesDir, "offline_" + url.hashCode() + ".html")
            val fullDoc = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>${esc(title)}</title></head><body><h1>${esc(title)}</h1>$contentHtml</body></html>"
            file.writeText(fullDoc)
        }
    }

    private fun getOfflineArticle(url: String): String? {
        try {
            val file = File(filesDir, "offline_" + url.hashCode() + ".html")
            if (file.exists()) return file.readText()
        } catch (e: Exception) {
        }
        return null
    }

    private fun mobileMetadata(): UserAgentMetadata {
        val major = Regex("Chrome/(\\d+)").find(mobileUa)?.groupValues?.get(1) ?: "124"
        val full = major + ".0.0.0"
        val brands = listOf(
            UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build(),
            UserAgentMetadata.BrandVersion.Builder().setBrand("Not-A.Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build(),
            UserAgentMetadata.BrandVersion.Builder().setBrand("rweb").setMajorVersion("1").setFullVersion("1.0").build()
        )
        return UserAgentMetadata.Builder()
            .setBrandVersionList(brands)
            .setFullVersion(full)
            .setPlatform("Android")
            .setPlatformVersion(Build.VERSION.RELEASE)
            .setModel(Build.MODEL)
            .setMobile(true)
            .build()
    }

    private fun desktopMetadata(): UserAgentMetadata {
        val major = Regex("Chrome/(\\d+)").find(mobileUa)?.groupValues?.get(1) ?: "124"
        val full = major + ".0.0.0"
        val brands = listOf(
            UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build(),
            UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build(),
            UserAgentMetadata.BrandVersion.Builder().setBrand("Not-A.Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build()
        )
        return UserAgentMetadata.Builder()
            .setBrandVersionList(brands)
            .setFullVersion(full)
            .setPlatform("Linux")
            .setPlatformVersion("6.5.0")
            .setArchitecture("x86")
            .setModel("")
            .setMobile(false)
            .setBitness(64)
            .build()
    }

    private fun applyClientIdentity(s: WebSettings, desktop: Boolean, touchMetadata: Boolean) {
        s.userAgentString = if (desktop) desktopUa else mobileUa
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                WebSettingsCompat.setUserAgentMetadata(s, if (desktop) desktopMetadata() else mobileMetadata())
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
            }
        } catch (e: Exception) {
        }
    }

    private val tldChoices = listOf(".com", ".in", ".org", ".net", ".co.in", ".io", ".ai", ".app", ".edu", ".gov")

    private fun defaultTld(): String {
        val t = (prefs.getString("tld", ".com") ?: ".com").trim().lowercase()
        return if (t.startsWith(".")) t else "." + t
    }

    private fun chooseTld() {
        val names = (tldChoices + "Other...").toTypedArray()
        val idx = tldChoices.indexOf(defaultTld())
        dlg()
            .setTitle("Default domain ending")
            .setSingleChoiceItems(names, idx) { d, i ->
                d.dismiss()
                if (i < tldChoices.size) {
                    prefs.edit().putString("tld", tldChoices[i]).apply()
                    toast("Default domain: " + tldChoices[i])
                } else {
                    askCustomTld()
                }
            }
            .show()
    }

    private fun askCustomTld() {
        val input = EditText(this)
        input.setSingleLine()
        input.hint = ".co.uk"
        dlg()
            .setTitle("Domain ending")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim().lowercase()
                if (v.length >= 2) {
                    prefs.edit().putString("tld", if (v.startsWith(".")) v else "." + v).apply()
                    toast("Default domain: " + defaultTld())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun searchBaseName(): String = allEngines().first { it.second == searchBase() }.first

    private fun typedSearchBases(): Triple<String, String, String> {
        val web = searchBase()
        return when {
            web.startsWith("https://duckduckgo.com") -> Triple(web, "https://duckduckgo.com/?iax=images&ia=images&q=", "https://duckduckgo.com/?iax=videos&ia=videos&q=")
            web.startsWith("https://www.bing.com") -> Triple(web, "https://www.bing.com/images/search?q=", "https://www.bing.com/videos/search?q=")
            else -> Triple(web, "https://www.google.com/search?tbm=isch&q=", "https://www.google.com/search?tbm=vid&q=")
        }
    }

    /** Page script for the start page. filterCategory lives here because scripts inserted via innerHTML never run. */
    private fun searchScript(): String {
        val b = typedSearchBases()
        return "<script>var B={web:" + JSONObject.quote(b.first) + ",img:" + JSONObject.quote(b.second) + ",vid:" + JSONObject.quote(b.third) +
            "};var cur='web';function pick(k){cur=k;['web','img','vid'].forEach(function(x){document.getElementById('c-'+x).className=(x===k)?'chip on':'chip';});" +
            "document.getElementById('q').placeholder=(k==='web')?'Search the web':(k==='img'?'Search images':'Search videos');}" +
            "function go(){var v=document.getElementById('q').value.trim();if(!v){return false;}location.href=B[cur]+encodeURIComponent(v);return false;}" +
            "function lgo(){var v=document.getElementById('lq');if(!v){return false;}v=v.value.trim();if(!v){return false;}location.href='rweb://search?q='+encodeURIComponent(v);return false;}" +
            "function filterCategory(c){document.querySelectorAll('.nw,.vc').forEach(function(e){e.style.display=(c==='All'||e.getAttribute('data-cat')===c)?'':'none';});}</script>"
    }

    private fun hideSuggestions() {
        if (::suggestBox.isInitialized) suggestBox.visibility = View.GONE
    }

    private fun openAddress(target: String) {
        hideKeyboard()
        hideSuggestions()
        val t = currentOrNull() ?: return
        navigate(t, if (isHttp(target)) target else "https://" + target)
    }

    private fun searchFor(q: String) {
        hideKeyboard()
        hideSuggestions()
        currentOrNull()?.let { navigate(it, searchBase() + Uri.encode(q)) }
    }

    private fun launchVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to search")
        try {
            voicePicker.launch(i)
        } catch (e: Exception) {
            toast("Voice search isn't available on this phone")
        }
    }

    private fun openPhotoSearch() {
        hideSuggestions()
        val url = if (searchBase().startsWith("https://www.bing.com")) "https://www.bing.com/visualsearch" else "https://lens.google.com/"
        currentOrNull()?.web?.loadUrl(url)
        toast("Choose or take a photo on the page")
    }

    private fun suggestRow(title: String, sub: String?, onClick: () -> Unit): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(10), dp(8), dp(10), dp(8))
        col.background = ripple()
        col.setOnClickListener { onClick() }
        val t = TextView(this)
        t.text = title
        t.textSize = 15f
        t.setTextColor(INK)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        col.addView(t)
        if (sub != null) {
            val st = TextView(this)
            st.text = sub
            st.textSize = 12f
            st.setTextColor(MUTE)
            st.maxLines = 1
            st.ellipsize = TextUtils.TruncateAt.END
            col.addView(st)
        }
        return col
    }

    private fun searchTypeRow(q: String): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(4), dp(6), dp(4), dp(2))
        val bases = typedSearchBases()
        fun chip(label: String, action: () -> Unit) {
            val c = TextView(this)
            c.text = label
            c.textSize = 13f
            c.setTextColor(INK)
            c.gravity = Gravity.CENTER
            val bg = GradientDrawable()
            bg.setColor(BAR)
            bg.cornerRadius = dp(16).toFloat()
            c.background = bg
            c.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(0, dp(34), 1f)
            lp.rightMargin = dp(6)
            row.addView(c, lp)
        }
        chip("Images") {
            hideKeyboard()
            hideSuggestions()
            currentOrNull()?.web?.loadUrl(bases.second + Uri.encode(q))
        }
        chip("Videos") {
            hideKeyboard()
            hideSuggestions()
            currentOrNull()?.web?.loadUrl(bases.third + Uri.encode(q))
        }
        chip("Voice") {
            hideKeyboard()
            hideSuggestions()
            launchVoice()
        }
        chip("Photo") {
            hideKeyboard()
            openPhotoSearch()
        }
        return row
    }

    private fun refreshSuggestions(text: String) {
        if (!::suggestBox.isInitialized) return
        val q = text.trim()
        if (q.isEmpty() || isHttp(q)) {
            hideSuggestions()
            return
        }
        suggestBox.removeAllViews()
        val tld = defaultTld()
        val bare = q.removePrefix("www.")
        if (Regex("^[A-Za-z0-9-]+$").matches(bare)) {
            val first = bare.lowercase() + tld
            suggestBox.addView(suggestRow("Go to " + first, "Website") { openAddress(first) })
            for (o in tldChoices.filter { it != tld }.take(4)) {
                val other = bare.lowercase() + o
                suggestBox.addView(suggestRow(other, "Other ending") { openAddress(other) })
            }
        } else if (!q.contains(' ') && q.contains('.')) {
            suggestBox.addView(suggestRow("Go to " + q, "Website") { openAddress(q) })
        }
        suggestBox.addView(suggestRow("Search the web for \u201C" + q + "\u201D", searchBaseName()) { searchFor(q) })
        suggestBox.addView(searchTypeRow(q))
        val isPrivate = currentOrNull()?.priv == true
        if (!isPrivate) {
            val hits = loadList("history").filter { it.first.contains(q, true) || it.second.contains(q, true) }.take(3)
            for (h in hits) {
                suggestBox.addView(suggestRow(if (h.first.isBlank()) h.second else h.first, h.second) { openAddress(h.second) })
            }
        }
        suggestBox.visibility = View.VISIBLE
    }

    private fun openVrViewer() {
        newTab(null)
        val t = currentOrNull() ?: return
        t.title = "360 viewer"
        t.web.loadDataWithBaseURL("https://rweb.local/", VrViewer.HTML, "text/html", "utf-8", null)
    }

    private fun setBarsHidden(hidden: Boolean) {
        barsHidden = hidden
        topBar.visibility = if (hidden) View.GONE else View.VISIBLE
        islandWrap.visibility = if (hidden) View.GONE else View.VISIBLE
        floatMenu.visibility = if (hidden) View.VISIBLE else View.GONE
    }

    private fun letterIcon(letter: String): Icon {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = BAR
        c.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = INK
        paint.textSize = 100f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        c.drawText(letter, size / 2f, size / 2f + 35f, paint)
        return Icon.createWithBitmap(bmp)
    }

    private fun addToHomeScreen() {
        val t = current()
        val host = hostOf(t.url)
        if (t.priv || host == null) {
            toast("Open a website in a normal tab first")
            return
        }
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            toast("Your launcher doesn't support home screen shortcuts")
            return
        }
        val label = (if (t.title.isBlank()) host else t.title).take(24)
        val launch = Intent(this, MainActivity::class.java)
        launch.action = Intent.ACTION_VIEW
        launch.data = Uri.parse(t.url)
        launch.putExtra("app_mode", true)
        val info = ShortcutInfo.Builder(this, "site-" + host)
            .setShortLabel(label)
            .setIcon(letterIcon(host.removePrefix("www.").take(1).uppercase()))
            .setIntent(launch)
            .build()
        sm.requestPinShortcut(info, null)
    }

    private fun loadAdBlockSettings() {
        adBlockOn = prefs.getBoolean("adblock", true)
        pausedHosts = prefs.getStringSet("adblock_paused", emptySet())?.toSet() ?: emptySet()
    }

    private fun hostOf(url: String): String? = if (isHttp(url)) Uri.parse(url).host?.lowercase() else null

    private fun reloadCurrent() {
        val t = currentOrNull() ?: return
        if (t.url.isEmpty()) loadStart(t.web) else t.web.reload()
    }

    private fun toggleAdBlock() {
        adBlockOn = !adBlockOn
        prefs.edit().putBoolean("adblock", adBlockOn).apply()
        reloadCurrent()
        toast(if (adBlockOn) "Ad and tracker blocking on" else "Ad and tracker blocking off")
    }

    private fun togglePauseHere() {
        val host = hostOf(current().url)
        if (host == null) {
            toast("Open a website first")
            return
        }
        val set = pausedHosts.toMutableSet()
        val paused = if (set.contains(host)) {
            set.remove(host)
            false
        } else {
            set.add(host)
            true
        }
        prefs.edit().putStringSet("adblock_paused", set).apply()
        pausedHosts = set
        reloadCurrent()
        toast(if (paused) "Blocking paused on " + host else "Blocking active on " + host)
    }

    // --- Custom Feed Manager Dialogs ---
    private fun showCustomFeedManager(isVideos: Boolean) {
        val key = if (isVideos) "custom_video_feeds" else "custom_news_feeds"
        val title = if (isVideos) "Manage Custom Video Channels" else "Manage Custom News Feeds"

        val feeds = getCustomFeeds(key).toMutableList()
        val labels = feeds.map { "${it.first}\n${it.second}" }.toMutableList()
        labels.add("+ Add New Feed")

        dlg()
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == feeds.size) {
                    showAddFeedDialog(isVideos)
                } else {
                    val selected = feeds[which]
                    dlg()
                        .setTitle("Delete feed?")
                        .setMessage(selected.first)
                        .setPositiveButton("Delete") { _, _ ->
                            feeds.removeAt(which)
                            saveCustomFeeds(key, feeds)
                            toast("Feed removed")
                            if (isVideos) refreshVideos() else refreshNews()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAddFeedDialog(isVideos: Boolean) {
        val key = if (isVideos) "custom_video_feeds" else "custom_news_feeds"
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(20), dp(10), dp(20), dp(10))

        val nameInput = EditText(this)
        nameInput.hint = "Feed or Channel Name"
        nameInput.setSingleLine()
        layout.addView(nameInput)

        val urlInput = EditText(this)
        urlInput.hint = "RSS or YouTube Feed URL"
        urlInput.setSingleLine()
        layout.addView(urlInput)

        dlg()
            .setTitle(if (isVideos) "Add Video Channel" else "Add News Feed")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    toast("Fields cannot be empty")
                } else if (!isHttp(url)) {
                    toast("The URL must start with http:// or https://")
                } else {
                    val feeds = getCustomFeeds(key).toMutableList()
                    feeds.add(Pair(name, url))
                    saveCustomFeeds(key, feeds)
                    toast("Feed added successfully")
                    if (isVideos) refreshVideos() else refreshNews()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= on-device search: homepage box, results page, crawling, indexing =================

    private fun indexVisited(): Boolean = prefs.getBoolean("index_visited", true)
    private fun wifiOnly(): Boolean = prefs.getBoolean("crawl_wifi", true)

    private val defaultCrawlSeeds = listOf(
        "https://en.wikipedia.org/wiki/Main_Page",
        "https://developer.mozilla.org/en-US/",
        "https://www.bbc.com/news",
        "https://www.theguardian.com/international",
        "https://www.nature.com/",
        "https://www.nasa.gov/"
    )

    private fun crawlSeeds(): List<String> {
        val saved = prefs.getString("crawl_seeds", null) ?: return defaultCrawlSeeds
        return saved.lines().map { it.trim() }.filter { isHttp(it) }
    }

    private fun localBoxHtml(): String {
        val n = localSearch.count()
        val hint = if (n == 0) "Search with rweb (empty index: menu > Search index)" else "Search with rweb \u00B7 " + n + " pages" + (if (serverApiBase() != null) " + your server" else "")
        return "<form class=\"lf\" onsubmit=\"return lgo()\"><input id=\"lq\" placeholder=\"" + esc(hint) + "\" autocomplete=\"off\"></form>"
    }

    private val localCss = """
        :root{--bg:#fff;--fg:#202124;--mute:#5f6368;--card:#f8f9fa;--fld:#f1f3f4;--acc:#c41e3a;--link:#1a0dab}
        html.dark{--bg:#3a0a14;--fg:#fff1f3;--mute:#e3a9b1;--card:#7a1022;--fld:#7a1022;--acc:#ff6b7d;--link:#ffb3be}
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font-family:sans-serif;line-height:1.45}
        a{color:var(--link);text-decoration:none}
        header{display:flex;gap:12px;align-items:center;padding:12px 14px;position:sticky;top:0;background:var(--bg)}
        .lg{color:var(--acc);font-weight:700;font-size:22px}header form{flex:1;margin:0}
        input{width:100%;padding:12px 18px;border-radius:26px;border:2px solid var(--acc);background:var(--fld);color:var(--fg);font-size:16px;outline:none}
        main{padding:4px 16px 40px;max-width:720px;margin:0 auto}.info{color:var(--mute);font-size:13px;margin:6px 0 14px}
        .r{margin:0 0 20px}.u{color:var(--mute);font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .t{font-size:18px;display:block;margin:2px 0}.s{font-size:14px;word-wrap:break-word}mark{background:none;color:var(--acc);font-weight:600}
        .web{margin:22px 0 8px}.web a{display:block;padding:12px 16px;border-radius:18px;background:var(--fld);margin-bottom:8px;color:var(--fg)}
        .top{display:block;padding:14px 16px;border-radius:18px;background:var(--card);margin:0 0 18px;border:2px solid var(--acc);color:var(--fg)}.top b{display:block;font-size:19px;margin:2px 0;color:var(--link)}.tl{color:var(--acc);font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.06em}.src{color:var(--acc)}.none{margin-top:36px;text-align:center;color:var(--mute)}.none a{display:inline-block;margin-top:14px;padding:10px 20px;border-radius:20px;background:var(--acc);color:#fff}
    """

    private class SrcHit(val hit: LocalSearch.Hit, val source: String)

    private fun serverApiBase(): String? = rwebSearchBase()?.substringBefore("/search?q=")?.takeIf { isHttp(it) }

    /** Results from your rweb-search server (empty when none is set up or it is unreachable). */
    private fun fetchServerHits(q: String): List<LocalSearch.Hit> {
        val base = serverApiBase() ?: return emptyList()
        val out = ArrayList<LocalSearch.Hit>()
        try {
            val c = URL(base + "/api/search?n=10&q=" + Uri.encode(q)).openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.setRequestProperty("User-Agent", "rweb-browser")
            try {
                if (c.responseCode in 200..299) {
                    val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val arr = JSONObject(body).getJSONArray("results")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        out.add(LocalSearch.Hit(o.optString("title"), o.getString("url"), o.optString("snippet")))
                    }
                }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun localResultsHtml(q: String, merged: List<SrcHit>, direct: LocalSearch.Hit?, indexSize: Int, serverOn: Boolean): String {
        val sb = StringBuilder()
        sb.append("<!doctype html><html").append(if (isDark()) " class=\"dark\"" else "").append("><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>").append(localCss).append("</style></head><body>")
        sb.append("<header><a class=\"lg\" href=\"rweb://home\">rweb</a>")
        sb.append("<form onsubmit=\"var v=this.q.value.trim();if(v){location.href='rweb://search?q='+encodeURIComponent(v);}return false\">")
        sb.append("<input name=\"q\" value=\"").append(esc(q)).append("\" autocomplete=\"off\"></form></header><main>")

        if (direct != null) {
            sb.append("<a class=\"top\" href=\"").append(esc(direct.url)).append("\"><span class=\"tl\">Top result</span><b>")
            sb.append(esc(direct.title.ifBlank { direct.url })).append("</b><span class=\"u\">").append(esc(direct.url)).append("</span></a>")
        }
        val rest = merged.filter { it.hit.url != direct?.url }
        if (rest.isEmpty() && direct == null) {
            if (indexSize == 0 && !serverOn) {
                sb.append("<div class=\"none\">Your index is empty.<br>Crawl your favourite sites or just browse: pages you visit are added.<br>")
                sb.append("<a href=\"rweb://crawl\">Build my index</a></div>")
            } else {
                sb.append("<div class=\"none\">Nothing found for <b>").append(esc(q)).append("</b>.<br>Try the web search below.</div>")
            }
        } else {
            sb.append("<div class=\"info\">").append(rest.size + (if (direct != null) 1 else 0)).append(" results \u00B7 ")
            sb.append(indexSize).append(" pages on this phone").append(if (serverOn) " + your server" else "").append("</div>")
            for (m in rest) {
                val snip = esc(m.hit.snippet).replace(LocalSearch.HL_OPEN, "<mark>").replace(LocalSearch.HL_CLOSE, "</mark>")
                sb.append("<div class=\"r\"><div class=\"u\">").append(esc(m.hit.url)).append("<span class=\"src\"> \u00B7 ").append(esc(m.source)).append("</span></div>")
                sb.append("<a class=\"t\" href=\"").append(esc(m.hit.url)).append("\">").append(esc(m.hit.title.ifBlank { m.hit.url })).append("</a>")
                sb.append("<div class=\"s\">").append(snip).append("</div></div>")
            }
        }
        val bases = typedSearchBases()
        sb.append("<div class=\"web\">")
        sb.append("<a href=\"").append(esc(engines0() + Uri.encode(q))).append("\">Search the web for \u201C").append(esc(q)).append("\u201D</a>")
        sb.append("<a href=\"").append(esc(bases.second + Uri.encode(q))).append("\">Images</a></div></main></body></html>")
        return sb.toString()
    }

    /** Google web-search URL prefix, used for the "Search the web" fallback. */
    private fun engines0(): String = baseEngines[0].second

    private fun openExact(): Boolean = prefs.getBoolean("open_exact", false)
    private fun crawlBudget(): Int = prefs.getInt("crawl_pages", 150)
    private val crawlSizes = listOf(150, 500, 1500)

    /** rweb Search: your phone's index and your own server, side by side, plus a Top result for exact matches. */
    private fun runLocalSearch(tab: Tab, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        Thread {
            val remote = java.util.concurrent.atomic.AtomicReference<List<LocalSearch.Hit>>(emptyList())
            val rt = Thread { remote.set(fetchServerHits(q)) }
            rt.start()
            val size = localSearch.count()
            val local: List<LocalSearch.Hit> = try {
                localSearch.search(q, 20).hits
            } catch (e: Exception) {
                emptyList()
            }
            rt.join(4500)
            val r = remote.get()

            val merged = ArrayList<SrcHit>()
            val seen = HashSet<String>()
            for (i in 0 until maxOf(local.size, r.size)) { // alternate: phone, server, phone, server...
                if (i < local.size && seen.add(local[i].url.trimEnd('/'))) merged.add(SrcHit(local[i], "on this phone"))
                if (i < r.size && seen.add(r[i].url.trimEnd('/'))) merged.add(SrcHit(r[i], "your server"))
            }
            val direct = LocalSearch.directMatch(q, merged.map { it.hit })

            runOnUiThread {
                if (isFinishing || isDestroyed || tabs.indexOf(tab) < 0) return@runOnUiThread
                if (direct != null && openExact()) {
                    navigate(tab, direct.url)
                    return@runOnUiThread
                }
                tab.web.loadDataWithBaseURL(
                    "https://rweb.local/", localResultsHtml(q, merged, direct, size, serverApiBase() != null), "text/html", "utf-8", null
                )
            }
        }.start()
    }

    // ---- indexing the pages you visit (normal tabs only) ----

    private val noIndexHosts = setOf(
        "mail.google.com", "web.whatsapp.com", "accounts.google.com", "outlook.live.com", "outlook.office.com",
        "drive.google.com", "docs.google.com", "calendar.google.com"
    )
    private val noIndexUrl = Regex("(login|log-in|signin|sign-in|signup|sign-up|auth|account|checkout|payment|password|banking)", RegexOption.IGNORE_CASE)

    private val indexJs = """
        (function(){try{
          if(document.querySelector('input[type=password]'))return '';
          var r=document.querySelector('meta[name=robots]');if(r&&/noindex/i.test(r.content))return '';
          var d=document.querySelector('meta[name=description]');
          return JSON.stringify({t:document.title||'',d:(d&&d.content)||'',x:((document.body&&document.body.innerText)||'').substring(0,30000)});
        }catch(e){return ''}})()
    """

    private fun indexCurrentPage(view: WebView?, url: String) {
        val web = view ?: return
        val host = hostOf(url) ?: return
        if (noIndexHosts.any { host == it || host.endsWith(".$it") } || noIndexUrl.containsMatchIn(url)) return
        web.evaluateJavascript(indexJs) { raw ->
            val text = try {
                JSONTokener(raw ?: "null").nextValue() as? String
            } catch (e: Exception) {
                null
            }
            if (!text.isNullOrEmpty()) {
                try {
                    val o = JSONObject(text)
                    localSearch.index(url, o.optString("t"), o.optString("d") + " " + o.optString("x"))
                } catch (e: Exception) {
                }
            }
        }
    }

    // ---- crawling ----

    private fun isMetered(): Boolean = try {
        (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
    } catch (e: Exception) {
        false // no permission or no service: do not block
    }

    private fun startCrawl() {
        if (crawler.running) {
            toast("Already crawling")
            return
        }
        if (wifiOnly() && isMetered()) {
            toast("Connect to Wi-Fi, or turn off \"Crawl on Wi-Fi only\"")
            return
        }
        val seeds = (crawlSeeds() + loadList("bookmarks").map { it.second }).distinct()
        if (seeds.isEmpty()) {
            toast("Add some crawl sites first")
            return
        }
        crawlStatus = "starting"
        toast("Crawling in the background. Keep rweb open.")
        Thread {
            val n = try {
                crawler.crawl(seeds, crawlBudget(), 2, maxOf(60, crawlBudget() / 5), 1000L) { count -> crawlStatus = count.toString() + " pages" }
            } catch (e: Exception) {
                0
            }
            runOnUiThread {
                crawlStatus = ""
                if (!isFinishing && !isDestroyed) toast("Indexed " + n + " new pages")
            }
        }.start()
    }

    private fun askCrawlSites() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
        input.minLines = 6
        input.gravity = Gravity.TOP
        input.setText(crawlSeeds().joinToString("\n"))
        dlg()
            .setTitle("Sites to crawl")
            .setMessage("One address per line. Your bookmarks are crawled too.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val lines = input.text.toString().lines().map { it.trim() }.filter { isHttp(it) }
                prefs.edit().putString("crawl_seeds", lines.joinToString("\n")).apply()
                toast(lines.size.toString() + " sites saved")
            }
            .setNeutralButton("Reset") { _, _ -> prefs.edit().remove("crawl_seeds").apply() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSearchIndexMenu() {
        val n = localSearch.count()
        val running = crawler.running
        val labels = arrayOf(
            if (running) "Stop crawling (" + crawlStatus + ")" else "Crawl now",
            "Edit sites to crawl",
            "Index pages I visit: " + (if (indexVisited()) "On" else "Off"),
            "Crawl on Wi-Fi only: " + (if (wifiOnly()) "On" else "Off"),
            "Crawl size: " + crawlBudget() + " pages per run",
            "Open exact matches directly: " + (if (openExact()) "On" else "Off"),
            "Clear search index (" + n + " pages)"
        )
        dlg()
            .setTitle("Search index \u00B7 " + n + " pages")
            .setItems(labels) { _, i ->
                when (i) {
                    0 -> if (running) {
                        crawler.stopRequested = true
                        toast("Stopping\u2026")
                    } else {
                        startCrawl()
                    }
                    1 -> askCrawlSites()
                    2 -> {
                        prefs.edit().putBoolean("index_visited", !indexVisited()).apply()
                        toast(if (indexVisited()) "Visited pages will be indexed" else "Visited pages are no longer indexed")
                    }
                    3 -> prefs.edit().putBoolean("crawl_wifi", !wifiOnly()).apply()
                    4 -> {
                        val next = crawlSizes[(crawlSizes.indexOf(crawlBudget()) + 1) % crawlSizes.size]
                        prefs.edit().putInt("crawl_pages", next).apply()
                        toast("Crawl size: " + next + " pages per run")
                    }
                    5 -> {
                        prefs.edit().putBoolean("open_exact", !openExact()).apply()
                        toast(if (openExact()) "Exact matches open directly" else "Exact matches show as Top result")
                    }
                    6 -> confirm("Clear search index?", "This deletes everything rweb has indexed. Your history and bookmarks stay.") {
                        localSearch.clear()
                        toast("Search index cleared")
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

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

    private val listCache = HashMap<String, List<Pair<String, String>>>()

    private fun parseList(key: String): List<Pair<String, String>> {
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

    private fun loadList(key: String): MutableList<Pair<String, String>> =
        listCache.getOrPut(key) { parseList(key) }.toMutableList()

    private fun saveList(key: String, list: List<Pair<String, String>>) {
        listCache[key] = list.toList()
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
        val t = currentOrNull() ?: return
        val rows = listOf(
            MenuRow("Bookmarks", null, false) { showList("Bookmarks", "bookmarks") },
            MenuRow("History", null, false) { showList("History", "history") },
            MenuRow("Downloads", null, false) { showDownloads() },
            MenuRow("Reading mode", if (t.readerUrl != null) "On" else "Off", false) { toggleReader() },
            MenuRow("360 / VR video viewer", null, false) { openVrViewer() },
            MenuRow("Find in page", null, false) { findInPage() },
            MenuRow("Search engine", allEngines().first { it.second == searchBase() }.first, false) { chooseEngine() },
            MenuRow("Search index", localSearch.count().toString() + " pages", false) { showSearchIndexMenu() },
            MenuRow("Default domain ending", defaultTld(), false) { chooseTld() },
            MenuRow("Homepage news", if (prefs.getBoolean("news_open", true)) "Expanded" else "Collapsed", false) { toggleNewsFold() },
            MenuRow("Homepage theme", if (isDark()) "Dark" else "Light", false) { toggleHomeTheme() },
            MenuRow("Homepage site icons", if (siteIcons()) "On" else "Off", false) { toggleIcons() },
            MenuRow("Third-party cookies", if (thirdPartyCookies()) "Allowed" else "Blocked", false) { toggleThirdPartyCookies() },
            MenuRow("Ad & tracker blocking", if (adBlockOn) "On \u00B7 " + t.blocked.get() + " blocked" else "Off", false) { toggleAdBlock() },
            MenuRow("Blocking on this site", if (hostOf(t.url)?.let { pausedHosts.contains(it) } == true) "Paused" else "Active", false) { togglePauseHere() },
            MenuRow("Desktop site", if (t.desktop) "On" else "Off", false) { toggleDesktop() },
            MenuRow("Manage custom video channels", null, false) { showCustomFeedManager(true) },
            MenuRow("Manage custom news feeds", null, false) { showCustomFeedManager(false) },
            MenuRow("Toolbars", if (barsHidden) "Hidden" else "Visible", false) { setBarsHidden(!barsHidden) },
            MenuRow("Add to home screen", null, false) { addToHomeScreen() },
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
        applyClientIdentity(t.web.settings, t.desktop, true)
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
        runIo {
            File(filesDir, "tabs.json").delete()
            File(filesDir, "tabs.bin").delete()
            File(filesDir, "vids_cache.json").delete()
            File(filesDir, "news_cache.json").delete()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        GeolocationPermissions.getInstance().clearAll()
        saveList("history", emptyList())
        localSearch.clear()
        toast("Browsing data cleared")
    }
}
