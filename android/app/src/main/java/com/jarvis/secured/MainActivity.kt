package com.jarvis.secured

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.View
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
    private lateinit var permissionStatus: TextView
    private lateinit var scopesStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshPermissions()
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        host.setText(prefs.getString("host", "http://192.168.1.100:8765"))
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
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun permissionRequests(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
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
            permissionLabel(Manifest.permission.CAMERA, "Camera")
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
                        .putStringSet("scopes", scopes.toSet()).apply()
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
            val scopes = obj.optJSONArray("scopes")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: getSharedPreferences("jarvis", MODE_PRIVATE).getStringSet("scopes", emptySet())!!.toList()
            getSharedPreferences("jarvis", MODE_PRIVATE).edit().putStringSet("scopes", scopes.toSet()).apply()
            runOnUiThread {
                status.text = if (ok) "✓ JARVIS paired and authenticated\nDevice: $deviceId" else "Authentication failed: $body"
                scopesStatus.text = "JARVIS capabilities\n" + (if (scopes.isEmpty()) "None" else scopes.joinToString("\n") { "• $it" })
            }
        }
    }

    companion object {
        private const val KEY_ALIAS = "jarvis-device-key"
        private const val PERMISSION_REQUEST = 1001
    }
}
