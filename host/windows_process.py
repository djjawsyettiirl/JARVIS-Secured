from __future__ import annotations

import os
import subprocess
from typing import Any


def hidden_process_kwargs(*, detached: bool = False) -> dict[str, Any]:
    """Return subprocess options that never allocate or flash a Windows console."""
    if os.name != "nt":
        return {}

    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    if detached:
        creationflags |= getattr(subprocess, "DETACHED_PROCESS", 0)

    startupinfo = subprocess.STARTUPINFO()
    startupinfo.dwFlags |= getattr(subprocess, "STARTF_USESHOWWINDOW", 0)
    startupinfo.wShowWindow = getattr(subprocess, "SW_HIDE", 0)
    return {"creationflags": creationflags, "startupinfo": startupinfo}


def detached_process_kwargs() -> dict[str, Any]:
    """Detach a windowed app without instructing Windows to hide its UI."""
    if os.name != "nt":
        return {}
    return {"creationflags": getattr(subprocess, "DETACHED_PROCESS", 0)}
