package com.jarvis.secured

import android.content.Context
import android.content.pm.PackageManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import java.util.zip.ZipException

internal class ReleaseSourceUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Fetches Direct updates from the official published GitHub Releases channel.
 * The signed manifest is verified with the publisher certificate of the installed
 * APK; it pins the exact release tag, package, version code, artifact name and SHA-256.
 */
class OfficialReleaseUpdater(context: Context) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.MINUTES)
        .build()

    fun downloadIfAvailable(): File? {
        val releases = JSONArray(getText(RELEASES_API, "application/vnd.github+json"))
        val installedCode = PrivateUpdateInstaller.versionCode(appContext)
            ?: throw SecurityException("Could not read the installed JARVIS version")

        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft", true)) continue
            val tag = release.optString("tag_name").trim()
            if (!TAG_PATTERN.matches(tag)) continue
            val version = tag.removePrefix("v")
            val apkName = "JARVIS-$version-Android-Direct.apk"
            val assets = release.optJSONArray("assets") ?: continue
            val apkUrl = findAssetUrl(assets, apkName) ?: continue
            val manifestUrl = findAssetUrl(assets, MANIFEST_ASSET) ?: continue
            if (!isOfficialReleaseAsset(apkUrl, tag, apkName) ||
                !isOfficialReleaseAsset(manifestUrl, tag, MANIFEST_ASSET)) {
                throw SecurityException("The release points outside the official JARVIS download channel")
            }

            val directory = File(appContext.cacheDir, "official-updates").apply { mkdirs() }
            val manifestFile = File(directory, "$MANIFEST_ASSET.part")
            val apkFile = File(directory, "$apkName.part")
            manifestFile.delete()
            apkFile.delete()
            try {
                downloadSmallAsset(manifestUrl, manifestFile)
                val manifest = readVerifiedManifest(manifestFile)
                validateManifest(manifest, tag, version, apkName)

                val candidateCode = manifest.getLong("version_code")
                if (candidateCode <= installedCode) return null

                val expectedHash = manifest.getString("sha256").lowercase()
                if (!SHA256_PATTERN.matches(expectedHash)) {
                    throw SecurityException("The signed update manifest contains an invalid APK hash")
                }
                downloadApk(apkUrl, apkFile)
                if (sha256(apkFile) != expectedHash) {
                    throw SecurityException("The Direct APK does not match the signed release manifest")
                }

                PrivateUpdateInstaller.verifyPackageAndSigningIdentity(appContext, apkFile)
                val archiveInfo = appContext.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
                ) ?: throw SecurityException("The downloaded Direct APK is invalid")
                val apkVersion = archiveInfo.versionName.orEmpty()
                    .removeSuffix("-direct")
                    .removeSuffix("-public")
                val apkCode = PrivateUpdateInstaller.versionCode(appContext, apkFile.absolutePath)
                if (archiveInfo.packageName != appContext.packageName ||
                    apkVersion != version ||
                    apkCode != candidateCode) {
                    throw SecurityException("The APK identity does not match the signed release manifest")
                }

                val ready = File(directory, apkName)
                ready.delete()
                if (!apkFile.renameTo(ready)) {
                    apkFile.copyTo(ready, overwrite = true)
                    apkFile.delete()
                }
                return ready
            } catch (error: ReleaseSourceUnavailable) {
                throw error
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                throw SecurityException("The official Direct update failed verification", error)
            } finally {
                manifestFile.delete()
                apkFile.delete()
            }
        }
        return null
    }

    private fun findAssetUrl(assets: JSONArray, expectedName: String): String? {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") == expectedName) {
                return asset.optString("browser_download_url").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun getText(url: String, accept: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "Assistant-Jarvis-Android-Updater")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        try {
            return http.newCall(request).execute().use { response ->
                if (response.request.url.host != "api.github.com" || !response.isSuccessful) {
                    throw ReleaseSourceUnavailable("The official JARVIS release service is unavailable")
                }
                val body = response.body ?: throw ReleaseSourceUnavailable("The release service returned an empty response")
                if (body.contentLength() > MAX_METADATA_BYTES) {
                    throw SecurityException("The release metadata is unexpectedly large")
                }
                body.string()
            }
        } catch (error: ReleaseSourceUnavailable) {
            throw error
        } catch (error: Exception) {
            throw ReleaseSourceUnavailable("Could not reach the official JARVIS release service", error)
        }
    }

    private fun downloadSmallAsset(url: String, destination: File) {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Assistant-Jarvis-Android-Updater")
            .get().build()
        download(request, destination, MAX_METADATA_BYTES)
    }

    private fun downloadApk(url: String, destination: File) {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Assistant-Jarvis-Android-Updater")
            .header("Accept", "application/octet-stream")
            .get().build()
        download(request, destination, MAX_APK_BYTES)
    }

    private fun download(request: Request, destination: File, maximumBytes: Long) {
        try {
            http.newCall(request).execute().use { response ->
                if (response.request.url.scheme != "https" ||
                    response.request.url.host !in RELEASE_DOWNLOAD_HOSTS ||
                    !response.isSuccessful) {
                    throw ReleaseSourceUnavailable("The official JARVIS release asset could not be downloaded")
                }
                val body = response.body ?: throw ReleaseSourceUnavailable("The release asset was empty")
                if (body.contentLength() !in 1..maximumBytes) {
                    throw SecurityException("The release asset has an unexpected size")
                }
                body.byteStream().use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                }
                if (destination.length() !in 1..maximumBytes) {
                    throw SecurityException("The release asset has an unexpected size")
                }
            }
        } catch (error: ReleaseSourceUnavailable) {
            throw error
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            throw ReleaseSourceUnavailable("Could not download the official JARVIS release asset", error)
        }
    }

    private fun readVerifiedManifest(file: File): JSONObject {
        try {
            JarFile(file, true).use { jar ->
                val payloadEntry = jar.getJarEntry(MANIFEST_PAYLOAD)
                    ?: throw SecurityException("The signed release manifest has no payload")
                val payload = jar.getInputStream(payloadEntry).use { it.readBytes() }
                val signers = payloadEntry.certificates?.toList().orEmpty()
                if (signers.isEmpty()) throw SecurityException("The release manifest has no valid publisher signature")
                PrivateUpdateInstaller.verifyPublisherCertificates(appContext, signers)

                val payloadNames = jar.entries().asSequence()
                    .filter { !it.isDirectory && !it.name.startsWith("META-INF/") }
                    .map { it.name }.toSet()
                if (payloadNames != setOf(MANIFEST_PAYLOAD)) {
                    throw SecurityException("The signed release manifest contains unexpected files")
                }
                return JSONObject(String(payload, Charsets.UTF_8))
            }
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            throw SecurityException("The release manifest signature could not be verified", error)
        }
    }

    private fun validateManifest(manifest: JSONObject, tag: String, version: String, apkName: String) {
        val valid =
            manifest.optString("repository") == OFFICIAL_REPOSITORY &&
            manifest.optString("tag") == tag &&
            manifest.optString("version") == version &&
            manifest.optString("edition") == "public_beta" &&
            manifest.optString("distribution") == "direct" &&
            manifest.optString("package") == appContext.packageName &&
            manifest.optString("artifact") == apkName &&
            manifest.optString("commit").matches(COMMIT_PATTERN) &&
            manifest.optString("sha256").matches(SHA256_PATTERN) &&
            manifest.optLong("version_code", 0L) > 0L
        if (!valid) throw SecurityException("The signed release manifest does not match this JARVIS edition")
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    companion object {
        private const val OFFICIAL_REPOSITORY = "djjawsyettiirl/JARVIS-Secured"
        private const val RELEASES_API = "https://api.github.com/repos/$OFFICIAL_REPOSITORY/releases?per_page=100"
        private const val MANIFEST_ASSET = "UPDATE-MANIFEST.jar"
        private const val MANIFEST_PAYLOAD = "release-manifest.json"
        private const val MAX_METADATA_BYTES = 1L * 1024 * 1024
        private const val MAX_APK_BYTES = 1024L * 1024 * 1024
        private val RELEASE_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com"
        )
        internal fun isOfficialReleaseAsset(url: String, tag: String, assetName: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            val expectedPath = "/$OFFICIAL_REPOSITORY/releases/download/$tag/$assetName"
            return parsed.scheme == "https" &&
                parsed.host == "github.com" &&
                parsed.encodedPath == expectedPath &&
                parsed.query == null &&
                parsed.fragment == null
        }

        private val TAG_PATTERN = Regex("^v[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?$")
        private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
        private val COMMIT_PATTERN = Regex("^[0-9a-fA-F]{40}$")
    }
}
