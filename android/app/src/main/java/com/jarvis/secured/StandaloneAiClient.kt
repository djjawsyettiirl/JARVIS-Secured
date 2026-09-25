package com.jarvis.secured

import android.content.Context
import android.util.Base64
import androidx.core.content.FileProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.io.File

class StandaloneAiClient(private val context: Context) {
    private val configuration by lazy { SecureCloudCredentials.load(context) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    fun ask(message: String, adultMode: Boolean = false): String {
        val system = buildString {
            append("You are JARVIS, a warm, capable, human-feeling mobile assistant. Give accurate, direct answers. You can write and explain code in any programming language. You can have natural conversations, give thoughtful personal advice, use humor, playful banter, and occasional profanity when it naturally matches the user's tone. Do not force jokes or swearing. Be emotionally aware without pretending to be human, conscious, or a substitute for real relationships. ")
            if (BuildConfig.ADULT_MODE_AVAILABLE && adultMode) append(AdultModeManager.systemInstruction())
            else append("Do not generate sexually explicit or pornographic content. Keep mature discussions non-graphic and appropriate for a general-audience app store release.")
        }
        if (LocalAiEngine.available(context)) {
            return LocalAiEngine.generate(context, "$system\n\nUser: $message\nJARVIS:")
        }
        val cloud = configuration ?: error("Install a local model or set up a cloud AI provider in Settings")
        val payload = JSONObject()
            .put("model", cloud.chatModel)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", message)))
        val response = execute("${cloud.endpoint}/chat/completions", payload, cloud.apiKey)
        return response.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")?.trim()
            ?.takeIf { it.isNotBlank() } ?: error("The provider returned no answer")
    }

    fun generateImage(prompt: String, adultMode: Boolean = false): String {
        AdultModeManager.validateImagePrompt(prompt, BuildConfig.ADULT_MODE_AVAILABLE && adultMode)
        if (LocalImageEngine.available(context)) return LocalImageEngine.generate(context, prompt)
        val cloud = configuration ?: error("Install a local image model or set up a cloud image provider in Settings")
        val payload = JSONObject().put("model", cloud.imageModel)
            .put("prompt", prompt).put("size", "1024x1024")
        val response = execute("${cloud.endpoint}/images/generations", payload, cloud.apiKey)
        val image = response.optJSONArray("data")?.optJSONObject(0) ?: error("The provider returned no image")
        image.optString("url").takeIf { it.startsWith("https://") }?.let { return it }
        val encoded = image.optString("b64_json").takeIf { it.isNotBlank() }
            ?: error("The provider returned an unsupported image response")
        val directory = File(context.cacheDir, "generated-images").apply { mkdirs() }
        val file = File(directory, "jarvis-${System.currentTimeMillis()}.png")
        file.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
    }

    private fun execute(url: String, payload: JSONObject, apiKey: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(json)).build()
        return http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                error(detail?.take(240) ?: "Cloud provider returned HTTP ${response.code}")
            }
            JSONObject(raw)
        }
    }
}
