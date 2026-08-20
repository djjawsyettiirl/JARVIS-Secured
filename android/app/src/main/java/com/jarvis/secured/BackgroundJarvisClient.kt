package com.jarvis.secured

import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.security.Signature
import java.util.concurrent.TimeUnit

class BackgroundJarvisClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val jsonType = "application/json".toMediaType()
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()

    private fun normalized(value: String?) = value.orEmpty().trim().trimEnd('/')

    private fun routes() = listOf(
        prefs.getString("active_host", null), prefs.getString("lan_host", null),
        prefs.getString("remote_host", null), prefs.getString("host", null)
    ).map(::normalized).filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()

    private fun sign(challenge: String): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getEntry("jarvis-device-key", null) as KeyStore.PrivateKeyEntry).privateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key); update(challenge.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }

    private fun token(route: String, deviceId: String): String {
        val challenge = http.newCall(Request.Builder().url("$route/auth/challenge?device_id=$deviceId")
            .post("".toRequestBody(null)).build()).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Authentication failed"))
            JSONObject(body).getString("challenge")
        }
        val payload = JSONObject().put("device_id", deviceId).put("signature_b64", sign(challenge))
        return http.newCall(Request.Builder().url("$route/auth/verify")
            .post(payload.toString().toRequestBody(jsonType)).build()).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Authentication failed"))
            JSONObject(body).getString("session_token")
        }
    }

    fun ask(command: String): String {
        val deviceId = prefs.getString("device_id", null) ?: error("Pair this phone with JARVIS first")
        var lastError: Exception? = null
        for (route in routes()) {
            try {
                val session = token(route, deviceId)
                val payload = JSONObject().put("message", command)
                val result = http.newCall(Request.Builder().url("$route/assistant")
                    .header("Authorization", "Bearer $session")
                    .post(payload.toString().toRequestBody(jsonType)).build()).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Command failed"))
                    JSONObject(body).optString("reply", "Done")
                }
                prefs.edit().putString("active_host", route).apply()
                return result
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("No reachable JARVIS host")
    }
}
