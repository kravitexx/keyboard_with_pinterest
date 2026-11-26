// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Links
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkUrl: String?,
    val apkName: String?,
    val apkSizeBytes: Long
)

sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersion: String, val latestTag: String, val htmlUrl: String?) : UpdateCheckResult
    data class NoReleases(val currentVersion: String, val htmlUrl: String) : UpdateCheckResult
    data class UpdateAvailable(val currentVersion: String, val release: ReleaseInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

sealed interface InstallLaunchResult {
    data object Launched : InstallLaunchResult
    data object NeedsUnknownSourcesPermission : InstallLaunchResult
    data class Failed(val message: String) : InstallLaunchResult
}

object AppUpdater {
    private const val UPDATES_DIR = "updates"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    suspend fun checkForUpdates(currentVersion: String = BuildConfig.VERSION_NAME): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL(Links.RELEASES_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "PinKeyboard-Updater/${BuildConfig.VERSION_NAME}")
                }
                val code = try {
                    conn.responseCode
                } catch (e: Exception) {
                    conn.disconnect()
                    throw e
                }
                val body = try {
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                } finally {
                    conn.disconnect()
                }

                if (code == 404) {
                    return@withContext UpdateCheckResult.NoReleases(
                        currentVersion = currentVersion,
                        htmlUrl = "${Links.GITHUB}/releases"
                    )
                }
                if (code !in 200..299) {
                    val apiMessage = runCatching { JSONObject(body).optString("message") }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                    return@withContext UpdateCheckResult.Error(apiMessage ?: "HTTP $code")
                }

                val json = JSONObject(body)
                val tagName = json.optString("tag_name").trim()
                val versionName = tagName.removePrefix("v").removePrefix("V")
                val htmlUrl = json.optString("html_url").ifBlank { "${Links.GITHUB}/releases" }
                val releaseNotes = json.optString("body").trim()

                val assets = json.optJSONArray("assets")
                val apkCandidates = mutableListOf<Triple<String, String, Long>>()
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name")
                        val downloadUrl = asset.optString("browser_download_url")
                        val size = asset.optLong("size", -1L)
                        if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                            apkCandidates.add(Triple(name, downloadUrl, size))
                        }
                    }
                }
                val chosenApk = chooseBestApkAsset(apkCandidates)
                val release = ReleaseInfo(
                    tagName = tagName.ifBlank { versionName },
                    versionName = versionName.ifBlank { tagName },
                    releaseNotes = releaseNotes,
                    htmlUrl = htmlUrl,
                    apkUrl = chosenApk?.second,
                    apkName = chosenApk?.first,
                    apkSizeBytes = chosenApk?.third ?: -1L
                )

                if (versionName.isNotBlank() && isNewerVersion(versionName, currentVersion)) {
                    UpdateCheckResult.UpdateAvailable(currentVersion, release)
                } else {
                    UpdateCheckResult.UpToDate(currentVersion, release.tagName, release.htmlUrl)
                }
            } catch (e: Exception) {
                UpdateCheckResult.Error(e.message ?: e::class.java.simpleName)
            }
        }

    private fun chooseBestApkAsset(candidates: List<Triple<String, String, Long>>): Triple<String, String, Long>? {
        if (candidates.isEmpty()) return null
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase(Locale.US)
        return candidates.maxByOrNull { (name, _, _) ->
            val lower = name.lowercase(Locale.US)
            var score = 0
            if (BuildConfig.DEBUG == lower.contains("debug")) score += 4
            if (primaryAbi != null && lower.contains(primaryAbi)) score += 3
            val hasAbiTag = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").any { lower.contains(it) }
            if (!hasAbiTag || lower.contains("universal")) score += 2
            score
        }
    }

    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        fileName: String?,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, UPDATES_DIR).apply {
            if (!exists()) mkdirs()
            listFiles()?.forEach { old -> if (old.isFile) runCatching { old.delete() } }
        }
        val safeName = (fileName ?: "update.apk")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        val targetFile = File(updatesDir, safeName)

        var currentUrl = apkUrl
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "PinKeyboard-Updater/${BuildConfig.VERSION_NAME}")
            }
            var completed = false
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect missing Location header")
                    currentUrl = URL(URL(currentUrl), location).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
                val totalBytes = conn.contentLengthLong
                var downloaded = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0L) {
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    withContext(Dispatchers.Main) { onProgress(pct) }
                                }
                            }
                        }
                    }
                }
                if (targetFile.length() <= 0L) {
                    throw IllegalStateException("Downloaded APK is empty")
                }
                completed = true
                return@withContext targetFile
            } finally {
                if (!completed) {
                    runCatching { targetFile.delete() }
                }
                conn.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    fun installApk(context: Context, apkFile: File): InstallLaunchResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(permIntent) }
            return InstallLaunchResult.NeedsUnknownSourcesPermission
        }
        return try {
            val authority = context.getString(R.string.clipboard_provider_authority)
            val contentUri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallLaunchResult.Launched
        } catch (e: Exception) {
            InstallLaunchResult.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    internal fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParsed = parseVersion(remote) ?: return false
        val currentParsed = parseVersion(current) ?: return remote != current
        val maxLen = maxOf(remoteParsed.numbers.size, currentParsed.numbers.size)
        for (i in 0 until maxLen) {
            val r = remoteParsed.numbers.getOrElse(i) { 0 }
            val c = currentParsed.numbers.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        // Stable (empty pre-release) > pre-release (e.g. 4.3 > 4.3-beta2)
        val rPre = remoteParsed.preRelease
        val cPre = currentParsed.preRelease
        if (rPre == null && cPre == null) return false
        if (rPre == null) return true
        if (cPre == null) return false
        return comparePreRelease(rPre, cPre) > 0
    }

    private data class ParsedVersion(val numbers: List<Int>, val preRelease: String?)

    private fun parseVersion(raw: String): ParsedVersion? {
        val cleaned = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .removeSuffix("-debug")
            .removeSuffix(".debug")
        if (cleaned.isEmpty()) return null
        val dashIdx = cleaned.indexOf('-')
        val core = if (dashIdx >= 0) cleaned.substring(0, dashIdx) else cleaned
        val pre = if (dashIdx >= 0) cleaned.substring(dashIdx + 1).ifBlank { null } else null
        val nums = core.split('.').mapNotNull { it.toIntOrNull() }
        if (nums.isEmpty()) return null
        return ParsedVersion(nums, pre)
    }

    private fun comparePreRelease(a: String, b: String): Int {
        val regex = Regex("([a-zA-Z]+)|(\\d+)")
        val aTokens = regex.findAll(a.lowercase(Locale.US)).map { it.value }.toList()
        val bTokens = regex.findAll(b.lowercase(Locale.US)).map { it.value }.toList()
        val maxLen = maxOf(aTokens.size, bTokens.size)
        for (i in 0 until maxLen) {
            val ta = aTokens.getOrNull(i) ?: return -1
            val tb = bTokens.getOrNull(i) ?: return 1
            val na = ta.toIntOrNull()
            val nb = tb.toIntOrNull()
            val cmp = when {
                na != null && nb != null -> na.compareTo(nb)
                else -> ta.compareTo(tb)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}
