package com.jarvis.secured

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.location.LocationManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.concurrent.TimeUnit
import java.util.Locale
import java.io.File

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()
    private lateinit var host: EditText
    private lateinit var code: EditText
    private lateinit var status: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var scopesStatus: TextView
    private lateinit var assistantInput: EditText
    private lateinit var assistantReply: TextView
    private var sessionToken: String? = null
    private var tts: TextToSpeech? = null
    private val messageHandler = Handler(Looper.getMainLooper())
    private var messagePolling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        tts = TextToSpeech(this, this)
        refreshPermissions()
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        host.setText(prefs.getString("host", "http://192.168.1.100:8765"))
        val savedDevice = prefs.getString("device_id", null)
        if (savedDevice != null) refreshSession(host.text.toString().trim().trimEnd('/'), savedDevice)
        if (!prefs.getBoolean("permissions_onboarded", false)) {
            prefs.edit().putBoolean("permissions_onboarded", true).apply()
            requestAllPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::permissionStatus.isInitialized) refreshPermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }
        val title = TextView(this).apply { text = "JARVIS"; textSize = 32f }
        val subtitle = TextView(this).apply {
            text = "Secure personal AI companion"
            textSize = 16f
        }
        permissionStatus = TextView(this).apply { textSize = 15f; setPadding(0, 18, 0, 8) }
        val request = Button(this).apply {
            text = "REQUEST / REVIEW PERMISSIONS"
            setOnClickListener { requestAllPermissions() }
        }
        val settings = Button(this).apply {
            text = "OPEN ANDROID APP SETTINGS"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        val pairTitle = TextView(this).apply { text = "Windows Host"; textSize = 22f; setPadding(0, 24, 0, 8) }
        host = EditText(this).apply {
            hint = "Host URL"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        code = EditText(this).apply {
            hint = "8-digit one-time pairing code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        }
        val pair = Button(this).apply {
            text = "PAIR WITH JARVIS"
            setOnClickListener { pairDevice() }
        }
        status = TextView(this).apply { text = "Not paired"; textSize = 17f; setPadding(0, 12, 0, 8) }
        scopesStatus = TextView(this).apply { textSize = 15f }
        val assistantTitle = TextView(this).apply { text = "Assistant"; textSize = 22f; setPadding(0, 28, 0, 8) }
        assistantInput = EditText(this).apply { hint = "Ask JARVIS"; maxLines = 3 }
        val ask = Button(this).apply { text = "ASK JARVIS"; setOnClickListener { askJarvis() } }
        val speak = Button(this).apply { text = "SPEAK TO JARVIS"; setOnClickListener { startVoiceInput() } }
        val update = Button(this).apply { text = "CHECK PRIVATE UPDATE"; setOnClickListener { installPrivateUpdate() } }
        assistantReply = TextView(this).apply { text = "Pair with the Windows host to begin."; textSize = 17f; setPadding(0, 12, 0, 20) }

        root.addView(title)
        root.addView(subtitle)
        root.addView(permissionStatus)
        root.addView(request)
        root.addView(settings)
        root.addView(pairTitle)
        root.addView(host)
        root.addView(code)
        root.addView(pair)
        root.addView(status)
        root.addView(scopesStatus)
        root.addView(assistantTitle)
        root.addView(assistantInput)
        root.addView(ask)
        root.addView(speak)
        root.addView(update)
        root.addView(assistantReply)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun permissionRequests(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return permissions.toTypedArray()
    }

    private fun requestAllPermissions() {
        val pending = permissionRequests().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (pending.isNotEmpty()) requestPermissions(pending.toTypedArray(), PERMISSION_REQUEST)
        else refreshPermissions()
    }

    private fun permissionLabel(permission: String, label: String): String {
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        return "$label: ${if (granted) "GRANTED" else "NOT GRANTED"}"
    }

    private fun refreshPermissions() {
        if (!::permissionStatus.isInitialized) return
        val lines = mutableListOf(
            permissionLabel(Manifest.permission.RECORD_AUDIO, "Microphone"),
            permissionLabel(Manifest.permission.CAMERA, "Camera"),
            permissionLabel(Manifest.permission.ACCESS_FINE_LOCATION, "Location sharing")
        )
        if (Build.VERSION.SDK_INT >= 33) lines.add(permissionLabel(Manifest.permission.POST_NOTIFICATIONS, "Notifications"))
        if (Build.VERSION.SDK_INT >= 31) {
            lines.add(permissionLabel(Manifest.permission.BLUETOOTH_SCAN, "Nearby devices / scan"))
            lines.add(permissionLabel(Manifest.permission.BLUETOOTH_CONNECT, "Nearby devices / connect"))
        }
        permissionStatus.text = "Permissions\n" + lines.joinToString("\n")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) refreshPermissions()
    }

    private fun ensureKeyPair() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    private fun publicKeyPem(): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = ks.getCertificate(KEY_ALIAS) ?: error("Device key missing")
        val b64 = Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }

    private fun sign(challenge: String): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = (ks.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(challenge.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signature.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun pairDevice() {
        val baseUrl = host.text.toString().trim().trimEnd('/')
        val pairingCode = code.text.toString().trim()
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            status.text = "Host must start with http:// or https://"
            return
        }
        if (!pairingCode.matches(Regex("\\d{8}"))) {
            status.text = "Enter the 8-digit pairing code"
            return
        }
        status.text = "Connecting..."
        Thread {
            try {
                ensureKeyPair()
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                val payload = JSONObject().apply {
                    put("code", pairingCode)
                    put("device_name", deviceName)
                    put("public_key_pem", publicKeyPem())
                }
                val request = Request.Builder().url("$baseUrl/pair")
                    .post(payload.toString().toRequestBody(jsonType)).build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Pairing failed"))
                    val obj = JSONObject(body)
                    val deviceId = obj.getString("device_id")
                    val challenge = obj.getString("challenge")
                    val scopes = obj.optJSONArray("scopes")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList()
                    getSharedPreferences("jarvis", MODE_PRIVATE).edit()
                        .putString("host", baseUrl).putString("device_id", deviceId)
                        .putStringSet("scopes", scopes.toSet()).commit()
                    authenticate(baseUrl, deviceId, challenge)
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Pairing failed: ${e.message ?: "connection error"}" }
            }
        }.start()
    }

    private fun authenticate(baseUrl: String, deviceId: String, challenge: String) {
        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("signature_b64", sign(challenge))
        }
        val request = Request.Builder().url("$baseUrl/auth/verify")
            .post(payload.toString().toRequestBody(jsonType)).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            val obj = JSONObject(body)
            val ok = response.isSuccessful && obj.optBoolean("authenticated", false)
            if (ok) sessionToken = obj.optString("session_token").takeIf { it.isNotBlank() }
            val scopes = obj.optJSONArray("scopes")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: getSharedPreferences("jarvis", MODE_PRIVATE).getStringSet("scopes", emptySet())!!.toList()
            getSharedPreferences("jarvis", MODE_PRIVATE).edit().putStringSet("scopes", scopes.toSet()).apply()
            runOnUiThread {
                status.text = if (ok) "✓ JARVIS paired and authenticated\nDevice: $deviceId" else "Authentication failed: $body"
                scopesStatus.text = "JARVIS capabilities\n" + (if (scopes.isEmpty()) "None" else scopes.joinToString("\n") { "• $it" })
                if (ok) assistantReply.text = "Ready. Ask me about Gmail, Calendar, Maps, or alarms."
                if (ok) startMessagePolling()
            }
        }
    }

    private fun refreshSession(baseUrl: String, deviceId: String) {
        status.text = "Reconnecting to JARVIS..."
        Thread {
            try {
                ensureKeyPair()
                val request = Request.Builder().url("$baseUrl/auth/challenge?device_id=$deviceId").post("".toRequestBody(null)).build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Authentication failed"))
                    authenticate(baseUrl, deviceId, JSONObject(body).getString("challenge"))
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Reconnect failed: ${e.message ?: "connection error"}" }
            }
        }.start()
    }

    private fun askJarvis() {
        val message = assistantInput.text.toString().trim()
        if (message.isEmpty()) return
        val token = sessionToken
        if (token == null) {
            assistantReply.text = "Pair or reconnect to the Windows host first."
            return
        }
        val baseUrl = host.text.toString().trim().trimEnd('/')
        assistantReply.text = "Thinking..."
        Thread {
            try {
                val payload = JSONObject().put("message", message)
                val request = Request.Builder().url("$baseUrl/assistant")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(jsonType)).build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    val obj = JSONObject(body)
                    if (!response.isSuccessful) error(obj.optString("detail", "Assistant request failed"))
                    val reply = obj.optString("reply", "I don't have a reply yet.")
                    val action = obj.optJSONObject("action")
                    runOnUiThread {
                        assistantReply.text = reply
                        tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "jarvis-reply")
                        if (action?.optString("type") == "open_url") {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.getString("url"))))
                        }
                        if (action?.optString("type") == "share_location") {
                            shareCurrentLocation(baseUrl, token, action.optString("message", "I'm here"))
                        }
                        if (action?.optString("type") == "send_message") {
                            sendMessageToHome(baseUrl, token, action.optString("message"))
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { assistantReply.text = "JARVIS error: ${e.message ?: "connection error"}" }
            }
        }.start()
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS")
        }
        try {
            startActivityForResult(intent, VOICE_REQUEST)
        } catch (_: Exception) {
            assistantReply.text = "Voice recognition is not available on this phone."
        }
    }

    private fun shareCurrentLocation(baseUrl: String, token: String, prefix: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST)
            assistantReply.text = "Grant location permission, then ask me to share your location again."
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (location == null) {
            assistantReply.text = "I couldn't get a recent location. Turn on Location and try again."
            return
        }
        val mapsUrl = "https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}"
        Thread {
            try {
                val payload = JSONObject().put("body", "$prefix: $mapsUrl")
                val request = Request.Builder().url("$baseUrl/messages")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(jsonType)).build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Message failed"))
                }
                runOnUiThread { assistantReply.text = "Your location was sent to the home client." }
            } catch (e: Exception) {
                runOnUiThread { assistantReply.text = "Location message failed: ${e.message ?: "connection error"}" }
            }
        }.start()
    }

    private fun sendMessageToHome(baseUrl: String, token: String, message: String) {
        if (message.isBlank()) return
        Thread {
            try {
                val payload = JSONObject().put("body", message)
                val request = Request.Builder().url("$baseUrl/messages")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(jsonType)).build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Message failed"))
                }
                runOnUiThread {
                    val confirmation = "Message sent to the Windows home client."
                    assistantReply.text = confirmation
                    tts?.speak(confirmation, TextToSpeech.QUEUE_FLUSH, null, "jarvis-message-sent")
                }
            } catch (e: Exception) {
                runOnUiThread { assistantReply.text = "Message failed: ${e.message ?: "connection error"}" }
            }
        }.start()
    }

    private fun startMessagePolling() {
        if (messagePolling) return
        messagePolling = true
        messageHandler.post(object : Runnable {
            override fun run() {
                pollMessages()
                if (messagePolling) messageHandler.postDelayed(this, 5000)
            }
        })
    }

    private fun pollMessages() {
        val token = sessionToken ?: return
        val baseUrl = host.text.toString().trim().trimEnd('/')
        Thread {
            try {
                val request = Request.Builder().url("$baseUrl/messages")
                    .header("Authorization", "Bearer $token").get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val items = org.json.JSONArray(response.body?.string() ?: "[]")
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        val message = "${item.optString("sender_name", "JARVIS")}: ${item.getString("body")}"
                        runOnUiThread {
                            assistantReply.text = message
                            tts?.speak(message, TextToSpeech.QUEUE_ADD, null, "jarvis-message-$index")
                        }
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun installPrivateUpdate() {
        val token = sessionToken
        if (token == null) {
            assistantReply.text = "Reconnect to the Windows host first."
            return
        }
        val baseUrl = host.text.toString().trim().trimEnd('/')
        assistantReply.text = "Checking the private update staged on Windows..."
        Thread {
            try {
                val request = Request.Builder().url("$baseUrl/updates/android")
                    .header("Authorization", "Bearer $token").get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        error(JSONObject(body).optString("detail", "No update is ready"))
                    }
                    val directory = File(cacheDir, "updates").apply { mkdirs() }
                    val apk = File(directory, "JARVIS-update.apk")
                    response.body?.byteStream()?.use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
                    runOnUiThread {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        assistantReply.text = "Android is ready to confirm the private JARVIS update."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { assistantReply.text = "Update unavailable: ${e.message ?: "connection error"}" }
            }
        }.start()
    }

    @Deprecated("Deprecated in Android, retained for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                assistantInput.setText(text)
                askJarvis()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }

    override fun onDestroy() {
        messagePolling = false
        messageHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val KEY_ALIAS = "jarvis-device-key"
        private const val PERMISSION_REQUEST = 1001
        private const val VOICE_REQUEST = 1002
    }
}
