from fastapi import APIRouter
from fastapi.responses import HTMLResponse

router = APIRouter()


@router.get('/assistant-v1', response_class=HTMLResponse)
def assistant_v1_home():
    return '''<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Assistant Jarvis · V 1.1</title>
<style>
:root{color-scheme:dark;--bg:#08090c;--panel:#15171c;--line:#2d3138;--text:#f3f4f6;--muted:#9aa3af;--accent:#e5e7eb}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 50% 0,#171a20 0,transparent 38%),var(--bg);color:var(--text);font-family:Inter,"Segoe UI",system-ui,sans-serif;height:100vh;overflow:hidden}
.shell{height:100vh;display:flex;flex-direction:column;max-width:1050px;margin:auto;padding:20px 24px 16px}.top{display:flex;align-items:center;gap:12px}.brand{font-weight:760;font-size:20px}.version{font-size:12px;color:var(--muted)}.spacer{flex:1}
button{border:1px solid var(--line);background:#181b21;color:var(--text);border-radius:14px;padding:10px 14px;cursor:pointer;font-weight:650}button:hover{background:#20242b}.settings{font-size:18px}.status{font-size:13px;color:var(--muted);margin-top:6px}
.hero{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding-bottom:70px}.hero h1{font-size:38px;line-height:1.1;margin:0 0 10px}.hero p{color:var(--muted);margin:0 0 26px}.reply{width:min(760px,100%);min-height:70px;background:#111318;border:1px solid #22262d;border-radius:20px;padding:18px;text-align:left;white-space:pre-wrap;line-height:1.55}
.mode-label{font-size:12px;color:var(--muted);margin:0 0 6px 14px}.composer{display:flex;align-items:center;gap:8px;background:#17191e;border:1px solid var(--line);border-radius:30px;padding:7px 8px;box-shadow:0 18px 50px #0008}.target{min-width:128px}.composer input{flex:1;border:0;outline:0;background:transparent;color:var(--text);font-size:16px;padding:12px}.composer input::placeholder{color:#717986}.round{width:44px;height:44px;border-radius:50%;padding:0}.foot{text-align:center;color:#6f7782;font-size:11px;padding-top:9px}
@media(max-width:700px){.shell{padding:14px}.hero h1{font-size:30px}.hero{padding-bottom:30px}.target{min-width:110px}}
</style></head><body><div class="shell">
<div class="top"><div><div class="brand">Assistant Jarvis</div><div class="version">V 1.1</div></div><div class="spacer"></div><button class="settings" onclick="location.href='/'" title="Settings">⚙ Settings</button></div>
<div id="status" class="status">● Windows host online</div>
<div class="hero"><h1>What can I do for you?</h1><p>One bar for Jarvis actions and private device messaging.</p><div id="reply" class="reply">Ready.</div></div>
<div id="modeLabel" class="mode-label">Mode: Ask Jarvis</div>
<div class="composer"><button id="target" class="target" onclick="toggleTarget()">Ask Jarvis</button><input id="message" placeholder="Ask Jarvis anything…" autocomplete="off"><button class="round" onclick="voice()">🎙</button><button class="round" onclick="send()">➤</button></div>
<div class="foot">Assistant Jarvis · V 1.1</div></div>
<script>
let homeMode=false;const input=document.getElementById('message'),reply=document.getElementById('reply'),target=document.getElementById('target'),modeLabel=document.getElementById('modeLabel');
input.addEventListener('keydown',e=>{if(e.key==='Enter')send()});
function toggleTarget(){homeMode=!homeMode;target.textContent=homeMode?'Message Devices':'Ask Jarvis';modeLabel.textContent=homeMode?'Mode: Message paired devices':'Mode: Ask Jarvis';input.placeholder=homeMode?'Type a message for paired devices…':'Ask Jarvis anything…';input.focus()}
async function send(){const message=input.value.trim();if(!message)return;input.value='';reply.textContent=homeMode?'Sending message to paired devices…':'Asking Jarvis…';try{const url=homeMode?'/messages/send':'/assistant';const response=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message})});const data=await response.json();if(!response.ok)throw new Error(data.detail||'Request failed');if(homeMode){reply.textContent='You → paired devices\n'+message}else{reply.textContent=data.reply||'Done.';speak(data.reply||'');if(data.action&&data.action.type==='open_url')window.open(data.action.url,'_blank','noopener')}}catch(e){reply.textContent='Assistant Jarvis error: '+e.message}}
async function voice(){reply.textContent=homeMode?'Listening for device message…':'Listening for Jarvis request…';try{const r=await fetch('/voice/listen',{method:'POST'}),d=await r.json();if(!r.ok)throw new Error(d.detail||'Voice failed');input.value=d.transcript||'';send()}catch(e){reply.textContent='Microphone error: '+e.message}}
async function speak(text){if(!text)return;try{await fetch('/voice/speak',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:text,queue:false})})}catch(_){}}
const seen=new Set();let initialized=false;async function inbox(){try{const items=await(await fetch('/messages/recent')).json();for(const item of [...items].reverse()){if(!seen.has(item.message_id)){seen.add(item.message_id);if(initialized&&item.sender_device_id!=='home'){const text=(item.sender_name||'Paired device')+': '+item.body;reply.textContent=text;speak(text)}}}initialized=true}catch(_){}}
inbox();setInterval(inbox,2500);
</script></body></html>'''
