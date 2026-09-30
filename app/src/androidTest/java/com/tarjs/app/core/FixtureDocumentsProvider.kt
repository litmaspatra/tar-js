package com.tarjs.app.core

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.util.concurrent.Executors

/** Deterministic, read-only SAF tree used only by instrumentation tests. */
class FixtureDocumentsProvider : DocumentsProvider() {
    private val executor = Executors.newSingleThreadExecutor()
    private val content = mapOf(
        "result" to "{\"phase\":\"2\",\"source\":\"saf-fixture\"}\n",
        "media-file" to "deterministic-media-fixture\n"
    )

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: DEFAULT_ROOTS).apply {
        newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "root")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root")
            add(DocumentsContract.Root.COLUMN_TITLE, "TAR-JS fixture")
            add(DocumentsContract.Root.COLUMN_FLAGS, 0)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOCUMENTS).also { addDocument(it, documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOCUMENTS).apply { children(parentDocumentId).forEach { addDocument(this, it) } }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        check(mode == "r") { "fixture is read-only" }
        val (read, write) = ParcelFileDescriptor.createPipe()
        val bytes = (content[documentId] ?: error("not a file: $documentId")).toByteArray()
        executor.execute { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) } }
        return read
    }

    private fun children(id: String) = when (id) {
        "root" -> listOf("exports")
        "exports" -> listOf("2026")
        "2026" -> listOf("result", "media")
        "media" -> listOf("media-file")
        else -> emptyList()
    }

    private fun addDocument(cursor: MatrixCursor, id: String) {
        val file = id == "result" || id == "media-file"
        val name = when (id) {
            "root" -> "fixture"; "exports" -> "exports"; "2026" -> "2026"
            "result" -> "result.json"; "media" -> "media"; "media-file" -> "photo.txt"
            else -> id
        }
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, if (file) "application/json" else DocumentsContract.Document.MIME_TYPE_DIR)
            add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            add(DocumentsContract.Document.COLUMN_SIZE, content[id]?.length ?: 0)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0)
        }
    }

    companion object {
        private val DEFAULT_ROOTS = arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES)
        private val DEFAULT_DOCUMENTS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    }
}
