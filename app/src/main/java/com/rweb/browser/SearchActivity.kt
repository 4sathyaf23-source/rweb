package com.example.yourapp // TODO: change to your project's package name

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Patterns
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONTokener

/**
 * Browser + self-learning search.
 *
 * Flow:
 *  1. You type. Results from YOUR index (pages you've visited) appear as you type.
 *  2. Press search. If your index has matches, you see them with a "Search the web" row at the bottom.
 *     If it has none, the query goes straight to the web engine.
 *  3. Every page you open is indexed, and the query that led you there is remembered,
 *     so next time the same kind of search is answered locally (and ranked by what you chose).
 *
 * Needs PersonalSearchEngine.kt in the same package.
 * Manifest: <uses-permission android:name="android.permission.INTERNET" />
 */
class SearchActivity : AppCompatActivity() {

    companion object {
        // Web fallback engine. The query is appended to the end.
        private const val SEARCH_URL = "https://duckduckgo.com/?q="
        private const val HOME_URL = "https://duckduckgo.com/"
        private const val MAX_LOCAL_RESULTS = 8
        private const val LIVE_SEARCH_DELAY_MS = 150L
        private const val INDEX_DELAY_MS = 1200L
    }

    private data class Row(val title: String, val subtitle: String, val url: String?, val isWeb: Boolean)

    private lateinit var engine: PersonalSearchEngine
    private lateinit var rootView: LinearLayout
    private lateinit var webView: WebView
    private lateinit var searchBox: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var resultsList: ListView
    private val adapter = ResultsAdapter()
    private val ui = Handler(Looper.getMainLooper())

    private val density get() = resources.displayMetrics.density
    private val searchHost: String by lazy {
        Uri.parse(SEARCH_URL).host.orEmpty().removePrefix("www.")
    }

    // Search / learning state
    private var searchSeq = 0
    private var displayedQuery = ""
    private var liveRunnable: Runnable? = null
    private var pendingWebQuery: String? = null     // query that sent us to the web engine
    private var nextLoadFromSearch = false          // next page was opened from local results

