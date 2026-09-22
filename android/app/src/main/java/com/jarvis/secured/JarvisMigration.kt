package com.jarvis.secured

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Portable, deliberately non-secret settings migration between JARVIS package variants.
 *
 * Android Keystore device identities and encrypted provider credentials are intentionally
 * non-exportable. A migrated install must be paired again and provider keys re-entered.
 */
object JarvisMigration {
    const val MIME_TYPE = "application/json"
    const val DEFAULT_FILE_NAME = "JARVIS-settings-v2.2.0.jarvis-transfer.json"

    private val portableKeys = setOf(
        "host", "active_host", "lan_host", "remote_host",
        "spoken_name", "tts_voice",
        "awareness_proactive", "awareness_screen", "awareness_location",
        "awareness_personal", "awareness_notifications",
        "wake_word_enabled"
    )

    data class ImportResult(val imported: Int, val requiresPairing: Boolean = true)

    fun exportTo(context: Context, destination: Uri) {
        val source = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).all
        val settings = JSONObject()
        portableKeys.forEach { key ->
            when (val value = source[key]) {
                is String, is Boolean, is Int, is Long, is Float -> settings.put(key, value)
            }
        }
        val root = JSONObject()
            .put("format", "com.jarvis.secured.settings-transfer")
            .put("schema", 1)
            .put("sourcePackage", context.packageName)
            .put("sourceVersion", BuildConfig.VERSION_NAME)
            .put("createdAtEpochMs", System.currentTimeMillis())
            .put("containsSecrets", false)
            .put("settings", settings)
            .put("notice", "Device pairing keys, API keys, PIN data, messages, and permissions are not exported.")

        context.contentResolver.openOutputStream(destination, "wt")?.bufferedWriter()?.use {
            it.write(root.toString(2))
        } ?: error("Could not open the selected destination")
    }

    fun importFrom(context: Context, source: Uri): ImportResult {
        val raw = context.contentResolver.openInputStream(source)?.bufferedReader()?.use { it.readText() }
            ?: error("Could not open the selected transfer file")
        require(raw.toByteArray().size <= 1_000_000) { "Transfer file is unexpectedly large" }
        val root = JSONObject(raw)
        require(root.optString("format") == "com.jarvis.secured.settings-transfer") {
            "This is not a JARVIS settings transfer file"
        }
        require(root.optInt("schema") == 1) { "Unsupported transfer format" }
        require(!root.optBoolean("containsSecrets", true)) { "Unsafe transfer file rejected" }

        val settings = root.getJSONObject("settings")
        val editor = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
        var count = 0
        portableKeys.forEach { key ->
            if (!settings.has(key) || settings.isNull(key)) return@forEach
            when (val value = settings.get(key)) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                else -> return@forEach
            }
            count++
        }

        // Never allow a transfer file to impersonate an already-paired device.
        editor.remove("device_id")
            .remove("route_secret")
            .remove("offline_queue")
            .remove("message_history")
            .apply()
        return ImportResult(count)
    }
}
