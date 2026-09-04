import os
import threading
import time
import traceback
from datetime import datetime, timezone

import uvicorn
import webview

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


def main() -> None:
    if os.environ.get("JARVIS_SMOKE_TEST") == "1":
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
    _startup_log("main window created")
    try:
        webview.start(_show_main_window, (window,), gui="edgechromium", private_mode=True)
        _startup_log("webview loop returned")
    except Exception:
        _startup_log("webview loop failed\n" + traceback.format_exc())
        raise
    finally:
        tunnel.stop_tunnel()
        admin_server.should_exit = True
        gateway_server.should_exit = True


if __name__ == "__main__":
    main()
