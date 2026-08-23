from __future__ import annotations

import socket
import time
from datetime import datetime
from html import escape
from fastapi import FastAPI, Form
from fastapi.responses import HTMLResponse, RedirectResponse
from pydantic import BaseModel, Field

from .store import Store, ALL_SCOPES
from . import tunnel
from .assistant import respond, store as assistant_store
from .google_account import google_account
from .updater import updater
from .online_assistant import online_assistant
from .windows_voice import windows_voice
from .version import VERSION

admin_app = FastAPI(title="JARVIS Host Control Panel", version=VERSION)
store = Store()
current_pairing_code = ""
VISIBLE_SCOPES = {
    "chat", "google_gmail", "google_calendar", "maps", "alarms",
    "messaging", "location_share", "software_updates", "offline_search",
}


class AssistantRequest(BaseModel):
    message: str = Field(min_length=1, max_length=1000)
    search_type: str = Field(default="web", pattern=r"^(web|images|videos)$")


class SearxngRequest(BaseModel):
    url: str = Field(min_length=8, max_length=2048)


class SerpApiRequest(BaseModel):
    api_key: str = Field(min_length=20, max_length=300)


class VoiceRequest(AssistantRequest):
    queue: bool = False


class TunnelConfigRequest(BaseModel):
    token: str = Field(min_length=80, max_length=4096)
    hostname: str = Field(min_length=10, max_length=253)


def _checked(scopes: list[str], name: str) -> str:
    return "checked" if name in scopes else ""


def _lan_ip() -> str:
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


def _device_state(device) -> tuple[str, str]:
    if device["revoked"]:
        return "REVOKED", "warn"
    last_seen = device["last_seen"] or 0
    age = time.time() - float(last_seen)
    if last_seen and age <= 45:
        return "CONNECTED NOW", "online"
    return "PAIRED / OFFLINE", "warn"


def _last_seen_text(value) -> str:
    if not value:
        return "Never"
    age = max(0, int(time.time() - float(value)))
    if age < 60:
        return f"{age}s ago"
    if age < 3600:
        return f"{age // 60}m ago"
    if age < 86400:
        return f"{age // 3600}h ago"
    return datetime.fromtimestamp(float(value)).strftime("%Y-%m-%d %H:%M")


