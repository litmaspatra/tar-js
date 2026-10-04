package com.tarjs.app.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.util.concurrent.Executors

/** Signature-protected, read-only DocumentsContract fixture for device tests. */
class FixtureDocumentsProvider : ContentProvider() {
    private val executor = Executors.newSingleThreadExecutor()
    private val content = mapOf(
        "result" to "{\"phase\":\"2\",\"source\":\"saf-fixture\"}\n",
        "media-file" to "deterministic-media-fixture\n"
    )

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val documentId = DocumentsContract.getDocumentId(uri)
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            if (uri.lastPathSegment == "children") children(documentId).forEach { addDocument(this, it) }
            else addDocument(this, documentId)
        }
    }

    override fun getType(uri: Uri): String =
        if (DocumentsContract.getDocumentId(uri) in content) "application/octet-stream" else DocumentsContract.Document.MIME_TYPE_DIR

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check(mode == "r") { "fixture is read-only" }
        val bytes = (content[DocumentsContract.getDocumentId(uri)] ?: error("not a file")).toByteArray()
        val (read, write) = ParcelFileDescriptor.createPipe()
        executor.execute { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) } }
        return read
    }

    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("read-only")

    private fun children(id: String) = when (id) {
        "root" -> listOf("exports")
        "exports" -> listOf("2026")
        "2026" -> listOf("result", "media")
        "media" -> listOf("media-file")
        else -> emptyList()
    }

    private fun addDocument(cursor: MatrixCursor, id: String) {
        val file = id in content
        val name = when (id) {
            "root" -> "fixture"
            "exports" -> "exports"
            "2026" -> "2026"
            "result" -> "result.json"
            "media" -> "media"
            "media-file" -> "photo.txt"
            else -> id
        }
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, if (file) "application/octet-stream" else DocumentsContract.Document.MIME_TYPE_DIR)
            add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            add(DocumentsContract.Document.COLUMN_SIZE, content[id]?.length ?: 0)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0)
        }
    }

    companion object {
        private val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
