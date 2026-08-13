package com.jarvis.secured

import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

class MainActivity : AppCompatActivity() {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()
    private lateinit var host: EditText
    private lateinit var code: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val title = TextView(this).apply { text = "JARVIS SECURED"; textSize = 28f }
        val help = TextView(this).apply {
            text = "Enter the Windows JARVIS host address and the one-time pairing code shown on the PC."
            textSize = 16f
        }
        host = EditText(this).apply {
            hint = "Host URL"
            setText("http://192.168.1.100:8765")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        code = EditText(this).apply {
            hint = "8-digit pairing code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        }
        val pair = Button(this).apply {
            text = "PAIR WITH JARVIS"
            setOnClickListener { pairDevice() }
        }
        status = TextView(this).apply { text = "Not paired"; textSize = 18f }
        root.addView(title)
        root.addView(help)
        root.addView(host)
        root.addView(code)
        root.addView(pair)
        root.addView(status)
        setContentView(root)
    }

    private fun ensureKeyPair() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
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
                val payload = JSONObject().apply {
                    put("code", pairingCode)
                    put("device_name", "Motorola Edge 2024")
                    put("public_key_pem", publicKeyPem())
                }
                val request = Request.Builder()
                    .url("$baseUrl/pair")
                    .post(payload.toString().toRequestBody(jsonType))
                    .build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Pairing failed"))
                    val obj = JSONObject(body)
                    val deviceId = obj.getString("device_id")
                    val challenge = obj.getString("challenge")
                    getSharedPreferences("jarvis", MODE_PRIVATE).edit()
                        .putString("host", baseUrl)
                        .putString("device_id", deviceId)
                        .apply()
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
        val request = Request.Builder()
            .url("$baseUrl/auth/verify")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            val ok = response.isSuccessful && JSONObject(body).optBoolean("authenticated", false)
            runOnUiThread {
                status.text = if (ok) "✓ JARVIS paired and authenticated\nDevice: $deviceId" else "Authentication failed: $body"
            }
        }
    }

    companion object { private const val KEY_ALIAS = "jarvis-device-key" }
}
