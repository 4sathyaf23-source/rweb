package com.rweb.browser

// A small built-in list of well-known third-party ad and tracking domains.
// A request is blocked when its host, or any parent domain of it, is on the list.
object AdBlock {
    private val domains = hashSetOf(
        // Google advertising and analytics
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "adservice.google.com", "google-analytics.com", "2mdn.net",
        // Ad exchanges and networks
        "adnxs.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.com",
        "criteo.net", "taboola.com", "outbrain.com", "amazon-adsystem.com", "advertising.com",
        "casalemedia.com", "smartadserver.com", "serving-sys.com", "teads.tv", "mgid.com",
        "revcontent.com", "yieldmo.com", "sharethrough.com", "indexww.com", "lijit.com",
        "bidswitch.net", "contextweb.com", "adform.net", "media.net", "adroll.com", "3lift.com",
        "triplelift.com", "gumgum.com", "tapad.com", "turn.com", "mookie1.com", "zedo.com",
        // Pop-up and aggressive ad networks
        "popads.net", "propellerads.com", "popcash.net", "exoclick.com", "trafficjunky.net",
        // Measurement, audience and tracking
        "scorecardresearch.com", "quantserve.com", "quantcount.com", "moatads.com",
        "adsafeprotected.com", "bluekai.com", "krxd.net", "demdex.net", "everesttech.net",
        "omtrdc.net", "rlcdn.com", "chartbeat.com", "bat.bing.com",
        // Session recording and heatmaps
        "hotjar.com", "mouseflow.com", "crazyegg.com"
    )

    fun isBlocked(host: String): Boolean {
        var h = host.lowercase()
        while (h.contains('.')) {
            if (domains.contains(h)) return true
            h = h.substring(h.indexOf('.') + 1)
        }
        return false
    }
}
