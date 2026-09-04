import re
import sys
from pathlib import Path

from fastapi import APIRouter, File, HTTPException, UploadFile
from fastapi.responses import FileResponse, HTMLResponse
from pydantic import BaseModel, Field

from .google_account import data_dir
from .windows_voice import windows_voice
from .version import VERSION

router = APIRouter()
ASSET_DIR = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1])) / "assets" / "avatar"
ALLOWED_IMAGE_TYPES = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}
ALLOWED_VOICE_TYPES = {"audio/wav": ".wav", "audio/mpeg": ".mp3", "audio/mp4": ".m4a", "audio/ogg": ".ogg"}


class VoiceSelection(BaseModel):
    voice_id: str = Field(min_length=1, max_length=200)


def _safe_upload_name(filename: str, suffix: str) -> str:
    stem = re.sub(r"[^a-zA-Z0-9_-]+", "-", Path(filename).stem).strip("-")[:50] or "upload"
    import secrets
    return f"{stem}-{secrets.token_hex(6)}{suffix}"


async def _save_upload(file: UploadFile, types: dict[str, str], folder: str, max_bytes: int) -> Path:
    suffix = types.get((file.content_type or "").lower())
    if not suffix:
        raise HTTPException(415, "Unsupported file type")
    payload = await file.read(max_bytes + 1)
    if len(payload) > max_bytes:
        raise HTTPException(413, f"File exceeds the {max_bytes // (1024 * 1024)} MB limit")
    target_dir = data_dir() / folder
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / _safe_upload_name(file.filename or folder, suffix)
    target.write_bytes(payload)
    return target


@router.get("/avatar/{name}")
def avatar_asset(name: str):
    if name not in {"viewer.html", "viewer.js", "david.fbx", "david.png", "A_Mistake.png"}:
        raise HTTPException(404, "Avatar asset not found")
    return FileResponse(ASSET_DIR / name)


@router.post("/media/images")
async def upload_image(file: UploadFile = File(...)):
    saved = await _save_upload(file, ALLOWED_IMAGE_TYPES, "images", 20 * 1024 * 1024)
    return {"uploaded": True, "name": saved.name, "message": "Image attached to this JARVIS session."}


@router.get("/voice/options")
def voice_options():
    return {"voices": windows_voice.voices()}


@router.post("/voice/select")
def select_voice(request: VoiceSelection):
    try:
        windows_voice.select(request.voice_id)
        return {"selected": request.voice_id}
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc


@router.post("/voice/custom")
async def upload_custom_voice(file: UploadFile = File(...)):
    saved = await _save_upload(file, ALLOWED_VOICE_TYPES, "voice-samples", 50 * 1024 * 1024)
    return {"uploaded": True, "name": saved.name, "status": "sample_saved"}


