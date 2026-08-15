from __future__ import annotations

import socket
from html import escape
from fastapi import FastAPI, Form
from fastapi.responses import HTMLResponse, RedirectResponse
from pydantic import BaseModel, Field

from .store import Store, ALL_SCOPES
from . import tunnel
from .assistant import respond, store as assistant_store
from .google_account import google_account
from .updater import updater
from .online_assistant import online_assistant, MODEL as AI_MODEL

admin_app = FastAPI(title="JARVIS Host Control Panel", version="0.4.5")
store = Store()
current_pairing_code = ""


class AssistantRequest(BaseModel):
    message: str = Field(min_length=1, max_length=1000)


class ApiKeyRequest(BaseModel):
    api_key: str = Field(min_length=20, max_length=300)


def _checked(scopes: list[str], name: str) -> str:
    return "checked" if name in scopes else ""


def _lan_ip() -> str:
    """Best-effort LAN IPv4 discovery without making an external connection."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("10.255.255.255", 1))
        return sock.getsockname()[0]
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "Unavailable"
    finally:
        sock.close()


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

    lan_ip = _lan_ip()
    local_gateway = "http://127.0.0.1:8765"
    lan_gateway = f"http://{lan_ip}:8765" if lan_ip != "Unavailable" else "Unavailable"
    admin_url = "http://127.0.0.1:8766"
    remote_url = tunnel.public_url or "Waiting for tunnel…"
    online_class = "online" if tunnel.status == "online" else "warn"
    return f"""<!doctype html>
