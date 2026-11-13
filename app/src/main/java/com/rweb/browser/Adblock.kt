package com.rweb.browser

import android.content.Context
import android.webkit.WebView
import android.widget.Toast

object AdBlock {

    private val blockedHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "ads.yahoo.com",
        "ads.twitter.com",
        "facebook.net",
        "amazon-adsystem.com",
        "taboola.com",
        "outbrain.com"
    )

    private var isReaderModeActive = false

    fun isBlocked(host: String): Boolean {
        val h = host.lowercase()

        return blockedHosts.any {
            h == it || h.endsWith(".$it")
        }
    }

    fun toggleReaderMode(
        web: WebView,
        context: Context
    ) {
        if (isReaderModeActive) {
            isReaderModeActive = false
            web.reload()

            Toast.makeText(
                context,
                "Exited Reader Mode",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        isReaderModeActive = true

        val readerJs = """
            (function() {
                var title = document.title || '';
                var paragraphs = document.querySelectorAll('p,h1,h2,h3,h4,img');
                var content = '';

                paragraphs.forEach(function(el) {
                    if (el.tagName === 'IMG') {
                        if (el.src && el.width > 300 && el.height > 200) {
                            content += '<img src="' + el.src +
                            '" style="max-width:100%;border-radius:12px;margin:16px 0;">';
                        }
                    } else {
                        var text = (el.innerText || '').trim();

                        if (text.length > 20 || el.tagName.startsWith('H')) {
                            content += '<' + el.tagName.toLowerCase() + '>' +
                                       text +
                                       '</' + el.tagName.toLowerCase() + '>';
                        }
                    }
                });

                if (!content) {
                    content = '<p>Could not extract article content.</p>';
                }

                var readerHtml =
                    '<html><head>' +
                    '<meta name="viewport" content="width=device-width,initial-scale=1">' +
                    '<style>' +
                    'body{background:#3A0A14;color:#FFF1F3;font-family:sans-serif;padding:24px;max-width:680px;margin:auto;line-height:1.6;}' +
                    'h1,h2,h3{color:#FF6B7D;}' +
                    'img{max-width:100%;display:block;margin:20px auto;}' +
                    '</style>' +
                    '</head><body>' +
                    '<h1>' + title + '</h1>' +
                    content +
                    '</body></html>';

                document.open();
                document.write(readerHtml);
                document.close();
            })();
        """.trimIndent()

        web.evaluateJavascript(readerJs, null)

        Toast.makeText(
            context,
            "Reader Mode active",
            Toast.LENGTH_SHORT
        ).show()
    }
}
