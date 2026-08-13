from __future__ import annotations

from html import escape
from fastapi import FastAPI

from .store import Store

admin_app = FastAPI(title="JARVIS Host Control Panel", version="0.2.0")
store = Store()

# run_host.py sets this for the current enrollment window.
current_pairing_code = ""


@admin_app.get("/", response_class=None)
def dashboard():
    devices = store.list_devices()
    rows = []
    for device in devices:
        state = "Revoked" if device["revoked"] else "Active"
        rows.append(
            f"<tr><td>{escape(device['name'])}</td>"
            f"<td><code>{escape(device['device_id'])}</code></td>"
            f"<td>{state}</td></tr>"
        )
    return f"""<!doctype html>
<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>JARVIS Host</title>
<style>
body{{font-family:system-ui;background:#0b0f14;color:#e8edf2;max-width:980px;margin:40px auto;padding:0 20px}}
.card{{background:#121923;border:1px solid #273444;border-radius:18px;padding:24px;margin:16px 0}}
.code{{font-size:42px;letter-spacing:8px;font-weight:800}}
.badge{{display:inline-block;padding:6px 10px;border-radius:999px;background:#173b2a;color:#8ff0b0}}
table{{width:100%;border-collapse:collapse}}td,th{{text-align:left;padding:12px;border-bottom:1px solid #273444}}
.small{{color:#9aa7b5}}
</style></head><body>
<h1>JARVIS</h1><p class='small'>Windows Host Control Panel — local only</p>
<div class='card'><h2>Pairing</h2><p>One-time enrollment code</p><div class='code'>{escape(current_pairing_code or '—')}</div><p class='small'>Expires after 5 minutes and becomes invalid after use.</p></div>
<div class='card'><h2>Devices</h2><table><tr><th>Name</th><th>Device ID</th><th>Status</th></tr>{''.join(rows) or '<tr><td colspan=3>No devices paired yet.</td></tr>'}</table></div>
<div class='card'><h2>Security</h2><span class='badge'>Device-bound cryptographic authentication enabled</span><p class='small'>This panel is bound to 127.0.0.1 and is not intended for public exposure.</p></div>
</body></html>"""
