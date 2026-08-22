package com.jarvis.secured

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class AlwaysListeningService : Service(), RecognitionListener, TextToSpeech.OnInitListener {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var stopping = false
    private var awaitingCommand = false
    @Volatile private var processing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startListening = Runnable {
        if (stopping || processing || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@Runnable
        if (recognizer == null) {
            recognizer = if (android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(this)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { recognizer?.startListening(intent) }.onFailure { restartListening(1000) }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Listening for “Jarvis”", true))
        tts = TextToSpeech(this, this)
        beginListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopListening(intent.getBooleanExtra(EXTRA_PRESERVE_MANUAL, false))
        return START_STICKY
    }

    private fun beginListening(delay: Long = 350) {
        if (stopping || processing || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        mainHandler.removeCallbacks(startListening)
        mainHandler.postDelayed(startListening, delay)
    }

    private fun restartListening(delay: Long = 450) {
        if (stopping || processing) return
        mainHandler.removeCallbacks(startListening)
        recognizer?.cancel()
        beginListening(delay)
    }

    private fun pauseRecognition() {
        mainHandler.removeCallbacks(startListening)
        recognizer?.cancel()
    }

    private fun handlePhrases(phrases: List<String>) {
        val phrase = phrases.firstOrNull().orEmpty().trim()
        if (phrase.isBlank()) return
        val wake = Regex("(?i)\\bjarvis\\b[,:]?\\s*(.*)").find(phrase)
        val command = when {
            awaitingCommand -> phrase
            wake != null -> wake.groupValues[1].trim()
            else -> return
        }
        if (command.isBlank()) {
            awaitingCommand = true
            processing = true
            pauseRecognition()
            updateNotification("Wake phrase heard — say your command")
            speak("Yes?", "wake")
            return
        }
        OfflineCapabilities.actionFor(command)?.let { local ->
            awaitingCommand = false
            processing = true
            pauseRecognition()
            updateNotification(local.confirmation, local.intent)
            speak("${local.confirmation}. Tap the notification to continue.", "offline-action")
            return
        }
        awaitingCommand = false
        processing = true
        pauseRecognition()
        updateNotification("Running: ${command.take(80)}")
        Thread {
            val reply = runCatching { BackgroundJarvisClient(this).ask(command) }
                .getOrElse { "I couldn't reach the JARVIS host. ${it.message.orEmpty()}" }
            mainHandler.post {
                updateNotification(reply.take(110))
                speak(reply, "reply")
            }
        }.start()
    }

    private fun speak(text: String, id: String) {
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            processing = false
            beginListening(700)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { mainHandler.post { processing = false; beginListening(500) } }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { mainHandler.post { processing = false; beginListening(700) } }
            })
        }
    }

    private fun stopListening(preserveManualChoice: Boolean = false) {
        stopping = true
        mainHandler.removeCallbacks(startListening)
        if (!preserveManualChoice) getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply()
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.destroy(); tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onError(error: Int) { if (!processing && !stopping) restartListening(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 500) }
    override fun onResults(results: Bundle?) { handlePhrases(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()); if (!awaitingCommand && !processing) beginListening() }
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "JARVIS always listening", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows when JARVIS is actively using the microphone for the wake phrase"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String, ongoing: Boolean, openIntent: Intent? = null): android.app.Notification {
        val home = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS always listening")
            .setContentText(text)
            .setOngoing(ongoing)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(home)
        if (!isDefaultAssistant(this)) {
            builder.addAction(0, "Stop", PendingIntent.getService(this, 1, Intent(this, AlwaysListeningService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (openIntent != null) {
            val open = PendingIntent.getActivity(this, 2, openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.setContentIntent(open).addAction(0, "Open", open)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, openIntent: Intent? = null) = getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, true, openIntent))

    companion object {
        const val PREF_ENABLED = "always_listening_enabled"
        private const val CHANNEL_ID = "jarvis_always_listening"
        private const val NOTIFICATION_ID = 2202
        private const val ACTION_STOP = "com.jarvis.secured.STOP_ALWAYS_LISTENING"
        private const val EXTRA_PRESERVE_MANUAL = "preserve_manual_choice"

        fun start(context: Context, manual: Boolean = true) {
            if (manual) context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, true).apply()
            ContextCompat.startForegroundService(context, Intent(context, AlwaysListeningService::class.java))
        }

        fun stop(context: Context, clearManualChoice: Boolean = true) {
            if (clearManualChoice) context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply()
            context.startService(Intent(context, AlwaysListeningService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_PRESERVE_MANUAL, !clearManualChoice))
        }

        fun isManuallyEnabled(context: Context): Boolean =
            context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, false)

        fun isDefaultAssistant(context: Context): Boolean {
            if (android.os.Build.VERSION.SDK_INT < 29) return false
            val roles = context.getSystemService(RoleManager::class.java)
            return roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }
    }
}
