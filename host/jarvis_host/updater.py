from __future__ import annotations

import json
import hashlib
import os
import shutil
import subprocess
import sys
import threading
import zipfile
from pathlib import Path

from .google_account import data_dir

REPOSITORY = "djjawsyettiirl/JARVIS-Secured"
BRANCH = "v0.1-secure-pairing"


class Updater:
    def __init__(self) -> None:
        self.status = "idle"
        self.last_error = ""
        self.windows_run: dict[str, object] | None = None
        self.android_run: dict[str, object] | None = None
        self._lock = threading.Lock()

    def current_build(self) -> dict[str, str]:
        candidate = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1])) / "build-info.json"
        try:
            data = json.loads(candidate.read_text(encoding="utf-8"))
            return {"version": str(data.get("version", "development")), "commit": str(data.get("commit", ""))}
        except Exception:
            return {"version": "development", "commit": ""}

    @property
    def update_dir(self) -> Path:
        path = data_dir() / "updates"
        path.mkdir(parents=True, exist_ok=True)
        return path

    @property
    def android_apk(self) -> Path:
        return self.update_dir / "android" / "app-release.apk"

    def _gh(self) -> str:
        candidates = [
            shutil.which("gh"),
            r"C:\Program Files\GitHub CLI\gh.exe",
            str(Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "WinGet" / "Links" / "gh.exe"),
        ]
        for candidate in candidates:
            if candidate and Path(candidate).is_file():
                return candidate
        raise FileNotFoundError("GitHub CLI is required for private updates. Install it and run gh auth login.")

    def _run(self, *args: str) -> str:
        completed = subprocess.run(
            [self._gh(), *args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180
        )
        if completed.returncode != 0:
            raise RuntimeError((completed.stderr or completed.stdout).strip())
        return completed.stdout

    def _latest(self, workflow: str) -> dict[str, object] | None:
        output = self._run(
            "run", "list", "--repo", REPOSITORY, "--workflow", workflow,
            "--branch", BRANCH, "--status", "success", "--limit", "1",
            "--json", "databaseId,headSha,createdAt,url",
        )
        runs = json.loads(output)
        return runs[0] if runs else None

    def check(self) -> dict[str, object]:
        try:
            self.windows_run = self._latest("windows-build.yml")
            self.android_run = self._latest("android-build.yml")
            current_commit = self.current_build()["commit"]
            latest_commits = {str(run.get("headSha", "")) for run in (self.windows_run, self.android_run) if run}
            self.status = "up_to_date" if current_commit and latest_commits == {current_commit} else ("update_available" if latest_commits else "no_builds")
            self.last_error = ""
        except Exception as exc:
            self.status = "error"
            self.last_error = str(exc)
        return self.snapshot()

    def download_async(self) -> bool:
        with self._lock:
            if self.status == "downloading":
                return False
            self.status = "downloading"
            self.last_error = ""
        threading.Thread(target=self._download, daemon=True).start()
        return True

    def auto_install_if_available(self) -> bool:
        if not getattr(sys, "frozen", False):
            return False
        latest = self._latest("windows-build.yml")
        current_commit = self.current_build()["commit"]
        if not latest or not current_commit or str(latest.get("headSha", "")) == current_commit:
            return False
        with self._lock:
            if self.status == "downloading":
                return False
            self.status = "downloading"
            self.last_error = ""
        self._download()
        if self.status != "ready":
            return False
        return self.stage_windows_restart()

    def _download(self) -> None:
        try:
            self.windows_run = self._latest("windows-build.yml")
            self.android_run = self._latest("android-build.yml")
            if self.windows_run:
                target = self.update_dir / "windows"
                pending = self.update_dir / "windows-next"
                shutil.rmtree(pending, ignore_errors=True)
                pending.mkdir(parents=True)
                self._run("run", "download", str(self.windows_run["databaseId"]), "--repo", REPOSITORY, "-n", "jarvis-windows-host", "-D", str(pending))
                shutil.rmtree(target, ignore_errors=True)
                pending.replace(target)
            if self.android_run:
                target = self.update_dir / "android"
                pending = self.update_dir / "android-next"
                shutil.rmtree(pending, ignore_errors=True)
                pending.mkdir(parents=True)
                self._run("run", "download", str(self.android_run["databaseId"]), "--repo", REPOSITORY, "-n", "jarvis-android-apk", "-D", str(pending))
                if not (pending / "app-release.apk").is_file():
                    raise FileNotFoundError("The latest Android build did not contain app-release.apk")
                shutil.rmtree(target, ignore_errors=True)
                pending.replace(target)
            self.status = "ready"
        except Exception as exc:
            self.status = "error"
            self.last_error = str(exc)

    def stage_windows_restart(self) -> bool:
        if not getattr(sys, "frozen", False):
            raise RuntimeError("Windows self-update is available only in the packaged JARVIS host")
        package_root = self.update_dir / "windows"
        outer = next(package_root.glob("JARVIS-Windows-Host.zip"), None)
        if not outer:
            raise FileNotFoundError("Download the Windows update first")
        extracted = package_root / "extracted"
        shutil.rmtree(extracted, ignore_errors=True)
        extracted.mkdir(parents=True)
        with zipfile.ZipFile(outer) as archive:
            archive.extractall(extracted)
        replacement = next(extracted.rglob("JARVIS.exe"), None)
        if not replacement:
            raise FileNotFoundError("The downloaded Windows artifact did not contain JARVIS.exe")
        companion = Path(sys.executable).with_name("JARVIS-Updater.exe")
        if not companion.is_file():
            raise FileNotFoundError("JARVIS-Updater.exe is missing. Install v0.4.8 manually once to enable internal updates.")
        digest = hashlib.sha256(replacement.read_bytes()).hexdigest()
        subprocess.Popen(
            [str(companion), "--current", str(Path(sys.executable)), "--replacement", str(replacement), "--sha256", digest],
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        return True

    def snapshot(self) -> dict[str, object]:
        return {
            "status": self.status,
            "error": self.last_error,
            "windows_run": self.windows_run,
            "android_run": self.android_run,
            "android_ready": self.status == "ready" and self.android_apk.is_file(),
            "current_build": self.current_build(),
        }


updater = Updater()
