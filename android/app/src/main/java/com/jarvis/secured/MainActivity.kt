package com.jarvis.secured

import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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

class MainActivity : AppCompatActivity() {
    private val http = OkHttpClient()
    private val jsonType = "application/json".toMediaType()
    private lateinit var host: EditText
    private lateinit var code: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        host = EditText(this).apply { hint = "Host URL (https://...)"; setText("https://YOUR-JARVIS-HOST") }
        code = EditText(this).apply { hint = "8-digit pairing code"; inputType = 2 }
        val pair = Button(this).apply { text = "PAIR WITH JARVIS"; setOnClickListener { pairDevice() } }
        status = TextView(this).apply { text = "Not paired"; textSize = 18f }
        root.addView(host); root.addView(code); root.addView(pair); root.addView(status)
        setContentView(root)
    }

    private fun ensureKeyPair() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
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
        return Base64.encodeToString(signature.sign(), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun pairDevice() {
        Thread {
            try {
                ensureKeyPair()
                val payload = JSONObject().apply {
                    put("code", code.text.toString().trim())
                    put("device_name", "Motorola Edge 2024")
                    put("public_key_pem", publicKeyPem())
                }
                val request = Request.Builder().url(host.text.toString().trimEnd('/') + "/pair")
                    .post(payload.toString().toRequestBody(jsonType)).build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Pairing failed"))
                    val obj = JSONObject(body)
                    val deviceId = obj.getString("device_id")
                    val challenge = obj.getString("challenge")
                    getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString("device_id", deviceId).apply()
                    authenticate(deviceId, challenge)
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Pairing failed: ${e.message}" }
            }
        }.start()
    }

    private fun authenticate(deviceId: String, challenge: String) {
        val payload = JSONObject().apply { put("device_id", deviceId); put("signature_b64", sign(challenge)) }
        val request = Request.Builder().url(host.text.toString().trimEnd('/') + "/auth/verify")
            .post(payload.toString().toRequestBody(jsonType)).build()
        http.newCall(request).execute().use { response ->
            val ok = response.isSuccessful
            runOnUiThread { status.text = if (ok) "✓ JARVIS paired: $deviceId" else "Authentication failed" }
        }
    }

    companion object { private const val KEY_ALIAS = "jarvis-device-key" }
}
