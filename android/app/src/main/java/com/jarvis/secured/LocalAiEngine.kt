package com.jarvis.secured

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference

object LocalAiEngine {
    private val lock = Any()
    @Volatile private var engine: LlmInference? = null
    @Volatile private var loadedPath: String? = null

    fun available(context: Context) = LocalModelStore.installed(context, LocalModelStore.Kind.LLM)

    fun generate(context: Context, prompt: String, maxTokens: Int = 768): String {
        require(prompt.isNotBlank()) { "Prompt cannot be empty" }
        val model = LocalModelStore.llm(context)
        require(model.isFile) { "Install the local chat/coding model in Settings first" }
        val runtime = synchronized(lock) {
            if (engine == null || loadedPath != model.absolutePath) {
                engine?.close()
                engine = LlmInference.createFromOptions(
                    context.applicationContext,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(model.absolutePath)
                        .setMaxTokens(maxTokens)
                        .setPreferredBackend(LlmInference.Backend.DEFAULT)
                        .build()
                )
                loadedPath = model.absolutePath
            }
            engine ?: error("Local AI engine could not start")
        }
        return synchronized(lock) { runtime.generateResponse(prompt).trim() }
            .ifBlank { error("The local model returned no answer") }
    }

    fun close() = synchronized(lock) {
        engine?.close()
        engine = null
        loadedPath = null
    }
}
