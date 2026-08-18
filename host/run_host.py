import os
import threading
import time

import uvicorn
import webview

from jarvis_host.app import app, store
from jarvis_host.admin import admin_app
import jarvis_host.admin as admin
from jarvis_host import tunnel
from jarvis_host.updater import updater
from jarvis_host import route_rendezvous
from jarvis_host import assistant_ui

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


def main() -> None:
    if os.environ.get("JARVIS_SMOKE_TEST") == "1":
        from jarvis_host.windows_voice import windows_voice
        windows_voice.validate()
        return
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

    time.sleep(0.8)
    webview.create_window(
        "Assistant Jarvis · V 1.2",
        "http://127.0.0.1:8766/assistant-v1",
        width=1280,
        height=860,
        min_size=(900, 650),
        background_color="#08090c",
        text_select=True,
    )
    try:
        webview.start(gui="edgechromium", private_mode=True)
    finally:
        tunnel.stop_tunnel()
        admin_server.should_exit = True
        gateway_server.should_exit = True


if __name__ == "__main__":
    main()
