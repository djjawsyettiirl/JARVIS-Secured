from __future__ import annotations

import argparse
import ctypes
import hashlib
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from windows_process import hidden_process_kwargs


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _archive_current_package(current: Path, archive_root: Path) -> Path:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    destination = archive_root / f"windows-host-{stamp}"
    suffix = 1
    while destination.exists():
        destination = archive_root / f"windows-host-{stamp}-{suffix}"
        suffix += 1
    destination.mkdir(parents=True)
    names = dict.fromkeys((current.name, "JARVIS.exe", "JARVIS-Beta.exe", "JARVIS-Updater.exe", "README.txt"))
    for name in names:
        source = current.with_name(name)
        if source.is_file():
            shutil.copy2(source, destination / name)
    return destination


def apply_update(current: Path, replacement: Path, expected_sha256: str, archive_root: Path | None = None) -> None:
    current = current.resolve()
    replacement = replacement.resolve()
    allowed_names = {"jarvis.exe", "jarvis-beta.exe"}
    if current.name.lower() not in allowed_names or replacement.name.lower() not in allowed_names:
        raise ValueError("The updater will replace only a verified JARVIS host executable")
    if not replacement.is_file() or _sha256(replacement) != expected_sha256.lower():
        raise ValueError("The staged JARVIS update failed integrity verification")

    # Kill every JARVIS bootloader/child by image name, but do not use /T here.
    # This updater is launched by JARVIS, so taskkill /T also kills the updater
    # itself before it can replace and restart the host.
    subprocess.run(
        ["taskkill", "/F", "/IM", current.name],
        capture_output=True,
        check=False,
        **hidden_process_kwargs(),
    )
    time.sleep(1)
    if archive_root is not None:
        _archive_current_package(current, archive_root.resolve())
    incoming = current.with_name(f"{current.stem}.new{current.suffix}")
    for _ in range(120):
        try:
            shutil.copy2(replacement, incoming)
            os.replace(incoming, current)
            break
        except PermissionError:
            time.sleep(1)
    else:
        raise RuntimeError("JARVIS did not close in time for the update")

    if _sha256(current) != expected_sha256.lower():
        raise RuntimeError("The installed JARVIS update failed verification")
    os.startfile(str(current))


def _ensure_elevated(arguments: list[str], current: Path) -> bool:
    """Request the permission needed to replace an install under Program Files."""
    if os.name != "nt" or os.access(current.resolve().parent, os.W_OK):
        return True
    command_line = subprocess.list2cmdline(arguments)
    result = ctypes.windll.shell32.ShellExecuteW(
        None, "runas", sys.executable, command_line, None, 0
    )
    if result > 32:
        return False
    # UAC was cancelled or blocked. Bring the old host back after its updater
    # parent exits, instead of leaving the user with a closed app.
    time.sleep(2)
    if current.is_file():
        os.startfile(str(current))
    return False


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--current", required=True, type=Path)
    parser.add_argument("--replacement", required=True, type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--archive-dir", type=Path)
    args = parser.parse_args()
    if not _ensure_elevated(sys.argv[1:], args.current):
        return
    try:
        apply_update(args.current, args.replacement, args.sha256, args.archive_dir)
    except Exception:
        # The host exits after starting this helper. If replacement fails,
        # restart the existing install so a failed update does not look like a crash.
        time.sleep(2)
        if args.current.is_file():
            os.startfile(str(args.current))
        raise


if __name__ == "__main__":
    main()
