"""
rweb community: a tiny old-Twitter-style feed. Text (max 200 characters) plus one optional link, replies, follows.
Part of rweb-search; standard library only. Wired into the HTTP server by rwebsearch.py (paths under /c/).

Accounts are  name@abigweb  (the name part is what people type: 3-20 of a-z 0-9 _).
Passwords are stored as salted PBKDF2 hashes; sessions are random tokens (only their SHA-256 is stored).
"""
from __future__ import annotations

import collections
import hashlib
import hmac
import re
import secrets
import sqlite3
import threading
import time
import urllib.parse

POST_MAX = 200          # characters of text per post
URL_MAX = 500
PW_MIN, PW_MAX = 6, 128
TOKEN_DAYS = 90
DOMAIN = "abigweb"
RESERVED = {"admin", "administrator", "abigweb", "rweb", "support", "root", "system", "moderator", "staff"}
USER_RE = re.compile(r"^[a-z0-9_]{3,20}$")
PBKDF2_ROUNDS = 200_000

SCHEMA = """
CREATE TABLE IF NOT EXISTS c_users(
  id INTEGER PRIMARY KEY, name TEXT UNIQUE NOT NULL, salt BLOB NOT NULL, pw BLOB NOT NULL,
  created INTEGER NOT NULL, banned INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS c_tokens(hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL, created INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS c_tokens_user ON c_tokens(user_id);
CREATE TABLE IF NOT EXISTS c_posts(
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, text TEXT NOT NULL, url TEXT,
  reply_to INTEGER, root_id INTEGER, created INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS c_posts_user ON c_posts(user_id, id);
CREATE INDEX IF NOT EXISTS c_posts_root ON c_posts(root_id, id);
CREATE TABLE IF NOT EXISTS c_follows(follower INTEGER NOT NULL, followee INTEGER NOT NULL, created INTEGER NOT NULL,
  PRIMARY KEY(follower, followee));
CREATE INDEX IF NOT EXISTS c_follows_followee ON c_follows(followee);
CREATE TABLE IF NOT EXISTS c_reports(id INTEGER PRIMARY KEY, post_id INTEGER, reporter INTEGER, reason TEXT, created INTEGER);
"""

# control characters (except newline) and bidi overrides, which can be used to disguise text
_BAD_CHARS = re.compile("[\x00-\x09\x0b-\x1f\x7f\u202a-\u202e\u2066-\u2069\u200e\u200f]")


class Window:
    """At most `n` events per `seconds` for each key (in memory)."""

    def __init__(self, n, seconds):
        self.n, self.seconds = n, seconds
        self.hits = collections.defaultdict(collections.deque)
        self.lock = threading.Lock()

    def ok(self, key):
        now = time.time()
        with self.lock:
            d = self.hits[key]
            while d and d[0] < now - self.seconds:
                d.popleft()
            if len(d) >= self.n:
                return False
            d.append(now)
            if len(self.hits) > 20000:
                self.hits.clear()
            return True


def clean_name(raw):
    if not isinstance(raw, str):
        return None
    n = raw.strip().lower().lstrip("@")
    if n.endswith("@" + DOMAIN):
        n = n[: -(len(DOMAIN) + 1)]
    if not USER_RE.match(n) or n in RESERVED:
        return None
    return n


def clean_text(raw):
    if raw is None:
        return ""
    if not isinstance(raw, str):
        return None
    t = _BAD_CHARS.sub("", raw).replace("\r\n", "\n").replace("\r", "\n")
    t = re.sub(r"\n{3,}", "\n\n", t).strip()
    return t if len(t) <= POST_MAX else None


def clean_url(raw):
    if raw in (None, ""):
        return ""
    if not isinstance(raw, str):
        return None
    u = raw.strip()
    if not u or len(u) > URL_MAX or re.search(r"\s", u) or _BAD_CHARS.search(u):
        return None
    try:
        p = urllib.parse.urlsplit(u)
        host = p.hostname
    except ValueError:
        return None
    if p.scheme not in ("http", "https") or not host or "@" in p.netloc:  # no javascript:, no user:pass@host
        return None
    return u


def _hash_pw(password, salt):
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PBKDF2_ROUNDS)


