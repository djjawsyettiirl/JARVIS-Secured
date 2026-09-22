package com.jarvis.secured

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureCloudCredentials {
    data class Configuration(
        val endpoint: String,
        val apiKey: String,
        val chatModel: String,
        val imageModel: String,
    )

    private const val ALIAS = "jarvis-cloud-credentials-v1"
    private const val PREFS = "jarvis_secure_cloud"
    private const val CIPHERTEXT = "configuration_ciphertext"
    private const val IV = "configuration_iv"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    fun save(context: Context, value: Configuration) {
        require(value.endpoint.startsWith("https://")) { "Cloud endpoint must use HTTPS" }
        val raw = listOf(value.endpoint.trimEnd('/'), value.apiKey, value.chatModel, value.imageModel)
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(raw)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context): Configuration? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = Base64.decode(prefs.getString(CIPHERTEXT, null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(prefs.getString(IV, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        val parts = String(cipher.doFinal(encrypted), Charsets.UTF_8).split("\u0000")
        if (parts.size != 4) return null
        Configuration(parts[0], parts[1], parts[2], parts[3])
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
