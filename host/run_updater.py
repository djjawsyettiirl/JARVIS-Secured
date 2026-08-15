from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import time
from pathlib import Path


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apply_update(current: Path, replacement: Path, expected_sha256: str) -> None:
    current = current.resolve()
    replacement = replacement.resolve()
    if current.name.lower() != "jarvis.exe" or replacement.name.lower() != "jarvis.exe":
        raise ValueError("The updater will replace only JARVIS.exe")
    if not replacement.is_file() or _sha256(replacement) != expected_sha256.lower():
        raise ValueError("The staged JARVIS update failed integrity verification")

    # A one-file PyInstaller app has a parent bootloader and a child process.
    # Closing only the child leaves the executable locked, so terminate every
    # JARVIS process before attempting the atomic replacement.
    subprocess.run(
        ["taskkill", "/F", "/IM", "JARVIS.exe"],
        capture_output=True,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        check=False,
    )
    time.sleep(1)
    incoming = current.with_name("JARVIS.new.exe")
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
    subprocess.Popen([str(current)], creationflags=getattr(subprocess, "DETACHED_PROCESS", 0))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--current", required=True, type=Path)
    parser.add_argument("--replacement", required=True, type=Path)
    parser.add_argument("--sha256", required=True)
    args = parser.parse_args()
    apply_update(args.current, args.replacement, args.sha256)


if __name__ == "__main__":
    main()
