package com.jarvis.secured

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object LocalModelStore {
    enum class Kind { LLM, IMAGE }

    private const val LLM_FILE = "jarvis-llm.task"
    private const val IMAGE_DIRECTORY = "image-generator"
    private const val MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024

    fun root(context: Context) = File(context.filesDir, "local-models").apply { mkdirs() }
    fun llm(context: Context) = File(root(context), LLM_FILE)
    fun image(context: Context) = File(root(context), IMAGE_DIRECTORY)
    fun installed(context: Context, kind: Kind) = when (kind) {
        Kind.LLM -> llm(context).isFile && llm(context).length() > 1024
        Kind.IMAGE -> image(context).isDirectory && image(context).walkTopDown().any { it.isFile }
    }

    fun status(context: Context): String = buildString {
        append(if (installed(context, Kind.LLM)) "Local chat/coding model installed" else "Local chat/coding model missing")
        append("\n")
        append(if (installed(context, Kind.IMAGE)) "Local image model installed" else "Local image model missing")
    }

    fun download(context: Context, kind: Kind, url: String, expectedSha256: String, progress: (Long, Long) -> Unit) {
        require(url.startsWith("https://")) { "Model URL must use HTTPS" }
        val expected = expectedSha256.trim().lowercase()
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "A 64-character SHA-256 checksum is required" }
        val temp = File.createTempFile("jarvis-model-", ".download", context.cacheDir)
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .callTimeout(2, TimeUnit.HOURS)
                .build()
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                require(response.isSuccessful) { "Model download failed with HTTP ${response.code}" }
                val body = response.body ?: error("Model download was empty")
                val total = body.contentLength()
                require(total in 1..MAX_ARCHIVE_BYTES) { "Model package has an invalid or unsupported size" }
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= MAX_ARCHIVE_BYTES) { "Model package exceeds the size limit" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            progress(copied, total)
                        }
                        output.fd.sync()
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual == expected) { "Model checksum mismatch; the download was rejected" }
            }
            installVerified(context, kind, temp)
        } finally {
            temp.delete()
        }
    }

    private fun installVerified(context: Context, kind: Kind, verified: File) {
        when (kind) {
            Kind.LLM -> {
                val target = llm(context)
                val staging = File(target.parentFile, "$LLM_FILE.new")
                verified.copyTo(staging, overwrite = true)
                require(staging.length() > 1024) { "Local language model is invalid" }
                if (target.exists()) target.delete()
                require(staging.renameTo(target)) { "Could not activate the language model" }
            }
            Kind.IMAGE -> installImageArchive(context, verified)
        }
    }

    private fun installImageArchive(context: Context, archive: File) {
        val parent = root(context)
        val staging = File(parent, "$IMAGE_DIRECTORY.new").apply { deleteRecursively(); mkdirs() }
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entries = 0
                var expanded = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= 20_000) { "Image model archive contains too many files" }
                    val target = File(staging, entry.name)
                    require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Unsafe model archive path" }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                expanded += count
                                require(expanded <= MAX_ARCHIVE_BYTES) { "Expanded image model exceeds the size limit" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
                require(entries > 0 && staging.walkTopDown().any { it.isFile }) { "Image model archive is empty" }
            }
            val target = image(context)
            target.deleteRecursively()
            require(staging.renameTo(target)) { "Could not activate the image model" }
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun remove(context: Context, kind: Kind) {
        when (kind) {
            Kind.LLM -> llm(context).delete()
            Kind.IMAGE -> image(context).deleteRecursively()
        }
    }
}
