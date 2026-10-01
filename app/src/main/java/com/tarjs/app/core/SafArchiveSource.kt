package com.tarjs.app.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** SAF helpers for read-only Telegram archive discovery and private snapshots. */
object SafArchiveSource {

    data class FoundArchive(
        val resultJsonUri: Uri,
        val resultJsonText: String,
        val parentFolderUri: Uri,
        val archiveSize: Int = 0
    )

    data class ArchiveSource(
        val id: String,
        val type: String,
        val path: String,
        val resultJsonHash: String,
        val importedAt: Long
    )

    fun takePersistableReadPermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun findResultJson(context: Context, treeUri: Uri): Uri? =
        findResultJsonWithContext(context, treeUri)?.resultJsonUri

    fun findResultJsonWithContext(context: Context, treeUri: Uri): FoundArchive? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val found = findWithParent(root) ?: return null
        val text = openResultJson(context.contentResolver, found.first) ?: return null
        if (validateTelegramExport(text).isFailure) return null
        return FoundArchive(found.first, text, found.second, roughMessageCount(text))
    }

    private fun findWithParent(folder: DocumentFile): Pair<Uri, Uri>? {
        val children = runCatching { folder.listFiles().toList() }.getOrDefault(emptyList())
        children.firstOrNull { it.isFile && it.name.equals("result.json", ignoreCase = true) }?.let {
            return it.uri to folder.uri
        }
        children.filter { it.isDirectory }.forEach { child ->
            findWithParent(child)?.let { return it }
        }
        return null
    }

    fun snapshotResultJson(context: Context, sourceUri: Uri, archiveId: String): Result<File> = runCatching {
        val text = openResultJson(context.contentResolver, sourceUri) ?: error("Unable to read result.json")
        validateTelegramExport(text).getOrThrow()
        val dir = File(context.filesDir, "archives/$archiveId").also { it.mkdirs() }
        val target = File(dir, "result.json")
        val temp = File(dir, "result.json.importing")
        temp.writeText(text, Charsets.UTF_8)
        if (target.exists()) target.delete()
        check(temp.renameTo(target) || runCatching { temp.copyTo(target, overwrite = true); temp.delete(); true }.getOrDefault(false)) {
            "Unable to create private result.json snapshot"
        }
        target
    }

    fun validateTelegramExport(jsonText: String): Result<Unit> = runCatching {
        val root = JSONObject(jsonText)
        val full = root.optJSONObject("chats")?.optJSONArray("list")
        val single = root.optJSONArray("messages")
        val hasFull = full != null && full.length() > 0
        val hasSingle = single != null
        require(hasFull || hasSingle) { "This JSON does not contain Telegram chats or messages" }
        val count = when {
            hasFull -> (0 until full!!.length()).sumOf { full.optJSONObject(it)?.optJSONArray("messages")?.length() ?: 0 }
            else -> single?.length() ?: 0
        }
        require(count > 0) { "Telegram export contains no messages" }
    }

    fun saveArchiveMetadata(context: Context, source: ArchiveSource) {
        val json = JSONObject()
            .put("id", source.id)
            .put("type", source.type)
            .put("path", source.path)
            .put("resultJsonHash", source.resultJsonHash)
            .put("importedAt", source.importedAt)
        context.getSharedPreferences("archive-sources", Context.MODE_PRIVATE)
            .edit().putString(source.id, json.toString()).apply()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun openResultJson(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull()

    private fun roughMessageCount(text: String): Int = runCatching {
        val root = JSONObject(text)
        root.optJSONObject("chats")?.optJSONArray("list")?.let { chats ->
            return@runCatching (0 until chats.length()).sumOf { chats.optJSONObject(it)?.optJSONArray("messages")?.length() ?: 0 }
        }
        root.optJSONArray("messages")?.length() ?: 0
    }.getOrDefault(0)
}
