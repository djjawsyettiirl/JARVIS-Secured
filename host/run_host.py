import os
import sys
import threading
import time
import traceback
from pathlib import Path
from datetime import datetime, timezone


def _smoke_requested() -> bool:
    return os.environ.get("JARVIS_SMOKE_TEST") == "1" or "--smoke-test" in sys.argv[1:]


# Keep the packaged smoke path ahead of GUI, server, and application imports.
# Importing that stack can initialize Windows components which should not be
# part of the build artifact's bounded startup validation.
if __name__ == "__main__" and _smoke_requested():
    from jarvis_host.windows_voice import windows_voice

    windows_voice.validate()
    raise SystemExit(0)

import uvicorn
import webview
import pystray
from PIL import Image

from jarvis_host.app import app, store
from jarvis_host.admin import admin_app
import jarvis_host.admin as admin
from jarvis_host import tunnel
from jarvis_host.updater import updater
from jarvis_host import route_rendezvous
from jarvis_host import assistant_ui
from jarvis_host.google_account import data_dir
from jarvis_host.version import VERSION
from jarvis_host.single_instance import acquire as acquire_single_instance

_exit_requested = threading.Event()
_tray_icon = None
_main_window = None

app.include_router(route_rendezvous.router)
admin_app.include_router(assistant_ui.router)


def _start_tunnel_when_gateway_is_ready() -> None:
    time.sleep(1.0)
    while True:
        try:
            tunnel.start_quick_tunnel()
        except Exception as exc:
            tunnel.status = "error"
            tunnel.last_error = str(exc)
        time.sleep(15)


def _route_handoff_loop() -> None:
    last_seen = ""
    while True:
        try:
            current = (tunnel.public_url or "").strip().rstrip("/")
            if current.startswith("https://") and current != last_seen:
                route_rendezvous.publish_if_changed()
                last_seen = current
        except Exception:
            pass
        time.sleep(2)


def _automatic_update_loop() -> None:
    while True:
        try:
            updater.stage_android_if_available()
            if updater.auto_install_if_available():
                time.sleep(1)
                os._exit(0)
        except Exception as exc:
            updater.status = "error"
            updater.last_error = str(exc)
        time.sleep(300)


def _startup_log(message: str) -> None:
    try:
        path = data_dir() / "startup.log"
        with path.open("a", encoding="utf-8") as stream:
            stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
            stream.write(f"{stamp} {message}\n")
    except Exception:
        pass


def _show_main_window(window) -> None:
    """Make packaged launches visible even when a hidden updater is the ancestor."""
    try:
        time.sleep(0.5)
        window.show()
        window.load_url("http://127.0.0.1:8766/assistant-v1")
        _startup_log("main window shown")
    except Exception:
        _startup_log("main window show failed\n" + traceback.format_exc())



def _tray_image() -> Image.Image:
    icon_path = Path(__file__).resolve().parent / "assets" / "jarvis.ico"
    if getattr(sys, "frozen", False):
        icon_path = Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent)) / "assets" / "jarvis.ico"
    try:
        return Image.open(icon_path)
    except Exception:
        return Image.new("RGB", (64, 64), "black")


def _open_from_tray(icon=None, item=None) -> None:
    if _main_window is not None:
        try:
            _main_window.show()
            _main_window.load_url("http://127.0.0.1:8766/assistant-v1")
        except Exception:
            _startup_log("tray open failed\n" + traceback.format_exc())


def _open_settings_from_tray(icon=None, item=None) -> None:
    _open_from_tray()
    if _main_window is not None:
        try:
            _main_window.evaluate_js("toggleSettings()")
        except Exception:
            pass


def _exit_from_tray(icon=None, item=None) -> None:
    _exit_requested.set()
    try:
        if icon is not None:
            icon.stop()
    finally:
        if _main_window is not None:
            try:
                _main_window.destroy()
            except Exception:
                pass


def _start_tray() -> None:
    global _tray_icon
    menu = pystray.Menu(
        pystray.MenuItem("Open JARVIS", _open_from_tray, default=True),
        pystray.MenuItem("Settings", _open_settings_from_tray),
        pystray.MenuItem("Exit JARVIS", _exit_from_tray),
    )
    _tray_icon = pystray.Icon("jarvis-secured", _tray_image(), "JARVIS Secured", menu)
    _tray_icon.run()


def _window_closed() -> bool:
    # Closing the desktop window hides it while the host/services stay alive.
    # Explicit Exit JARVIS is the only normal shutdown path.
    if not _exit_requested.is_set():
        try:
            if _main_window is not None:
                _main_window.hide()
        except Exception:
            pass
        return False
    return True

def main() -> None:
    global _main_window
    if _smoke_requested():
        from jarvis_host.windows_voice import windows_voice
        windows_voice.validate()
        return
    if not acquire_single_instance():
        return
    _startup_log("host startup began")
    code = store.create_pairing()
    admin.current_pairing_code = code

    admin_server = uvicorn.Server(
        uvicorn.Config(admin_app, host="127.0.0.1", port=8766, log_config=None, access_log=False)
    )
    threading.Thread(target=admin_server.run, daemon=True).start()
    threading.Thread(target=_start_tunnel_when_gateway_is_ready, daemon=True).start()
    threading.Thread(target=_route_handoff_loop, daemon=True).start()
    threading.Thread(target=_automatic_update_loop, daemon=True).start()

    gateway_server = uvicorn.Server(
        uvicorn.Config(app, host="0.0.0.0", port=8765, log_config=None, access_log=False)
    )
    threading.Thread(target=gateway_server.run, daemon=True).start()

    startup_html = f"""<!doctype html><html><head><meta charset='utf-8'><style>
    html,body{{height:100%;margin:0;background:#02060b;color:#9beaff;font-family:'Segoe UI',sans-serif}}
    body{{display:grid;place-items:center}}.core{{width:92px;height:92px;border:2px solid #37d7ff;border-right-color:transparent;border-radius:50%;box-shadow:0 0 48px #37d7ff88;animation:spin 1.1s linear infinite}}
    .label{{margin-top:24px;text-align:center;letter-spacing:.25em;font-size:12px}}@keyframes spin{{to{{transform:rotate(360deg)}}}}
    </style></head><body><div><div class='core'></div><div class='label'>INITIALIZING JARVIS V {VERSION}</div></div></body></html>"""
    window = webview.create_window(
        f"Assistant Jarvis · V {VERSION}",
        html=startup_html,
        width=1280,
        height=860,
        min_size=(900, 650),
        background_color="#02060b",
        text_select=True,
    )
    _main_window = window
    try:
        window.events.closing += _window_closed
    except Exception:
        _startup_log("window close interception unavailable")
    threading.Thread(target=_start_tray, daemon=True).start()
    _startup_log("main window created")
    try:
        webview.start(_show_main_window, (window,), gui="edgechromium", private_mode=True)
        _startup_log("webview loop returned")
    except Exception:
        _startup_log("webview loop failed\n" + traceback.format_exc())
        raise
    finally:
        if _tray_icon is not None:
            try:
                _tray_icon.stop()
            except Exception:
                pass
        tunnel.stop_tunnel()
        admin_server.should_exit = True
        gateway_server.should_exit = True


if __name__ == "__main__":
    main()