<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>JARVIS v0.4.5</title>
<style>
:root{{color-scheme:dark}}body{{font-family:system-ui;background:#080c12;color:#edf2f7;max-width:1200px;margin:0 auto;padding:32px 20px}}
.card{{background:#111824;border:1px solid #263346;border-radius:18px;padding:22px;margin:16px 0;box-shadow:0 12px 40px #0004}}
.code{{font-size:44px;letter-spacing:9px;font-weight:800}}.small{{color:#93a1b1}}code.url{{font-size:18px;word-break:break-all}}
.badge{{display:inline-block;padding:6px 10px;border-radius:999px;background:#173b2a;color:#8ff0b0}}.warn{{background:#4a3814;color:#ffd77a}}.online{{background:#173b2a;color:#8ff0b0}}.error{{color:#ff9ca8}}
.route-grid{{display:grid;grid-template-columns:180px 1fr;gap:12px 18px;align-items:center}}.route-label{{color:#93a1b1}}.route-value{{background:#0b111a;border:1px solid #263346;border-radius:10px;padding:10px 12px;word-break:break-all}}
table{{width:100%;border-collapse:collapse}}td,th{{text-align:left;padding:14px;border-bottom:1px solid #263346;vertical-align:top}}
.scopes{{display:grid;grid-template-columns:repeat(3,minmax(120px,1fr));gap:8px}}button{{border:0;border-radius:9px;padding:10px 14px;background:#2d6cdf;color:white;cursor:pointer;margin:8px 6px 0 0}}.danger{{background:#8f3340}}.secondary{{background:#374151}}
input.assistant{{width:min(720px,calc(100% - 28px));background:#0b111a;color:#edf2f7;border:1px solid #263346;border-radius:10px;padding:12px 14px;font-size:16px}}#assistantReply{{white-space:pre-wrap;line-height:1.5}}.listening{{background:#a12c48}}
@media(max-width:700px){{.route-grid{{grid-template-columns:1fr}}}}
</style></head><body>
<h1>JARVIS <span class='small'>v0.4 voice assistant</span></h1><p class='small'>Windows Host Dashboard · local administration only</p>

<div class='card'><h2>Assistant</h2>
<p class='small'>Speak or type a request. Voice recognition uses your browser microphone; replies can be spoken aloud.</p>
<input id='assistantInput' class='assistant' placeholder="Ask about Gmail, Calendar, or Maps" autocomplete='off'>
<button id='askButton' type='button' onclick='askJarvis()'>Ask JARVIS</button>
<button id='voiceButton' class='secondary' type='button' onclick='startVoice()'>🎙 Speak</button>
<p id='assistantReply'>Ready.</p></div>

<div class='card'><h2>Internet intelligence</h2>
<span id='aiBadge' class='warn badge'>Checking…</span>
<p class='small'>Enables live internet search, current answers, and recommendations inside JARVIS. The key is stored in Windows Credential Manager and is never sent to paired devices.</p>
<input id='openaiKey' class='assistant' type='password' placeholder='OpenAI API key' autocomplete='new-password'>
<button type='button' onclick='saveAiKey()'>Enable internet intelligence</button>
<button class='danger' type='button' onclick='removeAiKey()'>Remove key</button>
<p id='aiDetail' class='small'></p></div>

<div class='card'><h2>Google account</h2>
<span id='googleBadge' class='warn badge'>Checking…</span>
<p id='googleDetail' class='small'></p>
<button type='button' onclick='connectGoogle()'>Connect Google account</button>
<button class='danger' type='button' onclick='disconnectGoogle()'>Disconnect</button>
<p class='small'>JARVIS requests Gmail read-only and Calendar event access. OAuth tokens are kept in Windows Credential Manager. Maps opens directions in your browser.</p></div>

<div class='card'><h2>Home inbox</h2>
<p class='small'>Messages and explicitly shared locations from paired devices appear here.</p>
<input id='homeMessage' class='assistant' placeholder='Send a message to paired devices'>
<button type='button' onclick='sendHomeMessage()'>Send</button>
<div id='homeInbox'>No messages yet.</div></div>

<div class='card'><h2>Private updates</h2>
<span id='updateBadge' class='warn badge'>Idle</span><p id='updateDetail' class='small'></p>
<button type='button' onclick='checkUpdates()'>Check private builds</button>
<button type='button' onclick='downloadUpdates()'>Download updates</button>
<button class='danger' type='button' onclick='applyWindowsUpdate()'>Install Windows update</button>
<p class='small'>Updates come from private GitHub Actions artifacts using this PC's authenticated GitHub login. Windows performs a brief controlled restart. Android receives its APK through the paired host and shows the protected installer confirmation.</p></div>

<div class='card'><h2>Connection routes</h2>
<div class='route-grid'>
<div class='route-label'>Local gateway</div><div class='route-value'><code class='url'>{escape(local_gateway)}</code></div>
<div class='route-label'>LAN / same Wi-Fi</div><div class='route-value'><code class='url'>{escape(lan_gateway)}</code></div>
<div class='route-label'>Remote / internet</div><div class='route-value'><code id='remoteUrl' class='url'>{escape(remote_url)}</code></div>
<div class='route-label'>Admin dashboard</div><div class='route-value'><code class='url'>{escape(admin_url)}</code></div>
</div>
<p class='small'>Use the LAN address when the phone is on the same network. Use the HTTPS remote address when testing over cellular or another network. The admin dashboard stays local to this PC.</p>
</div>

<div class='card'><h2>Remote connection</h2><span id='tunnelBadge' class='{online_class} badge'>Tunnel: {escape(tunnel.status)}</span><p id='tunnelError' class='error'>{escape(tunnel.last_error)}</p>

<form method='get' action='/'>
<button class='secondary'>Refresh status now</button>
</form>

<form method='post' action='/tunnel/restart'>
<button>Restart remote tunnel</button>
</form>

<p class='small'>Refresh status re-checks the current LAN address and tunnel status without restarting JARVIS. The temporary trycloudflare.com URL is HTTPS and changes whenever the tunnel restarts. It is for testing only.</p></div>
<div class='card'><h2>Pair a device</h2><p class='small'>This code can be used exactly once and expires after five minutes.</p><div class='code'>{escape(current_pairing_code or '—')}</div><form method='post' action='/pairing/new'><button>Generate new pairing code</button></form></div>
<div class='card'><h2>Devices & permissions</h2><table><tr><th>Device</th><th>Status</th><th>JARVIS capabilities</th><th>Security</th></tr>{''.join(rows) or '<tr><td colspan=4>No devices paired yet.</td></tr>'}</table></div>
<div class='card'><h2>Security</h2><span class='badge'>ECDSA P-256 device authentication enabled</span><p class='small'>Only port 8765 is proxied through the remote tunnel. This admin dashboard remains bound to 127.0.0.1:8766 and is never published.</p></div>
<script>
const input = document.getElementById('assistantInput');
input.addEventListener('keydown', event => {{ if (event.key === 'Enter') askJarvis(); }});
function speak(text) {{
  if ('speechSynthesis' in window) {{ speechSynthesis.cancel(); speechSynthesis.speak(new SpeechSynthesisUtterance(text)); }}
}}
async function askJarvis() {{
  const message = input.value.trim(); if (!message) return;
  const reply = document.getElementById('assistantReply'); reply.textContent = 'Thinking…';
  try {{
    const response = await fetch('/assistant', {{method:'POST', headers:{{'Content-Type':'application/json'}}, body:JSON.stringify({{message}})}});
    const data = await response.json(); if (!response.ok) throw new Error(data.detail || 'Request failed');
    reply.textContent = data.reply;
    for (const source of (data.sources || [])) {{ const line = document.createElement('div'); const link = document.createElement('a'); link.href = source.url; link.target = '_blank'; link.rel = 'noopener'; link.textContent = source.title || source.url; line.appendChild(link); reply.appendChild(line); }}
    speak(data.reply);
    if (data.action && data.action.type === 'open_url') window.open(data.action.url, '_blank', 'noopener');
  }} catch (error) {{ reply.textContent = 'JARVIS error: ' + error.message; }}
}}
function startVoice() {{
  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!Recognition) {{ document.getElementById('assistantReply').textContent = 'Voice recognition is not supported by this browser. Try Microsoft Edge or Chrome.'; return; }}
  const recognition = new Recognition(); const button = document.getElementById('voiceButton');
  recognition.lang = navigator.language || 'en-US'; recognition.interimResults = false;
  recognition.onstart = () => {{ button.classList.add('listening'); button.textContent = 'Listening…'; }};
  recognition.onend = () => {{ button.classList.remove('listening'); button.textContent = '🎙 Speak'; }};
  recognition.onerror = event => {{ document.getElementById('assistantReply').textContent = 'Microphone error: ' + event.error; }};
  recognition.onresult = event => {{ input.value = event.results[0][0].transcript; askJarvis(); }};
  recognition.start();
}}
async function googleStatus() {{
  try {{
    const data = await (await fetch('/google/status')).json();
    const badge = document.getElementById('googleBadge'); badge.textContent = data.status === 'connected' ? 'Connected: ' + data.email : data.status.replace('_',' ');
    badge.className = (data.status === 'connected' ? 'online' : 'warn') + ' badge';
    document.getElementById('googleDetail').textContent = data.error || (!data.configured ? 'Setup needed: place your Desktop OAuth client JSON at ' + data.client_file : '');
  }} catch (error) {{ document.getElementById('googleDetail').textContent = error.message; }}
}}
async function connectGoogle() {{ await fetch('/google/connect', {{method:'POST'}}); googleStatus(); }}
async function disconnectGoogle() {{ await fetch('/google/disconnect', {{method:'POST'}}); googleStatus(); }}
async function aiStatus() {{
  try {{ const data = await (await fetch('/ai/status')).json(); const badge = document.getElementById('aiBadge'); badge.textContent = data.configured ? 'Online search ready' : 'Setup required'; badge.className = (data.configured ? 'online' : 'warn') + ' badge'; document.getElementById('aiDetail').textContent = data.configured ? 'Model: ' + data.model : 'Add an API key to enable live search and recommendations.'; }} catch (_) {{}}
}}
async function saveAiKey() {{ const input = document.getElementById('openaiKey'); const response = await fetch('/ai/key', {{method:'POST',headers:{{'Content-Type':'application/json'}},body:JSON.stringify({{api_key:input.value}})}}); const data = await response.json(); if (!response.ok) alert(data.detail || 'Could not save key'); else input.value=''; aiStatus(); }}
async function removeAiKey() {{ await fetch('/ai/key', {{method:'DELETE'}}); aiStatus(); }}
async function tunnelStatus() {{
  try {{
    const data = await (await fetch('/tunnel/status')).json();
    const badge = document.getElementById('tunnelBadge'); badge.textContent = 'Tunnel: ' + data.status;
    badge.className = (data.status === 'online' ? 'online' : 'warn') + ' badge';
    document.getElementById('remoteUrl').textContent = data.public_url || 'Waiting for tunnel…';
    document.getElementById('tunnelError').textContent = data.error || '';
  }} catch (_) {{}}
}}
async function checkAlarms() {{
  try {{
    const alarms = await (await fetch('/alarms/due')).json();
    for (const alarm of alarms) {{ const message = 'Alarm: ' + alarm.message; document.getElementById('assistantReply').textContent = message; speak(message); }}
  }} catch (_) {{}}
}}
async function homeInbox() {{
  try {{
    const messages = await (await fetch('/messages/recent')).json();
    document.getElementById('homeInbox').innerHTML = messages.length ? messages.map(item =>
      '<p><b>' + escapeHtml(item.sender_name || 'Paired device') + '</b>: ' + linkify(escapeHtml(item.body)) + '</p>'
    ).join('') : 'No messages yet.';
  }} catch (_) {{}}
}}
function escapeHtml(value) {{ const node = document.createElement('div'); node.textContent = value; return node.innerHTML; }}
function linkify(value) {{ return value.replace(/(https:\/\/www\.google\.com\/maps[^\s<]*)/g, '<a href="$1" target="_blank" rel="noopener">Open location</a>'); }}
async function sendHomeMessage() {{
  const input = document.getElementById('homeMessage'); const body = input.value.trim(); if (!body) return;
  const response = await fetch('/messages/send', {{method:'POST', headers:{{'Content-Type':'application/json'}}, body:JSON.stringify({{message:body}})}});
  if (response.ok) {{ input.value = ''; homeInbox(); }}
}}
async function updateStatus() {{
  try {{
    const data = await (await fetch('/updates/status')).json();
    document.getElementById('updateBadge').textContent = data.status;
    document.getElementById('updateDetail').textContent = data.error || (data.android_ready ? 'Android update is ready for paired phones.' : '');
  }} catch (_) {{}}
}}
async function checkUpdates() {{ await fetch('/updates/check', {{method:'POST'}}); updateStatus(); }}
async function downloadUpdates() {{ await fetch('/updates/download', {{method:'POST'}}); updateStatus(); }}
async function applyWindowsUpdate() {{
  if (!confirm('Install the staged Windows update and restart JARVIS?')) return;
  const response = await fetch('/updates/apply-windows', {{method:'POST'}});
  if (!response.ok) {{ const data = await response.json(); alert(data.detail || 'Update failed'); }}
}}
googleStatus(); aiStatus(); tunnelStatus(); homeInbox(); updateStatus(); setInterval(googleStatus, 3000); setInterval(aiStatus, 5000); setInterval(tunnelStatus, 2000); setInterval(homeInbox, 2000); setInterval(updateStatus, 2000); setInterval(checkAlarms, 1000);
</script>
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


@admin_app.get("/tunnel/status")
def tunnel_status():
    return {"status": tunnel.status, "public_url": tunnel.public_url, "error": tunnel.last_error}


@admin_app.get("/google/status")
def google_status():
    return google_account.snapshot()


@admin_app.post("/google/connect")
def google_connect():
    return {"started": google_account.connect_async()}


@admin_app.post("/google/disconnect")
def google_disconnect():
    google_account.disconnect()
    return {"disconnected": True}


@admin_app.get("/ai/status")
def ai_status():
    return {"configured": online_assistant.configured(), "model": AI_MODEL}


@admin_app.post("/ai/key")
def save_ai_key(request: ApiKeyRequest):
    try:
        online_assistant.save_key(request.api_key)
        return {"configured": True}
    except Exception as exc:
        from fastapi import HTTPException

        raise HTTPException(status_code=400, detail=str(exc)) from exc


@admin_app.delete("/ai/key")
def remove_ai_key():
    online_assistant.remove_key()
    return {"configured": False}


@admin_app.post("/assistant")
def assistant(request: AssistantRequest):
    try:
        return respond(request.message)
    except Exception as exc:
        from fastapi import HTTPException

        raise HTTPException(status_code=400, detail=str(exc)) from exc


@admin_app.get("/alarms/due")
def alarms_due():
    return assistant_store.due_reminders()


@admin_app.get("/messages/recent")
def recent_messages():
    return store.recent_messages()


@admin_app.post("/messages/send")
def send_home_message(request: AssistantRequest):
    return {"message_id": store.send_message("home", request.message.strip()), "sent": True}


@admin_app.get("/updates/status")
def update_status():
    return updater.snapshot()


@admin_app.post("/updates/check")
def check_updates():
    return updater.check()


@admin_app.post("/updates/download")
def download_updates():
    return {"started": updater.download_async()}


@admin_app.post("/updates/apply-windows")
def apply_windows_update():
    try:
        updater.stage_windows_restart()
        import os
        import threading

        threading.Timer(1.0, lambda: os._exit(0)).start()
        return {"restarting": True}
    except Exception as exc:
        from fastapi import HTTPException

        raise HTTPException(status_code=400, detail=str(exc)) from exc


@admin_app.post("/devices/{device_id}/scopes")
def save_scopes(device_id: str, scope: list[str] = Form(default=[])):
    store.set_scopes(device_id, scope)
    return RedirectResponse(url="/", status_code=303)


@admin_app.post("/devices/{device_id}/revoke")
def revoke(device_id: str):
    store.revoke_device(device_id)
    return RedirectResponse(url="/", status_code=303)
