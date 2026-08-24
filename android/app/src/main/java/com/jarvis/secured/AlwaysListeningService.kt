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
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
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
    private lateinit var wakeEngine: OfflineWakeWordEngine
    private lateinit var audioManager: AudioManager
    private var commandRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var stopping = false
    private var commandListening = false
    private var anotherAppRecording = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            if (stopping || commandListening || !::wakeEngine.isInitialized) return
            val ownSession = wakeEngine.audioSessionId
            val occupied = configs.orEmpty().any {
                ownSession == null || it.clientAudioSessionId != ownSession ||
                    (android.os.Build.VERSION.SDK_INT >= 29 && it.isClientSilenced)
            }
            if (occupied == anotherAppRecording) return
            anotherAppRecording = occupied
            if (occupied) {
                wakeEngine.pause()
                updateNotification("Paused while another app uses the microphone")
            } else {
                resumeWakeListening()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing offline wake listening", true))
        audioManager = getSystemService(AudioManager::class.java)
        wakeEngine = OfflineWakeWordEngine(
            this,
            onWakeWord = { handleWakeWord() },
            onState = { handleWakeState(it) },
            onFailure = { handleWakeFailure(it) },
        )
        audioManager.registerAudioRecordingCallback(recordingCallback, mainHandler)
        tts = TextToSpeech(this, this)
        resumeWakeListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening(intent.getBooleanExtra(EXTRA_PRESERVE_MANUAL, false))
        } else if (!stopping) {
            resumeWakeListening()
        }
        return START_STICKY
    }

    private fun handleWakeWord() {
        if (stopping || commandListening) return
        wakeEngine.pause()
        updateNotification("Wake phrase heard — waiting for your command")
        speak("Yes?", UTTERANCE_WAKE)
    }

    private fun startCommandListening() {
        if (stopping) return
        if (anotherAppRecording) {
            updateNotification("Command paused while another app uses the microphone")
            speak("The microphone is busy.", UTTERANCE_REPLY)
            return
        }
        destroyCommandRecognizer()
        commandRecognizer = if (android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        commandRecognizer?.setRecognitionListener(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        commandListening = true
        updateNotification("Listening for your command")
        runCatching { commandRecognizer?.startListening(intent) }.onFailure {
            commandListening = false
            destroyCommandRecognizer()
            speak("I couldn't start command recognition.", UTTERANCE_REPLY)
        }
    }

    private fun handleCommand(phrases: List<String>) {
        val command = phrases.firstOrNull().orEmpty().trim()
        if (command.isBlank()) {
            speak("I didn't hear a command.", UTTERANCE_REPLY)
            return
        }
        OfflineCapabilities.actionFor(command)?.let { local ->
            updateNotification(local.confirmation, local.intent)
            speak(local.confirmation + ". Tap the notification to continue.", UTTERANCE_REPLY)
            return
        }
        updateNotification("Running: " + command.take(80))
        Thread {
            val reply = runCatching { BackgroundJarvisClient(this).ask(command) }
                .getOrElse { "I couldn't reach the JARVIS host. " + it.message.orEmpty() }
            mainHandler.post {
                if (!stopping) {
                    updateNotification(reply.take(110))
                    speak(reply, UTTERANCE_REPLY)
                }
            }
        }.start()
    }

    private fun speak(text: String, id: String) {
        wakeEngine.pause()
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            if (id == UTTERANCE_WAKE) startCommandListening() else resumeWakeListening()
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            updateNotification("Spoken replies unavailable · wake listening ready")
            resumeWakeListening()
            return
        }
        tts?.language = Locale.getDefault()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId == UTTERANCE_WAKE) startCommandListening() else resumeWakeListening()
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId == UTTERANCE_WAKE) startCommandListening() else resumeWakeListening()
                }
            }
        })
    }

    private fun resumeWakeListening() {
        if (stopping || commandListening || anotherAppRecording) return
        updateNotification("Listening locally for “Jarvis”")
        wakeEngine.start()
    }

    private fun handleWakeFailure(message: String) {
        if (stopping) return
        updateNotification(message)
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_LAST_ERROR, message).apply()
    }

    private fun handleWakeState(message: String) {
        if (message.startsWith("Listening locally")) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_LAST_ERROR).apply()
        }
        updateNotification(message)
    }

    private fun destroyCommandRecognizer() {
        commandRecognizer?.destroy()
        commandRecognizer = null
    }

    private fun stopListening(preserveManualChoice: Boolean = false) {
        stopping = true
        if (!preserveManualChoice) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply()
        }
        wakeEngine.shutdown()
        destroyCommandRecognizer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        mainHandler.removeCallbacksAndMessages(null)
        if (::audioManager.isInitialized) audioManager.unregisterAudioRecordingCallback(recordingCallback)
        if (::wakeEngine.isInitialized) wakeEngine.shutdown()
        destroyCommandRecognizer()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onError(error: Int) {
        if (!commandListening || stopping) return
        commandListening = false
        destroyCommandRecognizer()
        val message = if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            "Microphone permission is unavailable."
        } else {
            "I didn't hear a command."
        }
        speak(message, UTTERANCE_REPLY)
    }

    override fun onResults(results: Bundle?) {
        if (!commandListening || stopping) return
        commandListening = false
        val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        destroyCommandRecognizer()
        handleCommand(phrases)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "JARVIS wake listening", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows when JARVIS is listening locally for its wake phrase"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String, ongoing: Boolean, openIntent: Intent? = null): android.app.Notification {
        val home = PendingIntent.getActivity(this, 0, Intent(this, AssistantHomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS wake listening")
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

    private fun updateNotification(text: String, openIntent: Intent? = null) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, true, openIntent))
    }

    companion object {
        const val PREF_ENABLED = "always_listening_enabled"
        const val PREF_LAST_ERROR = "wake_listener_last_error"
        private const val PREFS = "jarvis"
        private const val CHANNEL_ID = "jarvis_always_listening"
        private const val NOTIFICATION_ID = 2202
        private const val ACTION_STOP = "com.jarvis.secured.STOP_ALWAYS_LISTENING"
        private const val EXTRA_PRESERVE_MANUAL = "preserve_manual_choice"
        private const val UTTERANCE_WAKE = "wake"
        private const val UTTERANCE_REPLY = "reply"

        fun start(context: Context, manual: Boolean = true): Result<Unit> = runCatching {
            if (manual) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, true).apply()
            ContextCompat.startForegroundService(context, Intent(context, AlwaysListeningService::class.java))
        }.onFailure {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_LAST_ERROR, it.message.orEmpty()).apply()
        }

        fun stop(context: Context, clearManualChoice: Boolean = true) {
            if (clearManualChoice) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply()
            context.stopService(Intent(context, AlwaysListeningService::class.java))
        }

        fun isManuallyEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, false)

        fun lastError(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_LAST_ERROR, "").orEmpty()

        fun isDefaultAssistant(context: Context): Boolean {
            if (android.os.Build.VERSION.SDK_INT < 29) return false
            val roles = context.getSystemService(RoleManager::class.java)
            return roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }
    }
}
