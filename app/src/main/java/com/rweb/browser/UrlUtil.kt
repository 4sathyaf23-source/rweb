package com.rweb.browser

import android.net.Uri

object UrlUtil {
    private val scheme = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val localhost = Regex("^localhost(:\\d+)?$", RegexOption.IGNORE_CASE)
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$")
    private val domain = Regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}(:\\d+)?$", RegexOption.IGNORE_CASE)

    fun toNavigationTarget(input: String?, searchBase: String = "https://www.google.com/search?q="): String {
        val text = (input ?: "").trim()
        if (text.isEmpty()) return ""
        if (scheme.containsMatchIn(text)) return text
        if (text.none { it.isWhitespace() }) {
            val host = text.split('/', '?', '#')[0]
            if (localhost.matches(host) || ipv4.matches(host)) return "http://" + text
            if (domain.matches(host)) return "https://" + text
        }
        return searchBase + Uri.encode(text)
    }
}
