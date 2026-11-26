// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.pinterest

import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
    private val VIDEO_HASH_REGEX = Regex(
        """([0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{16,40})""",
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
                val candidates = buildCandidateUrls(url, mimeHint, userAgent, preferOriginals)
                var downloaded: DownloadedMediaFile? = null
                for (candidate in candidates) {
                    downloaded = downloadToFile(candidate, userAgent, cacheDir)
                    if (downloaded != null) break
                }
                if (downloaded == null || downloaded.byteCount <= 0L) {
                    downloaded?.tempFile?.delete()
                    mainHandler.post {
                        showToast(appContext, R.string.pinterest_send_failed)
                        onComplete(false)
                    }
                    return@execute
                }
                val resolvedMime = resolveMimeType(
                    headerBytes = downloaded.headerBytes,
                    headerMime = downloaded.contentType ?: mimeHint,
                    urlOrName = downloaded.finalUrl
                )
                val outFile = finalizeMediaFile(cacheDir, downloaded.tempFile, resolvedMime)
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

    private fun buildCandidateUrls(
        url: String,
        mimeHint: String?,
        userAgent: String?,
        preferOriginals: Boolean
    ): List<String> {
        val clean = url.trim()
        if (clean.startsWith("pinid://", ignoreCase = true)) {
            val pinId = clean.removePrefix("pinid://").trim()
            val resolved = resolveVideoUrlsFromPinResource(pinId, userAgent)
            if (resolved.isNotEmpty()) return resolved
            return emptyList()
        }

        val isVideoHint = mimeHint?.startsWith("video/", ignoreCase = true) == true
        val lower = clean.lowercase(Locale.US)
        val isVideoPath = lower.contains(".pinimg.com/videos/") || lower.contains(".m3u8") || lower.contains(".mp4")
        if (isVideoHint || isVideoPath) {
            val candidates = expandVideoUrlToCandidates(clean)
            if (candidates.isNotEmpty()) return candidates
        }

        if (!preferOriginals) return listOf(clean)
        val match = PINIMG_SIZE_REGEX.matchEntire(clean) ?: return listOf(clean)
        val originalsUrl = "${match.groupValues[1]}originals${match.groupValues[2]}"
        return if (originalsUrl == clean) listOf(clean) else listOf(originalsUrl, clean)
    }

    private fun expandVideoUrlToCandidates(cleanUrl: String): List<String> {
        val clean = cleanUrl.trim()
        val lower = clean.lowercase(Locale.US)
        val candidates = LinkedHashSet<String>()
        if (lower.substringBefore('?').substringBefore('#').endsWith(".mp4")) {
            candidates.add(clean)
        }
        val hashMatch = VIDEO_HASH_REGEX.find(clean)
        if (hashMatch != null) {
            val hash = hashMatch.groupValues[1]
            val preferMc = lower.contains("/videos/mc/")
            if (preferMc) {
                candidates.add("https://v1.pinimg.com/videos/mc/720p/$hash.mp4")
                candidates.add("https://v1.pinimg.com/videos/mc/expMp4/${hash}_720w.mp4")
                candidates.add("https://v1.pinimg.com/videos/iht/720p/$hash.mp4")
                candidates.add("https://v1.pinimg.com/videos/iht/expMp4/${hash}_720w.mp4")
            } else {
                candidates.add("https://v1.pinimg.com/videos/iht/720p/$hash.mp4")
                candidates.add("https://v1.pinimg.com/videos/iht/expMp4/${hash}_720w.mp4")
                candidates.add("https://v1.pinimg.com/videos/mc/720p/$hash.mp4")
                candidates.add("https://v1.pinimg.com/videos/mc/expMp4/${hash}_720w.mp4")
            }
            candidates.add("https://v1.pinimg.com/videos/iht/expMp4/${hash}_540w.mp4")
            candidates.add("https://v1.pinimg.com/videos/iht/expMp4/v2/${hash}_720w.mp4")
            candidates.add("https://v1.pinimg.com/videos/iht/expMp4/${hash}_480w.mp4")
            candidates.add("https://v1.pinimg.com/videos/iht/expMp4/${hash}_360w.mp4")
            if (lower.contains("/videos/thumbnails/")) {
                candidates.add(clean)
            }
        }
        return candidates.toList()
    }

    private fun resolveVideoUrlsFromPinResource(pinId: String, userAgent: String?): List<String> {
        if (pinId.isBlank()) return emptyList()
        return runCatching {
            val dataJson = JSONObject().apply {
                put("options", JSONObject().apply {
                    put("field_set_key", "unauth_react_main_pin")
                    put("id", pinId)
                })
            }.toString()
            val apiUrl = "https://www.pinterest.com/resource/PinResource/get/?data=" +
                URLEncoder.encode(dataJson, "UTF-8")
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Pinterest-PWS-Handler", "www/[username].js")
                setRequestProperty("Referer", "https://www.pinterest.com/pin/$pinId/")
                if (!userAgent.isNullOrBlank()) {
                    setRequestProperty("User-Agent", userAgent)
                }
                val cookies = runCatching { CookieManager.getInstance().getCookie(apiUrl) }.getOrNull()
                if (!cookies.isNullOrBlank()) {
                    setRequestProperty("Cookie", cookies)
                }
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching emptyList()
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val data = root.optJSONObject("resource_response")?.optJSONObject("data") ?: return@runCatching emptyList()
                val mp4s = LinkedHashSet<String>()
                val hls = LinkedHashSet<String>()
                collectVideoUrlsFromJson(data, mp4s, hls)
                val all = LinkedHashSet<String>()
                for (u in mp4s) all.addAll(expandVideoUrlToCandidates(u))
                for (u in hls) all.addAll(expandVideoUrlToCandidates(u))
                all.toList()
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(emptyList())
    }

    private fun collectVideoUrlsFromJson(
        node: Any?,
        mp4Out: MutableSet<String>,
        hlsOut: MutableSet<String>
    ) {
        when (node) {
            is JSONObject -> {
                val vl = node.optJSONObject("video_list")
                if (vl != null) {
                    val orderedKeys = listOf(
                        "V_EXP7", "V_720P", "V_EXP6", "V_480P", "V_EXP5", "V_360P",
                        "V_HLSV4", "V_HLSV3_MOBILE", "V_HLSV3_WEB"
                    )
                    for (k in orderedKeys) {
                        val u = vl.optJSONObject(k)?.optString("url")
                        if (!u.isNullOrBlank()) {
                            val lu = u.lowercase(Locale.US)
                            if (lu.contains(".mp4")) mp4Out.add(u)
                            else if (lu.contains(".m3u8")) hlsOut.add(u)
                        }
                    }
                    val keys = vl.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val u = vl.optJSONObject(k)?.optString("url")
                        if (!u.isNullOrBlank()) {
                            val lu = u.lowercase(Locale.US)
                            if (lu.contains(".mp4")) mp4Out.add(u)
                            else if (lu.contains(".m3u8")) hlsOut.add(u)
                        }
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != "video_list") {
                        collectVideoUrlsFromJson(node.opt(k), mp4Out, hlsOut)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectVideoUrlsFromJson(node.opt(i), mp4Out, hlsOut)
                }
            }
        }
    }

    private data class DownloadedMediaFile(
        val tempFile: File,
        val byteCount: Long,
        val headerBytes: ByteArray,
        val contentType: String?,
        val finalUrl: String
    )

    private fun downloadToFile(urlString: String, userAgent: String?, cacheDir: File): DownloadedMediaFile? {
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
            val tempFile = File.createTempFile("pin_dl_", ".tmp", cacheDir)
            var succeeded = false
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
                val lowerType = contentType?.lowercase(Locale.US).orEmpty()
                if (lowerType.startsWith("text/") ||
                    lowerType.contains("mpegurl") ||
                    lowerType.contains("json")
                ) {
                    Log.w(TAG, "Rejecting non-media Content-Type '$contentType' for $currentUrl")
                    return null
                }
                val header = ByteArray(32)
                var headerLen = 0
                var total = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { out ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_MEDIA_BYTES) {
                                throw IllegalStateException("Media exceeds max size limit")
                            }
                            if (headerLen < header.size) {
                                val toCopy = (header.size - headerLen).coerceAtMost(read)
                                System.arraycopy(buffer, 0, header, headerLen, toCopy)
                                headerLen += toCopy
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                }
                succeeded = true
                return DownloadedMediaFile(
                    tempFile = tempFile,
                    byteCount = total,
                    headerBytes = header.copyOf(headerLen),
                    contentType = contentType,
                    finalUrl = currentUrl
                )
            } finally {
                if (!succeeded) {
                    runCatching { tempFile.delete() }
                }
                conn.disconnect()
            }
        }
        return null
    }

    private fun finalizeMediaFile(dir: File, tempFile: File, mimeType: String): File {
        val ext = extensionForMime(mimeType)
        val finalFile = File(dir, "pin_${System.currentTimeMillis()}.$ext")
        if (tempFile.renameTo(finalFile)) {
            return finalFile
        }
        tempFile.inputStream().use { input ->
            FileOutputStream(finalFile).use { output ->
                input.copyTo(output)
            }
        }
        runCatching { tempFile.delete() }
        return finalFile
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
        Log.i(TAG, "dispatchToActiveEditor pkg=$targetPackage mime=$mimeType acceptsMime=$acceptsMime supported=${supportedMimeTypes.joinToString()}")

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
        if (targetPackage != null &&
            targetPackage.startsWith("com.instagram.") &&
            mimeType.startsWith("video/", ignoreCase = true)
        ) {
            val directComp = ComponentName(
                targetPackage,
                "com.instagram.direct.share.handler.DirectExternalMediaShareActivityVideo"
            )
            sendIntent.component = directComp
            if (sendIntent.resolveActivity(context.packageManager) == null) {
                sendIntent.component = null
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
        } catch (_: Exception) {
            if (sendIntent.component != null) {
                sendIntent.component = null
                val retried = runCatching {
                    context.startActivity(sendIntent)
                    true
                }.getOrDefault(false)
                if (retried) {
                    KeyboardSwitcher.getInstance().closePinterestDrawer()
                    return true
                }
            }
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
        }
        if (launched) {
            KeyboardSwitcher.getInstance().closePinterestDrawer()
        }
        return launched
    }

    fun showToast(context: Context, resId: Int) {
        Toast.makeText(context.applicationContext, resId, Toast.LENGTH_SHORT).show()
    }

    private fun resolveMimeType(headerBytes: ByteArray, headerMime: String?, urlOrName: String): String {
        detectMimeFromMagicBytes(headerBytes)?.let { return it }
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
