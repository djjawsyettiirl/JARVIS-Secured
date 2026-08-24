package com.jarvis.secured

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/** Continuous offline keyword detection without Android's short-session SpeechRecognizer API. */
class OfflineWakeWordEngine(
    context: Context,
    private val onWakeWord: () -> Unit,
    private val onState: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var loading = false
    private var requested = false
    private var generation = 0
    private var recorder: AudioRecord? = null
    private var recognizer: Recognizer? = null

    @Volatile
    var audioSessionId: Int? = null
        private set

    @Synchronized
    fun start() {
        requested = true
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requested = false
            onFailure("Microphone permission is required")
            return
        }
        if (recorder != null) return
        if (model == null) {
            loadModel()
            return
        }
        startCapture()
    }

    @Synchronized
    fun pause() {
        requested = false
        stopCapture()
    }

    @Synchronized
    fun shutdown() {
        requested = false
        stopCapture()
        model?.close()
        model = null
    }

    @Synchronized
    private fun loadModel() {
        if (loading) return
        loading = true
        onState("Preparing offline wake-word model")
        StorageService.unpack(
            appContext,
            "model-en-us",
            "wake-model",
            { ready ->
                synchronized(this) {
                    loading = false
                    model = ready
                    if (requested) startCapture()
                }
            },
            { failure ->
                synchronized(this) {
                    loading = false
                    requested = false
                }
                onFailure("Could not prepare offline wake-word model: " + failure.message.orEmpty())
            },
        )
    }

    @Synchronized
    private fun startCapture() {
        val readyModel = model ?: return
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            requested = false
            onFailure("Android could not create a microphone buffer")
            return
        }
        val localRecognizer = runCatching {
            Recognizer(readyModel, SAMPLE_RATE.toFloat(), "[\"jarvis\", \"[unk]\"]")
        }.getOrElse {
            requested = false
            onFailure("Could not start offline wake recognition: " + it.message.orEmpty())
            return
        }
        val localRecorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minimum * 2,
        )
        if (localRecorder.state != AudioRecord.STATE_INITIALIZED) {
            localRecognizer.close()
            localRecorder.release()
            requested = false
            onFailure("Android could not initialize the microphone")
            return
        }
        val localGeneration = ++generation
        recorder = localRecorder
        recognizer = localRecognizer
        audioSessionId = localRecorder.audioSessionId
        runCatching { localRecorder.startRecording() }.onFailure {
            stopCapture()
            onFailure("Android blocked background microphone access: " + it.message.orEmpty())
            return
        }
        onState("Listening locally for “Jarvis”")
        Thread({
            val buffer = ByteArray(minimum)
            try {
                while (isCurrent(localGeneration)) {
                    val count = localRecorder.read(buffer, 0, buffer.size)
                    if (count <= 0) continue
                    val completed = localRecognizer.acceptWaveForm(buffer, count)
                    val json = if (completed) localRecognizer.result else localRecognizer.partialResult
                    val text = JSONObject(json).optString(if (completed) "text" else "partial")
                    if (WAKE_WORD.containsMatchIn(text)) {
                        mainHandler.post {
                            if (isCurrent(localGeneration)) {
                                pause()
                                onWakeWord()
                            }
                        }
                        break
                    }
                }
            } catch (failure: Exception) {
                mainHandler.post {
                    if (isCurrent(localGeneration)) {
                        pause()
                        onFailure("Wake listener stopped: " + failure.message.orEmpty())
                    }
                }
            }
        }, "jarvis-offline-wake").apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun isCurrent(value: Int): Boolean = requested && generation == value

    @Synchronized
    private fun stopCapture() {
        generation++
        val localRecorder = recorder
        recorder = null
        audioSessionId = null
        runCatching { localRecorder?.stop() }
        localRecorder?.release()
        recognizer?.close()
        recognizer = null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private val WAKE_WORD = Regex("(?i)\\bjarvis\\b")
    }
}
