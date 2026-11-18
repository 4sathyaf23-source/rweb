package com.rweb.browser

import android.net.Uri

/** The History page shown at rweb://history: searchable list, delete per entry, "Clear all history". */
object HistoryPage {
    /** Base/history URL of the page. MainActivity shows it as "rweb://history" in the address bar. */
    const val URL = "https://rweb.local/history"

    private const val CSS = """
        :root{--bg:#fff;--fg:#202124;--mute:#5f6368;--fld:#f1f3f4;--acc:#c41e3a}
        html.dark{--bg:#3a0a14;--fg:#fff1f3;--mute:#e3a9b1;--fld:#7a1022;--acc:#ff6b7d}
        *{box-sizing:border-box}
        body{margin:0 auto;padding:0 16px 40px;background:var(--bg);color:var(--fg);font-family:sans-serif;max-width:720px}
        a{text-decoration:none;color:inherit}
        header{display:flex;justify-content:space-between;align-items:center;padding:16px 0 10px}
        header b{font-size:22px;color:var(--acc)}
        .clr{padding:8px 14px;border-radius:16px;background:var(--acc);color:#fff;font-size:13px}
        input{width:100%;padding:12px 18px;border-radius:24px;border:0;background:var(--fld);color:var(--fg);font-size:16px;outline:none}
        .cnt{color:var(--mute);font-size:12px;margin:14px 0 6px}
        .row{display:flex;align-items:center;border-bottom:1px solid rgba(128,128,128,.2)}
        .it{flex:1;min-width:0;padding:12px 4px}
        .t{display:block;font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .u{display:block;font-size:12px;color:var(--mute)}
        .x{padding:12px 14px;font-size:22px;color:var(--mute)}
        .none{text-align:center;color:var(--mute);margin-top:40px}
    """

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Everything inside <body>; also used to refresh the page in place after a delete/search/clear. */
    fun body(entries: List<Pair<String, String>>, query: String): String {
        val q = query.trim()
        val shown = if (q.isEmpty()) entries else entries.filter { it.first.contains(q, true) || it.second.contains(q, true) }
        val sb = StringBuilder()
        sb.append("<header><b>History</b>")
        if (entries.isNotEmpty()) sb.append("<a class=\"clr\" href=\"rweb://history/clear\">Clear all history</a>")
        sb.append("</header>")
        sb.append("<form onsubmit=\"location.href='rweb://history/search?q='+encodeURIComponent(this.q.value.trim());return false\">")
        sb.append("<input name=\"q\" placeholder=\"Search history\" value=\"").append(esc(q)).append("\" autocomplete=\"off\"></form>")
        if (shown.isEmpty()) {
            sb.append("<p class=\"none\">").append(if (entries.isEmpty()) "No history yet." else "Nothing matches your search.").append("</p>")
        } else {
            sb.append("<div class=\"cnt\">").append(shown.size).append(if (shown.size == 1) " page" else " pages").append("</div>")
            for (h in shown) {
                val title = h.first.ifBlank { h.second }
                val host = (Uri.parse(h.second).host ?: h.second).removePrefix("www.")
                sb.append("<div class=\"row\"><a class=\"it\" href=\"").append(esc(h.second)).append("\">")
                sb.append("<span class=\"t\">").append(esc(title)).append("</span><span class=\"u\">").append(esc(host)).append("</span></a>")
                sb.append("<a class=\"x\" href=\"rweb://history/delete?u=").append(Uri.encode(h.second)).append("\">\u00D7</a></div>")
            }
        }
        return sb.toString()
    }

    fun page(dark: Boolean, entries: List<Pair<String, String>>, query: String): String =
        "<!doctype html><html" + (if (dark) " class=\"dark\"" else "") + "><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>History</title><style>" + CSS +
            "</style></head><body>" + body(entries, query) + "</body></html>"
}
