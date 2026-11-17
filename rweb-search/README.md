# rweb-search

A small web search engine you host yourself: crawler, ranked index, mobile results page and JSON API.
Standard library only (Python 3.9+, SQLite with FTS5).

## Run it

    python3 selftest.py                       # offline check, should end with "all checks passed"
    python3 rwebsearch.py crawl seeds.txt --max-pages 500 --depth 2
    python3 rwebsearch.py serve --host 0.0.0.0 --port 8080
    python3 rwebsearch.py query "how to cook pasta"   # test from the command line

`crawl` can be re-run: it resumes the saved frontier. Add `--per-domain 50` to spread the budget over more
sites, `--delay 2` to be gentler. `rank` recomputes link scores (crawl does this automatically).

## Keeping it fresh (automatic)
`serve` runs a background job every `--maintain-hours` (default 24; `0` turns it off), first run a minute after start:
1. **Re-check stale pages** (older than `--max-age-days`, default 7), oldest first, `--refresh-limit` per run.
   Uses `ETag` / `Last-Modified`, so unchanged pages cost a tiny `304` instead of a download. Changed pages are
   re-indexed, pages that return 404/410 (or become noindex / blocked by robots.txt) are removed, and a page that
   keeps failing is dropped after 5 failed checks.
2. **Crawl newly found links** (`--crawl-batch` pages per run, 0 = none).
3. **Recompute link ranks.**

No server running? Use cron instead: `python3 rwebsearch.py refresh --limit 500` then
`python3 rwebsearch.py crawl --max-pages 200`.

## Fallback when results are thin (optional, off by default)
    python3 rwebsearch.py serve --fallback wikipedia --fallback-min 5 --wiki-lang en
When your index returns fewer than `--fallback-min` results (first page only), the query is also looked up on
Wikipedia and its hits are shown below yours under "More from Wikipedia" (also in the JSON API as `external`).
Privacy: this sends the search words to Wikipedia, so it is opt-in. Answers are cached for 10 minutes and a failed
lookup just shows nothing. More sources can be added in `PROVIDERS` (a function `query -> list of results`).

## What it does
- Crawler: robots.txt and Crawl-delay, per-site delay, 1 MB / HTML-only limits, noindex/nofollow,
  duplicate-content detection, tracking-parameter stripping, and an SSRF guard (private addresses are refused).
- Ranking: BM25 (title weighted 6x body, stemmed) x link popularity (PageRank), max 2 results per site on top.
- Queries: `words`, `"exact phrase"`, `-exclude`, `site:example.com`; falls back to OR when AND finds nothing.
- Endpoints: `/search?q=`, `/api/search?q=&p=&n=` (JSON), `/opensearch.xml`, `/healthz`. Rate-limited per IP.

## Use it from the rweb browser
Menu > Search engine > "Add rweb Search server..." and enter your server address.
- Over plain `http://` on a LAN, Android blocks cleartext traffic by default. Either put the server behind HTTPS
  (recommended; run `serve --trust-proxy` behind Caddy/nginx), or add `android:usesCleartextTraffic="true"` to
  `<application>` in the manifest while testing.

## Honest limits
- One machine and SQLite will hold roughly a few hundred thousand to a few million pages. It is a focused engine
  for the sites you choose, not a replacement for Google's index.
- No JavaScript rendering, English-centric stemming, no spam/quality classifier beyond link ranking.
- Re-crawl is polite and sequential (one request at a time per site), so a very large index refreshes slowly;
  raise `--refresh-limit` or run `refresh` more often.
- Crawl only sites you are comfortable crawling, and keep the default delay. Set a contact URL in the `UA`
  string at the top of `rwebsearch.py` before crawling at scale.