@admin_app.get("/", response_class=HTMLResponse)
def dashboard():
    devices = store.list_devices()
    rows = []
    for device in devices:
        scopes = store.get_scopes(device["device_id"])
        state, state_class = _device_state(device)
        route = device["last_route"] or "unknown"
        boxes = " ".join(
            f"<label><input type='checkbox' name='scope' value='{s}' {_checked(scopes, s)}> {s}</label>"
            for s in ALL_SCOPES if s in VISIBLE_SCOPES
        )
        revoke_button = "" if device["revoked"] else f"<form method='post' action='/devices/{escape(device['device_id'])}/revoke'><button class='danger'>Revoke</button></form>"
        forget_button = f"<form method='post' action='/devices/{escape(device['device_id'])}/delete' onsubmit=\"return confirm('Permanently forget this paired device?');\"><button class='secondary'>Forget permanently</button></form>"
        rows.append(f"""
        <tr><td><form method='post' action='/devices/{escape(device['device_id'])}/name'><input name='name' value='{escape(device['name'])}' maxlength='50' required><button>Rename</button></form><code>{escape(device['device_id'])}</code></td>
        <td><span class='{state_class} badge'>{state}</span><div class='small'>Last seen: {_last_seen_text(device['last_seen'])}<br>Last route: {escape(route)}</div></td>
        <td><form method='post' action='/devices/{escape(device['device_id'])}/scopes' class='scopes'>{boxes}<button>Save</button></form></td>
        <td>{revoke_button}{forget_button}</td></tr>
        """)

    lan_ip = _lan_ip()
    lan_gateway = f"http://{lan_ip}:8765" if lan_ip != "Unavailable" else "Unavailable"
    remote_url = tunnel.public_url or "Waiting for tunnel…"
    online_class = "online" if tunnel.status == "online" else "warn"
    home_name = store.home_name()
    return f"""<!doctype html>
<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>JARVIS v{VERSION}</title>
<style>
:root{{color-scheme:dark;--bg:#070b12;--panel:#101826;--panel2:#0b121d;--line:#24334a;--text:#f4f7fb;--muted:#92a4ba;--blue:#3979ef;--blue2:#245cca}}
*{{box-sizing:border-box}}body{{font-family:Inter,"Segoe UI",system-ui,sans-serif;background:radial-gradient(circle at 12% -10%,#16325f 0,transparent 32%),var(--bg);color:var(--text);max-width:1380px;margin:0 auto;padding:24px}}
.app-header{{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:10px 4px 20px}}.brand{{display:flex;align-items:center;gap:14px}}.orb{{width:42px;height:42px;border-radius:50%;background:radial-gradient(circle at 35% 30%,#8fc2ff,#3979ef 42%,#132a54 70%);box-shadow:0 0 28px #3979ef88}}h1{{font-size:27px;margin:0;letter-spacing:.03em}}h2{{font-size:19px;margin:0 0 8px}}p{{line-height:1.55}}
.dashboard{{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}}.card{{background:linear-gradient(145deg,#121c2b,#0e1622);border:1px solid var(--line);border-radius:20px;padding:22px;box-shadow:0 16px 48px #0005;margin:0;min-width:0}}.hero{{grid-column:1/-1}}.settings-grid{{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin-top:16px}}.full{{grid-column:1/-1}}
.small{{color:var(--muted);font-size:14px}}code.url{{font-size:15px;word-break:break-all}}.code{{font-size:38px;letter-spacing:8px;font-weight:800;color:#9ec5ff;margin:12px 0}}
.badge{{display:inline-flex;align-items:center;padding:6px 10px;border-radius:999px;background:#173b2a;color:#8ff0b0;font-size:13px;font-weight:650}}.warn{{background:#493814;color:#ffd77a}}.online{{background:#153d2b;color:#91f2b2}}.error{{color:#ff9ca8}}
.route-grid{{display:grid;grid-template-columns:150px 1fr;gap:10px 14px;align-items:center}}.route-label{{color:var(--muted)}}.route-value{{background:var(--panel2);border:1px solid var(--line);border-radius:11px;padding:10px 12px;word-break:break-all}}
table{{width:100%;border-collapse:collapse;font-size:14px}}td,th{{text-align:left;padding:12px;border-bottom:1px solid var(--line);vertical-align:top}}th{{color:var(--muted);font-weight:600}}.scopes{{display:grid;grid-template-columns:repeat(3,minmax(110px,1fr));gap:7px}}
button{{border:1px solid transparent;border-radius:10px;padding:10px 15px;background:linear-gradient(180deg,var(--blue),var(--blue2));color:white;cursor:pointer;margin:8px 6px 0 0;font-weight:650}}button:hover{{filter:brightness(1.1)}}button:disabled{{opacity:.55}}.danger{{background:#7e2d3b}}.secondary{{background:#26364d;border-color:#344863}}
input{{background:var(--panel2);color:var(--text);border:1px solid var(--line);border-radius:11px;padding:11px 13px;font-size:15px}}input:focus{{outline:2px solid #3979ef77;border-color:#4d8cff}}input.assistant{{width:min(760px,100%)}}.compose{{display:flex;gap:8px;align-items:center}}.compose input{{flex:1}}
#assistantReply{{white-space:pre-wrap;line-height:1.55;background:#0a111b;border-radius:12px;padding:14px;min-height:52px}}#homeInbox{{max-height:270px;overflow:auto;margin-top:12px;padding-right:6px}}#homeInbox p{{background:#0a111b;border-radius:12px;padding:10px 12px;margin:7px 0}}.listening{{background:#a12c48}}
details.advanced{{grid-column:1/-1;background:#0b121d;border:1px solid var(--line);border-radius:18px;padding:4px 18px 18px}}details.advanced>summary{{cursor:pointer;padding:16px 2px;font-weight:700;color:#b9c9dc;list-style:none}}details.advanced>summary:before{{content:'›';display:inline-block;margin-right:10px;transition:transform .2s}}details[open]>summary:before{{transform:rotate(90deg)}}
.unused-feature{{display:none!important}}
@media(max-width:850px){{body{{padding:16px}}.dashboard,.settings-grid{{grid-template-columns:1fr}}.hero,.full{{grid-column:auto}}.route-grid{{grid-template-columns:1fr}}.app-header{{align-items:flex-start;flex-direction:column}}}}
</style></head><body>
<header class='app-header'><div class='brand'><div class='orb'></div><div><h1>JARVIS</h1><div class='small'>Windows voice assistant · v{VERSION}</div></div></div><span class='{online_class} badge'>Remote {escape(tunnel.status)}</span></header>
<main class='dashboard'>

<section class='card hero'><h2>Assistant</h2>
<p class='small'>Speak or type a request. Voice recognition and spoken replies use the native Windows speech engine.</p>
<input id='assistantInput' class='assistant' placeholder="Ask about Gmail, Calendar, or Maps" autocomplete='off'>
<button id='askButton' type='button' onclick='askJarvis()'>Ask JARVIS</button>
<button id='voiceButton' class='secondary' type='button' onclick='startVoice()'>🎙 Speak</button>
<p id='assistantReply'>Ready.</p></section>

<div class='card'><h2>Internet search</h2>
<span id='searchBadge' class='warn badge'>Checking…</span>
<p class='small'>Internet search currently uses SerpAPI.</p>
<span class='unused-feature'><input id='searxngUrl' class='assistant' type='url' placeholder='http://127.0.0.1:8080' autocomplete='url'>
<button type='button' onclick='saveSearxng()'>Save and test SearXNG</button>
<button class='danger' type='button' onclick='removeSearxng()'>Use local default</button></span>
<p><input id='serpapiKey' class='assistant' type='password' placeholder='SerpAPI key' autocomplete='new-password'>
<button type='button' onclick='saveSerpApi()'>Save and test SerpAPI</button>
<button class='danger' type='button' onclick='removeSerpApi()'>Remove SerpAPI key</button></p>
<p id='searchDetail' class='small'></p></div>

<div class='card unused-feature'><h2>Google account</h2>
<span id='googleBadge' class='warn badge'>Checking…</span>
<p id='googleDetail' class='small'></p>
<button type='button' onclick='connectGoogle()'>Connect Google account</button>
<button class='danger' type='button' onclick='disconnectGoogle()'>Disconnect</button>
<p class='small'>JARVIS requests Gmail read-only and Calendar event access. OAuth tokens are kept in Windows Credential Manager. Maps opens directions in your browser.</p></div>

<section class='card hero'><h2>Home inbox</h2>
<p class='small'>Messages and explicitly shared locations from paired devices appear here.</p>
<form method='post' action='/home/name'><input class='assistant' name='name' value='{escape(home_name)}' maxlength='50' required><button>Save Windows spoken name</button></form>
<div class='compose'><input id='homeMessage' placeholder='Send a message to paired devices'><button type='button' onclick='sendHomeMessage()'>Send</button></div>
<div id='homeInbox'>No messages yet.</div></section>

<details class='advanced' open><summary>Connections, updates, accounts, and device settings</summary><div class='settings-grid'>
<div class='card'><h2>Private updates</h2>
<span id='updateBadge' class='warn badge'>Idle</span><p id='updateDetail' class='small'></p>
<button type='button' onclick='checkUpdates()'>Check private builds</button>
<button type='button' onclick='downloadUpdates()'>Download updates</button>
<button class='danger' type='button' onclick='applyWindowsUpdate()'>Install Windows update</button>
<p class='small'>JARVIS checks private Windows builds every five minutes and automatically closes, updates, and reopens when a newer build is ready. Android receives its APK through the paired host and shows the protected installer confirmation.</p></div>

<div class='card full'><h2>Connection routes</h2>
<p class='small'>Only routes a paired phone can actually use are shown here.</p>
<div class='route-grid'>
<div class='route-label'>LAN / same Wi-Fi</div><div class='route-value'><span class='online badge'>LOCAL</span> <code class='url'>{escape(lan_gateway)}</code></div>
<div class='route-label'>Remote / internet</div><div class='route-value'><span class='{online_class} badge'>REMOTE {escape(tunnel.status.upper())}</span> <code id='remoteUrl' class='url'>{escape(remote_url)}</code></div>
</div>
<p class='small'>Internal loopback services (127.0.0.1) stay hidden because Android cannot use them. Quick Tunnel replacements overwrite the previous remote route instead of creating another connection.</p>
</div>

<div class='card'><h2>Remote connection</h2><span id='tunnelBadge' class='{online_class} badge'>Tunnel: {escape(tunnel.status)}</span><p id='tunnelError' class='error'>{escape(tunnel.last_error)}</p>
<p id='tunnelMode' class='small'>Mode: {'Permanent named tunnel' if tunnel.named_configured() else 'Temporary quick tunnel'}</p>
<span class='unused-feature'><input id='tunnelHostname' class='assistant' placeholder='https://jarvis.yourdomain.com' value='{escape(tunnel.named_hostname())}'>
<input id='tunnelToken' class='assistant' type='password' placeholder='Cloudflare tunnel token (eyJ...)' autocomplete='off'>
<button type='button' onclick='saveNamedTunnel()'>Use permanent tunnel</button>
<button class='secondary' type='button' onclick='clearNamedTunnel()'>Return to temporary tunnel</button></span>
<form method='get' action='/'><button class='secondary'>Refresh status now</button></form>
<form method='post' action='/tunnel/restart'><button>Restart remote tunnel</button></form>
<p class='small'>The tunnel proxies only the secure JARVIS gateway on port 8765. The admin dashboard remains localhost-only.</p></div>
<div class='card'><h2>Pair a device</h2><p class='small'>This code can be used exactly once and expires after five minutes.</p><div class='code'>{escape(current_pairing_code or '—')}</div><form method='post' action='/pairing/new'><button>Generate new pairing code</button></form></div>
<div class='card full'><h2>Devices & permissions</h2><p class='small'>CONNECTED NOW means the device has authenticated or used JARVIS in the last 45 seconds. Revoke blocks its key; Forget permanently removes an obsolete test pairing.</p><table><tr><th>Device</th><th>Status</th><th>JARVIS capabilities</th><th>Security</th></tr>{''.join(rows) or '<tr><td colspan=4>No devices paired yet.</td></tr>'}</table></div>
<div class='card'><h2>Security</h2><span class='badge'>ECDSA P-256 device authentication enabled</span><p class='small'>Only port 8765 is proxied through the remote tunnel. This admin dashboard remains bound to 127.0.0.1:8766 and is never published.</p></div>
</div></details></main>
<script>
const input = document.getElementById('assistantInput');
input.addEventListener('keydown', event => {{ if (event.key === 'Enter') askJarvis(); }});
async function speak(text, queue = false) {{
  try {{ await fetch('/voice/speak', {{method:'POST', headers:{{'Content-Type':'application/json'}}, body:JSON.stringify({{message:text, queue}})}}); }} catch (_) {{}}
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
async function startVoice() {{
  const button = document.getElementById('voiceButton'); const reply = document.getElementById('assistantReply');
  button.classList.add('listening'); button.textContent = 'Listening…'; button.disabled = true; reply.textContent = 'Listening…';
  try {{
    const response = await fetch('/voice/listen', {{method:'POST'}}); const data = await response.json();
    if (!response.ok) throw new Error(data.detail || 'Voice recognition failed');
    input.value = data.transcript; await askJarvis();
  }} catch (error) {{ reply.textContent = 'Microphone error: ' + error.message; }}
  finally {{ button.classList.remove('listening'); button.textContent = '🎙 Speak'; button.disabled = false; }}
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
async function searchStatus() {{
  try {{ const data = await (await fetch('/search/status')).json(); const ready = data.connected || data.serpapi_configured; const badge = document.getElementById('searchBadge'); badge.textContent = data.connected ? 'SearXNG connected' : (data.serpapi_configured ? 'SerpAPI ready' : 'Search setup required'); badge.className = (ready ? 'online' : 'warn') + ' badge'; document.getElementById('searchDetail').textContent = data.connected ? 'Private SearXNG search is ready.' : (data.serpapi_configured ? 'SerpAPI search is ready.' : 'Add a SerpAPI key to enable internet search.'); }} catch (_) {{}}
}}
async function saveSearxng() {{ const input = document.getElementById('searxngUrl'); const response = await fetch('/search/searxng', {{method:'POST',headers:{{'Content-Type':'application/json'}},body:JSON.stringify({{url:input.value}})}}); const data = await response.json(); if (!response.ok) alert(data.detail || 'Could not save SearXNG'); searchStatus(); }}
async function removeSearxng() {{ await fetch('/search/searxng', {{method:'DELETE'}}); document.getElementById('searxngUrl').value=''; searchStatus(); }}
async function saveSerpApi() {{ const input = document.getElementById('serpapiKey'); const response = await fetch('/search/serpapi', {{method:'POST',headers:{{'Content-Type':'application/json'}},body:JSON.stringify({{api_key:input.value}})}}); const data = await response.json(); if (!response.ok) alert(data.detail || 'Could not validate SerpAPI key'); else input.value=''; searchStatus(); }}
async function removeSerpApi() {{ await fetch('/search/serpapi', {{method:'DELETE'}}); document.getElementById('serpapiKey').value=''; searchStatus(); }}
async function tunnelStatus() {{
  try {{
    const data = await (await fetch('/tunnel/status')).json();
    const badge = document.getElementById('tunnelBadge'); badge.textContent = 'Tunnel: ' + data.status;
    badge.className = (data.status === 'online' ? 'online' : 'warn') + ' badge';
    document.getElementById('remoteUrl').textContent = data.public_url || 'Waiting for tunnel…';
    document.getElementById('tunnelError').textContent = data.error || '';
    document.getElementById('tunnelMode').textContent = 'Mode: ' + (data.mode === 'named' ? 'Permanent named tunnel' : 'Temporary quick tunnel');
  }} catch (_) {{}}
}}
async function saveNamedTunnel() {{
  const token = document.getElementById('tunnelToken').value.trim();
  const hostname = document.getElementById('tunnelHostname').value.trim();
  const response = await fetch('/tunnel/named', {{method:'POST',headers:{{'Content-Type':'application/json'}},body:JSON.stringify({{token,hostname}})}});
  const data = await response.json();
  if (!response.ok) {{ document.getElementById('tunnelError').textContent = data.detail || 'Could not save tunnel'; return; }}
  document.getElementById('tunnelToken').value = '';
  tunnelStatus();
}}
async function clearNamedTunnel() {{
  await fetch('/tunnel/named', {{method:'DELETE'}});
  document.getElementById('tunnelHostname').value = '';
  tunnelStatus();
}}
async function checkAlarms() {{
  try {{
    const alarms = await (await fetch('/alarms/due')).json();
    for (const alarm of alarms) {{ const message = 'Alarm: ' + alarm.message; document.getElementById('assistantReply').textContent = message; speak(message); }}
  }} catch (_) {{}}
}}
const knownHomeMessages = new Set();
let homeInboxInitialized = false;
async function homeInbox() {{
  try {{
    const messages = await (await fetch('/messages/recent')).json();
    document.getElementById('homeInbox').innerHTML = messages.length ? messages.map(item =>
      '<p><b>' + escapeHtml(item.sender_name || 'Paired device') + '</b>: ' + linkify(escapeHtml(item.body)) + '</p>'
    ).join('') : 'No messages yet.';
    const incoming = [];
    for (const item of messages) {{
      if (!knownHomeMessages.has(item.message_id)) {{
        knownHomeMessages.add(item.message_id);
        if (homeInboxInitialized && item.sender_device_id !== 'home') incoming.push(item);
      }}
    }}
    homeInboxInitialized = true;
    for (const item of incoming.reverse()) {{
      const body = /https:\\/\\/www\\.google\\.com\\/maps/.test(item.body) ? 'shared a location with you' : item.body;
      await speak((item.sender_name || 'Paired device') + ' says: ' + body, true);
    }}
  }} catch (_) {{}}
}}
function escapeHtml(value) {{ const node = document.createElement('div'); node.textContent = value; return node.innerHTML; }}
function linkify(value) {{ return value.replace(/(https:\\/\\/www\\.google\\.com\\/maps[^\\s<]*)/g, '<a href="$1" target="_blank" rel="noopener">Open location</a>'); }}
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
searchStatus(); tunnelStatus(); homeInbox(); updateStatus(); setInterval(searchStatus, 30000); setInterval(tunnelStatus, 10000); setInterval(homeInbox, 5000); setInterval(updateStatus, 10000); setInterval(checkAlarms, 2000);
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
    return tunnel.snapshot()


@admin_app.post("/tunnel/named")
def save_named_tunnel(request: TunnelConfigRequest):
    try:
        tunnel.configure_named(request.token, request.hostname)
        tunnel.restart_tunnel()
        return tunnel.snapshot()
    except Exception as exc:
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@admin_app.delete("/tunnel/named")
def clear_named_tunnel():
    tunnel.clear_named()
    tunnel.restart_tunnel()
    return tunnel.snapshot()


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


@admin_app.get("/search/status")
def search_status():
    return online_assistant.connection_status()


@admin_app.post("/search/searxng")
def save_searxng(request: SearxngRequest):
    try:
        online_assistant.save_searxng_url(request.url)
        return online_assistant.connection_status()
    except Exception as exc:
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@admin_app.delete("/search/searxng")
def remove_searxng():
    online_assistant.remove_searxng_url()
    return online_assistant.connection_status()


@admin_app.post("/search/serpapi")
def save_serpapi(request: SerpApiRequest):
    try:
        online_assistant.save_serpapi_key(request.api_key)
        results = online_assistant._serpapi("SerpAPI connection test")
        if not results:
            raise ValueError("SerpAPI returned no test results")
        return {"configured": True, "tested": True}
    except Exception as exc:
        online_assistant.remove_serpapi_key()
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@admin_app.delete("/search/serpapi")
def remove_serpapi():
    online_assistant.remove_serpapi_key()
    return {"configured": False}


@admin_app.post("/assistant")
def assistant(request: AssistantRequest):
    try:
        return respond(request.message, search_type=request.search_type)
    except Exception as exc:
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@admin_app.post("/voice/listen")
def voice_listen():
    try:
        return {"transcript": windows_voice.listen()}
    except Exception as exc:
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@admin_app.post("/voice/speak")
def voice_speak(request: VoiceRequest):
    try:
        windows_voice.speak(request.message, interrupt=not request.queue)
        return {"spoken": True}
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


@admin_app.post("/devices/{device_id}/name")
def rename_device(device_id: str, name: str = Form(...)):
    clean = name.strip()
    if not clean or len(clean) > 50:
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail="Device name must be 1 to 50 characters")
    store.rename_device(device_id, clean)
    return RedirectResponse(url="/", status_code=303)


@admin_app.post("/home/name")
def rename_home(name: str = Form(...)):
    clean = name.strip()
    if not clean or len(clean) > 50:
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail="Home name must be 1 to 50 characters")
    store.set_home_name(clean)
    return RedirectResponse(url="/", status_code=303)


@admin_app.post("/devices/{device_id}/revoke")
def revoke(device_id: str):
    store.revoke_device(device_id)
    return RedirectResponse(url="/", status_code=303)


@admin_app.post("/devices/{device_id}/delete")
def delete_device(device_id: str):
    store.delete_device(device_id)
    return RedirectResponse(url="/", status_code=303)
