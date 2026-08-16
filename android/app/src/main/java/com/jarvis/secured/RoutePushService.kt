package com.jarvis.secured

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RoutePushService : Service() {
    @Volatile private var running = false
    private var workerThread: Thread? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Secure route push connected"))
        running = true
        workerThread = Thread({ pushLoop() }, "jarvis-route-push").apply {
            isDaemon = true
            start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        http.dispatcher.cancelAll()
        workerThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pushLoop() {
        var backoffMs = 2_000L
        while (running) {
            try {
                val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
                val broker = normalizedUrl(prefs.getString("route_broker", null))
                val topic = prefs.getString("route_topic", null).orEmpty().trim()
                val secret = prefs.getString("route_secret", null).orEmpty().trim()
                if (!broker.startsWith("https://") || topic.isBlank() || secret.isBlank()) {
                    RouteUpdateWorker.schedule(this)
                    sleepInterruptibly(5_000L)
                    continue
                }

                updateNotification("Secure route push connected")
                val request = Request.Builder()
                    .url("$broker/$topic/json")
                    .header("Accept", "application/x-ndjson")
                    .get()
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Route push broker returned HTTP ${response.code}")
                    val source = response.body?.source() ?: error("Route push stream had no body")
                    backoffMs = 2_000L
                    while (running && !source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isNotBlank()) processEnvelope(line, secret)
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                if (!running) return
                updateNotification("Secure route push reconnecting")
                sleepInterruptibly(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun processEnvelope(line: String, secret: String) {
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val envelope = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (envelope.optString("event") != "message") return
        val payload = runCatching { JSONObject(envelope.optString("message")) }.getOrNull() ?: return
        if (payload.optString("type") != "jarvis_route_update") return

        val generation = payload.optLong("generation", 0L)
        val timestamp = payload.optLong("timestamp", 0L)
        val remoteUrl = normalizedUrl(payload.optString("remote_url"))
        val suppliedSignature = payload.optString("signature")
        val storedGeneration = prefs.getLong("route_generation", 0L)

        if (generation <= storedGeneration || timestamp <= 0L || !remoteUrl.startsWith("https://")) return
        if (timestamp > System.currentTimeMillis() / 1000L + 300L) return

        val expected = signature(secret, generation, timestamp, remoteUrl)
        val supplied = runCatching { decodeUrlBase64(suppliedSignature) }.getOrNull() ?: return
        if (!MessageDigest.isEqual(expected, supplied)) return

        val active = normalizedUrl(prefs.getString("active_host", null))
        val editor = prefs.edit()
            .putString("remote_host", remoteUrl)
            .putLong("route_generation", generation)
            .putLong("route_handoff_received_at", System.currentTimeMillis())
        if (active.isBlank() || active.startsWith("https://")) editor.putString("active_host", remoteUrl)
        editor.apply()
        updateNotification("Secure route updated")
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

    private fun normalizedUrl(value: String?): String = value.orEmpty().trim().trimEnd('/')

    private fun sleepInterruptibly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            throw InterruptedException()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS secure connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps JARVIS route handoff available in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
        .setContentTitle("JARVIS")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_route_push"
        private const val NOTIFICATION_ID = 12056

        fun start(context: Context) {
            val intent = Intent(context, RoutePushService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
