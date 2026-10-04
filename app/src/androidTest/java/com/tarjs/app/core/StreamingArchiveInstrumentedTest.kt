package com.tarjs.app.core

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingArchiveInstrumentedTest {
    @Test(timeout = 10 * 60 * 1000L)
    fun streamsMediaRichArchiveAndBuildsSearchIndex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "tarjs_stream_correctness_test.db"
        val archive = File(context.cacheDir, "synthetic-media-rich-result.json")
        context.deleteDatabase(databaseName)
        writeArchive(archive, 50_000)
        Log.i(TAG, "archive-generated")

        val db = ArchiveDb(context, databaseName)
        try {
            var lastDone = 0
            val result = db.importJson(archive) { done, total ->
                assertEquals(50_000, total)
                assertTrue(done >= lastDone)
                lastDone = done
            }
            Log.i(TAG, "archive-imported")
            assertNull(result.error)
            assertEquals(50_000, result.messages)
            assertEquals(50_000, lastDone)
            assertEquals(1, db.chats().size)
            val firstPage = db.messages(42, limit = 100)
            assertEquals(100, firstPage.size)
            assertEquals("message 1", firstPage.first().text)
            Log.i(TAG, "rows-verified")
            val rawMatchExists = db.readableDatabase.rawQuery(
                "SELECT docid FROM messages_fts WHERE messages_fts MATCH 'search*' LIMIT 1",
                null
            ).use { it.moveToFirst() }
            Log.i(TAG, "raw-fts-probed")
            assertTrue(rawMatchExists)
            assertEquals(50, db.searchMessages(42, "search needle", 50).size)
            Log.i(TAG, "search-verified")
            assertTrue(db.searchMessages(42, "video_3", 10).any { it.mediaType == "video" })
            assertTrue(firstPage.any { it.mediaType == "sticker" })
            assertTrue(firstPage.any { it.mediaType == "voice" })
        } finally {
            Log.i(TAG, "cleanup-start")
            db.close()
            context.deleteDatabase(databaseName)
            archive.delete()
            Log.i(TAG, "cleanup-complete")
        }
    }

    private fun writeArchive(target: File, messages: Int) {
        target.outputStream().buffered(256 * 1024).writer(Charsets.UTF_8).use { out ->
            out.append("{\"personal_information\":{\"user_id\":\"user1\",\"first_name\":\"Owner\"},")
            out.append("\"chats\":{\"list\":[{\"name\":\"Synthetic chat\",\"type\":\"private_group\",\"id\":42,\"messages\":[")
            repeat(messages) { index ->
                if (index > 0) out.append(',')
                val id = index + 1
                val text = if (id % 1000 == 0) "search needle $id" else "message $id"
                out.append("{\"id\":").append(id.toString())
                    .append(",\"type\":\"message\",\"date\":\"2026-01-01T00:00:00+00:00\",\"date_unixtime\":\"")
                    .append((1_767_225_600L + id).toString()).append("\",\"from\":\"Owner\",\"from_id\":\"user1\",\"text\":\"")
                    .append(text).append("\"")
                when (id % 6) {
                    0 -> out.append(",\"photo\":\"photos/photo_").append(id.toString()).append(".jpg\",\"mime_type\":\"image/jpeg\"")
                    1 -> out.append(",\"file\":\"stickers/sticker_").append(id.toString()).append(".tgs\",\"sticker_emoji\":\"🙂\"")
                    2 -> out.append(",\"file\":\"stickers/static_").append(id.toString()).append(".webp\",\"mime_type\":\"image/webp\"")
                    3 -> out.append(",\"file\":\"video/video_").append(id.toString()).append(".mp4\",\"file_name\":\"video_").append(id.toString()).append(".mp4\",\"mime_type\":\"video/mp4\",\"duration_seconds\":12")
                    4 -> out.append(",\"file\":\"voice_messages/voice_").append(id.toString()).append(".ogg\",\"mime_type\":\"audio/ogg\",\"duration_seconds\":4")
                    else -> out.append(",\"file\":\"files/document_").append(id.toString()).append(".bin\",\"file_name\":\"document_").append(id.toString()).append(".bin\"")
                }
                out.append('}')
            }
            out.append("]}]}}")
        }
    }

    companion object { private const val TAG = "TARJS-StreamTest" }
}
