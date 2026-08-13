from __future__ import annotations

import hashlib
import hmac
import secrets
import sqlite3
import time
from pathlib import Path


class Store:
    def __init__(self, path: str = "jarvis.db") -> None:
        self.path = Path(path)
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
                    revoked INTEGER NOT NULL DEFAULT 0
                );
                """
            )

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
            c.execute("UPDATE pairings SET used=1 WHERE id=1 AND used=0", ())
            return c.execute("SELECT changes()").fetchone()[0] == 1

    def add_device(self, name: str, public_key_pem: str) -> str:
        device_id = secrets.token_urlsafe(18)
        with self._conn() as c:
            c.execute(
                "INSERT INTO devices(device_id,name,public_key_pem,created_at) VALUES(?,?,?,?)",
                (device_id, name, public_key_pem, time.time()),
            )
        return device_id

    def get_device(self, device_id: str):
        with self._conn() as c:
            return c.execute("SELECT * FROM devices WHERE device_id=?", (device_id,)).fetchone()

    def revoke_device(self, device_id: str) -> bool:
        with self._conn() as c:
            c.execute("UPDATE devices SET revoked=1 WHERE device_id=?", (device_id,))
            return c.execute("SELECT changes()").fetchone()[0] == 1

    def list_devices(self):
        with self._conn() as c:
            return c.execute("SELECT device_id,name,created_at,revoked FROM devices ORDER BY created_at DESC").fetchall()
