package com.rweb.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

data class SearchResult(
    val pageId: Long,
    val url: String,
    val title: String,
    val snippet: String,
    val score: Double
)

data class EngineStats(val pages: Int, val queries: Int, val localAnswerPercent: Int)

/**
 * A personal search engine that lives on the device and learns from how you browse.
 *
 * What it learns from:
 *  - Pages you visit (title + text are indexed with SQLite FTS4 full-text search)
 *  - Which result you open for which query (query -> page associations)
 *  - How long you stay on a page (dwell time) and quick "bounces"
 *  - How often and how recently you visit a page
 *
 * No extra Gradle dependencies: it uses Android's built-in SQLite.
 * All data stays on the device. Call clearAll() to wipe it.
 *
 * Use: val engine = PersonalSearchEngine.get(context)
 */
class PersonalSearchEngine private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "personal_search.db", null, 1) {

    companion object {
        @Volatile
        private var instance: PersonalSearchEngine? = null

        fun get(context: Context): PersonalSearchEngine =
            instance ?: synchronized(this) {
                instance ?: PersonalSearchEngine(context).also { instance = it }
            }

        private const val MAX_CONTENT = 20_000        // chars of page text kept per page
        private const val SUMMARY_LEN = 220
        private const val MAX_LEARNED = 2_000         // chars of learned query text per page
        private const val REVISIT_GAP_MS = 30_000L    // don't count reloads within 30s as new visits
        private const val BOUNCE_MS = 8_000L          // left a clicked result in under 8s = bounce
        private const val PRUNE_AFTER_MS = 90L * 24 * 60 * 60 * 1000

        // Pages are NOT indexed if the host or path contains one of these (privacy).
        // Add your own: banks, email, work tools, etc.
        private val BLOCKED_HOST_WORDS = listOf(
            "bank", "paypal", "accounts.google", "login", "signin", "auth", "mail.", "webmail"
        )
        private val BLOCKED_PATH_WORDS = listOf(
            "login", "signin", "sign-in", "logout", "checkout", "payment", "password", "oauth", "account"
        )
        private val TRACKING_PARAMS = setOf("fbclid", "gclid", "ref", "ref_src", "mc_cid", "mc_eid", "igshid")
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val splitter = Regex("[^\\p{L}\\p{Nd}]+")

    // ------------------------------------------------------------------ database

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE pages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                title TEXT,
                summary TEXT,
                visit_count INTEGER NOT NULL DEFAULT 0,
                click_count INTEGER NOT NULL DEFAULT 0,
                bounce_count INTEGER NOT NULL DEFAULT 0,
                total_dwell_ms INTEGER NOT NULL DEFAULT 0,
                first_visit INTEGER NOT NULL,
                last_visit INTEGER NOT NULL
            )"""
        )
        // Full-text index. "learned" holds past queries that led you to this page,
        // so similar future searches find it even if the words aren't on the page.
        db.execSQL("CREATE VIRTUAL TABLE pages_fts USING fts4(title, content, learned, tokenize=unicode61)")
        db.execSQL(
            """CREATE TABLE query_clicks(
                query TEXT NOT NULL,
                page_id INTEGER NOT NULL,
                clicks INTEGER NOT NULL DEFAULT 0,
                last_click INTEGER NOT NULL,
                PRIMARY KEY(query, page_id)
            )"""
        )
        db.execSQL(
            """CREATE TABLE query_log(
                query TEXT PRIMARY KEY,
                count INTEGER NOT NULL DEFAULT 0,
                local_hits INTEGER NOT NULL DEFAULT 0,
                last_used INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Add migrations here when you change the schema.
    }

    // ------------------------------------------------------------------ learning (write side)

    /**
     * Call when a page finishes loading. [fromQuery] is the search query that led here, if any;
     * this is how the engine learns from web-search fallbacks.
     */
    fun recordVisit(url: String, title: String, text: String, fromQuery: String? = null) {
        val u = normalizeUrl(url) ?: return
        if (!shouldIndex(u) || text.length < 50) return
        val body = text.take(MAX_CONTENT)
        val cleanTitle = title.trim().ifEmpty { u }
        val summary = body.replace(Regex("\\s+"), " ").take(SUMMARY_LEN)
        io.execute {
            try {
                upsertPage(u, cleanTitle, summary, body, fromQuery)
            } catch (_: Exception) {
            }
        }
    }

