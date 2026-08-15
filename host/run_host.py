import threading
import time

import uvicorn

from jarvis_host.app import app, store
from jarvis_host.admin import admin_app
import jarvis_host.admin as admin
from jarvis_host import tunnel


def _start_tunnel_when_gateway_is_ready() -> None:
    # Give Uvicorn a moment to bind before cloudflared begins probing it.
    time.sleep(1.0)
    tunnel.start_quick_tunnel()


if __name__ == "__main__":
    code = store.create_pairing()
    admin.current_pairing_code = code
    print("JARVIS pairing code (one use, expires in 5 minutes):", code)
    print("Gateway: http://0.0.0.0:8765 (use HTTPS/WSS through a secure tunnel/reverse proxy for internet exposure).")
    print("Local control panel: http://127.0.0.1:8766")

    admin_server = uvicorn.Server(
        uvicorn.Config(admin_app, host="127.0.0.1", port=8766, log_level="warning")
    )
    threading.Thread(target=admin_server.run, daemon=True).start()
    threading.Thread(target=_start_tunnel_when_gateway_is_ready, daemon=True).start()

    uvicorn.run(app, host="0.0.0.0", port=8765)
