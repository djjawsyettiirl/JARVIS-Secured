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
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.security.Signature
import java.util.Locale
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
    private var targetHome = false
    private var sessionToken: String? = null
    private var activeRoute: String? = null
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        tts = TextToSpeech(this, this)
        reconnect()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun normalized(v: String?) = v.orEmpty().trim().trimEnd('/')

    private fun routes(): List<String> {
        val p = getSharedPreferences("jarvis", MODE_PRIVATE)
        return listOf(p.getString("active_host", null), p.getString("lan_host", null), p.getString("remote_host", null))
            .map(::normalized).filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()
    }

    private fun rounded(fill: String, radius: Int, stroke: String? = null): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(fill))
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
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
            // Leave room for Android's status bar at the top and keep the composer raised at the bottom.
            setPadding(dp(18), dp(36), dp(18), dp(52))
            setBackgroundColor(Color.parseColor("#05070A"))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(6))
        }
        val brandBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandBlock.addView(TextView(this).apply {
            text = "Assistant Jarvis"
            textSize = 21f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
        })
        connection = TextView(this).apply {
            text = "Connecting…"
            textSize = 12.5f
            setTextColor(muted)
            setPadding(0, dp(2), 0, 0)
        }
        brandBlock.addView(connection)
        top.addView(brandBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Button(this).apply {
            text = "⚙"
            textSize = 20f
            isAllCaps = false
            minWidth = dp(50)
            minHeight = dp(46)
            background = rounded("#151A22", 18, "#252C37")
            setTextColor(primary)
            setOnClickListener { startActivity(Intent(this@AssistantHomeActivity, MainActivity::class.java)) }
        })
        root.addView(top)

        greeting = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(34), dp(12), dp(24))
        }
        greeting.addView(TextView(this).apply {
            text = "What can I do for you?"
            textSize = 30f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        greeting.addView(TextView(this).apply {
            text = "Ask Jarvis or message Home from the same bar."
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(greeting)

        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(4), 0, dp(10))
        }
        conversationScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(conversation, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(conversationScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            background = rounded("#14181E", 30, "#2A313B")
        }
        targetButton = Button(this).apply {
            text = "Jarvis"
            textSize = 13f
            isAllCaps = false
            minHeight = dp(46)
            minWidth = dp(70)
            setTextColor(primary)
            background = rounded("#222936", 22)
            setOnClickListener { targetHome = !targetHome; updateTarget() }
        }
        bar.addView(targetButton)
        composer = EditText(this).apply {
            hint = "Ask Jarvis anything…"
            textSize = 16f
            setTextColor(primary)
            setHintTextColor(Color.parseColor("#697382"))
            background = null
            setPadding(dp(12), dp(8), dp(8), dp(8))
            maxLines = 4
            minHeight = dp(48)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(false)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendCurrent(); true } else false
            }
        }
        bar.addView(composer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "🎙"
            textSize = 18f
            minWidth = dp(48)
            minHeight = dp(48)
            setTextColor(primary)
            background = rounded("#222936", 24)
            setOnClickListener { startVoice() }
        }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginStart = dp(4) })
        bar.addView(Button(this).apply {
            text = "➤"
            textSize = 18f
            minWidth = dp(48)
            minHeight = dp(48)
            setTextColor(Color.WHITE)
            background = rounded("#3269D8", 24)
            setOnClickListener { sendCurrent() }
        }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginStart = dp(6) })
        root.addView(bar)
        root.addView(TextView(this).apply {
            text = "Assistant Jarvis · V 1.1"
            textSize = 10.5f
            setTextColor(Color.parseColor("#596271"))
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
        })
        setContentView(root)
    }

    private fun updateTarget() {
        targetButton.text = if (targetHome) "Home" else "Jarvis"
        composer.hint = if (targetHome) "Message Home…" else "Ask Jarvis anything…"
    }

    private fun hideGreeting() { if (greeting.visibility != View.GONE) greeting.visibility = View.GONE }

    private fun addMessage(sender: String, body: String, mine: Boolean = false, system: Boolean = false) {
        hideGreeting()
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(7))
        }
        wrapper.addView(TextView(this).apply {
            text = sender
            textSize = 11.5f
            setTextColor(Color.parseColor("#778395"))
            setPadding(dp(8), 0, dp(8), dp(3))
        })
        wrapper.addView(TextView(this).apply {
            text = body
            textSize = 16f
            setTextColor(Color.parseColor("#F4F7FB"))
            setPadding(dp(15), dp(11), dp(15), dp(11))
            background = rounded(if (system) "#171C23" else if (mine) "#234F9B" else "#12171E", 18, if (mine) null else "#242C36")
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        })
        conversation.addView(wrapper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun reconnect() {
        val deviceId = getSharedPreferences("jarvis", MODE_PRIVATE).getString("device_id", null)
        if (deviceId == null) { connection.text = "Not paired · open Settings"; return }
        Thread {
            var last: Exception? = null
            for (route in routes()) {
                try {
                    val challengeReq = Request.Builder().url("$route/auth/challenge?device_id=$deviceId").post("".toRequestBody(null)).build()
                    val challenge = http.newCall(challengeReq).execute().use { r ->
                        val body = r.body?.string() ?: "{}"
                        if (!r.isSuccessful) error(errorDetail(body, "challenge failed"))
                        JSONObject(body).getString("challenge")
                    }
                    val payload = JSONObject().put("device_id", deviceId).put("signature_b64", sign(challenge))
                    val authReq = Request.Builder().url("$route/auth/verify").post(payload.toString().toRequestBody(jsonType)).build()
                    val obj = http.newCall(authReq).execute().use { r ->
                        val body = r.body?.string() ?: "{}"
                        if (!r.isSuccessful) error(errorDetail(body, "authentication failed"))
                        JSONObject(body)
                    }
                    sessionToken = obj.getString("session_token")
                    activeRoute = route
                    getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString("active_host", route).apply()
                    runOnUiThread {
                        connection.text = "● Connected via ${if (route.startsWith("https://")) "remote" else "local"}"
                        connection.setTextColor(Color.parseColor("#79AEFF"))
                    }
                    startPolling()
                    return@Thread
                } catch (e: Exception) { last = e }
            }
            runOnUiThread { connection.text = "Offline · ${last?.message ?: "no saved route"}"; connection.setTextColor(Color.parseColor("#D98B93")) }
        }.start()
    }

    private fun sign(challenge: String): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = (ks.getEntry("jarvis-device-key", null) as KeyStore.PrivateKeyEntry).privateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey); update(challenge.toByteArray()); Base64.encodeToString(sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }

    private fun sendCurrent() {
        val text = composer.text.toString().trim(); if (text.isEmpty()) return
        val token = sessionToken ?: run { addMessage("Assistant Jarvis", "Reconnecting…", system = true); reconnect(); return }
        val route = activeRoute ?: return
        composer.text.clear()
        addMessage(if (targetHome) "You → Home" else "You", text, mine = true)
        if (targetHome) sendHome(route, token, text) else ask(route, token, text)
    }

    private fun ask(route: String, token: String, text: String) {
        Thread {
            try {
                val req = Request.Builder().url("$route/assistant").header("Authorization", "Bearer $token")
                    .post(JSONObject().put("message", text).toString().toRequestBody(jsonType)).build()
                val obj = http.newCall(req).execute().use { r ->
                    val body = r.body?.string() ?: "{}"
                    if (!r.isSuccessful) error(errorDetail(body, "request failed"))
                    JSONObject(body)
                }
                val reply = obj.optString("reply", "No reply yet.")
                runOnUiThread {
                    addMessage("Jarvis", reply)
                    tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "assistant-reply")
                    obj.optJSONObject("action")?.takeIf { it.optString("type") == "open_url" }?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.getString("url")))) }
                }
            } catch (e: Exception) { runOnUiThread { addMessage("Jarvis", "Error: ${e.message}", system = true) } }
        }.start()
    }

    private fun sendHome(route: String, token: String, text: String) {
        Thread {
            try {
                val req = Request.Builder().url("$route/messages").header("Authorization", "Bearer $token")
                    .post(JSONObject().put("body", text).toString().toRequestBody(jsonType)).build()
                http.newCall(req).execute().use { r ->
                    val raw = r.body?.string().orEmpty()
                    if (!r.isSuccessful) error("HTTP ${r.code}: ${errorDetail(raw, "message failed")}")
                }
            } catch (e: Exception) {
                runOnUiThread { addMessage("Assistant Jarvis", "Message failed: ${e.message}", system = true) }
            }
        }.start()
    }

    private fun startPolling() {
        handler.removeCallbacksAndMessages(null)
        handler.post(object : Runnable { override fun run() { pollMessages(); handler.postDelayed(this, 5000) } })
    }

    private fun pollMessages() {
        val token = sessionToken ?: return; val route = activeRoute ?: return
        Thread {
            try {
                val req = Request.Builder().url("$route/messages").header("Authorization", "Bearer $token").get().build()
                http.newCall(req).execute().use { r ->
                    if (r.code == 401) { sessionToken = null; reconnect(); return@use }
                    if (!r.isSuccessful) return@use
                    val arr = org.json.JSONArray(r.body?.string() ?: "[]")
                    for (index in 0 until arr.length()) {
                        val item = arr.optJSONObject(index) ?: continue
                        val sender = item.optString("sender_name", "Home")
                        val body = item.optString("body")
                        runOnUiThread {
                            addMessage(sender, body)
                            tts?.speak("$sender: $body", TextToSpeech.QUEUE_ADD, null, "home-message-$index")
                        }
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun startVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2001); return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        startActivityForResult(intent, 2002)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2002 && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { composer.setText(it); sendCurrent() }
        }
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); tts?.shutdown(); super.onDestroy() }
}
