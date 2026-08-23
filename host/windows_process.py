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
