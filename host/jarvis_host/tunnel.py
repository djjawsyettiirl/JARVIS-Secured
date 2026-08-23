from __future__ import annotations

import re
import os
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

import keyring

PUBLIC_URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com", re.IGNORECASE)
KEYRING_SERVICE = "JARVIS-Secured"
TOKEN_USER = "cloudflare-tunnel-token"
HOSTNAME_USER = "cloudflare-tunnel-hostname"

public_url = ""
status = "offline"
last_error = ""
_process: subprocess.Popen[str] | None = None
_lock = threading.Lock()


def named_hostname() -> str:
    return (keyring.get_password(KEYRING_SERVICE, HOSTNAME_USER) or "").strip().rstrip("/")


def named_configured() -> bool:
    return bool(named_hostname() and keyring.get_password(KEYRING_SERVICE, TOKEN_USER))


def configure_named(token: str, hostname: str) -> None:
    token = token.strip()
    hostname = hostname.strip().rstrip("/")
    if not token.startswith("eyJ") or len(token) < 80:
        raise ValueError("Paste the tunnel token from Cloudflare's Add a replica screen")
    parsed = urlparse(hostname)
    if parsed.scheme != "https" or not parsed.hostname or parsed.path not in {"", "/"}:
        raise ValueError("Public hostname must look like https://jarvis.example.com")
    keyring.set_password(KEYRING_SERVICE, TOKEN_USER, token)
    keyring.set_password(KEYRING_SERVICE, HOSTNAME_USER, hostname)


def clear_named() -> None:
    for user in (TOKEN_USER, HOSTNAME_USER):
        try:
            keyring.delete_password(KEYRING_SERVICE, user)
        except keyring.errors.PasswordDeleteError:
            pass


def _cloudflared_path() -> str | None:
    candidates: list[Path] = []
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        candidates.append(Path(meipass) / "cloudflared.exe")
    if getattr(sys, "frozen", False):
        candidates.append(Path(sys.executable).resolve().parent / "cloudflared.exe")
    candidates.append(Path(__file__).resolve().parents[1] / "cloudflared.exe")
    for candidate in candidates:
        if candidate.exists():
            return str(candidate)
    return shutil.which("cloudflared") or shutil.which("cloudflared.exe")


def _reader(proc: subprocess.Popen[str]) -> None:
    global public_url, status, last_error
    try:
        assert proc.stdout is not None
        for line in proc.stdout:
            match = PUBLIC_URL_RE.search(line)
            if match:
                public_url = match.group(0)
                status = "online"
                last_error = ""
            if named_configured() and ("registered tunnel connection" in line.lower() or "connection registered" in line.lower()):
                public_url = named_hostname()
                status = "online"
                last_error = ""
        code = proc.wait()
        if code != 0 and not last_error:
            last_error = f"cloudflared exited with code {code}"
    except Exception as exc:
        last_error = str(exc)
    finally:
        if status != "stopping":
            status = "offline"


def start_quick_tunnel() -> bool:
    global _process, public_url, status, last_error
    with _lock:
        if _process and _process.poll() is None:
            return True
        exe = _cloudflared_path()
        if not exe:
            status = "missing"
            last_error = "cloudflared.exe was not found"
            return False
        named = named_configured()
        public_url = named_hostname() if named else ""
        last_error = ""
        status = "starting"
        creationflags = 0
        if sys.platform == "win32":
            creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        try:
            command = [exe, "tunnel", "--no-autoupdate", "run"] if named else [
                exe, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:8765"
            ]
            environment = os.environ.copy()
            if named:
                environment["TUNNEL_TOKEN"] = keyring.get_password(KEYRING_SERVICE, TOKEN_USER) or ""
            _process = subprocess.Popen(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
                creationflags=creationflags,
                env=environment,
            )
        except Exception as exc:
            status = "error"
            last_error = str(exc)
            return False
        threading.Thread(target=_reader, args=(_process,), daemon=True).start()
        return True


def stop_tunnel() -> None:
    global _process, public_url, status
    with _lock:
        proc = _process
        if not proc or proc.poll() is not None:
            _process = None
            public_url = ""
            status = "offline"
            return
        status = "stopping"
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass
        _process = None
        public_url = ""
        status = "offline"


def restart_tunnel() -> bool:
    stop_tunnel()
    time.sleep(0.2)
    return start_quick_tunnel()


def snapshot() -> dict[str, object]:
    return {
        "status": status,
        "public_url": public_url,
        "error": last_error,
        "mode": "named" if named_configured() else "quick",
        "named_configured": named_configured(),
        "named_hostname": named_hostname(),
    }