    /** Call when the user opens a result from your own index. */
    fun recordClick(query: String, url: String) {
        val u = normalizeUrl(url) ?: return
        io.execute {
            try {
                val db = writableDatabase
                db.beginTransaction()
                try {
                    val id = pageId(db, u)
                    if (id >= 0) applyClick(db, id, query)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Call when the user leaves a page / app is paused. [fromSearch] = page was opened from results. */
    fun recordDwell(url: String, dwellMs: Long, fromSearch: Boolean) {
        val u = normalizeUrl(url) ?: return
        if (dwellMs <= 0) return
        io.execute {
            try {
                val bounce = if (fromSearch && dwellMs < BOUNCE_MS) 1 else 0
                writableDatabase.execSQL(
                    "UPDATE pages SET total_dwell_ms = total_dwell_ms + ?, bounce_count = bounce_count + ? WHERE url = ?",
                    arrayOf<Any>(dwellMs, bounce, u)
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun upsertPage(u: String, title: String, summary: String, body: String, fromQuery: String?) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            var id = -1L
            var last = 0L
            db.rawQuery("SELECT id, last_visit FROM pages WHERE url = ?", arrayOf(u)).use {
                if (it.moveToFirst()) {
                    id = it.getLong(0)
                    last = it.getLong(1)
                }
            }
            if (id < 0) {
                val values = ContentValues().apply {
                    put("url", u)
                    put("title", title)
                    put("summary", summary)
                    put("visit_count", 1)
                    put("first_visit", now)
                    put("last_visit", now)
                }
                id = db.insert("pages", null, values)
                db.execSQL(
                    "INSERT INTO pages_fts(docid, title, content, learned) VALUES (?, ?, ?, '')",
                    arrayOf<Any>(id, title, body)
                )
            } else {
                val bump = if (now - last > REVISIT_GAP_MS) 1 else 0
                db.execSQL(
                    "UPDATE pages SET title = ?, summary = ?, visit_count = visit_count + ?, last_visit = ? WHERE id = ?",
                    arrayOf<Any>(title, summary, bump, now, id)
                )
                db.execSQL(
                    "UPDATE pages_fts SET title = ?, content = ? WHERE docid = ?",
                    arrayOf<Any>(title, body, id)
                )
            }
            if (!fromQuery.isNullOrBlank()) applyClick(db, id, fromQuery)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun applyClick(db: SQLiteDatabase, pageId: Long, query: String) {
        val qNorm = normalizeQuery(query)
        if (qNorm.isEmpty()) return
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT OR IGNORE INTO query_clicks(query, page_id, clicks, last_click) VALUES (?, ?, 0, ?)",
            arrayOf<Any>(qNorm, pageId, now)
        )
        db.execSQL(
            "UPDATE query_clicks SET clicks = clicks + 1, last_click = ? WHERE query = ? AND page_id = ?",
            arrayOf<Any>(now, qNorm, pageId)
        )
        db.execSQL("UPDATE pages SET click_count = click_count + 1 WHERE id = ?", arrayOf<Any>(pageId))

        // Teach the full-text index: this page is relevant to these words.
        var learned = ""
        db.rawQuery("SELECT learned FROM pages_fts WHERE docid = ?", arrayOf(pageId.toString())).use {
            if (it.moveToFirst()) learned = it.getString(0) ?: ""
        }
        if (!learned.contains(qNorm) && learned.length < MAX_LEARNED) {
            db.execSQL(
                "UPDATE pages_fts SET learned = ? WHERE docid = ?",
                arrayOf<Any>((learned + " " + qNorm).trim(), pageId)
            )
        }
    }

    private fun pageId(db: SQLiteDatabase, normalizedUrl: String): Long {
        db.rawQuery("SELECT id FROM pages WHERE url = ?", arrayOf(normalizedUrl)).use {
            return if (it.moveToFirst()) it.getLong(0) else -1L
        }
    }

    // ------------------------------------------------------------------ searching (read side)

    fun searchAsync(
        query: String,
        limit: Int = 8,
        log: Boolean = true,
        callback: (List<SearchResult>) -> Unit
    ) {
        io.execute {
            val results = try {
                search(query, limit, log)
            } catch (_: Exception) {
                emptyList()
            }
            main.post { callback(results) }
        }
    }

    /**
     * Ranking = text relevance + what the engine has learned about YOU:
     *  - exact-query click history (strongest signal)
     *  - words learned from past queries that led to the page
     *  - visit count, click count, dwell time, recency
     *  - penalty for pages you tend to bounce from
     */
    fun search(query: String, limit: Int = 8, log: Boolean = true): List<SearchResult> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val db = writableDatabase
        val ftsQuery = tokens.joinToString(" OR ") { "$it*" }
        val qNorm = normalizeQuery(query)
        val now = System.currentTimeMillis()

        val exact = HashMap<Long, Int>()
        db.rawQuery("SELECT page_id, clicks FROM query_clicks WHERE query = ?", arrayOf(qNorm)).use {
            while (it.moveToNext()) exact[it.getLong(0)] = it.getInt(1)
        }

        val results = ArrayList<SearchResult>()
        db.rawQuery(
            """SELECT p.id, p.url, p.title, p.visit_count, p.click_count, p.bounce_count,
                      p.total_dwell_ms, p.last_visit, offsets(pages_fts),
                      snippet(pages_fts, '', '', '...', 1, 18), p.summary
               FROM pages_fts JOIN pages p ON p.id = pages_fts.docid
               WHERE pages_fts MATCH ? LIMIT 150""",
            arrayOf(ftsQuery)
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)

                // offsets() = groups of 4 ints: column, term index, byte offset, size
                val parts = (c.getString(8) ?: "").split(' ')
                val seen = HashSet<Int>()
                var titleHits = 0
                var contentHits = 0
                var learnedHits = 0
                var i = 0
                while (i + 3 < parts.size) {
                    val col = parts[i].toIntOrNull() ?: break
                    val term = parts[i + 1].toIntOrNull() ?: break
                    seen.add(term)
                    when (col) {
                        0 -> titleHits++
                        1 -> contentHits++
                        2 -> learnedHits++
                    }
                    i += 4
                }

                val coverage = seen.size.toDouble() / tokens.size
                val exactClicks = exact[id] ?: 0
                // For multi-word queries, drop weak partial matches unless you chose this page before.
                if (tokens.size >= 2 && coverage < 0.5 && exactClicks == 0) continue

                val visits = c.getInt(3)
                val clicks = c.getInt(4)
                val bounces = c.getInt(5)
                val avgDwellSec = c.getLong(6) / 1000.0 / maxOf(1, visits)
                val ageDays = (now - c.getLong(7)) / 86_400_000.0

                var score = 0.0
                score += coverage * coverage * 3.0
                score += ln(1.0 + titleHits) * 1.5
                score += ln(1.0 + contentHits) * 0.7
                score += ln(1.0 + learnedHits) * 2.5       // learned from your past queries
                score += ln(1.0 + exactClicks) * 3.0       // you picked this for this query before
                score += ln(1.0 + visits) * 0.5
                score += ln(1.0 + clicks) * 0.5
                score += min(avgDwellSec, 120.0) / 120.0   // you actually read it
                score += exp(-ageDays / 30.0)              // recency
                score -= min(bounces, 3) * 0.4             // you usually leave quickly

                val snippet = (c.getString(9) ?: "").ifBlank { c.getString(10) ?: "" }
                results.add(SearchResult(id, c.getString(1), c.getString(2) ?: c.getString(1), snippet, score))
            }
        }
        results.sortByDescending { it.score }

        if (log) logQuery(db, query.trim().lowercase(), results.isNotEmpty())
        return results.take(limit)
    }

    private fun logQuery(db: SQLiteDatabase, query: String, hadLocal: Boolean) {
        val now = System.currentTimeMillis()
        val hit = if (hadLocal) 1 else 0
        db.execSQL(
            "INSERT OR IGNORE INTO query_log(query, count, local_hits, last_used) VALUES (?, 0, 0, ?)",
            arrayOf<Any>(query, now)
        )
        db.execSQL(
            "UPDATE query_log SET count = count + 1, local_hits = local_hits + ?, last_used = ? WHERE query = ?",
            arrayOf<Any>(hit, now, query)
        )
    }

    // ------------------------------------------------------------------ housekeeping

    fun statsAsync(callback: (EngineStats) -> Unit) {
        io.execute {
            val stats = try {
                val db = readableDatabase
                var pages = 0
                var queries = 0
                var local = 0
                db.rawQuery("SELECT COUNT(*) FROM pages", null).use { if (it.moveToFirst()) pages = it.getInt(0) }
                db.rawQuery("SELECT COALESCE(SUM(count),0), COALESCE(SUM(local_hits),0) FROM query_log", null).use {
                    if (it.moveToFirst()) {
                        queries = it.getInt(0)
                        local = it.getInt(1)
                    }
                }
                EngineStats(pages, queries, if (queries > 0) local * 100 / queries else 0)
            } catch (_: Exception) {
                EngineStats(0, 0, 0)
            }
            main.post { callback(stats) }
        }
    }

    /** Removes old pages you only saw once and never clicked. Safe to call on every app start. */
    fun pruneAsync() {
        io.execute {
            try {
                val db = writableDatabase
                val cutoff = System.currentTimeMillis() - PRUNE_AFTER_MS
                val where = "id IN (SELECT id FROM pages WHERE click_count = 0 AND visit_count <= 1 AND last_visit < ?)"
                db.beginTransaction()
                try {
                    db.execSQL("DELETE FROM pages_fts WHERE docid IN (SELECT id FROM pages WHERE click_count = 0 AND visit_count <= 1 AND last_visit < ?)", arrayOf<Any>(cutoff))
                    db.execSQL("DELETE FROM pages WHERE $where", arrayOf<Any>(cutoff))
                    db.execSQL("DELETE FROM query_clicks WHERE page_id NOT IN (SELECT id FROM pages)")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Wipes everything the engine has learned. */
    fun clearAll() {
        io.execute {
            try {
                val db = writableDatabase
                db.beginTransaction()
                try {
                    db.execSQL("DELETE FROM pages")
                    db.execSQL("DELETE FROM pages_fts")
                    db.execSQL("DELETE FROM query_clicks")
                    db.execSQL("DELETE FROM query_log")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(splitter).filter { it.length >= 2 }.distinct().take(8)

    private fun normalizeQuery(q: String): String = tokenize(q).sorted().joinToString(" ")

    /** Lower-cases host, drops fragment, trailing slash and tracking params so one page = one row. */
    private fun normalizeUrl(raw: String): String? {
        return try {
            val uri = Uri.parse(raw)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val authority = uri.encodedAuthority?.lowercase() ?: return null
            val builder = Uri.Builder()
                .scheme(scheme)
                .encodedAuthority(authority)
                .encodedPath((uri.encodedPath ?: "").trimEnd('/'))
            uri.queryParameterNames
                .filterNot { it.startsWith("utm_") || it in TRACKING_PARAMS }
                .sorted()
                .forEach { name ->
                    uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
                }
            builder.build().toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun shouldIndex(normalizedUrl: String): Boolean {
        val uri = Uri.parse(normalizedUrl)
        val host = uri.host?.lowercase() ?: return false
        val path = (uri.path ?: "").lowercase()
        if (host == "localhost" || host.matches(Regex("[0-9.]+"))) return false
        if (BLOCKED_HOST_WORDS.any { host.contains(it) }) return false
        if (BLOCKED_PATH_WORDS.any { path.contains(it) }) return false
        if (uri.queryParameterNames.any { it.contains("token") || it.contains("password") }) return false
        return true
    }
}
