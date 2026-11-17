#!/usr/bin/env python3
"""End-to-end check that needs no internet: crawls a tiny local site, ranks, searches, hits the HTTP API."""
import hashlib
import json
import os
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import rwebsearch as rs

PAGES = {
    "/": '<html><head><title>Home of the test site</title></head><body><nav>menu menu menu</nav>'
         '<h1>Welcome</h1><p>This test site is about cooking, gardening and rockets. ' + "Filler text. " * 20 + '</p>'
         '<a href="/pasta">pasta</a> <a href="/garden">garden</a> <a href="/rocket">rocket</a> '
         '<a href="/pasta-copy">copy</a> <a href="/private/secret">secret</a> <a href="/nofollow-me" rel="nofollow">nf</a>'
         '<a href="/noindex">noindex</a><a href="/data.pdf">pdf</a></body></html>',
    "/pasta": '<html><head><title>How to cook pasta</title><meta name="description" content="Boil water, add pasta."></head>'
              '<body><p>Cooking pasta is easy. Boil salted water, add the pasta and cook for ten minutes. Drain the pasta and serve '
              'with tomato sauce. Pasta is a classic dish.</p><a href="/">home</a><a href="/garden">garden</a></body></html>',
    "/pasta-copy": '<html><head><title>How to cook pasta</title></head><body><p>Cooking pasta is easy. Boil salted water, add the pasta '
                   'and cook for ten minutes. Drain the pasta and serve with tomato sauce. Pasta is a classic dish.</p><a href="/">home</a><a href="/garden">garden</a></body></html>',
    "/garden": '<html><head><title>Gardening for beginners</title></head><body><p>Gardening starts with good soil. Water your plants, '
               'plant tomatoes in spring and pull weeds. Tomatoes love sun. ' + "Garden filler. " * 10 + '</p><a href="/">home</a></body></html>',
    "/rocket": '<html><head><title>Rockets and spaceflight</title></head><body><p>A rocket burns fuel to reach orbit. Rocket engines '
               'push gas out of a nozzle. Spaceflight needs powerful rockets.</p><a href="/">home</a><a href="/pasta">pasta</a></body></html>',
    "/noindex": '<html><head><title>Hidden page</title><meta name="robots" content="noindex"></head><body><p>This page should never '
                'show up in search results because it says noindex. ' + "Padding. " * 10 + '</p></body></html>',
    "/private/secret": '<html><head><title>Secret</title></head><body><p>Secret page blocked by robots.txt that mentions pasta too. '
                       + "Padding. " * 10 + '</p></body></html>',
    "/nofollow-me": '<html><head><title>Nofollow target</title></head><body><p>Only reachable by a nofollow link. ' + "Padding. " * 10 + '</p></body></html>',
}


class Site(BaseHTTPRequestHandler):
    n304 = 0

    def log_message(self, *a):
        pass

    def do_GET(self):
        etag = None
        if self.path == "/robots.txt":
            body, ctype = b"User-agent: *\nDisallow: /private/\n", "text/plain"
        elif self.path in PAGES:
            body, ctype = PAGES[self.path].encode(), "text/html; charset=utf-8"
            etag = '"%s"' % hashlib.md5(body).hexdigest()
            if self.headers.get("If-None-Match") == etag:
                Site.n304 += 1
                self.send_response(304)
                self.send_header("ETag", etag)
                self.end_headers()
                return
        elif self.path == "/data.pdf":
            body, ctype = b"%PDF", "application/pdf"
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        if etag:
            self.send_header("ETag", etag)
        self.end_headers()
        self.wfile.write(body)


def check(name, cond):
    print(("PASS  " if cond else "FAIL  ") + name)
    if not cond:
        raise SystemExit(1)


