from __future__ import annotations

import ctypes
import os

_mutex_handle = None


def acquire() -> bool:
    """Keep one packaged Windows host instance without blocking PyInstaller's worker."""
    global _mutex_handle
    if os.name != "nt":
        return True
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateMutexW.argtypes = (ctypes.c_void_p, ctypes.c_bool, ctypes.c_wchar_p)
    kernel32.CreateMutexW.restype = ctypes.c_void_p
    handle = kernel32.CreateMutexW(None, False, "Local\\AssistantJarvisHost")
    if not handle:
        return False
    if ctypes.get_last_error() == 183:
        kernel32.CloseHandle(handle)
        return False
    _mutex_handle = handle
    return True
