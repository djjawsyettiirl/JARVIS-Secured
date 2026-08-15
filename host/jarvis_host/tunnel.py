from __future__ import annotations

import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

PUBLIC_URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com", re.IGNORECASE)

public_url = ""
status = "offline"
last_error = ""
_process: subprocess.Popen[str] | None = None
_lock = threading.Lock()


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
        public_url = ""
        last_error = ""
        status = "starting"
        creationflags = 0
        if sys.platform == "win32":
            creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        try:
            _process = subprocess.Popen(
                [exe, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:8765"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
                creationflags=creationflags,
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