@router.get('/assistant-v1', response_class=HTMLResponse)
def assistant_v1_home():
    return '''<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Assistant Jarvis · V __JARVIS_VERSION__</title>
<style>
:root{color-scheme:dark;--bg:#03070d;--panel:#0b1420;--line:#1f4354;--text:#eefaff;--muted:#82a6b8;--cyan:#37d7ff;--red:#ff476f}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 50% -20%,#123651 0,transparent 45%),var(--bg);color:var(--text);font-family:Inter,"Segoe UI",system-ui,sans-serif;height:100vh;overflow:hidden}
.splash{position:fixed;inset:0;z-index:20;display:grid;place-items:center;background:#02060b;transition:opacity .45s,visibility .45s}.splash.done{opacity:0;visibility:hidden}.splash-core{width:86px;height:86px;border:2px solid var(--cyan);border-radius:50%;box-shadow:0 0 45px #37d7ff88,inset 0 0 28px #37d7ff44;animation:boot 1.2s linear infinite}.splash-copy{text-align:center;letter-spacing:.25em;color:#9beaff;margin-top:22px;font-size:12px}@keyframes boot{to{transform:rotate(360deg);border-right-color:transparent}}
.shell{height:100vh;display:flex;flex-direction:column;max-width:1320px;margin:auto;padding:18px 24px 14px}.top{display:flex;align-items:center;gap:12px}.brand{font-weight:800;font-size:20px;letter-spacing:.12em}.version{font-size:11px;color:var(--muted)}.spacer{flex:1}
button{border:1px solid var(--line);background:#101d29;color:var(--text);border-radius:14px;padding:10px 14px;cursor:pointer;font-weight:650}button:hover{border-color:var(--cyan);box-shadow:0 0 18px #37d7ff22}.settings{font-size:15px}.status{font-size:12px;color:#8fffd4;margin-top:5px}
.hero{flex:1;min-height:0;display:grid;grid-template-columns:minmax(280px,.8fr) minmax(420px,1.2fr);gap:22px;align-items:center}.avatar-shell{height:min(64vh,640px);min-height:340px;border:1px solid #1b5268;border-radius:28px;overflow:hidden;background:radial-gradient(circle at 50% 45%,#0b2d3c99,transparent 60%);box-shadow:inset 0 0 70px #051019,0 20px 70px #0008}.avatar-shell iframe{border:0;width:100%;height:100%}.conversation{min-height:0;display:flex;flex-direction:column;justify-content:center}.conversation h1{font-size:36px;line-height:1.1;margin:0 0 8px}.conversation>p{color:var(--muted);margin:0 0 20px}.tabs{display:flex;gap:7px;margin-bottom:8px}.tabs button{padding:7px 14px;font-size:12px}.tabs button.active{background:#136985;border-color:#48dcff}.reply{min-height:130px;max-height:32vh;overflow:auto;background:#08111b;border:1px solid #193748;border-radius:20px;padding:18px;text-align:left;white-space:pre-wrap;line-height:1.55}.reply a{display:block;color:#79ddff;margin-top:8px;text-decoration:none}.reply img{width:92px;height:64px;object-fit:cover;border-radius:9px;margin:8px 10px 0 0}
.mode-label{font-size:11px;color:var(--muted);margin:0 0 6px 14px}.composer{display:flex;align-items:center;gap:7px;background:#0b1621;border:1px solid var(--line);border-radius:30px;padding:7px 8px;box-shadow:0 18px 50px #0008}.target{min-width:120px}.composer input[type=text]{flex:1;border:0;outline:0;background:transparent;color:var(--text);font-size:16px;padding:12px}.round{width:44px;height:44px;border-radius:50%;padding:0}.send{background:#126c89}.foot{text-align:center;color:#567384;font-size:10px;padding-top:7px}.drawer{position:fixed;z-index:12;right:20px;top:70px;width:min(390px,calc(100vw - 40px));max-height:calc(100vh - 90px);overflow:auto;padding:18px;border:1px solid var(--line);border-radius:20px;background:#08111bf2;box-shadow:0 24px 80px #000}.drawer h2{margin:0 0 5px}.drawer label{display:block;color:var(--muted);font-size:12px;margin-top:14px}.drawer select,.drawer input{width:100%;margin-top:6px;padding:10px;border-radius:10px;background:#0d1d2a;color:var(--text);border:1px solid var(--line)}
@media(max-width:760px){.shell{padding:14px}.hero{grid-template-columns:1fr}.avatar-shell{height:34vh;min-height:220px}.conversation h1{font-size:28px}.reply{min-height:90px}.target{min-width:92px;font-size:11px}}
</style></head><body><div id="splash" class="splash"><div><div class="splash-core"></div><div class="splash-copy">INITIALIZING JARVIS</div></div></div><div class="shell">
<div class="top"><div><div class="brand">JARVIS</div><div class="version">SYSTEM V __JARVIS_VERSION__</div></div><div class="spacer"></div><button class="settings" onclick="toggleSettings()" title="Voice and avatar settings">⚙ Customize</button><button class="settings" onclick="location.href='/'" title="System settings">System</button></div>
<div id="status" class="status">● Windows host online</div>
<div class="hero"><div class="avatar-shell"><iframe id="avatar" title="David live avatar" src="/avatar/viewer.html?model=david.fbx"></iframe></div><div class="conversation"><h1>What can I do for you?</h1><p>David is online and ready.</p><div id="tabs" class="tabs" hidden><button data-kind="web" class="active">Web</button><button data-kind="images">Images</button><button data-kind="videos">Videos</button></div><div id="reply" class="reply">Systems ready.</div></div></div>
<div id="modeLabel" class="mode-label">Mode: Ask Jarvis</div>
<div class="composer"><button id="target" class="target" onclick="toggleTarget()">Ask Jarvis</button><input id="imageInput" type="file" accept="image/jpeg,image/png,image/webp" hidden onchange="uploadImage(this)"><button class="round" onclick="imageInput.click()" title="Attach picture">＋</button><input id="message" type="text" placeholder="Ask Jarvis anything…" autocomplete="off"><button class="round" onclick="voice()">🎙</button><button class="round send" onclick="send()">➤</button></div>
<div class="foot">Assistant Jarvis · V __JARVIS_VERSION__</div></div>
<aside id="drawer" class="drawer" hidden><h2>Customize JARVIS</h2><div class="version">Live avatar: David Martinez</div><label>Installed Windows voice<select id="voiceSelect"></select></label><button onclick="selectVoice()">Use selected voice</button><label>Upload a custom voice sample<input id="customVoice" type="file" accept="audio/wav,audio/mpeg,audio/mp4,audio/ogg"></label><button onclick="uploadVoice()">Save voice sample</button><p class="version">Voice samples stay on this PC. Creating a cloned voice requires a separately configured voice engine and explicit approval.</p></aside>
<script>
let homeMode=false,lastQuery='',searchType='web';const input=document.getElementById('message'),reply=document.getElementById('reply'),target=document.getElementById('target'),modeLabel=document.getElementById('modeLabel'),tabs=document.getElementById('tabs'),avatar=document.getElementById('avatar');
window.addEventListener('load',()=>setTimeout(()=>document.getElementById('splash').classList.add('done'),650));
function avatarState(state){try{avatar.contentWindow.setJarvisState(state)}catch(_){}}
function toggleSettings(){const d=document.getElementById('drawer');d.hidden=!d.hidden;if(!d.hidden)loadVoices()}
input.addEventListener('keydown',e=>{if(e.key==='Enter')send()});
function toggleTarget(){homeMode=!homeMode;target.textContent=homeMode?'Message Devices':'Ask Jarvis';modeLabel.textContent=homeMode?'Mode: Message paired devices':'Mode: Ask Jarvis';input.placeholder=homeMode?'Type a message for paired devices…':'Ask Jarvis anything…';input.focus()}
function showAssistant(data){reply.textContent=data.reply||'Done.';tabs.hidden=!(data.sources||[]).length;for(const source of (data.sources||[]).slice(0,3)){if(source.thumbnail){const image=document.createElement('img');image.src=source.thumbnail;image.alt='';image.loading='lazy';reply.appendChild(image)}const link=document.createElement('a');link.href=source.url;link.target='_blank';link.rel='noopener';link.textContent=source.title||source.url;reply.appendChild(link)}}
async function runSearch(message){reply.textContent='Thinking…';avatarState('thinking');try{const response=await fetch('/assistant',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message,search_type:searchType})});const data=await response.json();if(!response.ok)throw new Error(data.detail||'Request failed');showAssistant(data);avatarState('speaking');await speak(data.reply||'')}catch(e){reply.textContent='Assistant Jarvis error: '+e.message}finally{setTimeout(()=>avatarState('idle'),900)}}
for(const button of tabs.querySelectorAll('button'))button.addEventListener('click',()=>{searchType=button.dataset.kind;for(const item of tabs.querySelectorAll('button'))item.classList.toggle('active',item===button);if(lastQuery)runSearch(lastQuery)});
async function send(){const message=input.value.trim();if(!message)return;input.value='';if(!homeMode){lastQuery=message;runSearch(message);return}reply.textContent='Sending message to paired devices…';try{const response=await fetch('/messages/send',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message})});const data=await response.json();if(!response.ok)throw new Error(data.detail||'Request failed');reply.textContent='You → paired devices\\n'+message}catch(e){reply.textContent='Assistant Jarvis error: '+e.message}}
async function voice(){reply.textContent=homeMode?'Listening for device message…':'Listening for Jarvis request…';avatarState('listening');try{const r=await fetch('/voice/listen',{method:'POST'}),d=await r.json();if(!r.ok)throw new Error(d.detail||'Voice failed');input.value=d.transcript||'';send()}catch(e){reply.textContent='Microphone error: '+e.message;avatarState('idle')}}
async function speak(text){if(!text)return;try{await fetch('/voice/speak',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:text,queue:false})})}catch(_){}}
const seen=new Set();let initialized=false;async function inbox(){try{const items=await(await fetch('/messages/recent')).json();for(const item of [...items].reverse()){if(!seen.has(item.message_id)){seen.add(item.message_id);if(initialized&&item.sender_device_id!=='home'){const text=(item.sender_name||'Paired device')+': '+item.body;reply.textContent=text;speak(text)}}}initialized=true}catch(_){}}
async function uploadImage(el){const file=el.files[0];if(!file)return;const form=new FormData();form.append('file',file);reply.textContent='Uploading '+file.name+'…';const r=await fetch('/media/images',{method:'POST',body:form});const d=await r.json();reply.textContent=r.ok?'Picture attached: '+d.name:'Upload failed: '+(d.detail||'unknown error');el.value=''}
async function loadVoices(){const select=document.getElementById('voiceSelect');select.innerHTML='<option>Loading…</option>';try{const d=await(await fetch('/voice/options')).json();select.innerHTML=d.voices.map(v=>`<option value="${v.id}">${v.name} · ${v.gender} · ${v.culture}</option>`).join('')||'<option>No Windows voices found</option>'}catch(e){select.innerHTML='<option>Voice service unavailable</option>'}}
async function selectVoice(){const id=document.getElementById('voiceSelect').value;const r=await fetch('/voice/select',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({voice_id:id})});reply.textContent=r.ok?'Voice changed to '+id:'Could not change voice'}
async function uploadVoice(){const el=document.getElementById('customVoice'),file=el.files[0];if(!file)return;const form=new FormData();form.append('file',file);const r=await fetch('/voice/custom',{method:'POST',body:form});const d=await r.json();reply.textContent=r.ok?'Voice sample saved: '+d.name:'Voice upload failed: '+(d.detail||'unknown error');el.value=''}
inbox();setInterval(inbox,2500);
</script></body></html>'''.replace("__JARVIS_VERSION__", VERSION)
