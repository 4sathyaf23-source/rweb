package com.rweb.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.max

/**
 * rweb's own search engine, running on the phone.
 *
 * Index: SQLite tables (docs + postings). Ranking: BM25 with title words counted 4x, boosted by how often you
 * opened a page. Queries: words, "exact phrase", -exclude, site:example.com, prefix match on the last word.
 * Pages get in through [index] (pages you visit) and [LocalCrawler] (sites you choose + your bookmarks).
 */
class LocalSearch(context: Context) : SQLiteOpenHelper(context.applicationContext, "rweb_search.db", null, 1) {

    class Hit(val title: String, val url: String, val snippet: String)
    class Result(val hits: List<Hit>, val total: Int, val ms: Long)
    private class Row(val id: Long, val url: String, val title: String, val body: String, val visits: Int)

    companion object {
        const val HL_OPEN = "\u0002"
        const val HL_CLOSE = "\u0003"
        private const val MAX_DOCS = 20000
        private const val K1 = 1.2
        private const val B = 0.75
        private val TOKEN = Regex("[\\p{L}\\p{N}]+")
        private val QUERY_TOKEN = Regex("(?:(?<=\\s)|^)-[\\p{L}\\p{N}]+|[\\p{L}\\p{N}]+")
        private val WS = Regex("\\s+")
        private val STOP = setOf(
            "the", "a", "an", "of", "to", "in", "and", "or", "is", "are", "for", "on", "with", "as", "at", "by",
            "it", "be", "this", "that", "from", "was", "were", "but", "not", "you", "your", "have", "has", "had",
            "will", "can"
        )

        /** Light stemmer: enough that gardens/gardening/garden and cooked/cooking/cook meet. Used for index AND queries. */
        fun stem(word: String): String {
            var s = word
            if (s.length > 4 && s.endsWith("ies")) return s.dropLast(3) + "y"
            if (s.length > 5 && s.endsWith("ing")) s = s.dropLast(3)
            else if (s.length > 4 && s.endsWith("ed")) s = s.dropLast(2)
            else if (s.length > 4 && s.endsWith("es")) s = s.dropLast(2)
            else if (s.length > 3 && s.endsWith("s") && !s.endsWith("ss")) s = s.dropLast(1)
            else return s
            if (s.length > 3 && s[s.length - 1] == s[s.length - 2] && s.last() !in "lsz") s = s.dropLast(1) // runn -> run
            return s
        }

        fun tokens(text: String): List<String> =
            TOKEN.findAll(text.lowercase(Locale.ROOT)).map { it.value }
                .filter { it.length in 2..40 && it !in STOP }.map { stem(it) }.toList()

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "rweb-index").apply { isDaemon = true } }
    private var inserts = 0

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE docs(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT UNIQUE NOT NULL, host TEXT, title TEXT, " +
                "body TEXT, len INTEGER, hash TEXT, added INTEGER, visits INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE postings(term TEXT NOT NULL, doc INTEGER NOT NULL, tf INTEGER NOT NULL, PRIMARY KEY(term, doc)) WITHOUT ROWID")
        db.execSQL("CREATE INDEX postings_doc ON postings(doc)")
        db.execSQL("CREATE INDEX docs_hash ON docs(hash)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ------------------------------------------------------------ writing

    /** Queue a page for indexing (returns immediately). */
    fun index(url: String, title: String, text: String) {
        io.execute {
            try {
                indexNow(url, title, text)
            } catch (e: Exception) {
            }
        }
    }

    /** Index on the calling thread (used by the crawler). Returns true if the page was stored or refreshed. */
    fun indexBlocking(url: String, title: String, text: String): Boolean =
        try {
            indexNow(url, title, text)
        } catch (e: Exception) {
            false
        }

    private fun hostOfUrl(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase(Locale.ROOT)

    private fun indexNow(url: String, title: String, text: String): Boolean {
        val body = text.replace(WS, " ").trim().take(30000)
        if (body.length < 80) return false
        val t = title.replace(WS, " ").trim().take(300)
        val hash = sha1(body)
        val db = writableDatabase
        val dup = db.rawQuery("SELECT 1 FROM docs WHERE hash=? AND url<>?", arrayOf(hash, url)).use { it.moveToFirst() }
        if (dup) return false

        val counts = HashMap<String, Int>()
        var len = 0
        for (w in tokens(body)) {
            counts[w] = (counts[w] ?: 0) + 1
            len++
        }
        for (w in tokens(t)) {
            counts[w] = (counts[w] ?: 0) + 4 // title words count 4x
            len += 4
        }
        if (counts.isEmpty()) return false

        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            var id = -1L
            var same = false
            db.rawQuery("SELECT id, hash FROM docs WHERE url=?", arrayOf(url)).use { c ->
                if (c.moveToFirst()) {
                    id = c.getLong(0)
                    same = c.getString(1) == hash
                }
            }
            if (same) {
                db.execSQL("UPDATE docs SET added=? WHERE id=?", arrayOf<Any>(now, id))
                db.setTransactionSuccessful()
                return true
            }
            val cv = ContentValues()
            cv.put("url", url)
            cv.put("host", hostOfUrl(url))
            cv.put("title", t)
            cv.put("body", body)
            cv.put("len", len)
            cv.put("hash", hash)
            cv.put("added", now)
            if (id >= 0) {
                db.update("docs", cv, "id=?", arrayOf(id.toString()))
                db.delete("postings", "doc=?", arrayOf(id.toString()))
            } else {
                id = db.insertOrThrow("docs", null, cv)
            }
            val ins = db.compileStatement("INSERT INTO postings(term, doc, tf) VALUES(?,?,?)")
            for ((w, n) in counts) {
                ins.bindString(1, w)
                ins.bindLong(2, id)
                ins.bindLong(3, n.toLong())
                ins.executeInsert()
            }
            ins.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (++inserts % 100 == 0) evictIfNeeded(db)
        return true
    }

    /** Keep the index bounded: drop the oldest, least-opened pages first. */
    private fun evictIfNeeded(db: SQLiteDatabase) {
        val n = db.rawQuery("SELECT COUNT(*) FROM docs", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (n <= MAX_DOCS) return
        val drop = n - MAX_DOCS + 500
        db.execSQL("DELETE FROM postings WHERE doc IN (SELECT id FROM docs ORDER BY visits, added LIMIT $drop)")
        db.execSQL("DELETE FROM docs WHERE id IN (SELECT id FROM docs ORDER BY visits, added LIMIT $drop)")
    }

    /** The user opened this page from a result or card: rank it higher next time. */
    fun visit(url: String) {
        io.execute {
            try {
                writableDatabase.execSQL("UPDATE docs SET visits=visits+1 WHERE url=?", arrayOf<Any>(url))
            } catch (e: Exception) {
            }
        }
    }

    fun clear() {
        io.execute {
            try {
                val db = writableDatabase
                db.execSQL("DELETE FROM postings")
                db.execSQL("DELETE FROM docs")
            } catch (e: Exception) {
            }
        }
    }

    fun count(): Int = try {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM docs", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) {
        0
    }

    fun hasRecent(url: String, maxAgeMs: Long): Boolean = try {
        readableDatabase.rawQuery("SELECT 1 FROM docs WHERE url=? AND added>?", arrayOf(url, (System.currentTimeMillis() - maxAgeMs).toString()))
            .use { it.moveToFirst() }
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------ searching

    private fun expand(db: SQLiteDatabase, prefix: String): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("SELECT DISTINCT term FROM postings WHERE term >= ? AND term < ? LIMIT 25", arrayOf(prefix, prefix + "\uffff"))
            .use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    private fun scoreTerm(db: SQLiteDatabase, term: String, n: Int, avgdl: Double, host: String?): HashMap<Long, Double> {
        val out = HashMap<Long, Double>()
        var sql = "SELECT p.doc, p.tf, d.len FROM postings p JOIN docs d ON d.id=p.doc WHERE p.term=?"
        val args = ArrayList<String>()
        args.add(term)
        if (host != null) {
            sql += " AND (d.host=? OR d.host LIKE ?)"
            args.add(host)
            args.add("%.$host")
        }
        db.rawQuery(sql, args.toTypedArray()).use { c ->
            val rows = ArrayList<Triple<Long, Int, Int>>()
            while (c.moveToNext()) rows.add(Triple(c.getLong(0), c.getInt(1), c.getInt(2)))
            val df = rows.size
            val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
            for ((doc, tf, len) in rows) {
                val norm = K1 * (1 - B + B * len / avgdl)
                out[doc] = idf * tf * (K1 + 1) / (tf + norm)
            }
        }
        return out
    }

    fun search(query: String, limit: Int = 30): Result {
        val t0 = System.currentTimeMillis()
        val empty = { Result(emptyList(), 0, System.currentTimeMillis() - t0) }

        var q = query.take(200)
        var site: String? = null
        val sm = Regex("\\bsite:([\\w.\\-]+)").find(q)
        if (sm != null) {
            site = sm.groupValues[1].lowercase(Locale.ROOT)
            q = q.replace(sm.value, " ")
        }
        val phrases = Regex("\"([^\"]+)\"").findAll(q).map { it.groupValues[1].trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toList()
        val rest = q.replace(Regex("\"[^\"]*\""), " ")
        val pos = ArrayList<String>()
        val neg = ArrayList<String>()
        for (m in QUERY_TOKEN.findAll(rest)) {
            val t = m.value
            if (t.startsWith("-")) neg.addAll(tokens(t.substring(1))) else pos.add(t.lowercase(Locale.ROOT))
        }

        var stems = tokens(pos.joinToString(" "))
        if (stems.isEmpty()) stems = pos.map { stem(it) } // query made only of common words
        val groups = ArrayList<MutableList<String>>()
        for (s in stems.distinct()) groups.add(mutableListOf(s))
        for (p in phrases) for (s in tokens(p).distinct()) if (groups.none { it[0] == s }) groups.add(mutableListOf(s))
        if (groups.isEmpty()) return empty()

        val db = readableDatabase
        val stats = db.rawQuery("SELECT COUNT(*), COALESCE(AVG(len), 1) FROM docs", null).use {
            if (it.moveToFirst()) Pair(it.getInt(0), it.getDouble(1)) else Pair(0, 1.0)
        }
        if (stats.first == 0) return empty()
        val avgdl = max(stats.second, 1.0)

        // the word being typed matches as a prefix too ("gard" finds garden)
        val lastRaw = pos.lastOrNull()
        if (lastRaw != null && lastRaw.length >= 3 && phrases.isEmpty() && lastRaw !in STOP) {
            val g = groups.firstOrNull { it[0] == stem(lastRaw) }
            if (g != null) for (e in expand(db, lastRaw)) if (e !in g) g.add(e)
        }

        val maps = groups.map { alts ->
            val m = HashMap<Long, Double>()
            for (a in alts) for ((d, s) in scoreTerm(db, a, stats.first, avgdl, site)) if (s > (m[d] ?: 0.0)) m[d] = s
            m
        }
        val combined = HashMap<Long, Double>()
        val smallest = maps.minByOrNull { it.size } ?: return empty()
        for (d in smallest.keys) {
            var sum = 0.0
            var all = true
            for (m in maps) {
                val s = m[d]
                if (s == null) {
                    all = false
                    break
                }
                sum += s
            }
            if (all) combined[d] = sum
        }
        if (combined.isEmpty() && maps.size > 1) { // nothing has every word: fall back to any word
            for (m in maps) for ((d, s) in m) combined[d] = (combined[d] ?: 0.0) + s
        }
        for (t in neg.distinct()) {
            db.rawQuery("SELECT doc FROM postings WHERE term=?", arrayOf(t)).use { c -> while (c.moveToNext()) combined.remove(c.getLong(0)) }
        }
        if (combined.isEmpty()) return empty()

        val top = combined.entries.sortedByDescending { it.value }.take(120)
        val rows = HashMap<Long, Row>()
        db.rawQuery(
            "SELECT id, url, title, body, visits FROM docs WHERE id IN (" + top.joinToString(",") { it.key.toString() } + ")", null
        ).use { c ->
            while (c.moveToNext()) rows[c.getLong(0)] = Row(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getInt(4))
        }

        val scored = ArrayList<Pair<Double, Row>>()
        for (e in top) {
            val r = rows[e.key] ?: continue
            if (phrases.isNotEmpty()) {
                val hay = (r.title + " " + r.body).lowercase(Locale.ROOT)
                if (!phrases.all { hay.contains(it) }) continue
            }
            scored.add(Pair(e.value * (1.0 + 0.3 * ln(1.0 + r.visits)), r))
        }
        scored.sortByDescending { it.first }

        // at most 3 results per site ahead of the rest
        val perHost = HashMap<String, Int>()
        val first = ArrayList<Row>()
        val later = ArrayList<Row>()
        for ((_, r) in scored) {
            val h = hostOfUrl(r.url)
            val n = (perHost[h] ?: 0) + 1
            perHost[h] = n
            if (n <= 3) first.add(r) else later.add(r)
        }
        val ordered = first + later

        val hl = Regex("(?i)\\b(" + stems.distinct().joinToString("|") { Regex.escape(it) } + ")[\\p{L}\\p{N}]*")
        val hits = ordered.take(limit).map { Hit(it.title.ifBlank { it.url }, it.url, snippet(it.body, hl)) }
        return Result(hits, ordered.size, System.currentTimeMillis() - t0)
    }

    /** About 200 characters around the first match, with matches wrapped in HL_OPEN/HL_CLOSE. */
    private fun snippet(body: String, hl: Regex): String {
        val m = hl.find(body)
        var start = if (m == null) 0 else max(0, m.range.first - 60)
        if (start > 0) {
            val sp = body.lastIndexOf(' ', start)
            if (sp >= 0 && start - sp < 30) start = sp + 1
        }
        val end = minOf(body.length, start + 200)
        var piece = body.substring(start, end)
        piece = hl.replace(piece) { HL_OPEN + it.value + HL_CLOSE }
        return (if (start > 0) "\u2026 " else "") + piece + (if (end < body.length) " \u2026" else "")
    }
}

// ====================================================================== HTML -> text

object HtmlText {
    class Parsed(
        val title: String,
        val desc: String,
        val text: String,
        val links: List<String>,
        val noindex: Boolean,
        val nofollow: Boolean
    )

    private val META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ANCHOR = Regex("<a\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DROP = Regex(
        "<(script|style|noscript|template|svg|nav|footer|aside|form|iframe|head)\\b[^>]*>.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val DROP_MIN = Regex("<(script|style|noscript|template|svg)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]+>")
    private val WS = Regex("\\s+")

    private fun attrs(tag: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (a in ATTR.findAll(tag)) m[a.groupValues[1].lowercase(Locale.ROOT)] = a.groupValues[2].ifEmpty { a.groupValues[3] }
        return m
    }

    private fun cp(n: Int): String = if (n in 1..0x10FFFF && n !in 0xD800..0xDFFF) String(Character.toChars(n)) else ""

    fun decode(s: String): String {
        var r = Regex("&#(\\d{1,7});").replace(s) { cp(it.groupValues[1].toIntOrNull() ?: 0) }
        r = Regex("&#[xX]([0-9a-fA-F]{1,6});").replace(r) { cp(it.groupValues[1].toIntOrNull(16) ?: 0) }
        return r.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }

    private fun textOf(html: String, drop: Regex): String =
        decode(TAG.replace(COMMENT.replace(drop.replace(html, " "), " "), " ")).replace(WS, " ").trim()

    fun parse(html: String, baseUrl: String): Parsed {
        var desc = ""
        var noindex = false
        var nofollow = false
        for (mt in META.findAll(html)) {
            val a = attrs(mt.value)
            val name = (a["name"] ?: a["property"] ?: "").lowercase(Locale.ROOT)
            val content = a["content"] ?: ""
            if ((name == "description" || name == "og:description") && desc.isEmpty()) desc = decode(content).trim()
            if (name == "robots") {
                val c = content.lowercase(Locale.ROOT)
                if (c.contains("noindex")) noindex = true
                if (c.contains("nofollow")) nofollow = true
            }
        }
        val title = decode(TITLE.find(html)?.groupValues?.get(1) ?: "").replace(WS, " ").trim().take(300)

        val links = ArrayList<String>()
        for (am in ANCHOR.findAll(html)) {
            val a = attrs(am.value)
            val href = a["href"] ?: continue
            if (href.startsWith("#") || href.startsWith("javascript:") || href.startsWith("mailto:") || href.startsWith("tel:")) continue
            if ((a["rel"] ?: "").lowercase(Locale.ROOT).contains("nofollow")) continue
            LocalCrawler.canon(decode(href), baseUrl)?.let { if (it !in links) links.add(it) }
            if (links.size >= 100) break
        }

        var text = textOf(html, DROP)
        if (text.length < 200) text = textOf(html, DROP_MIN) // an unclosed <nav> must not swallow the page
        return Parsed(title, desc.take(400), text.take(60000), links, noindex, nofollow)
    }
}

// ====================================================================== crawler

/**
 * Polite, single-threaded crawler: robots.txt, 1 request/second per site, HTML only (max 1 MB),
 * https only, public hosts only, stays within the page budget you give it.
 */
class LocalCrawler(private val ls: LocalSearch) {
    @Volatile var running = false
    @Volatile var stopRequested = false

    companion object {
        const val UA = "rweb-bot/0.1 (on-device personal index; honours robots.txt)"
        private const val MAX_BYTES = 1_000_000
        private val SKIP_EXT = Regex(
            "\\.(jpe?g|png|gif|svg|webp|ico|pdf|zip|gz|tar|rar|7z|mp3|mp4|avi|mov|mkv|webm|css|js|json|xml|rss|exe|dmg|apk|iso|woff2?|ttf|eot|docx?|xlsx?|pptx?)$",
            RegexOption.IGNORE_CASE
        )
        private val TRACK = Regex("^(utm_|fbclid$|gclid$|mc_eid$|ref$|ref_src$|igshid$)", RegexOption.IGNORE_CASE)

        /** Normalised https URL, or null if it should not be crawled. */
        fun canon(url: String, base: String?): String? {
            try {
                val raw = url.trim()
                if (raw.isEmpty() || raw.length > 2000) return null
                val u = if (base != null) URI(base).resolve(raw) else URI(raw)
                val scheme = u.scheme?.lowercase(Locale.ROOT) ?: return null
                if (scheme != "http" && scheme != "https") return null
                val host = u.host?.lowercase(Locale.ROOT) ?: return null
                var path = u.rawPath ?: ""
                if (path.isEmpty()) path = "/"
                if (SKIP_EXT.containsMatchIn(path)) return null
                val q = u.rawQuery?.split("&")?.filter { it.isNotEmpty() && !TRACK.containsMatchIn(it.substringBefore("=")) }?.joinToString("&")
                val port = u.port
                val portPart = if (port == -1 || port == 80 || port == 443) "" else ":$port"
                return "https://" + host + portPart + path + (if (q.isNullOrEmpty()) "" else "?$q")
            } catch (e: Exception) {
                return null
            }
        }
    }

    private class Rules(val allow: List<String>, val disallow: List<String>) {
        fun allowed(path: String): Boolean {
            val a = allow.filter { path.startsWith(it.removeSuffix("*")) }.maxOfOrNull { it.length } ?: -1
            val d = disallow.filter { path.startsWith(it.removeSuffix("*")) }.maxOfOrNull { it.length } ?: -1
            return d < 0 || a >= d
        }
    }

    private val denyAll = Rules(emptyList(), listOf("/"))
    private val allowAll = Rules(emptyList(), emptyList())
    private val hostOk = HashMap<String, Boolean>()

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase(Locale.ROOT)
    private fun pathOf(url: String): String = "/" + url.substringAfter("://").substringAfter('/', "")

    private fun publicHost(host: String): Boolean = hostOk.getOrPut(host) {
        try {
            val all = InetAddress.getAllByName(host)
            all.isNotEmpty() && all.none {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress ||
                    (it.address.size == 16 && (it.address[0].toInt() and 0xFE) == 0xFC) // IPv6 unique-local
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun get(url: String, timeout: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    private fun loadRobots(host: String): Rules {
        val c = try {
            get("https://$host/robots.txt", 5000)
        } catch (e: Exception) {
            return allowAll
        }
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) return denyAll
            if (code !in 200..299) return allowAll
            val text = c.inputStream.use { it.readBytes().take(500_000).toByteArray().toString(Charsets.UTF_8) }
            return parseRobots(text)
        } catch (e: Exception) {
            return allowAll
        } finally {
            c.disconnect()
        }
    }

    private fun parseRobots(text: String): Rules {
        val sa = ArrayList<String>(); val sd = ArrayList<String>() // rules for rweb-bot
        val wa = ArrayList<String>(); val wd = ArrayList<String>() // rules for *
        var agents = ArrayList<String>()
        var lastWasAgent = false
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            val idx = line.indexOf(':')
            if (idx < 0) continue
            val key = line.substring(0, idx).trim().lowercase(Locale.ROOT)
            val value = line.substring(idx + 1).trim()
            if (key == "user-agent") {
                if (!lastWasAgent) agents = ArrayList()
                agents.add(value.lowercase(Locale.ROOT))
                lastWasAgent = true
                continue
            }
            lastWasAgent = false
            if ((key != "allow" && key != "disallow") || value.isEmpty()) continue
            val specific = agents.contains("rweb-bot")
            val star = agents.contains("*")
            if (key == "allow") {
                if (specific) sa.add(value)
                if (star) wa.add(value)
            } else {
                if (specific) sd.add(value)
                if (star) wd.add(value)
            }
        }
        return if (sa.isNotEmpty() || sd.isNotEmpty()) Rules(sa, sd) else Rules(wa, wd)
    }

    /** (final URL, html) or null when the page is not usable HTML. */
    private fun fetchPage(url: String): Pair<String, String>? {
        val c = get(url, 8000)
        try {
            if (c.responseCode !in 200..299) return null
            val ct = c.contentType ?: ""
            if (!ct.contains("html", ignoreCase = true)) return null
            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            var total = 0
            c.inputStream.use { s ->
                while (total < MAX_BYTES) {
                    val r = s.read(tmp)
                    if (r <= 0) break
                    buf.write(tmp, 0, r)
                    total += r
                }
            }
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
            val bytes = buf.toByteArray()
            val text = try {
                String(bytes, charset(cs ?: "UTF-8"))
            } catch (e: Exception) {
                String(bytes, Charsets.UTF_8)
            }
            return Pair(c.url.toString(), text)
        } finally {
            c.disconnect()
        }
    }

    /** Crawl from [seeds]; returns how many pages were indexed. Call from a background thread. */
    fun crawl(seeds: List<String>, maxPages: Int, maxDepth: Int, perDomain: Int, delayMs: Long, onProgress: (Int) -> Unit): Int {
        running = true
        stopRequested = false
        var indexed = 0
        try {
            val queue = ArrayDeque<Pair<String, Int>>()
            val seen = HashSet<String>()
            for (s in seeds) canon(s, null)?.let { if (seen.add(it)) queue.add(Pair(it, 0)) }
            val perHost = HashMap<String, Int>()
            val nextOk = HashMap<String, Long>()
            val robots = HashMap<String, Rules>()
            var fetched = 0

            while (queue.isNotEmpty() && fetched < maxPages && !stopRequested) {
                var pick: Pair<String, Int>? = null
                val now = System.currentTimeMillis()
                val iter = queue.iterator()
                while (iter.hasNext()) {
                    val p = iter.next()
                    if ((nextOk[hostOf(p.first)] ?: 0L) <= now) {
                        pick = p
                        iter.remove()
                        break
                    }
                }
                if (pick == null) {
                    Thread.sleep(100)
                    continue
                }
                val url = pick.first
                val depth = pick.second
                val host = hostOf(url)
                if ((perHost[host] ?: 0) >= perDomain) continue
                perHost[host] = (perHost[host] ?: 0) + 1
                fetched++
                try {
                    if (!publicHost(host)) continue
                    val rules = robots.getOrPut(host) { loadRobots(host) }
                    if (!rules.allowed(pathOf(url))) continue
                    if (ls.hasRecent(url, 7L * 24 * 3600 * 1000)) continue // indexed this week already
                    val got = fetchPage(url) ?: continue
                    val finalUrl = canon(got.first, null) ?: url
                    if (hostOf(finalUrl) != host && !publicHost(hostOf(finalUrl))) continue
                    val parsed = HtmlText.parse(got.second, finalUrl)
                    if (!parsed.noindex && ls.indexBlocking(finalUrl, parsed.title, parsed.desc + " " + parsed.text)) {
                        indexed++
                        onProgress(indexed)
                    }
                    if (depth < maxDepth && !parsed.nofollow) {
                        for (l in parsed.links) if (seen.add(l)) queue.add(Pair(l, depth + 1))
                    }
                } catch (e: Exception) {
                } finally {
                    nextOk[host] = System.currentTimeMillis() + delayMs
                }
            }
        } finally {
            running = false
        }
        return indexed
    }
}
