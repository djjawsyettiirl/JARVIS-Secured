import threading
import time

import uvicorn
import webview

from jarvis_host.app import app, store
from jarvis_host.admin import admin_app
import jarvis_host.admin as admin
from jarvis_host import tunnel


def _start_tunnel_when_gateway_is_ready() -> None:
    # Give Uvicorn a moment to bind before cloudflared begins probing it.
    time.sleep(1.0)
    tunnel.start_quick_tunnel()


def main() -> None:
    code = store.create_pairing()
    admin.current_pairing_code = code

    admin_server = uvicorn.Server(
        uvicorn.Config(admin_app, host="127.0.0.1", port=8766, log_config=None, access_log=False)
    )
    threading.Thread(target=admin_server.run, daemon=True).start()
    threading.Thread(target=_start_tunnel_when_gateway_is_ready, daemon=True).start()

    gateway_server = uvicorn.Server(
        uvicorn.Config(app, host="0.0.0.0", port=8765, log_config=None, access_log=False)
    )
    threading.Thread(target=gateway_server.run, daemon=True).start()

    # The dashboard is hosted only inside this native desktop window. The local
    # web service remains an implementation detail and no external browser opens.
    time.sleep(0.8)
    webview.create_window(
        "JARVIS",
        "http://127.0.0.1:8766",
        width=1280,
        height=860,
        min_size=(900, 650),
        background_color="#080c12",
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
