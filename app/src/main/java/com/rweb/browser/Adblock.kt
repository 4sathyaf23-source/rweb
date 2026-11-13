private var isReaderModeActive = false

    private fun toggleReaderMode() {
        val web = currentOrNull()?.web ?: return
        if (isReaderModeActive) {
            // Reload page to exit reader mode
            isReaderModeActive = false
            web.reload()
            toast("Exited Reader Mode")
        } else {
            isReaderModeActive = true
            val readerJs = """
                (function() {
                    // Simple text & heading extraction for reader view
                    var title = document.title || '';
                    var paragraphs = document.querySelectorAll('p, h1, h2, h3, h4, img');
                    var content = '';
                    paragraphs.forEach(function(el) {
                        if (el.tagName === 'IMG') {
                            if (el.src && el.width > 300 && el.height > 200) {
                                content += '<img src="' + el.src + '" style="max-width:100%;border-radius:12px;margin:16px 0;">';
                            }
                        } else {
                            var text = el.innerText.trim();
                            if (text.length > 20 || el.tagName.startsWith('H')) {
                                content += '<' + el.tagName.toLowerCase() + '>' + text + '</' + el.tagName.toLowerCase() + '>';
                            }
                        }
                    });
                    
                    if (!content) {
                        content = '<p>Could not extract article content.</p>';
                    }

                    var readerHtml = '<html><head><meta name="viewport" content="width=device-width,initial-scale=1">' +
                        '<style>' +
                        'body { background: #3A0A14; color: #FFF1F3; font-family: sans-serif; padding: 24px 20px; max-width: 680px; margin: 0 auto; line-height: 1.6; }' +
                        'h1, h2, h3 { color: #FF6B7D; }' +
                        'h1 { font-size: 28px; margin-bottom: 8px; }' +
                        'p { font-size: 16px; margin-bottom: 16px; color: #FFF1F3; }' +
                        'img { display: block; margin: 20px auto; }' +
                        '</style></head><body>' +
                        '<h1>' + title + '</h1>' +
                        '<hr style="border:0;border-top:1px solid #7A1022;margin:16px 0 24px;">' +
                        content +
                        '</body></html>';
                    
                    document.open();
                    document.write(readerHtml);
                    document.close();
                })();
            """.trimIndent()
            web.evaluateJavascript(readerJs, null)
            toast("Reader Mode active")
        }
    }
