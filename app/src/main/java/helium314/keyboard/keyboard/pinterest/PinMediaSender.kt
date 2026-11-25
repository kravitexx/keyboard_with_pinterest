// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.pinterest

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object PinMediaSender {
    private const val TAG = "PinMediaSender"
    private const val SHARED_PINS_DIR = "shared_pins"
    private const val MAX_MEDIA_BYTES = 50 * 1024 * 1024 // 50 MB guard
    private val CACHE_MAX_AGE_MS = TimeUnit.HOURS.toMillis(1)
    private val PINIMG_SIZE_REGEX = Regex(
        """^(https?://i\.pinimg\.com/)(?:236x|474x|564x|600x315|736x|1200x)(/.+)$""",
        RegexOption.IGNORE_CASE
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun sendFromUrl(
        latinIME: LatinIME,
        url: String,
        mimeHint: String?,
        userAgent: String?,
        preferOriginals: Boolean,
        onComplete: (Boolean) -> Unit
    ) {
        val appContext = latinIME.applicationContext
        executor.execute {
            try {
                val cacheDir = prepareAndPruneCacheDir(appContext)
                val candidates = buildCandidateUrls(url, preferOriginals)
                var downloaded: DownloadedMedia? = null
                for (candidate in candidates) {
                    downloaded = downloadBytes(candidate, userAgent)
                    if (downloaded != null) break
                }
                if (downloaded == null || downloaded.bytes.isEmpty()) {
                    mainHandler.post {
                        showToast(appContext, R.string.pinterest_send_failed)
                        onComplete(false)
                    }
                    return@execute
                }
                val resolvedMime = resolveMimeType(
                    bytes = downloaded.bytes,
                    headerMime = downloaded.contentType ?: mimeHint,
                    urlOrName = downloaded.finalUrl
                )
                val outFile = saveMediaFile(cacheDir, downloaded.bytes, resolvedMime)
                mainHandler.post {
                    val sent = dispatchToActiveEditor(latinIME, outFile, resolvedMime)
                    onComplete(sent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download/send Pin URL: $url", e)
                mainHandler.post {
                    showToast(appContext, R.string.pinterest_send_failed)
                    onComplete(false)
                }
            }
        }
    }

    fun sendFromDataOrBlob(
        latinIME: LatinIME,
        dataUrl: String,
        mimeHint: String?,
        filenameHint: String?,
        onComplete: (Boolean) -> Unit
    ) {
        val appContext = latinIME.applicationContext
        executor.execute {
            try {
                val cacheDir = prepareAndPruneCacheDir(appContext)
                var parsedMime = mimeHint
                val base64Payload = if (dataUrl.startsWith("data:", ignoreCase = true)) {
                    val commaIdx = dataUrl.indexOf(',')
                    if (commaIdx <= 0) throw IllegalArgumentException("Malformed data URL")
                    val header = dataUrl.substring(5, commaIdx)
                    val semiIdx = header.indexOf(';')
                    val headerMime = (if (semiIdx >= 0) header.substring(0, semiIdx) else header).trim()
                    if (headerMime.isNotEmpty() && parsedMime.isNullOrBlank()) {
                        parsedMime = headerMime
                    }
                    dataUrl.substring(commaIdx + 1)
                } else {
                    dataUrl
                }
                val bytes = Base64.decode(base64Payload, Base64.DEFAULT)
                if (bytes.isEmpty()) {
                    mainHandler.post {
                        showToast(appContext, R.string.pinterest_send_failed)
                        onComplete(false)
                    }
                    return@execute
                }
                val resolvedMime = resolveMimeType(bytes, parsedMime, filenameHint ?: "")
                val outFile = saveMediaFile(cacheDir, bytes, resolvedMime)
                mainHandler.post {
                    val sent = dispatchToActiveEditor(latinIME, outFile, resolvedMime)
                    onComplete(sent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode/send Pin blob", e)
                mainHandler.post {
                    showToast(appContext, R.string.pinterest_send_failed)
                    onComplete(false)
                }
            }
        }
    }

    private fun prepareAndPruneCacheDir(context: Context): File {
        val dir = File(context.cacheDir, SHARED_PINS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
        return dir
    }

    private fun buildCandidateUrls(url: String, preferOriginals: Boolean): List<String> {
        val clean = url.trim()
        if (!preferOriginals) return listOf(clean)
        val match = PINIMG_SIZE_REGEX.matchEntire(clean) ?: return listOf(clean)
        val originalsUrl = "${match.groupValues[1]}originals${match.groupValues[2]}"
        return if (originalsUrl == clean) listOf(clean) else listOf(originalsUrl, clean)
    }

    private data class DownloadedMedia(
        val bytes: ByteArray,
        val contentType: String?,
        val finalUrl: String
    )

    private fun downloadBytes(urlString: String, userAgent: String?): DownloadedMedia? {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Referer", "https://www.pinterest.com/")
                setRequestProperty(
                    "Accept",
                    "image/jpeg,image/png,image/gif,image/webp,video/mp4,image/*;q=0.8,video/*;q=0.7,*/*;q=0.5"
                )
                if (!userAgent.isNullOrBlank()) {
                    setRequestProperty("User-Agent", userAgent)
                }
                val cookies = runCatching { CookieManager.getInstance().getCookie(currentUrl) }.getOrNull()
                if (!cookies.isNullOrBlank()) {
                    setRequestProperty("Cookie", cookies)
                }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return null
                    currentUrl = URL(URL(currentUrl), location).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    Log.w(TAG, "HTTP $code for $currentUrl")
                    return null
                }
                val contentType = conn.contentType?.substringBefore(';')?.trim()
                val bytes = conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_MEDIA_BYTES) {
                            throw IllegalStateException("Media exceeds max size limit")
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
                return DownloadedMedia(bytes, contentType, currentUrl)
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun saveMediaFile(dir: File, bytes: ByteArray, mimeType: String): File {
        val ext = extensionForMime(mimeType)
        val file = File(dir, "pin_${System.currentTimeMillis()}.$ext")
        file.outputStream().use { it.write(bytes) }
        return file
    }

    private fun dispatchToActiveEditor(latinIME: LatinIME, file: File, mimeType: String): Boolean {
        val context = latinIME.applicationContext
        val authority = context.getString(R.string.clipboard_provider_authority)
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)

        val editorInfo = latinIME.currentInputEditorInfo
        val inputConnection = latinIME.currentInputConnection
        val targetPackage = editorInfo?.packageName?.takeIf { it.isNotBlank() }

        val supportedMimeTypes = if (editorInfo != null) {
            EditorInfoCompat.getContentMimeTypes(editorInfo)
        } else {
            emptyArray()
        }
        val acceptsMime = supportedMimeTypes.any { pattern ->
            ClipDescription.compareMimeTypes(mimeType, pattern)
        }

        if (acceptsMime && inputConnection != null && editorInfo != null) {
            if (targetPackage != null) {
                runCatching {
                    context.grantUriPermission(targetPackage, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val description = ClipDescription("Pinterest Pin", arrayOf(mimeType))
            val contentInfo = InputContentInfoCompat(contentUri, description, null)
            val committed = runCatching {
                InputConnectionCompat.commitContent(
                    inputConnection,
                    editorInfo,
                    contentInfo,
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    null
                )
            }.getOrDefault(false)
            if (committed) {
                KeyboardSwitcher.getInstance().closePinterestDrawer()
                return true
            }
        }

        // Fallback: ACTION_SEND targeted to the active app package
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            clipData = ClipData.newUri(context.contentResolver, "Pinterest Pin", contentUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (targetPackage != null) {
                setPackage(targetPackage)
            }
        }
        if (targetPackage != null) {
            runCatching {
                context.grantUriPermission(targetPackage, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val launched = try {
            context.startActivity(sendIntent)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                sendIntent.setPackage(null)
                val chooser = Intent.createChooser(sendIntent, context.getString(R.string.pinterest_send_pin))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                true
            } catch (e: Exception) {
                Log.e(TAG, "ACTION_SEND fallback failed", e)
                showToast(context, R.string.pinterest_send_failed)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_SEND failed", e)
            showToast(context, R.string.pinterest_send_failed)
            false
        }
        if (launched) {
            KeyboardSwitcher.getInstance().closePinterestDrawer()
        }
        return launched
    }

    fun showToast(context: Context, resId: Int) {
        Toast.makeText(context.applicationContext, resId, Toast.LENGTH_SHORT).show()
    }

    private fun resolveMimeType(bytes: ByteArray, headerMime: String?, urlOrName: String): String {
        detectMimeFromMagicBytes(bytes)?.let { return it }
        val normalizedHeader = headerMime?.lowercase(Locale.US)?.substringBefore(';')?.trim()
        if (!normalizedHeader.isNullOrBlank() && normalizedHeader != "application/octet-stream") {
            return normalizedHeader
        }
        val pathLower = urlOrName.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        return when {
            pathLower.endsWith(".gif") -> "image/gif"
            pathLower.endsWith(".png") -> "image/png"
            pathLower.endsWith(".webp") -> "image/webp"
            pathLower.endsWith(".mp4") -> "video/mp4"
            pathLower.endsWith(".webm") -> "video/webm"
            pathLower.endsWith(".jpg") || pathLower.endsWith(".jpeg") -> "image/jpeg"
            else -> "image/jpeg"
        }
    }

    private fun detectMimeFromMagicBytes(bytes: ByteArray): String? {
        if (bytes.size >= 6) {
            // GIF87a / GIF89a
            if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() &&
                bytes[3] == 0x38.toByte() && (bytes[4] == 0x37.toByte() || bytes[4] == 0x39.toByte()) &&
                bytes[5] == 0x61.toByte()
            ) return "image/gif"
        }
        if (bytes.size >= 8) {
            // PNG
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            ) return "image/png"
        }
        if (bytes.size >= 3) {
            // JPEG
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                return "image/jpeg"
            }
        }
        if (bytes.size >= 12) {
            // RIFF....WEBP
            if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
            ) return "image/webp"
            // ....ftyp (MP4 / QuickTime)
            if (bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
                bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()
            ) return "video/mp4"
        }
        return null
    }

    private fun extensionForMime(mimeType: String): String = when (mimeType.lowercase(Locale.US)) {
        "image/gif" -> "gif"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        else -> "jpg"
    }
}