def _tok_hash(token):
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class Community:
    def __init__(self, db_path):
        self.db_path = db_path
        con = self._con()
        con.executescript(SCHEMA)
        con.commit()
        con.close()
        self.win_auth = Window(10, 600)       # login attempts per IP
        self.win_register = Window(5, 3600)   # new accounts per IP
        self.win_post = Window(30, 3600)      # posts per user
        self.win_report = Window(20, 3600)

    def _con(self):
        con = sqlite3.connect(self.db_path, timeout=30)
        con.row_factory = sqlite3.Row
        return con

    # ------------------------------------------------------------ auth

    def _issue(self, con, uid):
        tok = secrets.token_hex(32)
        now = int(time.time())
        con.execute("INSERT INTO c_tokens(hash, user_id, created) VALUES(?,?,?)", (_tok_hash(tok), uid, now))
        con.execute(
            "DELETE FROM c_tokens WHERE user_id=? AND hash NOT IN "
            "(SELECT hash FROM c_tokens WHERE user_id=? ORDER BY created DESC LIMIT 10)", (uid, uid))
        con.commit()
        return tok

    def _auth(self, con, token):
        if not token:
            return None
        row = con.execute(
            "SELECT u.id, u.name FROM c_tokens t JOIN c_users u ON u.id=t.user_id "
            "WHERE t.hash=? AND t.created>? AND u.banned=0",
            (_tok_hash(token), int(time.time()) - TOKEN_DAYS * 86400)).fetchone()
        return (row["id"], row["name"]) if row else None

    def _register(self, body, ip):
        if not self.win_register.ok(ip):
            return 429, {"error": "Too many new accounts from this network. Try later."}
        name = clean_name(body.get("user"))
        pw = body.get("password")
        if not name:
            return 400, {"error": "Username: 3-20 letters, numbers or _ (not a reserved name)."}
        if not isinstance(pw, str) or not (PW_MIN <= len(pw) <= PW_MAX):
            return 400, {"error": "Password must be %d-%d characters." % (PW_MIN, PW_MAX)}
        salt = secrets.token_bytes(16)
        con = self._con()
        try:
            try:
                cur = con.execute("INSERT INTO c_users(name, salt, pw, created) VALUES(?,?,?,?)",
                                  (name, salt, _hash_pw(pw, salt), int(time.time())))
            except sqlite3.IntegrityError:
                return 409, {"error": "That username is taken."}
            tok = self._issue(con, cur.lastrowid)
            return 200, {"token": tok, "user": name}
        finally:
            con.close()

    def _login(self, body, ip):
        if not self.win_auth.ok(ip):
            return 429, {"error": "Too many attempts. Wait a few minutes."}
        name = clean_name(body.get("user"))
        pw = body.get("password")
        if not name or not isinstance(pw, str) or len(pw) > PW_MAX:
            return 401, {"error": "Wrong username or password."}
        con = self._con()
        try:
            row = con.execute("SELECT id, salt, pw, banned FROM c_users WHERE name=?", (name,)).fetchone()
            salt = row["salt"] if row else b"\0" * 16          # same work whether or not the user exists
            ok = hmac.compare_digest(_hash_pw(pw, salt), row["pw"] if row else b"\0" * 32) and row is not None
            if not ok:
                return 401, {"error": "Wrong username or password."}
            if row["banned"]:
                return 403, {"error": "This account is suspended."}
            return 200, {"token": self._issue(con, row["id"]), "user": name}
        finally:
            con.close()

    # ------------------------------------------------------------ posts

    @staticmethod
    def _fmt(row, me_id, followed):
        return {
            "id": row["id"], "user": row["name"], "text": row["text"], "url": row["url"] or "",
            "reply_to": row["reply_to"], "root_id": row["root_id"] or row["id"],
            "replies": row["replies"] if "replies" in row.keys() else 0,
            "time": row["created"], "mine": row["user_id"] == me_id, "following": row["user_id"] in followed,
        }

    def _followed(self, con, me):
        if not me:
            return set()
        return {r[0] for r in con.execute("SELECT followee FROM c_follows WHERE follower=?", (me[0],))}

    _POST_SQL = (
        "SELECT p.id, p.user_id, p.text, p.url, p.reply_to, p.root_id, p.created, u.name, "
        "(SELECT COUNT(*) FROM c_posts r WHERE r.root_id=p.id) AS replies "
        "FROM c_posts p JOIN c_users u ON u.id=p.user_id WHERE u.banned=0 "
    )

    def _feed(self, q, token):
        con = self._con()
        try:
            me = self._auth(con, token)
            tab = q.get("tab", "global")
            limit = min(max(int(q.get("limit", 20) or 20), 1), 50)
            sql, args = self._POST_SQL + "AND p.reply_to IS NULL ", []
            if tab == "following":
                if not me:
                    return 401, {"error": "Log in to see who you follow."}
                sql += "AND (p.user_id=? OR p.user_id IN (SELECT followee FROM c_follows WHERE follower=?)) "
                args += [me[0], me[0]]
            if q.get("before"):
                sql += "AND p.id<? "
                args.append(int(q["before"]))
            rows = con.execute(sql + "ORDER BY p.id DESC LIMIT ?", args + [limit]).fetchall()
            followed = self._followed(con, me)
            return 200, {"posts": [self._fmt(r, me[0] if me else None, followed) for r in rows], "me": me[1] if me else None}
        finally:
            con.close()

    def _thread(self, post_id, token):
        con = self._con()
        try:
            me = self._auth(con, token)
            row = con.execute(self._POST_SQL + "AND p.id=?", (post_id,)).fetchone()
            if not row:
                return 404, {"error": "Post not found."}
            root_id = row["root_id"] or row["id"]
            root = row if root_id == row["id"] else con.execute(self._POST_SQL + "AND p.id=?", (root_id,)).fetchone()
            if not root:
                return 404, {"error": "Post not found."}
            replies = con.execute(self._POST_SQL + "AND p.root_id=? ORDER BY p.id LIMIT 200", (root_id,)).fetchall()
            followed = self._followed(con, me)
            mid = me[0] if me else None
            return 200, {"root": self._fmt(root, mid, followed), "replies": [self._fmt(r, mid, followed) for r in replies],
                         "me": me[1] if me else None}
        finally:
            con.close()

    def _post(self, body, token):
        con = self._con()
        try:
            me = self._auth(con, token)
            if not me:
                return 401, {"error": "Log in to post."}
            if not self.win_post.ok(me[0]):
                return 429, {"error": "Slow down: 30 posts per hour."}
            text = clean_text(body.get("text"))
            url = clean_url(body.get("url"))
            if text is None:
                return 400, {"error": "Posts are at most %d characters." % POST_MAX}
            if url is None:
                return 400, {"error": "The link must be a normal http(s) address."}
            if not text and not url:
                return 400, {"error": "Write something or add a link."}
            reply_to = body.get("reply_to")
            root = None
            if reply_to is not None:
                if not isinstance(reply_to, int) or isinstance(reply_to, bool):
                    return 400, {"error": "Bad reply."}
                parent = con.execute("SELECT id, root_id FROM c_posts WHERE id=?", (reply_to,)).fetchone()
                if not parent:
                    return 404, {"error": "The post you reply to is gone."}
                root = parent["root_id"] or parent["id"]
            cur = con.execute("INSERT INTO c_posts(user_id, text, url, reply_to, root_id, created) VALUES(?,?,?,?,?,?)",
                              (me[0], text, url or None, reply_to, root, int(time.time())))
            con.commit()
            return 200, {"id": cur.lastrowid}
        finally:
            con.close()

    def _delete(self, body, token):
        con = self._con()
        try:
            me = self._auth(con, token)
            if not me:
                return 401, {"error": "Log in first."}
            pid = body.get("id")
            row = con.execute("SELECT user_id FROM c_posts WHERE id=?", (pid,)).fetchone() if isinstance(pid, int) else None
            if not row:
                return 404, {"error": "Post not found."}
            if row["user_id"] != me[0]:
                return 403, {"error": "You can only delete your own posts."}
            con.execute("DELETE FROM c_posts WHERE id=? OR root_id=?", (pid, pid))
            con.commit()
            return 200, {"ok": True}
        finally:
            con.close()

    def _follow(self, body, token, on):
        con = self._con()
        try:
            me = self._auth(con, token)
            if not me:
                return 401, {"error": "Log in to follow people."}
            name = clean_name(body.get("user"))
            row = con.execute("SELECT id FROM c_users WHERE name=? AND banned=0", (name,)).fetchone() if name else None
            if not row:
                return 404, {"error": "No such user."}
            if row["id"] == me[0]:
                return 400, {"error": "You cannot follow yourself."}
            if on:
                con.execute("INSERT OR IGNORE INTO c_follows(follower, followee, created) VALUES(?,?,?)",
                            (me[0], row["id"], int(time.time())))
            else:
                con.execute("DELETE FROM c_follows WHERE follower=? AND followee=?", (me[0], row["id"]))
            con.commit()
            return 200, {"following": bool(on)}
        finally:
            con.close()

    def _profile(self, name, token):
        con = self._con()
        try:
            me = self._auth(con, token)
            n = clean_name(name)
            row = con.execute("SELECT id, name, created FROM c_users WHERE name=? AND banned=0", (n,)).fetchone() if n else None
            if not row:
                return 404, {"error": "No such user."}
            one = lambda sql, *a: con.execute(sql, a).fetchone()[0]
            followed = self._followed(con, me)
            posts = con.execute(self._POST_SQL + "AND p.user_id=? AND p.reply_to IS NULL ORDER BY p.id DESC LIMIT 20", (row["id"],)).fetchall()
            return 200, {
                "user": row["name"], "joined": row["created"],
                "followers": one("SELECT COUNT(*) FROM c_follows WHERE followee=?", row["id"]),
                "following": one("SELECT COUNT(*) FROM c_follows WHERE follower=?", row["id"]),
                "is_following": row["id"] in followed,
                "posts": [self._fmt(r, me[0] if me else None, followed) for r in posts],
            }
        finally:
            con.close()

    def _report(self, body, token):
        con = self._con()
        try:
            me = self._auth(con, token)
            if not me:
                return 401, {"error": "Log in to report."}
            if not self.win_report.ok(me[0]):
                return 429, {"error": "Too many reports."}
            pid = body.get("id")
            if not isinstance(pid, int) or not con.execute("SELECT 1 FROM c_posts WHERE id=?", (pid,)).fetchone():
                return 404, {"error": "Post not found."}
            reason = body.get("reason")
            con.execute("INSERT INTO c_reports(post_id, reporter, reason, created) VALUES(?,?,?,?)",
                        (pid, me[0], (reason if isinstance(reason, str) else "")[:200], int(time.time())))
            con.commit()
            return 200, {"ok": True}
        finally:
            con.close()

    def _logout(self, token):
        if token:
            con = self._con()
            try:
                con.execute("DELETE FROM c_tokens WHERE hash=?", (_tok_hash(token),))
                con.commit()
            finally:
                con.close()
        return 200, {"ok": True}

    # ------------------------------------------------------------ routing

    def handle(self, method, path, query, body, token, ip):
        """-> (http status, json-able dict). Never raises for bad input."""
        try:
            if method == "POST":
                if path == "/c/register":
                    return self._register(body, ip)
                if path == "/c/login":
                    return self._login(body, ip)
                if path == "/c/logout":
                    return self._logout(token)
                if path == "/c/post":
                    return self._post(body, token)
                if path == "/c/delete":
                    return self._delete(body, token)
                if path == "/c/follow":
                    return self._follow(body, token, True)
                if path == "/c/unfollow":
                    return self._follow(body, token, False)
                if path == "/c/report":
                    return self._report(body, token)
            elif method == "GET":
                if path == "/c/feed":
                    return self._feed(query, token)
                if path == "/c/thread":
                    return self._thread(int(query.get("id", "0")), token)
                m = re.fullmatch(r"/c/user/([^/]+)", path)
                if m:
                    return self._profile(urllib.parse.unquote(m.group(1)), token)
            return 404, {"error": "Not found."}
        except (ValueError, TypeError):
            return 400, {"error": "Bad request."}

    # ------------------------------------------------------------ moderation (command line)

    def reports(self):
        con = self._con()
        try:
            return [dict(r) for r in con.execute(
                "SELECT r.id AS report, r.post_id, r.reason, p.text, p.url, u.name AS author, r.created "
                "FROM c_reports r LEFT JOIN c_posts p ON p.id=r.post_id LEFT JOIN c_users u ON u.id=p.user_id "
                "ORDER BY r.id DESC LIMIT 100")]
        finally:
            con.close()

    def remove_post(self, post_id):
        con = self._con()
        try:
            con.execute("DELETE FROM c_posts WHERE id=? OR root_id=?", (post_id, post_id))
            con.execute("DELETE FROM c_reports WHERE post_id=?", (post_id,))
            con.commit()
        finally:
            con.close()

    def ban(self, name, banned=True):
        con = self._con()
        try:
            row = con.execute("SELECT id FROM c_users WHERE name=?", (clean_name(name) or "",)).fetchone()
            if not row:
                return False
            con.execute("UPDATE c_users SET banned=? WHERE id=?", (1 if banned else 0, row["id"]))
            if banned:
                con.execute("DELETE FROM c_tokens WHERE user_id=?", (row["id"],))
            con.commit()
            return True
        finally:
            con.close()
