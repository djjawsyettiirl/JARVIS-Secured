package com.jarvis.secured

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object DirectSerpApiSearch {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    fun search(context: Context, query: String, searchType: String = "web"): String? {
        val key = SecureSearchCredentials.serpApiKey(context) ?: return null
        if (searchType !in setOf("web", "images", "videos")) return null
        return runCatching {
            val url = "https://serpapi.com/search.json".toHttpUrl().newBuilder()
                .addQueryParameter("engine", mapOf("web" to "google", "images" to "google_images", "videos" to "google_videos").getValue(searchType)).addQueryParameter("q", query)
                .addQueryParameter("api_key", key).addQueryParameter("hl", "en")
                .addQueryParameter("gl", "us").addQueryParameter("safe", "active").build()
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val data = JSONObject(response.body?.string() ?: "{}")
                if (!response.isSuccessful || data.has("error")) return@use null
                val results = data.optJSONArray(mapOf("web" to "organic_results", "images" to "images_results", "videos" to "video_results").getValue(searchType)) ?: return@use null
                val label = mapOf("web" to "Web", "images" to "Image", "videos" to "Video").getValue(searchType)
                buildString {
                    append("Limited mode · ").append(label).append(" results via SerpAPI:")
                    for (index in 0 until minOf(3, results.length())) {
                        val item = results.optJSONObject(index) ?: continue
                        val link = item.optString("link").ifBlank { item.optString("original") }; if (link.isBlank()) continue
                        append("\n\n").append(index + 1).append(". ").append(item.optString("title", link))
                        item.optString("snippet").ifBlank { item.optString("source") }.ifBlank { item.optString("channel") }.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.take(120)) }
                        append("\n").append(link)
                    }
                }.takeIf { it != "Limited mode · $label results via SerpAPI:" }
            }
        }.getOrNull()
    }
}
