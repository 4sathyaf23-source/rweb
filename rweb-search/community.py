import hashlib
import hmac
import os
import sqlite3
import time

PBKDF2_ROUNDS = 200_000
POST_MAX = 200

class Window:
    def __init__(self, limit, window):
        self.limit = limit
        self.window = window

class Community:
    def __init__(self, db_path):
        self.db_path = db_path
        self.win_register = Window(5, 3600)
        self.win_auth = Window(10, 600)
        self.win_post = Window(30, 3600)
        self.init_db()

    def conn(self):
        c = sqlite3.connect(self.db_path)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA foreign_keys = ON")
        return c

    def init_db(self):
        with self.conn() as c:
            c.executescript("""
                CREATE TABLE IF NOT EXISTS c_users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user TEXT UNIQUE NOT NULL,
                    pw_hash TEXT NOT NULL,
                    salt TEXT NOT NULL,
                    banned INTEGER DEFAULT 0,
                    created INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS c_tokens (
                    hash TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    created INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES c_users(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS c_follows (
                    follower_id INTEGER NOT NULL,
                    following_id INTEGER NOT NULL,
                    PRIMARY KEY(follower_id, following_id),
                    FOREIGN KEY(follower_id) REFERENCES c_users(id) ON DELETE CASCADE,
                    FOREIGN KEY(following_id) REFERENCES c_users(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS c_posts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    url TEXT DEFAULT '',
                    reply_to INTEGER,
                    time INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES c_users(id) ON DELETE CASCADE,
                    FOREIGN KEY(reply_to) REFERENCES c_posts(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS c_reports (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    post_id INTEGER NOT NULL,
                    user_id INTEGER NOT NULL,
                    reason TEXT,
                    time INTEGER NOT NULL,
                    FOREIGN KEY(post_id) REFERENCES c_posts(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES c_users(id) ON DELETE CASCADE
                );
            """)

    def hash_pw(self, password, salt=None):
        if not salt:
            salt = os.urandom(16).hex()
        dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), bytes.fromhex(salt), PBKDF2_ROUNDS)
        return salt, dk.hex()

    def verify_pw(self, password, salt, expected_hash):
        _, h = self.hash_pw(password, salt)
        return hmac.compare_digest(h, expected_hash)

    def register(self, user, password):
        user = user.lower().strip().split("@")[0]
        if not (3 <= len(user) <= 20) or not user.isalnum() or user in {"admin", "support", "rweb"}:
            return 400, {"error": "Invalid username"}
        if len(password) < 6:
            return 400, {"error": "Password must be at least 6 characters"}
        salt, phash = self.hash_pw(password)
        try:
            with self.conn() as c:
                cur = c.execute("INSERT INTO c_users (user, pw_hash, salt, created) VALUES (?, ?, ?, ?)",
                                (user, phash, salt, int(time.time())))
                uid = cur.lastrowid
                token = os.urandom(32).hex()
                thash = hashlib.sha256(token.encode()).hexdigest()
                c.execute("INSERT INTO c_tokens (hash, user_id, created) VALUES (?, ?, ?)", (thash, uid, int(time.time())))
                return 200, {"user": user, "token": token}
        except sqlite3.IntegrityError:
            return 409, {"error": "Username already taken"}

    def login(self, user, password):
        user = user.lower().strip().split("@")[0]
        with self.conn() as c:
            row = c.execute("SELECT id, pw_hash, salt, banned FROM c_users WHERE user = ?", (user,)).fetchone()
            if not row or not self.verify_pw(password, row["salt"], row["pw_hash"]):
                return 401, {"error": "Invalid username or password"}
            if row["banned"]:
                return 403, {"error": "Account is suspended"}
            token = os.urandom(32).hex()
            thash = hashlib.sha256(token.encode()).hexdigest()
            c.execute("INSERT INTO c_tokens (hash, user_id, created) VALUES (?, ?, ?)", (thash, row["id"], int(time.time())))
            return 200, {"user": user, "token": token}

    def logout(self, token):
        if not token:
            return 200, {}
        thash = hashlib.sha256(token.encode()).hexdigest()
        with self.conn() as c:
            c.execute("DELETE FROM c_tokens WHERE hash = ?", (thash,))
        return 200, {}

    def get_user_by_token(self, token):
        if not token:
            return None
        thash = hashlib.sha256(token.encode()).hexdigest()
        with self.conn() as c:
            row = c.execute("""
                SELECT u.id, u.user, u.banned FROM c_tokens t
                JOIN c_users u ON t.user_id = u.id WHERE t.hash = ?
            """, (thash,)).fetchone()
            if not row or row["banned"]:
                return None
            return {"id": row["id"], "user": row["user"]}

    def post(self, token, text, url, reply_to):
        user = self.get_user_by_token(token)
        if not user:
            return 401, {"error": "Unauthorized"}
        text = text.strip() if text else ""
        url = url.strip() if url else ""
        if not text and not url:
            return 400, {"error": "Post cannot be empty"}
        if len(text) > POST_MAX:
            return 400, {"error": f"Post exceeds {POST_MAX} characters"}
        if url and not (url.startswith("http://") or url.startswith("https://")):
            return 400, {"error": "Invalid URL scheme"}
        
        with self.conn() as c:
            if reply_to:
                parent = c.execute("SELECT id FROM c_posts WHERE id = ?", (reply_to,)).fetchone()
                if not parent:
                    return 404, {"error": "Parent post not found"}
            cur = c.execute("""
                INSERT INTO c_posts (user_id, text, url, reply_to, time) VALUES (?, ?, ?, ?, ?)
            """, (user["id"], text, url, reply_to, int(time.time())))
            return 200, {"id": cur.lastrowid}

    def feed(self, token, tab, before, limit):
        me = self.get_user_by_token(token)
        with self.conn() as c:
            query = """
                SELECT p.*, u.user, 
                (SELECT COUNT(*) FROM c_posts r WHERE r.reply_to = p.id) as replies,
                (SELECT COUNT(*) FROM c_follows f WHERE f.follower_id = ? AND f.following_id = p.user_id) as is_following
                FROM c_posts p JOIN c_users u ON p.user_id = u.id
                WHERE p.reply_to IS NULL AND u.banned = 0
            """
            params = [me["id"] if me else 0]
            if tab == "following":
                if not me:
                    return 401, {"error": "Unauthorized"}
                query += " AND (p.user_id = ? OR p.user_id IN (SELECT following_id FROM c_follows WHERE follower_id = ?))"
                params.extend([me["id"], me["id"]])
            if before:
                query += " AND p.id < ?"
                params.append(before)
            query += " ORDER BY p.id DESC LIMIT ?"
            params.append(limit)

            rows = c.execute(query, params).fetchall()
            posts = []
            for r in rows:
                posts.append({
                    "id": r["id"],
                    "user": r["user"],
                    "mine": me and r["user_id"] == me["id"],
                    "text": r["text"],
                    "url": r["url"],
                    "reply_to": r["reply_to"],
                    "time": r["time"],
                    "replies": r["replies"],
                    "following": bool(r["is_following"])
                })
            return 200, {"me": me["user"] if me else None, "posts": posts}

    def follow(self, token, target_user, is_follow):
        me = self.get_user_by_token(token)
        if not me:
            return 401, {"error": "Unauthorized"}
        target_user = target_user.lower().strip()
        with self.conn() as c:
            target = c.execute("SELECT id FROM c_users WHERE user = ?", (target_user,)).fetchone()
            if not target:
                return 404, {"error": "User not found"}
            if target["id"] == me["id"]:
                return 400, {"error": "Cannot follow yourself"}
            if is_follow:
                c.execute("INSERT OR IGNORE INTO c_follows (follower_id, following_id) VALUES (?, ?)", (me["id"], target["id"]))
            else:
                c.execute("DELETE FROM c_follows WHERE follower_id = ? AND following_id = ?", (me["id"], target["id"]))
            return 200, {}

    def thread(self, token, post_id):
        me = self.get_user_by_token(token)
        with self.conn() as c:
            root = c.execute("""
                SELECT p.*, u.user FROM c_posts p JOIN c_users u ON p.user_id = u.id WHERE p.id = ?
            """, (post_id,)).fetchone()
            if not root:
                return 404, {"error": "Post not found"}
            
            replies_raw = c.execute("""
                SELECT p.*, u.user FROM c_posts p JOIN c_users u ON p.user_id = u.id WHERE p.reply_to = ? ORDER BY p.id ASC
            """, (post_id,)).fetchall()
            
            root_dict = {
                "id": root["id"], "user": root["user"], "mine": me and root["user_id"] == me["id"],
                "text": root["text"], "url": root["url"], "time": root["time"]
            }
            replies = [{
                "id": r["id"], "user": r["user"], "mine": me and r["user_id"] == me["id"],
                "text": r["text"], "url": r["url"], "time": r["time"]
            } for r in replies_raw]
            return 200, {"me": me["user"] if me else None, "root": root_dict, "replies": replies}

    def delete(self, token, post_id):
        me = self.get_user_by_token(token)
        if not me:
            return 401, {"error": "Unauthorized"}
        with self.conn() as c:
            p = c.execute("SELECT user_id FROM c_posts WHERE id = ?", (post_id,)).fetchone()
            if not p:
                return 404, {"error": "Post not found"}
            if p["user_id"] != me["id"]:
                return 403, {"error": "Forbidden"}
            c.execute("DELETE FROM c_posts WHERE id = ?", (post_id,))
            return 200, {}

    def report(self, token, post_id, reason):
        me = self.get_user_by_token(token)
        if not me:
            return 401, {"error": "Unauthorized"}
        with self.conn() as c:
            c.execute("INSERT INTO c_reports (post_id, user_id, reason, time) VALUES (?, ?, ?, ?)",
                      (post_id, me["id"], reason, int(time.time())))
            return 200, {}

    def ban(self, username, ban=True):
        with self.conn() as c:
            c.execute("UPDATE c_users SET banned = ? WHERE user = ?", (1 if ban else 0, username))
            return c.rowcount > 0

    def reports(self):
        with self.conn() as c:
            return [dict(r) for r in c.execute("SELECT * FROM c_reports ORDER BY id DESC").fetchall()]
