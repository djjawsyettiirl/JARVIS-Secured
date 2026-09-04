package com.jarvis.secured

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.security.Signature
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class AssistantHomeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()
    private lateinit var composer: EditText
    private lateinit var connection: TextView
    private lateinit var targetButton: Button
    private lateinit var conversation: LinearLayout
    private lateinit var conversationScroll: ScrollView
    private lateinit var greeting: LinearLayout
    private lateinit var searchTabs: LinearLayout
    private lateinit var avatarView: WebView
    private var searchType = "web"
    private var lastSearchQuery: String? = null
    private var targetHome = false
    private var sessionToken: String? = null
    private var activeRoute: String? = null
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var reconnecting = false
    @Volatile private var flushingQueue = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
        restoreQueuedMessages()
        tts = TextToSpeech(this, this)
        reconnect()
        if (intent.getBooleanExtra("start_voice", false)) handler.postDelayed({ startVoice() }, 500)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("start_voice", false)) handler.postDelayed({ startVoice() }, 250)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun normalized(v: String?) = v.orEmpty().trim().trimEnd('/')
    private fun routes(): List<String> {
        val p = getSharedPreferences("jarvis", MODE_PRIVATE)
        return listOf(p.getString("active_host", null), p.getString("lan_host", null), p.getString("remote_host", null))
            .map(::normalized).filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()
    }
    private fun rounded(fill: String, radius: Int, stroke: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(fill)); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
    }
    private fun errorDetail(raw: String, fallback: String): String {
        if (raw.isBlank()) return fallback
        return runCatching { JSONObject(raw).optString("detail").takeIf { it.isNotBlank() } }.getOrNull()
            ?: raw.trim().trim('"').take(240).ifBlank { fallback }
    }

    private fun buildUi() {
        val primary = Color.parseColor("#F4F7FB")
        val muted = Color.parseColor("#8B96A7")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(52))
            setBackgroundColor(Color.parseColor("#05070A"))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2),0,dp(2),dp(6)) }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply { text="Assistant Jarvis"; textSize=21f; setTextColor(primary); setTypeface(typeface, Typeface.BOLD) })
        connection = TextView(this).apply { text="Connecting…"; textSize=12.5f; setTextColor(muted); setPadding(0,dp(2),0,0) }
        brand.addView(connection)
        top.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Button(this).apply {
            text="⚙"; textSize=20f; isAllCaps=false; minWidth=dp(50); minHeight=dp(46); background=rounded("#151A22",18,"#252C37"); setTextColor(primary)
            setOnClickListener { startActivity(Intent(this@AssistantHomeActivity, MainActivity::class.java)) }
        })
        root.addView(top)

        avatarView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            loadUrl("file:///android_asset/avatar/viewer.html?model=crimson-silk-empress.glb")
        }
        root.addView(avatarView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(290)).apply {
            topMargin = dp(4)
            bottomMargin = dp(2)
        })

        greeting = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(12),dp(34),dp(12),dp(24)) }
        greeting.addView(TextView(this).apply { text="What can I do for you?"; textSize=30f; setTextColor(primary); setTypeface(typeface,Typeface.BOLD); gravity=Gravity.CENTER })
        greeting.addView(TextView(this).apply { text="Ask Jarvis or message Home from the same bar."; textSize=14f; setTextColor(muted); gravity=Gravity.CENTER; setPadding(0,dp(8),0,0) })
        root.addView(greeting)

        searchTabs = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.START; visibility=View.GONE; setPadding(0,0,0,dp(7)) }
        listOf("web" to "Web", "images" to "Images", "videos" to "Videos").forEach { (kind,label) ->
            searchTabs.addView(Button(this).apply {
                text=label; textSize=12f; isAllCaps=false; tag=kind; minHeight=dp(38); setTextColor(primary); background=rounded(if(kind==searchType)"#3269D8" else "#171C23",16,"#2A313B")
                setOnClickListener { selectSearchType(kind) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(40)).apply{marginEnd=dp(7)})
        }
        root.addView(searchTabs)

        conversation = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.BOTTOM; setPadding(0,dp(4),0,dp(10)) }
        conversationScroll = ScrollView(this).apply { isFillViewport=true; clipToPadding=false; addView(conversation, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)) }
        root.addView(conversationScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))

        val bar = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(7),dp(6),dp(7),dp(6)); background=rounded("#14181E",30,"#2A313B") }
        targetButton = Button(this).apply {
            text="Jarvis"; textSize=13f; isAllCaps=false; minHeight=dp(46); minWidth=dp(70); setTextColor(primary); background=rounded("#222936",22)
            setOnClickListener { targetHome=!targetHome; updateTarget() }
        }
        bar.addView(targetButton)
        bar.addView(Button(this).apply {
            text="＋"; textSize=20f; contentDescription="Attach a picture"; setTextColor(primary); background=rounded("#222936",24)
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE)},2003) }
        }, LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginStart=dp(4)})
        composer = EditText(this).apply {
            hint="Ask Jarvis anything…"; textSize=16f; setTextColor(primary); setHintTextColor(Color.parseColor("#697382")); background=null
            setPadding(dp(12),dp(8),dp(8),dp(8)); maxLines=4; minHeight=dp(48); imeOptions=EditorInfo.IME_ACTION_SEND; setSingleLine(false)
            setOnEditorActionListener { _, actionId, _ -> if (actionId==EditorInfo.IME_ACTION_SEND) { sendCurrent(); true } else false }
            setOnFocusChangeListener { _, focused ->
                avatarView.visibility = if(focused) View.GONE else View.VISIBLE
                if(focused) greeting.visibility = View.GONE
                conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        bar.addView(composer, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        bar.addView(Button(this).apply { text="🎙"; textSize=18f; setTextColor(primary); background=rounded("#222936",24); setOnClickListener{startVoice()} }, LinearLayout.LayoutParams(dp(50),dp(50)).apply{marginStart=dp(4)})
        bar.addView(Button(this).apply { text="➤"; textSize=18f; setTextColor(Color.WHITE); background=rounded("#3269D8",24); setOnClickListener{sendCurrent()} }, LinearLayout.LayoutParams(dp(50),dp(50)).apply{marginStart=dp(6)})
        root.addView(bar)
        root.addView(TextView(this).apply { text="Assistant Jarvis · V ${BuildConfig.VERSION_NAME}"; textSize=10.5f; setTextColor(Color.parseColor("#596271")); gravity=Gravity.CENTER; setPadding(0,dp(7),0,0) })
        setContentView(root)
    }

    private fun updateTarget() { targetButton.text=if(targetHome)"Home" else "Jarvis"; composer.hint=if(targetHome)"Message Home…" else "Ask Jarvis anything…" }
    private fun avatarState(state:String) {
        if (::avatarView.isInitialized) avatarView.evaluateJavascript("window.setJarvisState(${JSONObject.quote(state)})", null)
    }

    private fun uploadImage(uri: Uri) {
        val token=sessionToken; val route=activeRoute
        if(token==null || route==null){addMessage("JARVIS", "Reconnect before attaching a picture.", system=true);return}
        val type=contentResolver.getType(uri) ?: "image/jpeg"
        val extension=when(type){"image/png"->"png";"image/webp"->"webp";else->"jpg"}
        val target=File(cacheDir,"jarvis-image-${System.currentTimeMillis()}.$extension")
        try { contentResolver.openInputStream(uri)?.use { input->target.outputStream().use{output->input.copyTo(output)} } ?: error("Image could not be opened") }
        catch(e:Exception){addMessage("JARVIS","Could not attach picture: ${e.message}",system=true);return}
        addMessage("JARVIS", "Uploading picture…", system=true); avatarState("thinking")
        Thread {
            try {
                val body=MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file",target.name,target.asRequestBody(type.toMediaType())).build()
                val request=Request.Builder().url("$route/media/images").header("Authorization","Bearer $token").post(body).build()
                val message=http.newCall(request).execute().use { response ->
                    val raw=response.body?.string().orEmpty(); if(!response.isSuccessful) error(errorDetail(raw,"upload failed"))
                    JSONObject(raw).optString("name",target.name)
                }
                runOnUiThread{addMessage("Picture attached",message,mine=true);avatarState("idle")}
            } catch(e:Exception){runOnUiThread{addMessage("JARVIS","Picture upload failed: ${e.message}",system=true);avatarState("idle")}}
            finally { target.delete() }
        }.start()
    }
    private fun selectSearchType(kind:String) {
        searchType=kind
        for(i in 0 until searchTabs.childCount)(searchTabs.getChildAt(i) as Button).background=rounded(if(searchTabs.getChildAt(i).tag==kind)"#3269D8" else "#171C23",16,"#2A313B")
        val query=lastSearchQuery?:return
        addMessage("Search", "${kind.replaceFirstChar{it.uppercase()}} · $query", mine=true)
        val token=sessionToken;val route=activeRoute
        if(token!=null&&route!=null)ask(route,token,query,false) else directSearch(query,false)
    }
    private fun showSearchTabs(query:String) { lastSearchQuery=query;searchTabs.visibility=View.VISIBLE }
    private fun hideGreeting() { if (greeting.visibility != View.GONE) greeting.visibility = View.GONE }
    private fun addMessage(sender:String, body:String, mine:Boolean=false, system:Boolean=false) {
        hideGreeting()
        val wrapper=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=if(mine)Gravity.END else Gravity.START;setPadding(0,dp(3),0,dp(7))}
        wrapper.addView(TextView(this).apply{text=sender;textSize=11.5f;setTextColor(Color.parseColor("#778395"));setPadding(dp(8),0,dp(8),dp(3))})
        wrapper.addView(TextView(this).apply{
            text=body;textSize=16f;setTextColor(Color.parseColor("#F4F7FB"));setPadding(dp(15),dp(11),dp(15),dp(11));
            background=rounded(if(system)"#171C23" else if(mine)"#234F9B" else "#12171E",18,if(mine)null else "#242C36");maxWidth=(resources.displayMetrics.widthPixels*.82f).toInt()
            autoLinkMask=Linkify.WEB_URLS;linksClickable=true;movementMethod=LinkMovementMethod.getInstance();setLinkTextColor(Color.parseColor("#79AEFF"))
        })
        conversation.addView(wrapper,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));conversationScroll.post{conversationScroll.fullScroll(View.FOCUS_DOWN)}
    }

    private data class Pending(val id:String,val target:String,val body:String)
    private fun loadQueue(): MutableList<Pending> {
        val raw=getSharedPreferences("jarvis",MODE_PRIVATE).getString("offline_queue","[]") ?: "[]"
        return runCatching {
            val arr=JSONArray(raw); MutableList(arr.length()){i->val o=arr.getJSONObject(i);Pending(o.getString("id"),o.getString("target"),o.getString("body"))}
        }.getOrDefault(mutableListOf())
    }
    private fun saveQueue(items:List<Pending>) {
        val arr=JSONArray();items.forEach{arr.put(JSONObject().put("id",it.id).put("target",it.target).put("body",it.body))}
        getSharedPreferences("jarvis",MODE_PRIVATE).edit().putString("offline_queue",arr.toString()).apply()
    }
    private fun enqueue(target:String, body:String) {
        val items=loadQueue();items.add(Pending(UUID.randomUUID().toString(),target,body));saveQueue(items)
        addMessage("Queued", if(target=="home")"Home · $body" else "Jarvis · $body", system=true)
        connection.text="Offline · ${items.size} queued"
        reconnect()
    }
    private fun restoreQueuedMessages() { loadQueue().forEach{addMessage("Queued", if(it.target=="home")"Home · ${it.body}" else "Jarvis · ${it.body}", system=true)} }

    private fun reconnect() {
        if (reconnecting) return
        reconnecting=true
        val deviceId=getSharedPreferences("jarvis",MODE_PRIVATE).getString("device_id",null)
        if(deviceId==null){connection.text="Not paired · open Settings";reconnecting=false;return}
        Thread {
            var last:Exception?=null
            for(route in routes()) {
                try {
                    val challengeReq=Request.Builder().url("$route/auth/challenge?device_id=$deviceId").post("".toRequestBody(null)).build()
                    val challenge=http.newCall(challengeReq).execute().use{r->val b=r.body?.string()?:"{}";if(!r.isSuccessful)error(errorDetail(b,"challenge failed"));JSONObject(b).getString("challenge")}
                    val payload=JSONObject().put("device_id",deviceId).put("signature_b64",sign(challenge))
                    val authReq=Request.Builder().url("$route/auth/verify").post(payload.toString().toRequestBody(jsonType)).build()
                    val obj=http.newCall(authReq).execute().use{r->val b=r.body?.string()?:"{}";if(!r.isSuccessful)error(errorDetail(b,"authentication failed"));JSONObject(b)}
                    sessionToken=obj.getString("session_token");activeRoute=route;getSharedPreferences("jarvis",MODE_PRIVATE).edit().putString("active_host",route).apply()
                    val scopes=obj.optJSONArray("scopes")?.let{arr->(0 until arr.length()).map{arr.getString(it)}} ?: emptyList()
                    if(route.startsWith("https://") && "offline_search" in scopes) syncSearchCredentials(route,sessionToken!!)
                    runOnUiThread{connection.text="● Connected via ${if(route.startsWith("https://"))"remote" else "local"}";connection.setTextColor(Color.parseColor("#79AEFF"))}
                    startPolling();flushOfflineQueue();reconnecting=false;return@Thread
                } catch(e:Exception){last=e}
            }
            runOnUiThread{connection.text=OfflineCapabilities.status(this);connection.setTextColor(Color.parseColor("#D9B26F"))};reconnecting=false
        }.start()
    }

    private fun sign(challenge:String):String {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};val privateKey=(ks.getEntry("jarvis-device-key",null) as KeyStore.PrivateKeyEntry).privateKey
        return Signature.getInstance("SHA256withECDSA").run{initSign(privateKey);update(challenge.toByteArray());Base64.encodeToString(sign(),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}
    }

    private fun sendCurrent() {
        val text=composer.text.toString().trim();if(text.isEmpty())return;composer.text.clear()
        addMessage(if(targetHome)"You → Home" else "You",text,mine=true)
        if(!targetHome) AwarenessManager.handleLocalCommand(this,text)?.let { addMessage("Jarvis · awareness",it);return }
        val target=if(targetHome)"home" else "jarvis"
        if(!targetHome)lastSearchQuery=text
        val token=sessionToken;val route=activeRoute
        if(token==null || route==null){
            if(!targetHome) {
                OfflineCapabilities.launch(this,text)?.let{addMessage("Jarvis · limited",it,system=true);return}
                if(SecureSearchCredentials.serpApiKey(this)!=null) directSearch(text,true) else enqueue(target,text)
            } else enqueue(target,text)
            return
        }
        if(targetHome)sendHome(route,token,text,true) else ask(route,token,text,true)
    }

    private fun ask(route:String, token:String, text:String, queueOnFailure:Boolean) {
        avatarState("thinking")
        Thread {
            try {
                val req=Request.Builder().url("$route/assistant").header("Authorization","Bearer $token").post(JSONObject().put("message",text).put("search_type",searchType).toString().toRequestBody(jsonType)).build()
                val obj=http.newCall(req).execute().use{r->val b=r.body?.string()?:"{}";if(!r.isSuccessful)error(errorDetail(b,"request failed"));JSONObject(b)}
                val reply=obj.optString("reply","No reply yet.")
                runOnUiThread{if((obj.optJSONArray("sources")?.length() ?: 0)>0)showSearchTabs(text);addMessage("Jarvis",reply);avatarState("speaking");tts?.speak(reply,TextToSpeech.QUEUE_FLUSH,null,"assistant-reply");handler.postDelayed({avatarState("idle")},1200);obj.optJSONObject("action")?.takeIf{it.optString("type")=="open_url"}?.let{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(it.getString("url"))))}}
            } catch(e:Exception) {
                sessionToken=null
                val local=OfflineCapabilities.actionFor(text)
                if(local!=null) {
                    runOnUiThread{val confirmation=OfflineCapabilities.launch(this,text)?:local.confirmation;addMessage("Jarvis · limited",confirmation,system=true);connection.text=OfflineCapabilities.status(this)}
                } else if(SecureSearchCredentials.serpApiKey(this)!=null) {
                    val reply=directSerpApi(text)
                    runOnUiThread{connection.text=OfflineCapabilities.status(this);if(reply!=null){showSearchTabs(text);addMessage("Jarvis · direct SerpAPI",reply);tts?.speak(reply,TextToSpeech.QUEUE_FLUSH,null,"direct-search-reply")}else if(queueOnFailure)enqueue("jarvis",text) else addMessage("Jarvis","Queued until reconnect",system=true)}
                } else {
                    runOnUiThread{if(queueOnFailure)enqueue("jarvis",text) else addMessage("Jarvis","Queued until reconnect",system=true)}
                }
            }
        }.start()
    }

    private fun syncSearchCredentials(route:String, token:String) {
        runCatching {
            val request=Request.Builder().url("$route/search/mobile-credentials").header("Authorization","Bearer $token").get().build()
            http.newCall(request).execute().use { response ->
                if(!response.isSuccessful)return@use
                val data=JSONObject(response.body?.string()?:"{}")
                SecureSearchCredentials.saveSerpApiKey(this,data.optString("serpapi_key").takeIf{it.isNotBlank() && it!="null"})
            }
        }
    }

    private fun directSearch(text:String, queueOnFailure:Boolean) {
        Thread {
            val reply=directSerpApi(text)
            runOnUiThread {
                if(reply!=null){showSearchTabs(text);addMessage("Jarvis · direct SerpAPI",reply);tts?.speak(reply,TextToSpeech.QUEUE_FLUSH,null,"direct-search-reply")}
                else if(queueOnFailure)enqueue("jarvis",text)
                else addMessage("Jarvis","Direct search is unavailable",system=true)
            }
        }.start()
    }

    private fun directSerpApi(text:String):String? {
        return DirectSerpApiSearch.search(this,text,searchType)
    }

    private fun sendHome(route:String, token:String, text:String, queueOnFailure:Boolean) {
        Thread {
            try {
                val req=Request.Builder().url("$route/messages").header("Authorization","Bearer $token").post(JSONObject().put("body",text).toString().toRequestBody(jsonType)).build()
                http.newCall(req).execute().use{r->val raw=r.body?.string().orEmpty();if(!r.isSuccessful)error("HTTP ${r.code}: ${errorDetail(raw,"message failed")}")}
            } catch(e:Exception) {
                sessionToken=null
                runOnUiThread{if(queueOnFailure)enqueue("home",text) else addMessage("Assistant Jarvis","Queued until reconnect",system=true)}
            }
        }.start()
    }

    private fun flushOfflineQueue() {
        if(flushingQueue)return
        val token=sessionToken?:return;val route=activeRoute?:return
        flushingQueue=true
        Thread {
            val pending=loadQueue();val remaining=mutableListOf<Pending>()
            for(item in pending) {
                try {
                    val path=if(item.target=="home")"/messages" else "/assistant"
                    val payload=if(item.target=="home")JSONObject().put("body",item.body) else JSONObject().put("message",item.body)
                    val req=Request.Builder().url("$route$path").header("Authorization","Bearer $token").post(payload.toString().toRequestBody(jsonType)).build()
                    val reply=http.newCall(req).execute().use{r->val raw=r.body?.string().orEmpty();if(!r.isSuccessful)error(errorDetail(raw,"send failed"));if(item.target=="jarvis")runCatching{JSONObject(raw).optString("reply")}.getOrDefault("") else ""}
                    runOnUiThread{addMessage("Sent", if(item.target=="home")"Home · ${item.body}" else item.body, system=true);if(reply.isNotBlank())addMessage("Jarvis",reply)}
                } catch(_:Exception){remaining.add(item);remaining.addAll(pending.dropWhile{it.id!=item.id}.drop(1));break}
            }
            saveQueue(remaining)
            flushingQueue=false
            if(remaining.isNotEmpty()) runOnUiThread{connection.text="Offline · ${remaining.size} queued"}
        }.start()
    }

    private fun startPolling(){handler.removeCallbacksAndMessages(null);handler.post(object:Runnable{override fun run(){pollMessages();if(sessionToken!=null)flushOfflineQueue();handler.postDelayed(this,5000)}})}
    private fun pollMessages(){val token=sessionToken?:return;val route=activeRoute?:return;Thread{try{val req=Request.Builder().url("$route/messages").header("Authorization","Bearer $token").get().build();http.newCall(req).execute().use{r->if(r.code==401){sessionToken=null;reconnect();return@use};if(!r.isSuccessful)return@use;val arr=JSONArray(r.body?.string()?:"[]");for(i in 0 until arr.length()){val item=arr.optJSONObject(i)?:continue;val sender=item.optString("sender_name","Home");val body=item.optString("body");runOnUiThread{addMessage(sender,body);tts?.speak("$sender: $body",TextToSpeech.QUEUE_ADD,null,"home-message-$i")}}}}catch(_:Exception){sessionToken=null;reconnect()}}.start()}

    private fun startVoice(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),2001);return};avatarState("listening");val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault())};startActivityForResult(intent,2002)}
    @Deprecated("Deprecated in Android") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==2002&&resultCode==RESULT_OK){data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let{composer.setText(it);sendCurrent()}};if(requestCode==2003&&resultCode==RESULT_OK)data?.data?.let{uploadImage(it)}}
    override fun onInit(status:Int){if(status==TextToSpeech.SUCCESS)tts?.language=Locale.getDefault()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);tts?.shutdown();super.onDestroy()}
}