def main():
    site = ThreadingHTTPServer(("127.0.0.1", 0), Site)
    threading.Thread(target=site.serve_forever, daemon=True).start()
    base = "http://127.0.0.1:%d" % site.server_address[1]

    tmp = tempfile.mkdtemp()
    dbp = os.path.join(tmp, "t.db")
    db = rs.DB(dbp)
    indexed = rs.crawl(db, [base + "/"], max_pages=50, max_depth=2, delay=0.05, workers=3, allow_private=True, log=lambda m: None)
    rs.pagerank(db)

    urls = {r["url"].replace(base, "") for r in db.q("SELECT url FROM pages")}
    check("crawled the linked pages", {"/", "/pasta", "/garden", "/rocket"} <= urls)
    check("robots.txt respected (/private not indexed)", "/private/secret" not in urls)
    check("noindex respected", "/noindex" not in urls)
    check("nofollow link not followed", "/nofollow-me" not in urls)
    check("non-HTML (pdf) skipped", "/data.pdf" not in urls)
    check("duplicate content indexed once", ("/pasta" in urls) != ("/pasta-copy" in urls))

    r = rs.search(dbp, "cook pasta")
    check("stemmed search finds pasta page first", r["results"] and r["results"][0]["url"].endswith("/pasta"))
    check("snippet highlights terms", rs.HL_OPEN in r["results"][0]["snippet"])
    r = rs.search(dbp, "rocket")
    check("single-word search", r["total"] >= 1 and r["results"][0]["url"].endswith("/rocket"))
    r = rs.search(dbp, "gard")
    check("prefix match on last word", any(x["url"].endswith("/garden") for x in r["results"]))
    r = rs.search(dbp, '"tomato sauce"')
    check("phrase search", r["total"] == 1)
    r = rs.search(dbp, "pasta -garden")
    check("exclusion", all(not x["url"].endswith("/garden") for x in r["results"]) and r["total"] >= 1)
    r = rs.search(dbp, "pasta site:127.0.0.1")
    check("site: operator", r["total"] >= 1)
    r = rs.search(dbp, "pasta site:example.org")
    check("site: operator filters out", r["total"] == 0)
    r = rs.search(dbp, "zzzzqqq")
    check("no results handled", r["total"] == 0)
    r = rs.search(dbp, 'weird " ( ) * : ^ AND OR NOT')
    check("hostile query syntax does not crash", isinstance(r["total"], int))
    r = rs.search(dbp, "spaceflight tomatoes")
    check("falls back to OR when AND finds nothing", r["total"] >= 2)

    # HTTP layer
    httpd = rs.ThreadingHTTPServer(("127.0.0.1", 0), rs.make_handler(dbp, rs.RateLimit(5), False))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    url = "http://127.0.0.1:%d" % httpd.server_address[1]
    get = lambda p: urllib.request.urlopen(url + p, timeout=5).read().decode()
    check("home page", "pages indexed" in get("/"))
    page = get("/search?q=cook+pasta")
    check("results page renders", "How to cook pasta" in page and "<mark>" in page)
    check("results page escapes input", "<script>" not in get("/search?q=%3Cscript%3Ealert(1)%3C/script%3E"))
    api = json.loads(get("/api/search?q=rocket"))
    check("JSON API", api["total"] >= 1 and "\x02" not in api["results"][0]["snippet"])
    check("opensearch description", "OpenSearchDescription" in get("/opensearch.xml"))
    codes = []
    for _ in range(8):
        try:
            urllib.request.urlopen(url + "/api/search?q=rocket", timeout=5)
            codes.append(200)
        except urllib.error.HTTPError as e:
            codes.append(e.code)
    check("rate limiter returns 429", 429 in codes)

    # re-crawl: one page changed, one disappeared, the rest unchanged (served as 304)
    PAGES["/rocket"] = PAGES["/rocket"].replace("spaceflight", "spaceflight and the ionosphere")
    del PAGES["/garden"]
    stats = rs.refresh(db, max_age=-5, limit=100, delay=0.01, allow_private=True, log=lambda m: None)
    check("refresh re-indexes a changed page", stats.get("updated", 0) >= 1 and any(
        x["url"].endswith("/rocket") for x in rs.search(dbp, "ionosphere")["results"]))
    check("refresh removes a page that now returns 404", stats.get("removed", 0) >= 1 and not any(
        x["url"].endswith("/garden") for x in rs.search(dbp, "gardening soil")["results"]))
    check("unchanged pages are answered with 304", stats.get("unchanged", 0) >= 1 and Site.n304 >= 1)
    old_db = os.path.join(tmp, "old.db")
    import sqlite3
    con = sqlite3.connect(old_db)
    con.executescript("CREATE TABLE pages(id INTEGER PRIMARY KEY, url TEXT UNIQUE NOT NULL, domain TEXT, title TEXT, "
                      "description TEXT, hash TEXT, fetched INTEGER, rank REAL DEFAULT 0, inlinks INTEGER DEFAULT 0);")
    con.close()
    rs.DB(old_db)
    cols = {r[1] for r in sqlite3.connect(old_db).execute("PRAGMA table_info(pages)")}
    check("older databases are migrated", {"etag", "lastmod", "fails"} <= cols)

    # optional fallback source (faked: no internet in the test)
    calls = []

    def fake(q):
        calls.append(q)
        return [{"title": "Pasta (fake)", "url": "https://en.wikipedia.org/wiki/Pasta", "domain": "en.wikipedia.org",
                 "snippet": "A staple " + rs.HL_OPEN + "pasta" + rs.HL_CLOSE + " food", "source": "Wikipedia"}]

    rs.PROVIDERS["wikipedia"] = fake
    h2 = rs.ThreadingHTTPServer(("127.0.0.1", 0), rs.make_handler(dbp, rs.RateLimit(100), False, "wikipedia", 10))
    threading.Thread(target=h2.serve_forever, daemon=True).start()
    u2 = "http://127.0.0.1:%d" % h2.server_address[1]
    g2 = lambda p: urllib.request.urlopen(u2 + p, timeout=5).read().decode()
    check("fallback fills thin results and is labelled", "More from Wikipedia" in g2("/search?q=zzqq+pasta") and calls)
    check("fallback shown in the JSON API", json.loads(g2("/api/search?q=zzqq+pasta"))["external"][0]["source"] == "Wikipedia")
    n = len(calls)
    g2("/search?q=zzqq+pasta")
    check("fallback answers are cached", len(calls) == n)
    check("fallback not used on page 2", "More from" not in g2("/search?q=zzqq+pasta&p=2"))
    h3 = rs.ThreadingHTTPServer(("127.0.0.1", 0), rs.make_handler(dbp, rs.RateLimit(100), False, "wikipedia", 0))
    threading.Thread(target=h3.serve_forever, daemon=True).start()
    rs.EXT_CACHE.clear()
    n = len(calls)
    urllib.request.urlopen("http://127.0.0.1:%d/search?q=pasta" % h3.server_address[1], timeout=5).read()
    check("fallback skipped when local results are enough", len(calls) == n)

    # SSRF guard
    rs.CONFIG["allow_private"] = False
    check("private addresses blocked by default", not rs.is_public("http://127.0.0.1/"))
    print("\nall checks passed (%d pages indexed)" % indexed)


if __name__ == "__main__":
    main()
