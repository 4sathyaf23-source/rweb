#!/usr/bin/env python3
"""
rweb-search: a small, self-hostable web search engine.

  crawl  - polite crawler (robots.txt, per-domain delay, size/type limits, SSRF guard)
  rank   - PageRank-style link score over everything crawled so far
  serve  - results page for phones + JSON API (/search?q=..., /api/search?q=...)
  query  - run a search from the command line
  stats  - what is in the index

Index: SQLite FTS5 (porter stemming, BM25 with title weighted above body),
re-ranked with link popularity and a per-domain diversity cap.
Standard library only. Python 3.9+.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import html
import ipaddress
import json
import math
import os
import re
import socket
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import urllib.robotparser
from html.parser import HTMLParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from string import Template

BOT = "rweb-bot"
UA = BOT + "/0.1 (personal search engine; honours robots.txt)"
CONFIG = {"allow_private": False}

SCHEMA = """
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS pages(
  id INTEGER PRIMARY KEY,
  url TEXT UNIQUE NOT NULL,
  domain TEXT,
  title TEXT,
  description TEXT,
  hash TEXT,
  fetched INTEGER,
  rank REAL DEFAULT 0,
  inlinks INTEGER DEFAULT 0,
  etag TEXT,
  lastmod TEXT,
  fails INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS pages_hash ON pages(hash);
CREATE INDEX IF NOT EXISTS pages_domain ON pages(domain);
CREATE TABLE IF NOT EXISTS links(src INTEGER, dst TEXT, UNIQUE(src, dst));
CREATE INDEX IF NOT EXISTS links_dst ON links(dst);
CREATE TABLE IF NOT EXISTS frontier(
  url TEXT PRIMARY KEY,
  depth INTEGER,
  status INTEGER DEFAULT 0,   -- 0 pending, 1 done, 2 skipped/failed
  added INTEGER
);
CREATE VIRTUAL TABLE IF NOT EXISTS pages_fts USING fts5(title, body, tokenize='porter unicode61');
"""

# ---------------------------------------------------------------- URLs

TRACKING = re.compile(r"^(utm_|fbclid$|gclid$|mc_eid$|ref$|ref_src$|igshid$)", re.I)
SKIP_EXT = re.compile(
    r"\.(jpe?g|png|gif|svg|webp|ico|pdf|zip|gz|tar|rar|7z|mp3|mp4|avi|mov|mkv|webm|css|js|json|xml|rss|"
    r"exe|dmg|apk|iso|woff2?|ttf|eot|docx?|xlsx?|pptx?)$",
    re.I,
)


def canon(url, base=None):
    """Normalise a URL for dedupe; None if it is not crawlable."""
    try:
        if base:
            url = urllib.parse.urljoin(base, url)
        p = urllib.parse.urlsplit(url.strip())
        if p.scheme not in ("http", "https") or not p.hostname or len(url) > 2000:
            return None
        host = p.hostname.lower().rstrip(".")
        port = p.port
    except ValueError:
        return None
    if ":" in host:
        host = "[" + host + "]"
    default = 80 if p.scheme == "http" else 443
    netloc = host if port in (None, default) else "%s:%d" % (host, port)
    path = p.path or "/"
    if SKIP_EXT.search(path):
        return None
    q = [(k, v) for k, v in urllib.parse.parse_qsl(p.query, keep_blank_values=True) if not TRACKING.match(k)]
    return urllib.parse.urlunsplit((p.scheme, netloc, path, urllib.parse.urlencode(q), ""))


def is_public(url):
    """False for loopback/private/link-local targets (SSRF guard)."""
    if CONFIG["allow_private"]:
        return True
    try:
        host = urllib.parse.urlsplit(url).hostname
        infos = socket.getaddrinfo(host, None)
        return bool(infos) and all(ipaddress.ip_address(i[4][0].split("%")[0]).is_global for i in infos)
    except Exception:
        return False


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not is_public(newurl):
            raise urllib.error.URLError("redirect to a non-public address blocked")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


OPENER = urllib.request.build_opener(SafeRedirect())


def decode(raw, ctype):
    m = re.search(r"charset=([\w-]+)", ctype or "", re.I)
    enc = m.group(1) if m else None
    if not enc:
        m = re.search(rb"<meta[^>]+charset=[\"']?([\w-]+)", raw[:4096], re.I)
        enc = m.group(1).decode("ascii", "ignore") if m else "utf-8"
    try:
        return raw.decode(enc, errors="replace")
    except LookupError:
        return raw.decode("utf-8", errors="replace")


def fetch(url, timeout=10, max_bytes=1_000_000, etag=None, lastmod=None):
    """None if the URL is not HTML, else {status, final, text, etag, lastmod}. status 304 = unchanged since last fetch."""
    headers = {"User-Agent": UA, "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1", "Accept-Language": "en"}
    if etag:
        headers["If-None-Match"] = etag
    if lastmod:
        headers["If-Modified-Since"] = lastmod
    req = urllib.request.Request(url, headers=headers)
    try:
        with OPENER.open(req, timeout=timeout) as r:
            ctype = r.headers.get("Content-Type", "")
            if not re.match(r"\s*(text/html|application/xhtml)", ctype, re.I):
                return None
            raw = r.read(max_bytes + 1)[:max_bytes]
            return {
                "status": 200,
                "final": r.geturl(),
                "text": decode(raw, ctype),
                "etag": r.headers.get("ETag"),
                "lastmod": r.headers.get("Last-Modified"),
            }
    except urllib.error.HTTPError as e:
        if e.code == 304:
            return {"status": 304, "final": url, "text": "", "etag": etag, "lastmod": lastmod}
        raise


# ---------------------------------------------------------------- robots.txt


class Robots:
    def __init__(self):
        self.cache = {}
        self.lock = threading.Lock()

    def _load(self, base):
        rp = urllib.robotparser.RobotFileParser()
        try:
            req = urllib.request.Request(base + "/robots.txt", headers={"User-Agent": UA})
            with OPENER.open(req, timeout=5) as r:
                rp.parse(r.read(500_000).decode("utf-8", "replace").splitlines())
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                rp.disallow_all = True
            else:
                rp.allow_all = True
        except Exception:
            rp.allow_all = True
        return rp

    def get(self, url):
        p = urllib.parse.urlsplit(url)
        base = "%s://%s" % (p.scheme, p.netloc)
        with self.lock:
            rp = self.cache.get(base)
        if rp is None:
            rp = self._load(base)
            with self.lock:
                self.cache[base] = rp
        return rp

    def allowed(self, url):
        return self.get(url).can_fetch(BOT, url)

    def delay(self, url):
        try:
            d = self.get(url).crawl_delay(BOT)
            return float(d) if d else 0.0
        except Exception:
            return 0.0


# ---------------------------------------------------------------- HTML extraction


class Extract(HTMLParser):
    SKIP = {"script", "style", "noscript", "template", "svg", "nav", "footer", "aside", "form", "iframe", "select", "button"}
    HARD = {"script", "style", "noscript", "template", "svg"}

    def __init__(self):
        super().__init__()
        self.title = ""
        self.h1 = ""
        self.desc = ""
        self.og_title = ""
        self.base = None
        self.noindex = False
        self.nofollow = False
        self.links = []  # (href, nofollow)
        self.body = []
        self.all_text = []
        self._skip = 0
        self._hard = 0
        self._in_title = False
        self._in_h1 = False

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "title":
            self._in_title = True
        elif tag == "h1":
            self._in_h1 = True
        elif tag == "base" and a.get("href") and not self.base:
            self.base = a["href"]
        elif tag == "meta":
            name = (a.get("name") or a.get("property") or "").lower()
            content = a.get("content") or ""
            if name in ("description", "og:description") and not self.desc:
                self.desc = content.strip()
            elif name == "og:title":
                self.og_title = content.strip()
            elif name == "robots":
                c = content.lower()
                self.noindex = self.noindex or "noindex" in c
                self.nofollow = self.nofollow or "nofollow" in c
        elif tag == "a" and a.get("href"):
            self.links.append((a["href"], "nofollow" in (a.get("rel") or "").lower()))
        if tag in self.HARD:
            self._hard += 1
        if tag in self.SKIP:
            self._skip += 1
        self.body.append(" ")
        self.all_text.append(" ")

    def handle_endtag(self, tag):
        if tag == "title":
            self._in_title = False
        elif tag == "h1":
            self._in_h1 = False
        if tag in self.HARD and self._hard:
            self._hard -= 1
        if tag in self.SKIP and self._skip:
            self._skip -= 1
        self.body.append(" ")
        self.all_text.append(" ")

    def handle_data(self, data):
        if self._in_title:
            self.title += data
        if self._hard:
            return
        if self._in_h1 and len(self.h1) < 200:
            self.h1 += data
        self.all_text.append(data)
        if not self._skip:
            self.body.append(data)

    def result(self):
        squash = lambda s: re.sub(r"\s+", " ", s).strip()
        body = squash("".join(self.body))
        if len(body) < 200:  # boilerplate filter ate everything (e.g. unclosed <nav>): keep all text
            body = squash("".join(self.all_text))
        title = squash(self.title) or squash(self.og_title) or squash(self.h1)
        return title[:300], squash(self.desc)[:400], body[:100_000]


def analyze(final, text):
    """Parse one fetched page into everything the indexer needs."""
    ex = Extract()
    try:
        ex.feed(text)
        ex.close()
    except Exception:
        pass
    title, desc, body = ex.result()
    base = ex.base and urllib.parse.urljoin(final, ex.base) or final
    links, seen = [], set()
    for href, nf in ex.links:
        if href.startswith(("#", "javascript:", "mailto:", "tel:")):
            continue
        u = canon(href, base)
        if u and u != final and u not in seen:
            seen.add(u)
            links.append((u, nf))
        if len(links) >= 300:
            break
    return {
        "title": title,
        "desc": desc,
        "body": body,
        "links": links,
        "noindex": ex.noindex,
        "nofollow": ex.nofollow,
        "digest": hashlib.sha1(body.encode("utf-8", "ignore")).hexdigest(),
    }


# ---------------------------------------------------------------- storage


class DB:
    def __init__(self, path):
        self.path = path
        self.c = sqlite3.connect(path, check_same_thread=False, timeout=30)
        self.c.row_factory = sqlite3.Row
        self.lock = threading.Lock()
        with self.lock:
            self.c.executescript(SCHEMA)
            have = {r[1] for r in self.c.execute("PRAGMA table_info(pages)")}
            for col, ddl in (("etag", "TEXT"), ("lastmod", "TEXT"), ("fails", "INTEGER DEFAULT 0")):
                if col not in have:  # databases created before re-crawl support
                    self.c.execute("ALTER TABLE pages ADD COLUMN %s %s" % (col, ddl))
            self.c.commit()

    def q(self, sql, args=()):
        with self.lock:
            cur = self.c.execute(sql, args)
            self.c.commit()
            return cur.fetchall()

    def add_frontier(self, url, depth):
        """True if the URL was new."""
        with self.lock:
            cur = self.c.execute(
                "INSERT OR IGNORE INTO frontier(url, depth, added) VALUES(?,?,?)", (url, depth, int(time.time()))
            )
            self.c.commit()
            return cur.rowcount == 1

    def set_status(self, url, status):
        self.q("UPDATE frontier SET status=? WHERE url=?", (status, url))

    def upsert_page(self, url, title, desc, body, digest, links, etag=None, lastmod=None):
        domain = urllib.parse.urlsplit(url).netloc
        with self.lock:
            c = self.c
            row = c.execute("SELECT id FROM pages WHERE url=?", (url,)).fetchone()
            if row:
                pid = row[0]
                c.execute(
                    "UPDATE pages SET title=?, description=?, hash=?, fetched=?, etag=?, lastmod=?, fails=0 WHERE id=?",
                    (title, desc, digest, int(time.time()), etag, lastmod, pid),
                )
                c.execute("DELETE FROM pages_fts WHERE rowid=?", (pid,))
                c.execute("DELETE FROM links WHERE src=?", (pid,))
            else:
                pid = c.execute(
                    "INSERT INTO pages(url, domain, title, description, hash, fetched, etag, lastmod) VALUES(?,?,?,?,?,?,?,?)",
                    (url, domain, title, desc, digest, int(time.time()), etag, lastmod),
                ).lastrowid
            c.execute("INSERT INTO pages_fts(rowid, title, body) VALUES(?,?,?)", (pid, title, body))
            c.executemany("INSERT OR IGNORE INTO links(src, dst) VALUES(?,?)", [(pid, d) for d in links])
            c.commit()
            return pid

    def touch_page(self, url, etag=None, lastmod=None):
        self.q("UPDATE pages SET fetched=?, etag=COALESCE(?, etag), lastmod=COALESCE(?, lastmod), fails=0 WHERE url=?",
               (int(time.time()), etag, lastmod, url))

    def delete_page(self, url):
        with self.lock:
            row = self.c.execute("SELECT id FROM pages WHERE url=?", (url,)).fetchone()
            if row:
                self.c.execute("DELETE FROM pages_fts WHERE rowid=?", (row[0],))
                self.c.execute("DELETE FROM links WHERE src=?", (row[0],))
                self.c.execute("DELETE FROM pages WHERE id=?", (row[0],))
            self.c.commit()

    def bump_fail(self, url, max_fails=5):
        """A failed re-check: retry on the next cycle, drop the page after repeated failures."""
        self.q("UPDATE pages SET fails=fails+1, fetched=? WHERE url=?", (int(time.time()), url))
        rows = self.q("SELECT fails FROM pages WHERE url=?", (url,))
        if rows and rows[0][0] >= max_fails:
            self.delete_page(url)
            return True
        return False


# ---------------------------------------------------------------- crawler


def crawl(db, seeds, max_pages=200, max_depth=2, per_domain=100, delay=1.0, workers=4, allow_private=False, log=print):
    CONFIG["allow_private"] = allow_private
    robots = Robots()
    for s in seeds:
        u = canon(s)
        if u:
            db.add_frontier(u, 0)
    queue = sorted(((r["url"], r["depth"]) for r in db.q("SELECT url, depth FROM frontier WHERE status=0")), key=lambda x: x[1])
    dom_count = collections.Counter({r["domain"]: r["n"] for r in db.q("SELECT domain, count(*) AS n FROM pages GROUP BY domain")})
    next_ok = {}
    lock = threading.Lock()
    state = {"taken": 0, "active": 0, "indexed": 0}

    def take():
        while True:
            dropped = []
            with lock:
                if state["taken"] >= max_pages:
                    return None
                now = time.time()
                pick, keep = None, []
                for item in queue:
                    if pick is not None:
                        keep.append(item)
                        continue
                    dom = urllib.parse.urlsplit(item[0]).netloc
                    if dom_count[dom] >= per_domain:
                        dropped.append(item[0])
                    elif next_ok.get(dom, 0) <= now:
                        pick = item
                    else:
                        keep.append(item)
                queue[:] = keep
                if pick is not None:
                    dom = urllib.parse.urlsplit(pick[0]).netloc
                    dom_count[dom] += 1
                    state["taken"] += 1
                    state["active"] += 1
                    next_ok[dom] = now + 3600  # reserved until this fetch finishes
                elif not queue and state["active"] == 0:
                    return None
            for u in dropped:
                db.set_status(u, 2)
            if pick is not None:
                return pick
            time.sleep(0.05)

    def process(url, depth):
        dom = urllib.parse.urlsplit(url).netloc
        wait = delay
        try:
            if not is_public(url) or not robots.allowed(url):
                db.set_status(url, 2)
                return
            wait = max(delay, robots.delay(url))
            got = fetch(url)
            if got is None:
                db.set_status(url, 2)
                return
            final = canon(got["final"]) or url
            a = analyze(final, got["text"])
            links = a["links"]
            if not a["noindex"] and len(a["body"]) >= 50:
                dup = db.q("SELECT url FROM pages WHERE hash=? AND url!=?", (a["digest"], final))
                if dup:
                    log("  duplicate of %s" % dup[0]["url"])
                else:
                    db.upsert_page(final, a["title"], a["desc"], a["body"], a["digest"], [u for u, _ in links], got["etag"], got["lastmod"])
                    with lock:
                        state["indexed"] += 1
            db.set_status(url, 1)
            if final != url:
                db.add_frontier(final, depth)
                db.set_status(final, 1)
            if depth < max_depth and not a["nofollow"]:
                new = []
                for u, nf in links:
                    if not nf and db.add_frontier(u, depth + 1):
                        new.append((u, depth + 1))
                with lock:
                    queue.extend(new)
            log("[%d] %s (%d chars) %s" % (state["taken"], a["title"][:50] or "(no title)", len(a["body"]), final))
        except Exception as e:
            db.set_status(url, 2)
            log("  skip %s: %s" % (url, str(e)[:80]))
        finally:
            with lock:
                next_ok[dom] = time.time() + wait
                state["active"] -= 1

    def worker():
        while True:
            item = take()
            if item is None:
                return
            process(*item)

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(max(1, workers))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return state["indexed"]


# ---------------------------------------------------------------- ranking


def pagerank(db, iters=30, damping=0.85):
    ids = {r["url"]: r["id"] for r in db.q("SELECT id, url FROM pages")}
    n = len(ids)
    if n == 0:
        return 0
    out = collections.defaultdict(set)
    inl = collections.Counter()
    for r in db.q("SELECT src, dst FROM links"):
        d = ids.get(r["dst"])
        if d is not None and d != r["src"] and d not in out[r["src"]]:
            out[r["src"]].add(d)
            inl[d] += 1
    pr = {i: 1.0 / n for i in ids.values()}
    for _ in range(iters):
        dangling = sum(pr[i] for i in pr if not out.get(i))
        new = {i: (1 - damping) / n + damping * dangling / n for i in pr}
        for src, dsts in out.items():
            share = damping * pr[src] / len(dsts)
            for d in dsts:
                new[d] += share
        pr = new
    top = max(pr.values()) or 1.0
    with db.lock:
        db.c.executemany("UPDATE pages SET rank=?, inlinks=? WHERE id=?", [(pr[i] / top, inl[i], i) for i in pr])
        db.c.commit()
    return n


# ---------------------------------------------------------------- keeping the index fresh


def refresh(db, max_age=7 * 86400, limit=200, delay=1.0, allow_private=False, log=print):
    """Re-check the stalest pages. 304 = unchanged, 404/410 or noindex = removed, changed = re-indexed."""
    CONFIG["allow_private"] = allow_private
    robots = Robots()
    stats = collections.Counter()
    cutoff = int(time.time()) - max_age
    rows = db.q("SELECT url, hash, etag, lastmod FROM pages WHERE fetched < ? ORDER BY fetched LIMIT ?", (cutoff, limit))
    next_ok = {}
    for r in rows:
        url = r["url"]
        dom = urllib.parse.urlsplit(url).netloc
        time.sleep(max(0.0, next_ok.get(dom, 0) - time.time()))
        wait = delay
        try:
            if not is_public(url) or not robots.allowed(url):
                db.delete_page(url)
                stats["removed"] += 1
                continue
            wait = max(delay, robots.delay(url))
            got = fetch(url, etag=r["etag"], lastmod=r["lastmod"])
            if got is None:  # no longer an HTML page
                db.delete_page(url)
                stats["removed"] += 1
                continue
            if got["status"] == 304:
                db.touch_page(url, got["etag"], got["lastmod"])
                stats["unchanged"] += 1
                continue
            final = canon(got["final"]) or url
            a = analyze(final, got["text"])
            if a["noindex"] or len(a["body"]) < 50:
                db.delete_page(url)
                stats["removed"] += 1
                continue
            if final != url:  # moved: keep the new address only
                db.delete_page(url)
            if final == url and a["digest"] == r["hash"]:
                db.touch_page(url, got["etag"], got["lastmod"])
                stats["unchanged"] += 1
            else:
                db.upsert_page(final, a["title"], a["desc"], a["body"], a["digest"], [u for u, _ in a["links"]], got["etag"], got["lastmod"])
                stats["updated"] += 1
                for u, nf in a["links"]:
                    if not nf:
                        db.add_frontier(u, 1)  # new links are picked up by the next crawl pass
        except urllib.error.HTTPError as e:
            if e.code in (404, 410):
                db.delete_page(url)
                stats["removed"] += 1
            else:
                stats["errors"] += 1
                if db.bump_fail(url):
                    stats["removed"] += 1
        except Exception:
            stats["errors"] += 1
            if db.bump_fail(url):
                stats["removed"] += 1
        finally:
            next_ok[dom] = time.time() + wait
    log("refresh: checked %d, unchanged %d, updated %d, removed %d, errors %d"
        % (len(rows), stats["unchanged"], stats["updated"], stats["removed"], stats["errors"]))
    return dict(stats)


def maintenance(db_path, every_hours, refresh_limit, crawl_batch, max_age_days, delay, stop):
    """Background loop used by `serve`: refresh stale pages, crawl newly found links, recompute ranks."""
    db = DB(db_path)
    log = lambda m: sys.stderr.write("[maintenance] %s\n" % m)
    if stop.wait(60):
        return
    while True:
        try:
            refresh(db, int(max_age_days * 86400), refresh_limit, delay, False, log=log)
            if crawl_batch:
                n = crawl(db, [], crawl_batch, 2, 100, delay, 2, False, log=lambda m: None)
                log("crawl: %d new pages" % n)
            log("ranked %d pages" % pagerank(db))
        except Exception as e:
            log("error: %r" % (e,))
        if stop.wait(every_hours * 3600):
            return


# ---------------------------------------------------------------- search

STOP = {"the", "a", "an", "of", "to", "in", "and", "or", "is", "are", "for", "on", "with", "as", "at", "by", "it", "be"}
HL_OPEN, HL_CLOSE = "\x02", "\x03"


def parse_query(q):
    q = (q or "")[:200]
    site = None
    m = re.search(r"\bsite:([\w.\-]+)", q)
    if m:
        site = m.group(1).lower()
        q = q.replace(m.group(0), " ")
    phrases = [" ".join(re.findall(r"\w+", p)) for p in re.findall(r'"([^"]+)"', q)]
    phrases = [p for p in phrases if p]
    rest = re.sub(r'"[^"]*"', " ", q)
    pos, neg = [], []
    for tok in re.findall(r"(?:(?<=\s)|^)-\w+|\w+", rest):
        (neg if tok.startswith("-") else pos).append(tok.lstrip("-").lower())
    content = [t for t in pos if t not in STOP]
    if content:
        pos = content
    return pos, phrases, neg, site


def fts_expr(pos, phrases, neg, mode="and"):
    parts = []
    for i, t in enumerate(pos):
        s = '"%s"' % t
        if mode == "and" and i == len(pos) - 1 and not phrases and len(t) >= 2:
            s += "*"  # type-ahead style match on the last word
        parts.append(s)
    parts += ['"%s"' % p for p in phrases]
    if not parts:
        return None
    expr = (" OR " if mode == "or" else " ").join(parts)
    if neg and mode == "and":
        expr = "(%s) NOT (%s)" % (expr, " OR ".join('"%s"' % n for n in neg))
    return expr


def search(db_path, q, page=1, per=10):
    t0 = time.time()
    pos, phrases, neg, site = parse_query(q)
    empty = {"query": q, "total": 0, "page": page, "per": per, "results": [], "took_ms": 0}
    if not (pos or phrases):
        return empty
    con = sqlite3.connect(db_path, timeout=30)
    con.row_factory = sqlite3.Row
    sql = (
        "SELECT p.url, p.title, p.description, p.domain, p.rank, p.inlinks, bm25(pages_fts, 6.0, 1.0) AS s, "
        "snippet(pages_fts, 1, '%s', '%s', ' \u2026 ', 28) AS snip "
        "FROM pages_fts JOIN pages p ON p.id = pages_fts.rowid WHERE pages_fts MATCH ?" % (HL_OPEN, HL_CLOSE)
    )
    args = []
    if site:
        sql += " AND (p.domain = ? OR p.domain LIKE ? OR p.domain LIKE ?)"
        args += [site, site + ":%", "%." + site]
    sql += " ORDER BY s LIMIT 300"
    rows = []
    try:
        for mode in ("and", "or"):
            expr = fts_expr(pos, phrases, neg, mode)
            if not expr:
                continue
            rows = con.execute(sql, [expr] + args).fetchall()
            if rows or len(pos) + len(phrases) < 2:
                break
    except sqlite3.OperationalError:
        rows = []
    finally:
        con.close()

    scored = []
    for r in rows:
        relevance = -r["s"]  # bm25() is lower-is-better and negative
        boost = 1.0 + 1.5 * math.sqrt(max(r["rank"], 0.0))
        scored.append((relevance * boost, r))
    scored.sort(key=lambda x: -x[0])

    # diversity: at most 2 results per domain ahead of the rest
    seen = collections.Counter()
    first, later = [], []
    for sc, r in scored:
        seen[r["domain"]] += 1
        (first if seen[r["domain"]] <= 2 else later).append((sc, r))
    ordered = first + later

    start = (page - 1) * per
    results = []
    for sc, r in ordered[start:start + per]:
        snip = r["snip"] or r["description"] or ""
        results.append(
            {
                "title": r["title"] or r["url"],
                "url": r["url"],
                "domain": r["domain"],
                "snippet": snip,
                "score": round(sc, 6),
            }
        )
    return {
        "query": q,
        "total": len(ordered),
        "page": page,
        "per": per,
        "results": results,
        "took_ms": int((time.time() - t0) * 1000),
    }


# ---------------------------------------------------------------- optional fallback source
# Off by default. When enabled and local results are thin, the query is also sent to the provider (the query
# leaves your server) and its hits are shown below yours, labelled with their source.

EXT_CACHE = {}
EXT_LOCK = threading.Lock()


def wikipedia(q, limit=5, timeout=3.0):
    lang = CONFIG.get("wiki_lang", "en")
    url = "https://%s.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=%d&srsearch=%s" % (
        lang, limit, urllib.parse.quote(q))
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = json.loads(r.read(500_000).decode("utf-8", "replace"))
    out = []
    for h in data.get("query", {}).get("search", []):
        title = h.get("title", "")
        snip = re.sub(r'<span class="searchmatch">(.*?)</span>', lambda m: HL_OPEN + m.group(1) + HL_CLOSE, h.get("snippet", ""))
        snip = html.unescape(re.sub(r"<[^>]+>", "", snip))
        out.append({
            "title": title,
            "url": "https://%s.wikipedia.org/wiki/%s" % (lang, urllib.parse.quote(title.replace(" ", "_"))),
            "domain": "%s.wikipedia.org" % lang,
            "snippet": snip,
            "source": "Wikipedia",
        })
    return out


PROVIDERS = {"wikipedia": wikipedia}


def external_results(q, provider, ttl=600):
    fn = PROVIDERS.get(provider)
    if not fn or not q:
        return []
    key = (provider, q.lower().strip())
    now = time.time()
    with EXT_LOCK:
        hit = EXT_CACHE.get(key)
        if hit and hit[0] > now:
            return hit[1]
    try:
        res = fn(q)
    except Exception:
        return []  # failures are not cached
    with EXT_LOCK:
        if len(EXT_CACHE) > 500:
            EXT_CACHE.clear()
        EXT_CACHE[key] = (now + ttl, res)
    return res


# ---------------------------------------------------------------- web UI

CSS = """
:root{--bg:#fff;--fg:#202124;--mute:#5f6368;--card:#f8f9fa;--fld:#f1f3f4;--acc:#c41e3a;--link:#1a0dab;--sh:0 4px 14px rgba(0,0,0,.08)}
@media(prefers-color-scheme:dark){:root{--bg:#3a0a14;--fg:#fff1f3;--mute:#e3a9b1;--card:#7a1022;--fld:#7a1022;--acc:#ff6b7d;--link:#ffb3be;--sh:0 4px 14px rgba(0,0,0,.35)}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font-family:system-ui,sans-serif;line-height:1.45}
a{color:var(--link);text-decoration:none}
.logo{color:var(--acc);font-weight:700}
form{margin:0}input[type=search]{width:100%;padding:14px 20px;border-radius:28px;border:0;background:var(--fld);color:var(--fg);font-size:16px;box-shadow:var(--sh);outline:none}
.home{min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:24px 16px}
.home h1{font-size:42px;margin:0 0 22px}.home form{width:100%;max-width:520px}.meta{margin-top:18px;color:var(--mute);font-size:13px}
header{display:flex;gap:12px;align-items:center;padding:12px 14px;position:sticky;top:0;background:var(--bg);z-index:2}
header .logo{font-size:22px}header form{flex:1}
main{max-width:720px;margin:0 auto;padding:4px 16px 40px}
.info{color:var(--mute);font-size:13px;margin:6px 0 14px}
.r{margin:0 0 22px}.u{color:var(--mute);font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.t{font-size:18px;display:block;margin:2px 0}.s{font-size:14px;color:var(--fg);opacity:.9;word-wrap:break-word}
mark{background:none;color:var(--acc);font-weight:600}
.pager{display:flex;justify-content:space-between;margin-top:10px}.pager a{padding:10px 18px;border-radius:20px;background:var(--fld);color:var(--fg)}
.none{margin-top:40px;text-align:center;color:var(--mute)}
.src{color:var(--acc)}
"""

LOGO = '<svg width="%d" height="%d" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"/></svg>'

PAGE = Template(
    """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
<link rel="search" type="application/opensearchdescription+xml" title="rweb" href="/opensearch.xml">
<style>$css</style></head><body>$body</body></html>"""
)


def esc(s):
    return html.escape(s or "", quote=True)


def highlight(snippet):
    return esc(snippet).replace(esc(HL_OPEN), "<mark>").replace(esc(HL_CLOSE), "</mark>")


def display_url(url):
    p = urllib.parse.urlsplit(url)
    path = urllib.parse.unquote(p.path).rstrip("/")
    return p.netloc + (" \u203a " + path.strip("/").replace("/", " \u203a ") if path else "")


def home_page(n_pages):
    body = (
        '<div class="home"><div class="logo">%s</div><h1 class="logo">rweb</h1>'
        '<form action="/search" method="get"><input type="search" name="q" placeholder="Search" autofocus autocomplete="off"></form>'
        '<div class="meta">%s pages indexed</div></div>' % (LOGO % (56, 56), format(n_pages, ","))
    )
    return PAGE.substitute(title="rweb search", css=CSS, body=body)


def result_item(r):
    src = '<span class="src"> \u00b7 %s</span>' % esc(r["source"]) if r.get("source") else ""
    return (
        '<div class="r"><div class="u">%s%s</div><a class="t" rel="noopener noreferrer" href="%s">%s</a><div class="s">%s</div></div>'
        % (esc(display_url(r["url"])), src, esc(r["url"]), esc(r["title"]), highlight(r["snippet"]))
    )


def results_page(res, external=None):
    q = res["query"]
    external = external or []
    items = [result_item(r) for r in res["results"]]
    if external:
        items.append('<div class="info">More from %s</div>' % esc(external[0].get("source", "other sources")))
        items += [result_item(r) for r in external]
    if not items:
        items.append('<div class="none">No results for <b>%s</b>.<br>Try fewer or different words.</div>' % esc(q))
    pager = []
    qs = urllib.parse.quote_plus(q)
    if res["page"] > 1:
        pager.append('<a href="/search?q=%s&p=%d">\u2039 Previous</a>' % (qs, res["page"] - 1))
    else:
        pager.append("<span></span>")
    if res["page"] * res["per"] < res["total"]:
        pager.append('<a href="/search?q=%s&p=%d">Next \u203a</a>' % (qs, res["page"] + 1))
    body = (
        '<header><a class="logo" href="/">%s</a><form action="/search" method="get">'
        '<input type="search" name="q" value="%s" autocomplete="off"></form></header>'
        '<main><div class="info">%s results (%d ms)</div>%s<div class="pager">%s</div></main>'
        % (LOGO % (28, 28), esc(q), format(res["total"], ","), res["took_ms"], "".join(items), "".join(pager))
    )
    return PAGE.substitute(title=esc(q) + " - rweb search", css=CSS, body=body)


class RateLimit:
    def __init__(self, per_minute):
        self.n = per_minute
        self.hits = collections.defaultdict(collections.deque)
        self.lock = threading.Lock()

    def ok(self, ip):
        now = time.time()
        with self.lock:
            d = self.hits[ip]
            while d and d[0] < now - 60:
                d.popleft()
            if len(d) >= self.n:
                return False
            d.append(now)
            if len(self.hits) > 5000:
                self.hits.clear()
            return True


def make_handler(db_path, limiter, trust_proxy, fallback=None, fallback_min=5):
    class H(BaseHTTPRequestHandler):
        server_version = "rweb-search"

        def log_message(self, fmt, *a):
            sys.stderr.write("%s %s\n" % (self.address_string(), fmt % a))

        def _send(self, code, ctype, data, extra=None):
            if isinstance(data, str):
                data = data.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(data)

        def _ip(self):
            if trust_proxy and self.headers.get("X-Forwarded-For"):
                return self.headers["X-Forwarded-For"].split(",")[0].strip()
            return self.client_address[0]

        def _external(self, q, res, page):
            """Fallback hits, only on page 1 and only when local results are thin."""
            if not fallback or page != 1 or res["total"] >= fallback_min:
                return []
            pos, phrases, _, _ = parse_query(q)
            terms = " ".join(pos + phrases).strip()
            have = {r["url"] for r in res["results"]}
            return [e for e in external_results(terms, fallback) if e["url"] not in have]

        def do_GET(self):
            u = urllib.parse.urlsplit(self.path)
            qs = urllib.parse.parse_qs(u.query)
            first = lambda k, d="": (qs.get(k) or [d])[0]
            if u.path in ("/search", "/api/search") and not limiter.ok(self._ip()):
                return self._send(429, "text/plain; charset=utf-8", "Too many requests", {"Retry-After": "30"})
            try:
                if u.path == "/":
                    con = sqlite3.connect(db_path)
                    n = con.execute("SELECT count(*) FROM pages").fetchone()[0]
                    con.close()
                    return self._send(200, "text/html; charset=utf-8", home_page(n))
                if u.path == "/search":
                    q = first("q").strip()
                    if not q:
                        return self._send(302, "text/plain", "", {"Location": "/"})
                    page = min(max(int(first("p", "1") or 1), 1), 30)
                    res = search(db_path, q, page)
                    return self._send(200, "text/html; charset=utf-8", results_page(res, self._external(q, res, page)))
                if u.path == "/api/search":
                    page = min(max(int(first("p", "1") or 1), 1), 30)
                    per = min(max(int(first("n", "10") or 10), 1), 50)
                    res = search(db_path, first("q"), page, per)
                    res["external"] = self._external(first("q"), res, page)
                    for r in res["results"] + res["external"]:
                        r["snippet"] = r["snippet"].replace(HL_OPEN, "").replace(HL_CLOSE, "")
                    return self._send(200, "application/json; charset=utf-8", json.dumps(res), {"Access-Control-Allow-Origin": "*"})
                if u.path == "/opensearch.xml":
                    proto = self.headers.get("X-Forwarded-Proto", "http") if trust_proxy else "http"
                    base = "%s://%s" % (proto, self.headers.get("Host", "localhost"))
                    xml = (
                        '<?xml version="1.0"?><OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">'
                        "<ShortName>rweb</ShortName><Description>rweb search</Description><InputEncoding>UTF-8</InputEncoding>"
                        '<Url type="text/html" template="%s/search?q={searchTerms}"/></OpenSearchDescription>' % esc(base)
                    )
                    return self._send(200, "application/opensearchdescription+xml", xml)
                if u.path == "/healthz":
                    return self._send(200, "text/plain", "ok")
                return self._send(404, "text/plain; charset=utf-8", "Not found")
            except ValueError:
                return self._send(400, "text/plain; charset=utf-8", "Bad request")
            except Exception as e:  # never leak internals
                sys.stderr.write("error: %r\n" % (e,))
                return self._send(500, "text/plain; charset=utf-8", "Server error")

    return H


def serve(db_path, host, port, rate=120, trust_proxy=False, fallback=None, fallback_min=5, maintain=None):
    DB(db_path)  # make sure the schema exists
    stop = threading.Event()
    if maintain:
        threading.Thread(target=maintenance, args=(db_path, *maintain, stop), daemon=True).start()
    httpd = ThreadingHTTPServer((host, port), make_handler(db_path, RateLimit(rate), trust_proxy, fallback, fallback_min))
    print("rweb-search listening on http://%s:%d  (db: %s)" % (host, port, db_path))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
    return httpd


# ---------------------------------------------------------------- CLI


def read_seeds(path):
    with open(path, encoding="utf-8") as f:
        return [ln.strip() for ln in f if ln.strip() and not ln.startswith("#")]


def main(argv=None):
    ap = argparse.ArgumentParser(description="rweb-search: crawl, rank and serve your own web index")
    ap.add_argument("--db", default="rweb.db")
    sub = ap.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("crawl")
    c.add_argument("seeds", nargs="?", help="text file with one start URL per line (omit to resume the saved frontier)")
    c.add_argument("--max-pages", type=int, default=200)
    c.add_argument("--depth", type=int, default=2)
    c.add_argument("--per-domain", type=int, default=100)
    c.add_argument("--delay", type=float, default=1.0, help="seconds between requests to one site")
    c.add_argument("--workers", type=int, default=4)
    c.add_argument("--allow-private", action="store_true", help="allow localhost/LAN targets (testing only)")

    sub.add_parser("rank")
    s = sub.add_parser("serve")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=8080)
    s.add_argument("--rate", type=int, default=120, help="max searches per IP per minute")
    s.add_argument("--trust-proxy", action="store_true", help="use X-Forwarded-For/Proto (behind a reverse proxy)")
    s.add_argument("--maintain-hours", type=float, default=24, help="background re-crawl interval; 0 turns it off (default 24)")
    s.add_argument("--refresh-limit", type=int, default=200, help="pages re-checked per maintenance run")
    s.add_argument("--crawl-batch", type=int, default=100, help="new pages crawled per maintenance run (0 = none)")
    s.add_argument("--max-age-days", type=float, default=7, help="a page is stale after this many days")
    s.add_argument("--maintain-delay", type=float, default=1.0)
    s.add_argument("--fallback", choices=["none", "wikipedia"], default="none",
                   help="when local results are thin, also show hits from this source (sends the query to it)")
    s.add_argument("--fallback-min", type=int, default=5, help="use the fallback when fewer local results than this")
    s.add_argument("--wiki-lang", default="en", help="Wikipedia language code for --fallback wikipedia")
    r = sub.add_parser("refresh", help="re-check the stalest pages once (use from cron if you do not run `serve`)")
    r.add_argument("--max-age-days", type=float, default=7)
    r.add_argument("--limit", type=int, default=200)
    r.add_argument("--delay", type=float, default=1.0)
    q = sub.add_parser("query")
    q.add_argument("text")
    sub.add_parser("stats")

    a = ap.parse_args(argv)
    if a.cmd == "crawl":
        db = DB(a.db)
        n = crawl(db, read_seeds(a.seeds) if a.seeds else [], a.max_pages, a.depth, a.per_domain, a.delay, a.workers, a.allow_private)
        print("indexed %d pages; computing ranks..." % n)
        pagerank(db)
    elif a.cmd == "rank":
        print("ranked %d pages" % pagerank(DB(a.db)))
    elif a.cmd == "serve":
        if not re.fullmatch(r"[a-z]{2,3}(-[a-z]+)?", a.wiki_lang):
            ap.error("--wiki-lang must look like en, de, pt-br")
        CONFIG["wiki_lang"] = a.wiki_lang
        maintain = None
        if a.maintain_hours > 0:
            maintain = (a.maintain_hours, a.refresh_limit, a.crawl_batch, a.max_age_days, a.maintain_delay)
        serve(a.db, a.host, a.port, a.rate, a.trust_proxy, None if a.fallback == "none" else a.fallback, a.fallback_min, maintain)
    elif a.cmd == "refresh":
        db = DB(a.db)
        refresh(db, int(a.max_age_days * 86400), a.limit, a.delay)
        print("ranked %d pages" % pagerank(db))
    elif a.cmd == "query":
        DB(a.db)
        res = search(a.db, a.text)
        print("%d results (%d ms)" % (res["total"], res["took_ms"]))
        for r in res["results"]:
            print("\n%s\n  %s\n  %s" % (r["title"], r["url"], r["snippet"].replace(HL_OPEN, "*").replace(HL_CLOSE, "*")))
    elif a.cmd == "stats":
        db = DB(a.db)
        one = lambda sql: db.q(sql)[0][0]
        print("pages:    %d" % one("SELECT count(*) FROM pages"))
        print("domains:  %d" % one("SELECT count(DISTINCT domain) FROM pages"))
        print("links:    %d" % one("SELECT count(*) FROM links"))
        print("pending:  %d" % one("SELECT count(*) FROM frontier WHERE status=0"))
        print("db size:  %.1f MB" % (os.path.getsize(a.db) / 1e6))


if __name__ == "__main__":
    main()
