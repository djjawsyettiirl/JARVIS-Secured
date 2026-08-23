from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import shutil
import sqlite3
import time
from pathlib import Path

DEFAULT_SCOPES = ["chat", "pc_status", "notifications"]
ALL_SCOPES = [
    "chat", "pc_status", "notifications", "microphone", "camera",
    "files_read", "files_write", "pc_control", "google_gmail",
    "google_calendar", "maps", "alarms", "messaging", "location_share", "software_updates", "admin",
    "offline_search",
]


class Store:
    def __init__(self, path: str | None = None) -> None:
        if path:
            self.path = Path(path)
        elif os.environ.get("JARVIS_DATA_DIR"):
            self.path = Path(os.environ["JARVIS_DATA_DIR"]) / "jarvis.db"
        elif os.name == "nt":
            local_path = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "JARVIS" / "jarvis.db"
            legacy_path = Path(os.environ.get("PROGRAMDATA", Path.home())) / "JARVIS" / "jarvis.db"
            if not local_path.exists() and legacy_path.exists():
                local_path.parent.mkdir(parents=True, exist_ok=True)
                try:
                    shutil.copy2(legacy_path, local_path)
                except OSError:
                    pass
            self.path = local_path
        else:
            self.path = Path("jarvis.db")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._init()

    def _conn(self) -> sqlite3.Connection:
        c = sqlite3.connect(self.path)
        c.row_factory = sqlite3.Row
        return c

    def _init(self) -> None:
        with self._conn() as c:
            c.executescript(
                """
                CREATE TABLE IF NOT EXISTS pairings (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    code_hash BLOB NOT NULL,
                    salt BLOB NOT NULL,
                    expires_at REAL NOT NULL,
                    used INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS devices (
                    device_id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    public_key_pem TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    revoked INTEGER NOT NULL DEFAULT 0,
                    scopes_json TEXT NOT NULL DEFAULT '["chat","pc_status","notifications"]',
                    last_seen REAL,
                    last_route TEXT NOT NULL DEFAULT ''
                );
                CREATE TABLE IF NOT EXISTS reminders (
                    reminder_id TEXT PRIMARY KEY,
                    message TEXT NOT NULL,
                    due_at REAL NOT NULL,
                    delivered INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS messages (
                    message_id TEXT PRIMARY KEY,
                    sender_device_id TEXT NOT NULL,
                    body TEXT NOT NULL,
                    created_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS message_receipts (
                    message_id TEXT NOT NULL,
                    recipient_device_id TEXT NOT NULL,
                    delivered INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(message_id, recipient_device_id)
                );
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );
                """
            )
            c.execute("INSERT OR IGNORE INTO settings(key,value) VALUES('home_name','Home JARVIS')")
            cols = {row[1] for row in c.execute("PRAGMA table_info(devices)").fetchall()}
            if "scopes_json" not in cols:
                c.execute("ALTER TABLE devices ADD COLUMN scopes_json TEXT NOT NULL DEFAULT '[\"chat\",\"pc_status\",\"notifications\"]'")
            if "last_seen" not in cols:
                c.execute("ALTER TABLE devices ADD COLUMN last_seen REAL")
            if "last_route" not in cols:
                c.execute("ALTER TABLE devices ADD COLUMN last_route TEXT NOT NULL DEFAULT ''")

    @staticmethod
    def _hash(code: str, salt: bytes) -> bytes:
        return hashlib.sha256(salt + code.encode("ascii")).digest()

    def create_pairing(self, ttl_seconds: int = 300) -> str:
        code = f"{secrets.randbelow(100_000_000):08d}"
        salt = secrets.token_bytes(32)
        now = time.time()
        with self._conn() as c:
            c.execute("DELETE FROM pairings")
            c.execute(
                "INSERT INTO pairings(id, code_hash, salt, expires_at, used) VALUES(1, ?, ?, ?, 0)",
                (self._hash(code, salt), salt, now + ttl_seconds),
            )
        return code

    def consume_pairing(self, code: str) -> bool:
        with self._conn() as c:
            row = c.execute("SELECT * FROM pairings WHERE id=1").fetchone()
            if not row or row["used"] or row["expires_at"] < time.time():
                return False
            expected = self._hash(code, row["salt"])
            if not hmac.compare_digest(expected, row["code_hash"]):
                return False
            c.execute("UPDATE pairings SET used=1 WHERE id=1 AND used=0")
            return c.execute("SELECT changes()").fetchone()[0] == 1

    def add_device(self, name: str, public_key_pem: str) -> str:
        device_id = secrets.token_urlsafe(18)
        now = time.time()
        with self._conn() as c:
            c.execute(
                "INSERT INTO devices(device_id,name,public_key_pem,created_at,scopes_json,last_seen) VALUES(?,?,?,?,?,?)",
                (device_id, name, public_key_pem, now, json.dumps(DEFAULT_SCOPES), now),
            )
        return device_id

    def get_device(self, device_id: str):
        with self._conn() as c:
            return c.execute("SELECT * FROM devices WHERE device_id=?", (device_id,)).fetchone()

    def get_scopes(self, device_id: str) -> list[str]:
        row = self.get_device(device_id)
        if not row:
            return []
        try:
            return [s for s in json.loads(row["scopes_json"]) if s in ALL_SCOPES]
        except Exception:
            return []

    def set_scopes(self, device_id: str, scopes: list[str]) -> bool:
        clean = sorted(set(scopes) & set(ALL_SCOPES))
        with self._conn() as c:
            c.execute("UPDATE devices SET scopes_json=? WHERE device_id=?", (json.dumps(clean), device_id))
            return c.execute("SELECT changes()").fetchone()[0] == 1

    def touch_device(self, device_id: str, route: str = "") -> bool:
        with self._conn() as c:
            c.execute(
                "UPDATE devices SET last_seen=?, last_route=CASE WHEN ?<>'' THEN ? ELSE last_route END WHERE device_id=?",
                (time.time(), route, route, device_id),
            )
            return c.execute("SELECT changes()").fetchone()[0] == 1

    def revoke_device(self, device_id: str) -> bool:
        with self._conn() as c:
            c.execute("UPDATE devices SET revoked=1 WHERE device_id=?", (device_id,))
            return c.execute("SELECT changes()").fetchone()[0] == 1

    def delete_device(self, device_id: str) -> bool:
        """Permanently forget a device and its queued receipts.

        This is intentionally separate from revoke. Revocation keeps an audit-visible
        record and blocks the key; delete is for stale/revoked test pairings that the
        owner explicitly wants removed.
        """
        with self._conn() as c:
            c.execute("DELETE FROM message_receipts WHERE recipient_device_id=?", (device_id,))
            c.execute("DELETE FROM devices WHERE device_id=?", (device_id,))
            return c.execute("SELECT changes()").fetchone()[0] == 1

    def list_devices(self):
        with self._conn() as c:
            return c.execute(
                "SELECT device_id,name,created_at,revoked,scopes_json,last_seen,last_route FROM devices ORDER BY created_at DESC"
            ).fetchall()

    def rename_device(self, device_id: str, name: str) -> bool:
        with self._conn() as c:
            c.execute("UPDATE devices SET name=? WHERE device_id=?", (name.strip(), device_id))
            return c.execute("SELECT changes()").fetchone()[0] == 1

    def home_name(self) -> str:
        with self._conn() as c:
            row = c.execute("SELECT value FROM settings WHERE key='home_name'").fetchone()
            return str(row["value"]) if row else "Home JARVIS"

    def set_home_name(self, name: str) -> None:
        with self._conn() as c:
            c.execute(
                "INSERT INTO settings(key,value) VALUES('home_name',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (name.strip(),),
            )

    def add_reminder(self, message: str, due_at: float) -> str:
        reminder_id = secrets.token_urlsafe(12)
        with self._conn() as c:
            c.execute(
                "INSERT INTO reminders(reminder_id,message,due_at) VALUES(?,?,?)",
                (reminder_id, message, due_at),
            )
        return reminder_id

    def due_reminders(self, now: float | None = None) -> list[dict[str, object]]:
        current = now if now is not None else time.time()
        with self._conn() as c:
            rows = c.execute(
                "SELECT reminder_id,message,due_at FROM reminders WHERE delivered=0 AND due_at<=? ORDER BY due_at",
                (current,),
            ).fetchall()
            if rows:
                c.executemany("UPDATE reminders SET delivered=1 WHERE reminder_id=?", [(row["reminder_id"],) for row in rows])
            return [dict(row) for row in rows]

    def pending_reminders(self) -> list[dict[str, object]]:
        with self._conn() as c:
            return [dict(row) for row in c.execute(
                "SELECT reminder_id,message,due_at FROM reminders WHERE delivered=0 ORDER BY due_at"
            ).fetchall()]

    def send_message(self, sender_device_id: str, body: str) -> str:
        message_id = secrets.token_urlsafe(12)
        with self._conn() as c:
            c.execute(
                "INSERT INTO messages(message_id,sender_device_id,body,created_at) VALUES(?,?,?,?)",
                (message_id, sender_device_id, body, time.time()),
            )
            recipients = c.execute(
                "SELECT device_id FROM devices WHERE revoked=0 AND device_id<>?",
                (sender_device_id,),
            ).fetchall()
            c.executemany(
                "INSERT INTO message_receipts(message_id,recipient_device_id) VALUES(?,?)",
                [(message_id, row["device_id"]) for row in recipients],
            )
        return message_id

    def device_messages(self, device_id: str, mark_delivered: bool = True) -> list[dict[str, object]]:
        with self._conn() as c:
            rows = c.execute(
                """
                SELECT m.message_id,m.sender_device_id,m.body,m.created_at,
                       CASE WHEN m.sender_device_id='home' THEN ? ELSE COALESCE(d.name,'Paired device') END AS sender_name
                FROM messages m
                JOIN message_receipts r ON r.message_id=m.message_id
                LEFT JOIN devices d ON d.device_id=m.sender_device_id
                WHERE r.recipient_device_id=? AND r.delivered=0
                ORDER BY m.created_at
                """,
                (self.home_name(), device_id),
            ).fetchall()
            if mark_delivered and rows:
                c.executemany(
                    "UPDATE message_receipts SET delivered=1 WHERE message_id=? AND recipient_device_id=?",
                    [(row["message_id"], device_id) for row in rows],
                )
            return [dict(row) for row in rows]

    def recent_messages(self, limit: int = 20) -> list[dict[str, object]]:
        with self._conn() as c:
            return [dict(row) for row in c.execute(
                """
                SELECT m.message_id,m.sender_device_id,m.body,m.created_at,
                       CASE WHEN m.sender_device_id='home' THEN ? ELSE COALESCE(d.name,'Paired device') END AS sender_name
                FROM messages m LEFT JOIN devices d ON d.device_id=m.sender_device_id
                ORDER BY m.created_at DESC LIMIT ?
                """,
                (self.home_name(), limit),
            ).fetchall()]