    // Dwell-time tracking
    private var currentUrl: String? = null
    private var pageShownAt = 0L
    private var currentFromSearch = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                resultsList.visibility == View.VISIBLE -> hideResults()
                webView.canGoBack() -> webView.goBack()
            }
            updateBack()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = PersonalSearchEngine.get(this)
        engine.pruneAsync()

        buildUi()
        setupWebView()
        setupSearchBox()

        onBackPressedDispatcher.addCallback(this, backCallback)

        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else webView.loadUrl(HOME_URL)

        engine.statsAsync { s ->
            searchBox.hint = if (s.pages > 0) "Search ${s.pages} saved pages or the web" else "Search or enter address"
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val pad = (8 * density).toInt()

        searchBox = EditText(this).apply {
            hint = "Search or enter address"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(pad * 2, pad, pad * 2, pad)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        webView = WebView(this)

        val bg = TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, bg, true)
        resultsList = ListView(this).apply {
            setBackgroundColor(bg.data)
            visibility = View.GONE
            this.adapter = this@SearchActivity.adapter
        }

        val content = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(-1, -1))
            addView(resultsList, FrameLayout.LayoutParams(-1, -1))
        }

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            isFocusableInTouchMode = true
            addView(
                searchBox,
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(pad, pad, pad, 0) }
            )
            addView(progressBar, LinearLayout.LayoutParams(-1, (4 * density).toInt()))
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(rootView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                flushDwell()
                progressBar.visibility = View.VISIBLE
                if (!searchBox.hasFocus() && url != null) searchBox.setText(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progressBar.visibility = View.GONE
                if (url != null) handlePageFinished(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                updateBack()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
            }
        }
    }

    private fun setupSearchBox() {
        searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(v.text.toString())
                true
            } else {
                false
            }
        }

        searchBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) searchBox.post { searchBox.selectAll() }
        }

        // Search-as-you-type against your own index (not logged as a query).
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!searchBox.hasFocus()) return
                liveRunnable?.let { ui.removeCallbacks(it) }
                val q = s?.toString()?.trim().orEmpty()
                if (q.length < 2 || looksLikeUrl(q)) {
                    hideResults()
                    return
                }
                liveRunnable = Runnable { showResults(q, log = false, goWebIfEmpty = false) }
                    .also { ui.postDelayed(it, LIVE_SEARCH_DELAY_MS) }
            }
        })

        resultsList.setOnItemClickListener { _, _, position, _ ->
            val row = adapter.rows[position]
            val query = displayedQuery
            finishSearchUi()
            if (row.isWeb) {
                searchWeb(query)
            } else if (row.url != null) {
                engine.recordClick(query, row.url)   // learn: you chose this page for this query
                nextLoadFromSearch = true
                pendingWebQuery = null
                webView.loadUrl(row.url)
            }
        }
    }

    // ------------------------------------------------------------------ searching

    private fun performSearch(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        liveRunnable?.let { ui.removeCallbacks(it) }
        pendingWebQuery = null
        nextLoadFromSearch = false

        if (looksLikeUrl(text)) {
            finishSearchUi()
            webView.loadUrl(if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text")
            return
        }
        hideKeyboard()
        showResults(text, log = true, goWebIfEmpty = true)
    }

    private fun showResults(query: String, log: Boolean, goWebIfEmpty: Boolean) {
        val seq = ++searchSeq
        engine.searchAsync(query, MAX_LOCAL_RESULTS, log) { results ->
            if (seq != searchSeq) return@searchAsync // a newer search replaced this one
            if (results.isEmpty()) {
                if (goWebIfEmpty) {
                    finishSearchUi()
                    searchWeb(query)
                } else {
                    hideResults()
                }
                return@searchAsync
            }
            val rows = results.map {
                val host = Uri.parse(it.url).host.orEmpty().removePrefix("www.")
                Row(it.title, listOf(host, it.snippet).filter { s -> s.isNotBlank() }.joinToString(" - "), it.url, false)
            } + Row("Search the web for \"$query\"", "Results from $searchHost", null, true)

            displayedQuery = query
            adapter.rows = rows
            adapter.notifyDataSetChanged()
            resultsList.setSelection(0)
            resultsList.visibility = View.VISIBLE
            updateBack()
        }
    }

    private fun searchWeb(query: String) {
        pendingWebQuery = query          // the first page you open from the web results gets learned
        nextLoadFromSearch = false
        webView.loadUrl(SEARCH_URL + Uri.encode(query))
    }

    private fun finishSearchUi() {
        hideResults()
        hideKeyboard()
        rootView.requestFocus()
    }

    private fun hideResults() {
        resultsList.visibility = View.GONE
        updateBack()
    }

    private fun updateBack() {
        backCallback.isEnabled = resultsList.visibility == View.VISIBLE || webView.canGoBack()
    }

    // ------------------------------------------------------------------ learning hooks

    private fun handlePageFinished(url: String) {
        val host = Uri.parse(url).host.orEmpty().lowercase().removePrefix("www.")
        // Skip the search engine's own result pages (and its redirect pages).
        if (host == searchHost || host.endsWith(".$searchHost")) return

        val fromQuery = pendingWebQuery
        val fromSearch = nextLoadFromSearch || fromQuery != null
        pendingWebQuery = null
        nextLoadFromSearch = false

        flushDwell()
        currentUrl = url
        pageShownAt = System.currentTimeMillis()
        currentFromSearch = fromSearch

        // Give JS-heavy pages a moment to render, then index the visible text.
        webView.postDelayed({
            if (webView.url == url) indexPage(url, fromQuery)
        }, INDEX_DELAY_MS)
    }

    private fun indexPage(url: String, fromQuery: String?) {
        val js = "(function(){return document.body ? document.body.innerText.substring(0, 20000) : '';})()"
        webView.evaluateJavascript(js) { raw ->
            engine.recordVisit(url, webView.title.orEmpty(), decodeJsString(raw), fromQuery)
        }
    }

    private fun flushDwell() {
        val url = currentUrl ?: return
        if (pageShownAt == 0L) return
        engine.recordDwell(url, System.currentTimeMillis() - pageShownAt, currentFromSearch)
        pageShownAt = 0L
        currentFromSearch = false
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return try {
            JSONTokener(raw).nextValue() as? String ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun looksLikeUrl(text: String): Boolean =
        !text.contains(" ") && Patterns.WEB_URL.matcher(text).matches()

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    private inner class ResultsAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val container = (convertView as? LinearLayout) ?: LinearLayout(this@SearchActivity).apply {
                orientation = LinearLayout.VERTICAL
                val h = (16 * density).toInt()
                val v = (10 * density).toInt()
                setPadding(h, v, h, v)
                addView(TextView(context).apply {
                    textSize = 16f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    textSize = 12f
                    maxLines = 2
                    alpha = 0.7f
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            val title = container.getChildAt(0) as TextView
            val subtitle = container.getChildAt(1) as TextView
            title.text = row.title
            title.setTypeface(null, if (row.isWeb) Typeface.BOLD else Typeface.NORMAL)
            subtitle.text = row.subtitle
            return container
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        flushDwell()
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (currentUrl != null) pageShownAt = System.currentTimeMillis()
    }

    override fun onDestroy() {
        flushDwell()
        ui.removeCallbacksAndMessages(null)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
