package com.jarvis.secured

import android.content.Context
import android.util.Base64
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RouteUpdateWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()
    private val prefs = applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    override fun doWork(): Result {
        val deviceId = prefs.getString("device_id", null) ?: return Result.success()
        return try {
            if (prefs.getString("route_topic", null).isNullOrBlank() ||
                prefs.getString("route_secret", null).isNullOrBlank()) {
                bootstrapRendezvous(deviceId)
            }
            fetchLatestRoute()
            Result.success()
        } catch (_: Exception) {
            // WorkManager will run this again. The existing LAN/remote routes stay untouched.
            Result.retry()
        }
    }

    private fun normalizedUrl(value: String?): String = value.orEmpty().trim().trimEnd('/')

    private fun routeCandidates(): List<String> = listOf(
        normalizedUrl(prefs.getString("active_host", null)),
        normalizedUrl(prefs.getString("remote_host", null)),
        normalizedUrl(prefs.getString("lan_host", null)),
        normalizedUrl(prefs.getString("host", null))
    ).filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()

    private fun sign(challenge: String): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = (ks.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(challenge.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signature.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sessionToken(baseUrl: String, deviceId: String): String {
        val challengeRequest = Request.Builder()
            .url("$baseUrl/auth/challenge?device_id=$deviceId")
            .post("".toRequestBody(null))
            .build()
        val challenge = http.newCall(challengeRequest).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Challenge failed"))
            JSONObject(body).getString("challenge")
        }
        val payload = JSONObject()
            .put("device_id", deviceId)
            .put("signature_b64", sign(challenge))
        val verifyRequest = Request.Builder()
            .url("$baseUrl/auth/verify")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        return http.newCall(verifyRequest).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Authentication failed"))
            JSONObject(body).getString("session_token")
        }
    }

    private fun bootstrapRendezvous(deviceId: String) {
        var lastError: Exception? = null
        for (baseUrl in routeCandidates()) {
            try {
                val token = sessionToken(baseUrl, deviceId)
                val request = Request.Builder()
                    .url("$baseUrl/route-rendezvous/register")
                    .header("Authorization", "Bearer $token")
                    .post("".toRequestBody(null))
                    .build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Route registration failed"))
                    val obj = JSONObject(body)
                    val broker = normalizedUrl(obj.getString("broker"))
                    val topic = obj.getString("topic").trim()
                    val secret = obj.getString("secret").trim()
                    if (!broker.startsWith("https://") || topic.length < 20 || secret.length < 32) {
                        error("Invalid route handoff registration")
                    }
                    val editor = prefs.edit()
                        .putString("route_broker", broker)
                        .putString("route_topic", topic)
                        .putString("route_secret", secret)
                    val currentRemote = normalizedUrl(obj.optString("remote_url"))
                    if (currentRemote.startsWith("https://")) editor.putString("remote_host", currentRemote)
                    editor.apply()
                }
                return
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("No reachable JARVIS route for handoff registration")
    }

    private fun signature(secret: String, generation: Long, timestamp: Long, remoteUrl: String): ByteArray {
        val canonical = "$generation\n$timestamp\n$remoteUrl".toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical)
    }

    private fun decodeUrlBase64(value: String): ByteArray {
        var normalized = value.trim()
        normalized += "=".repeat((4 - normalized.length % 4) % 4)
        return Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun fetchLatestRoute() {
        val broker = normalizedUrl(prefs.getString("route_broker", null))
        val topic = prefs.getString("route_topic", null).orEmpty().trim()
        val secret = prefs.getString("route_secret", null).orEmpty().trim()
        if (!broker.startsWith("https://") || topic.isBlank() || secret.isBlank()) return

        val request = Request.Builder()
            .url("$broker/$topic/json?poll=1&since=latest")
            .get()
            .build()
        val body = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Route broker returned HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val storedGeneration = prefs.getLong("route_generation", 0L)
        var newestGeneration = storedGeneration
        var newestUrl: String? = null

        body.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val envelope = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            if (envelope.optString("event") != "message") return@forEach
            val payload = runCatching { JSONObject(envelope.optString("message")) }.getOrNull() ?: return@forEach
            if (payload.optString("type") != "jarvis_route_update") return@forEach
            val generation = payload.optLong("generation", 0L)
            val timestamp = payload.optLong("timestamp", 0L)
            val remoteUrl = normalizedUrl(payload.optString("remote_url"))
            val suppliedSignature = payload.optString("signature")
            if (generation <= newestGeneration || timestamp <= 0L || !remoteUrl.startsWith("https://")) return@forEach
            // Reject messages claiming to come far from the future, but allow cached recovery messages.
            if (timestamp > System.currentTimeMillis() / 1000L + 300L) return@forEach
            val expected = signature(secret, generation, timestamp, remoteUrl)
            val supplied = runCatching { decodeUrlBase64(suppliedSignature) }.getOrNull() ?: return@forEach
            if (!MessageDigest.isEqual(expected, supplied)) return@forEach
            newestGeneration = generation
            newestUrl = remoteUrl
        }

        newestUrl?.let { remoteUrl ->
            val active = normalizedUrl(prefs.getString("active_host", null))
            val editor = prefs.edit()
                .putString("remote_host", remoteUrl)
                .putLong("route_generation", newestGeneration)
                .putLong("route_handoff_received_at", System.currentTimeMillis())
            // If Android was using an old internet route, replace it immediately.
            // Keep an active LAN route in place while the phone is at home.
            if (active.isBlank() || active.startsWith("https://")) editor.putString("active_host", remoteUrl)
            editor.apply()
        }
    }

    companion object {
        private const val KEY_ALIAS = "jarvis-device-key"
        private const val PERIODIC_WORK = "jarvis-secure-route-handoff"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val periodic = PeriodicWorkRequestBuilder<RouteUpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
            )
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<RouteUpdateWorker>()
                    .setConstraints(constraints)
                    .build()
            )
        }
    }
}
