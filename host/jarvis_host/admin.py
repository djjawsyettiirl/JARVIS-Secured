from __future__ import annotations

from html import escape
from fastapi import FastAPI, Form
from fastapi.responses import HTMLResponse, RedirectResponse

from .store import Store, ALL_SCOPES
from . import tunnel

admin_app = FastAPI(title="JARVIS Host Control Panel", version="0.3.0")
store = Store()
current_pairing_code = ""


def _checked(scopes: list[str], name: str) -> str:
    return "checked" if name in scopes else ""


@admin_app.get("/", response_class=HTMLResponse)
def dashboard():
    devices = store.list_devices()
    rows = []
    for device in devices:
        scopes = store.get_scopes(device["device_id"])
        state = "Revoked" if device["revoked"] else "Active"
        boxes = " ".join(
            f"<label><input type='checkbox' name='scope' value='{s}' {_checked(scopes, s)}> {s}</label>"
            for s in ALL_SCOPES if s != "admin"
        )
        rows.append(f"""
        <tr><td><b>{escape(device['name'])}</b><br><code>{escape(device['device_id'])}</code></td>
        <td>{state}</td>
        <td><form method='post' action='/devices/{escape(device['device_id'])}/scopes' class='scopes'>{boxes}<button>Save</button></form></td>
        <td><form method='post' action='/devices/{escape(device['device_id'])}/revoke'><button class='danger'>Revoke</button></form></td></tr>
        """)

    remote_url = tunnel.public_url or "Waiting for tunnel…"
    online_class = "online" if tunnel.status == "online" else "warn"
    error_html = f"<p class='error'>{escape(tunnel.last_error)}</p>" if tunnel.last_error else ""
    return f"""<!doctype html>
<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<meta http-equiv='refresh' content='5'>
<title>JARVIS v0.3</title>
<style>
:root{{color-scheme:dark}}body{{font-family:system-ui;background:#080c12;color:#edf2f7;max-width:1200px;margin:0 auto;padding:32px 20px}}
.card{{background:#111824;border:1px solid #263346;border-radius:18px;padding:22px;margin:16px 0;box-shadow:0 12px 40px #0004}}
.code{{font-size:44px;letter-spacing:9px;font-weight:800}}.small{{color:#93a1b1}}code.url{{font-size:18px;word-break:break-all}}
.badge{{display:inline-block;padding:6px 10px;border-radius:999px;background:#173b2a;color:#8ff0b0}}.warn{{background:#4a3814;color:#ffd77a}}.online{{background:#173b2a;color:#8ff0b0}}.error{{color:#ff9ca8}}
table{{width:100%;border-collapse:collapse}}td,th{{text-align:left;padding:14px;border-bottom:1px solid #263346;vertical-align:top}}
.scopes{{display:grid;grid-template-columns:repeat(3,minmax(120px,1fr));gap:8px}}button{{border:0;border-radius:9px;padding:10px 14px;background:#2d6cdf;color:white;cursor:pointer;margin:8px 6px 0 0}}.danger{{background:#8f3340}}.secondary{{background:#374151}}
</style></head><body>
<h1>JARVIS <span class='small'>v0.3 remote test</span></h1><p class='small'>Windows Host Dashboard · local administration only</p>
<div class='card'><h2>Remote connection</h2><span class='{online_class} badge'>Tunnel: {escape(tunnel.status)}</span><p>Android remote host:</p><code class='url'>{escape(remote_url)}</code>{error_html}
<form method='post' action='/tunnel/restart'><button>Restart remote tunnel</button></form><p class='small'>The temporary trycloudflare.com URL is HTTPS and changes whenever the tunnel restarts. It is for testing only.</p></div>
<div class='card'><h2>Pair a device</h2><p class='small'>This code can be used exactly once and expires after five minutes.</p><div class='code'>{escape(current_pairing_code or '—')}</div><form method='post' action='/pairing/new'><button>Generate new pairing code</button></form></div>
<div class='card'><h2>Devices & permissions</h2><table><tr><th>Device</th><th>Status</th><th>JARVIS capabilities</th><th>Security</th></tr>{''.join(rows) or '<tr><td colspan=4>No devices paired yet.</td></tr>'}</table></div>
<div class='card'><h2>Security</h2><span class='badge'>ECDSA P-256 device authentication enabled</span><p class='small'>Only port 8765 is proxied through the remote tunnel. This admin dashboard remains bound to 127.0.0.1:8766 and is never published.</p></div>
</body></html>"""


@admin_app.post("/pairing/new")
def new_pairing():
    global current_pairing_code
    current_pairing_code = store.create_pairing()
    return RedirectResponse(url="/", status_code=303)


@admin_app.post("/tunnel/restart")
def restart_tunnel():
    tunnel.restart_tunnel()
    return RedirectResponse(url="/", status_code=303)


@admin_app.post("/devices/{device_id}/scopes")
def save_scopes(device_id: str, scope: list[str] = Form(default=[])):
    store.set_scopes(device_id, scope)
    return RedirectResponse(url="/", status_code=303)


@admin_app.post("/devices/{device_id}/revoke")
def revoke(device_id: str):
    store.revoke_device(device_id)
    return RedirectResponse(url="/", status_code=303)
