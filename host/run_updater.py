from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import time
from pathlib import Path

from windows_process import hidden_process_kwargs


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

    # Kill every JARVIS bootloader/child by image name, but do not use /T here.
    # This updater is launched by JARVIS, so taskkill /T also kills the updater
    # itself before it can replace and restart the host.
    subprocess.run(
        ["taskkill", "/F", "/IM", "JARVIS.exe"],
        capture_output=True,
        check=False,
        **hidden_process_kwargs(),
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
    os.startfile(str(current))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--current", required=True, type=Path)
    parser.add_argument("--replacement", required=True, type=Path)
    parser.add_argument("--sha256", required=True)
    args = parser.parse_args()
    apply_update(args.current, args.replacement, args.sha256)


if __name__ == "__main__":
    main()
