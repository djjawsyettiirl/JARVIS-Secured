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
import android.view.ViewGroup
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
    private lateinit var transcript: TextView
    private lateinit var connection: TextView
    private lateinit var targetButton: Button
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

    private fun buildUi() {
        val primary = Color.parseColor("#F4F7FB")
        val muted = Color.parseColor("#94A3B8")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(18))
            setBackgroundColor(Color.parseColor("#07090D"))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "Assistant Jarvis"
            textSize = 20f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Button(this).apply {
            text = "⚙"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@AssistantHomeActivity, MainActivity::class.java)) }
        })
        root.addView(top)

        connection = TextView(this).apply { text = "Connecting…"; textSize = 13f; setTextColor(muted) }
        root.addView(connection)

        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(90), 0, dp(30)) }
        center.addView(TextView(this).apply {
            text = "What can I do for you?"
            textSize = 30f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        center.addView(TextView(this).apply {
            text = "Ask Jarvis or switch the same message bar to Home messaging."
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        })
        transcript = TextView(this).apply {
            text = "Ready."
            textSize = 16f
            setTextColor(primary)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(Color.parseColor("#11151B")); cornerRadius = dp(18).toFloat() }
        }
        center.addView(transcript, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(center, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#171B22")); setStroke(dp(1), Color.parseColor("#303641")); cornerRadius = dp(28).toFloat()
            }
        }
        targetButton = Button(this).apply {
            text = "Jarvis"
            isAllCaps = false
            setOnClickListener { targetHome = !targetHome; updateTarget() }
        }
        bar.addView(targetButton)
        composer = EditText(this).apply {
            hint = "Ask anything"
            setTextColor(primary)
            setHintTextColor(Color.parseColor("#6F7885"))
            background = null
            maxLines = 4
        }
        bar.addView(composer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply { text = "🎙"; setOnClickListener { startVoice() } })
        bar.addView(Button(this).apply { text = "➤"; setOnClickListener { sendCurrent() } })
        root.addView(bar)
        root.addView(TextView(this).apply { text = "Assistant Jarvis · V 1.0"; textSize = 11f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        setContentView(root)
    }

    private fun updateTarget() {
        targetButton.text = if (targetHome) "Home" else "Jarvis"
        composer.hint = if (targetHome) "Message Home…" else "Ask Jarvis anything…"
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
                        val body = r.body?.string() ?: "{}"; if (!r.isSuccessful) error(JSONObject(body).optString("detail", "challenge failed")); JSONObject(body).getString("challenge")
                    }
                    val payload = JSONObject().put("device_id", deviceId).put("signature_b64", sign(challenge))
                    val authReq = Request.Builder().url("$route/auth/verify").post(payload.toString().toRequestBody(jsonType)).build()
                    val obj = http.newCall(authReq).execute().use { r -> val body = r.body?.string() ?: "{}"; if (!r.isSuccessful) error(JSONObject(body).optString("detail", "authentication failed")); JSONObject(body) }
                    sessionToken = obj.getString("session_token")
                    activeRoute = route
                    getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString("active_host", route).apply()
                    runOnUiThread { connection.text = "● Connected via ${if (route.startsWith("https://")) "remote" else "local"}" }
                    startPolling()
                    return@Thread
                } catch (e: Exception) { last = e }
            }
            runOnUiThread { connection.text = "Offline · ${last?.message ?: "no saved route"}" }
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
        val token = sessionToken ?: run { transcript.text = "Reconnecting…"; reconnect(); return }
        val route = activeRoute ?: return
        composer.text.clear()
        if (targetHome) sendHome(route, token, text) else ask(route, token, text)
    }

    private fun ask(route: String, token: String, text: String) {
        transcript.text = "Thinking…"
        Thread {
            try {
                val req = Request.Builder().url("$route/assistant").header("Authorization", "Bearer $token")
                    .post(JSONObject().put("message", text).toString().toRequestBody(jsonType)).build()
                val obj = http.newCall(req).execute().use { r -> val body = r.body?.string() ?: "{}"; if (!r.isSuccessful) error(JSONObject(body).optString("detail", "request failed")); JSONObject(body) }
                val reply = obj.optString("reply", "No reply yet.")
                runOnUiThread {
                    transcript.text = reply
                    tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "assistant-reply")
                    obj.optJSONObject("action")?.takeIf { it.optString("type") == "open_url" }?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.getString("url")))) }
                }
            } catch (e: Exception) { runOnUiThread { transcript.text = "Jarvis error: ${e.message}" } }
        }.start()
    }

    private fun sendHome(route: String, token: String, text: String) {
        Thread {
            try {
                val req = Request.Builder().url("$route/messages").header("Authorization", "Bearer $token")
                    .post(JSONObject().put("body", text).toString().toRequestBody(jsonType)).build()
                http.newCall(req).execute().use { r -> if (!r.isSuccessful) error(JSONObject(r.body?.string() ?: "{}").optString("detail", "message failed")) }
                runOnUiThread { transcript.text = "You → Home\n$text" }
            } catch (e: Exception) { runOnUiThread { transcript.text = "Message failed: ${e.message}" } }
        }.start()
    }

    private fun startPolling() {
        handler.removeCallbacksAndMessages(null)
        handler.post(object : Runnable {
            override fun run() { pollMessages(); handler.postDelayed(this, 5000) }
        })
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
                    if (arr.length() > 0) {
                        val item = arr.getJSONObject(arr.length() - 1)
                        val msg = "${item.optString("sender_name", "Home")}: ${item.optString("body")}" 
                        runOnUiThread { transcript.text = msg; tts?.speak(msg, TextToSpeech.QUEUE_ADD, null, "home-message") }
                    }
                }
            } catch (_: IOException) { }
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
