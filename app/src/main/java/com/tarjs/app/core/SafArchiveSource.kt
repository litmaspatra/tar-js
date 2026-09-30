package com.tarjs.app.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** SAF helpers for private folder imports without copying or mutating the source. */
object SafArchiveSource {
    fun takePersistableReadPermission(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun findResultJson(context: Context, treeUri: Uri): Uri? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return find(root)
    }

    private fun find(folder: DocumentFile): Uri? {
        folder.listFiles().forEach { item ->
            if (item.isFile && item.name.equals("result.json", ignoreCase = true)) return item.uri
            if (item.isDirectory) find(item)?.let { return it }
        }
        return null
    }

    fun openResultJson(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull()
}
