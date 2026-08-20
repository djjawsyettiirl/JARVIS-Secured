from __future__ import annotations

import json
import hashlib
import os
import shutil
import subprocess
import sys
import threading
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from .google_account import data_dir
from .version import VERSION
from windows_process import hidden_process_kwargs

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

    @property
    def android_build_file(self) -> Path:
        return self.update_dir / "android" / "android-build.json"

    def android_build(self) -> dict[str, object]:
        try:
            return json.loads(self.android_build_file.read_text(encoding="utf-8"))
        except Exception:
            return {}

    @property
    def old_development_dir(self) -> Path:
        path = data_dir() / "Old Development"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def _archive_existing(self, target: Path, label: str) -> None:
        if not target.exists():
            return
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        destination = self.old_development_dir / f"{label}-{stamp}"
        suffix = 1
        while destination.exists():
            destination = self.old_development_dir / f"{label}-{stamp}-{suffix}"
            suffix += 1
        shutil.move(str(target), str(destination))

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
            [self._gh(), *args],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=180,
            **hidden_process_kwargs(),
        )
        if completed.returncode != 0:
            raise RuntimeError((completed.stderr or completed.stdout).strip())
        return completed.stdout

    def _latest(self, workflow: str) -> dict[str, object] | None:
        output = self._run(
            "run", "list", "--repo", REPOSITORY, "--workflow", workflow,
            "--branch", BRANCH, "--event", "push", "--status", "success", "--limit", "1",
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

    def stage_android_if_available(self) -> bool:
        """Keep the matching phone installer ready even when Windows is current."""
        latest = self._latest("android-build.yml")
        if not latest:
            return False
        target = self.update_dir / "android"
        marker = target / ".run-id"
        run_id = str(latest["databaseId"])
        if self.android_apk.is_file() and marker.is_file() and marker.read_text(encoding="utf-8").strip() == run_id:
            if self.status in {"idle", "up_to_date"}:
                self.status = "ready"
            return False
        with self._lock:
            if self.status == "downloading":
                return False
            self.status = "downloading"
            self.last_error = ""
        try:
            self.android_run = latest
            self._stage_android_run(latest)
            self.status = "ready"
            return True
        except Exception as exc:
            self.status = "error"
            self.last_error = str(exc)
            return False

    def _stage_android_run(self, run: dict[str, object]) -> None:
        target = self.update_dir / "android"
        pending = self.update_dir / "android-next"
        shutil.rmtree(pending, ignore_errors=True)
        pending.mkdir(parents=True)
        self._run("run", "download", str(run["databaseId"]), "--repo", REPOSITORY, "-n", "jarvis-android-apk", "-D", str(pending))
        if not (pending / "app-release.apk").is_file():
            raise FileNotFoundError("The latest Android build did not contain app-release.apk")
        build_file = pending / "android-build.json"
        if not build_file.is_file():
            raise FileNotFoundError("The Android artifact has no build identity and was rejected as outdated")
        build = json.loads(build_file.read_text(encoding="utf-8"))
        if str(build.get("commit", "")) != str(run.get("headSha", "")):
            raise RuntimeError("The Android artifact commit does not match the selected build")
        if str(build.get("version", "")) != VERSION:
            raise RuntimeError(f"The Android artifact version is {build.get('version', 'unknown')}, expected {VERSION}")
        if int(build.get("version_code", 0)) < 1_601_000:
            raise RuntimeError("The Android artifact version code is outdated")
        (pending / ".run-id").write_text(str(run["databaseId"]), encoding="utf-8")
        self._archive_existing(target, "android-update")
        pending.replace(target)

    def _download(self) -> None:
        windows_staged = False
        try:
            self.windows_run = self._latest("windows-build.yml")
            self.android_run = self._latest("android-build.yml")
            if self.windows_run:
                target = self.update_dir / "windows"
                pending = self.update_dir / "windows-next"
                shutil.rmtree(pending, ignore_errors=True)
                pending.mkdir(parents=True)
                self._run("run", "download", str(self.windows_run["databaseId"]), "--repo", REPOSITORY, "-n", "jarvis-windows-host", "-D", str(pending))
                self._archive_existing(target, "windows-update")
                pending.replace(target)
                windows_staged = True
            if self.android_run:
                try:
                    self._stage_android_run(self.android_run)
                except RuntimeError as exc:
                    # A host upgrading to a new product version cannot validate
                    # that version's Android APK until the new Windows host is
                    # running. Do not block the Windows restart; startup will
                    # stage the matching Android artifact immediately afterward.
                    if not windows_staged or "expected" not in str(exc):
                        raise
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
            raise FileNotFoundError("JARVIS-Updater.exe is missing. Install the current package manually once to enable internal updates.")
        replacement_companion = next(extracted.rglob("JARVIS-Updater.exe"), None)
        if replacement_companion:
            shutil.copy2(replacement_companion, companion)
        digest = hashlib.sha256(replacement.read_bytes()).hexdigest()
        subprocess.Popen(
            [str(companion), "--current", str(Path(sys.executable)), "--replacement", str(replacement), "--sha256", digest],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            close_fds=True,
            **hidden_process_kwargs(detached=True),
        )
        return True

    def snapshot(self) -> dict[str, object]:
        return {
            "status": self.status,
            "error": self.last_error,
            "windows_run": self.windows_run,
            "android_run": self.android_run,
            "android_ready": self.status == "ready" and self.android_apk.is_file(),
            "android_build": self.android_build(),
            "current_build": self.current_build(),
        }


updater = Updater()
