package com.tarjs.app.core

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.net.URLConnection
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MessageDirection {
    fun isMine(ownerId: String?, senderId: String?, senderName: String?, ownerName: String?): Boolean =
        (ownerId != null && senderId == ownerId) || (ownerId == null && ownerName != null && senderName == ownerName)

    fun displayOnRight(mine: Boolean, swapped: Boolean): Boolean =
        (mine && !swapped) || (!mine && swapped)
}

data class MediaItem(val relativePath: String, val kind: String)

data class MediaMetadata(
    val kind: String,
    val mimeType: String,
    val size: Long = 0,
    val durationSeconds: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val isVoiceNote: Boolean = false
)

/** Lazy, app-private media materialization and Telegram media inspection. */
class MediaResolver(private val privateCache: File) {

    fun cacheFile(sourceKey: String, relativePath: String): File {
        val safeName = File(relativePath).name.ifBlank { "media.bin" }
        return File(privateCache, "${sourceKey.hashCode()}_${relativePath.hashCode()}_$safeName")
    }

    fun inspectMedia(source: File, relativePath: String): MediaMetadata? {
        val file = resolveSource(source, relativePath)
        if (!file.isFile) {
            handleMissingMedia(relativePath)
            return null
        }
        val lower = relativePath.lowercase()
        val kind = when {
            lower.endsWith(".tgs") -> "sticker_animated"
            lower.endsWith(".webm") && (lower.contains("sticker") || lower.contains("stickers/")) -> "sticker_webm"
            lower.endsWith(".webp") && (lower.contains("sticker") || lower.contains("stickers/")) -> "sticker_static"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".webp") -> "photo"
            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") -> "video"
            lower.endsWith(".ogg") && (lower.contains("voice") || lower.contains("voice_messages/")) -> "voice"
            lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".opus") || lower.endsWith(".m4a") -> "audio"
            else -> "file"
        }
        val mime = guessMime(file, kind)
        var width: Int? = null
        var height: Int? = null
        var duration: Long? = null

        if (kind == "photo" || kind == "sticker_static") {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth > 0) width = opts.outWidth
            if (opts.outHeight > 0) height = opts.outHeight
        }
        if (kind == "video" || kind == "sticker_webm" || kind == "audio" || kind == "voice") {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let { width = it }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.let { height = it }
                } finally {
                    retriever.release()
                }
            }
        }
        return MediaMetadata(kind, mime, file.length(), duration, width, height, kind == "voice")
    }

    suspend fun materializeAsync(
        source: File,
        sourceKey: String,
        relativePath: String,
        onProgress: (percent: Int) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val input = resolveSource(source, relativePath)
        if (!input.isFile) {
            handleMissingMedia(relativePath)
            return@withContext null
        }
        val target = cacheFile(sourceKey, relativePath)
        if (target.isFile && target.length() == input.length() && target.length() > 0L) {
            onProgress(100)
            return@withContext target
        }
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".part")
            var copied = 0L
            val total = input.length().coerceAtLeast(1L)
            input.inputStream().buffered(256 * 1024).use { src ->
                temp.outputStream().buffered(256 * 1024).use { dst ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = src.read(buffer)
                        if (read <= 0) break
                        dst.write(buffer, 0, read)
                        copied += read
                        onProgress(((copied * 100L) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (target.exists()) target.delete()
            check(temp.renameTo(target) || runCatching { temp.copyTo(target, overwrite = true); temp.delete(); true }.getOrDefault(false))
            onProgress(100)
            target
        }.onFailure { Log.w(TAG, "Failed to materialize $relativePath", it) }.getOrNull()
    }

    /** Kept for compatibility with the Phase-1 API. */
    fun materialize(source: File, sourceKey: String, relativePath: String): File? {
        val input = resolveSource(source, relativePath)
        if (!input.isFile) {
            handleMissingMedia(relativePath)
            return null
        }
        val target = cacheFile(sourceKey, relativePath)
        return runCatching {
            target.parentFile?.mkdirs()
            if (!target.exists() || target.length() != input.length()) input.copyTo(target, overwrite = true)
            target
        }.getOrNull()
    }

    fun handleMissingMedia(relativePath: String) {
        Log.w(TAG, "Missing archive media: $relativePath")
    }

    fun decompressTgs(source: File): String? = runCatching {
        GZIPInputStream(source.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.onFailure { Log.w(TAG, "Invalid TGS ${source.name}", it) }.getOrNull()

    fun getStickerDisplaySize(): Int = 170

    fun isValidMediaFile(file: File, kind: String): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return when (kind) {
            "sticker_animated" -> decompressTgs(file)?.trimStart()?.startsWith("{") == true
            "photo", "sticker_static" -> {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                opts.outWidth > 0 && opts.outHeight > 0
            }
            else -> true
        }
    }

    private fun resolveSource(source: File, relativePath: String): File =
        if (source.isDirectory) File(source, relativePath.trimStart('/')) else source

    private fun guessMime(file: File, kind: String): String {
        return URLConnection.guessContentTypeFromName(file.name) ?: when (kind) {
            "sticker_animated" -> "application/x-tgsticker"
            "sticker_webm" -> "video/webm"
            "sticker_static" -> "image/webp"
            "voice" -> "audio/ogg"
            "video" -> "video/mp4"
            "audio" -> "audio/mpeg"
            "photo" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    companion object { private const val TAG = "TARJS-Media" }
}
