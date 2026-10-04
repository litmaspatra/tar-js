package com.tarjs.app.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveDbTest {
    private lateinit var context: Context
    private lateinit var db: ArchiveDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("tarjs_archive.db")
        db = ArchiveDb(context)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("tarjs_archive.db")
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { "Missing fixture: $name" }.readText()

    @Test
    fun deduplicateOnReImport() {
        val json = fixture("dummy_telegram_export.json")
        val first = db.importJson(json) { _, _ -> }
        val second = db.importJson(json) { _, _ -> }

        assertNull(first.error)
        assertNull(second.error)
        assertEquals(6, first.messages)
        assertEquals(6, second.deduped)
        assertEquals(4, db.messages(100, olderThanDate = Long.MAX_VALUE, limit = 50).size)
        assertEquals(2, db.messages(200, olderThanDate = Long.MAX_VALUE, limit = 50).size)
    }

    @Test
    fun bidirectionalPagingReturnsCorrectWindows() {
        db.importJson(fixture("dummy_telegram_export.json")) { _, _ -> }

        val latestWindow = db.messages(100, olderThanDate = Long.MAX_VALUE, limit = 2)
        assertEquals(listOf(30L, 40L), latestWindow.map { it.dateUnix })
        assertTrue(db.hasOlderMessages(100, latestWindow.first().dateUnix))
        assertFalse(db.hasNewerMessages(100, latestWindow.last().dateUnix))

        val older = db.messages(100, olderThanDate = 30, limit = 10)
        assertEquals(listOf(10L, 20L), older.map { it.dateUnix })

        val newer = db.messages(100, newerThanDate = 20, limit = 10)
        assertEquals(listOf(30L, 40L), newer.map { it.dateUnix })
    }

    @Test
    fun richTextArrayExtractionWorks() {
        db.importJson(fixture("dummy_telegram_export.json")) { _, _ -> }
        val messages = db.messages(100, olderThanDate = Long.MAX_VALUE, limit = 20)
        val rich = messages.single { it.id == 2L }
        assertEquals("rich text array", rich.text)
    }

    @Test
    fun groupChatDetectionCorrect() {
        db.importJson(fixture("dummy_telegram_export.json")) { _, _ -> }
        val group = db.chats().single { it.id == 200L }
        assertTrue(group.isGroup)
        assertEquals("Dummy Group", group.title)
    }

    @Test
    fun ownerDetectionFromPersonalInfo() {
        val result = db.importJson(fixture("dummy_telegram_export.json")) { _, _ -> }
        assertEquals("user_owner", result.ownerId)

        val personal = db.messages(100, olderThanDate = Long.MAX_VALUE, limit = 20)
        assertFalse(personal.single { it.id == 1L }.mine)
        assertTrue(personal.single { it.id == 2L }.mine)

        val group = db.messages(200, olderThanDate = Long.MAX_VALUE, limit = 20)
        assertFalse(group.single { it.id == 1L }.mine)
        assertTrue(group.single { it.id == 2L }.mine)
    }

    @Test
    fun invalidExportReturnsExplicitError() {
        val result = db.importJson("{\"not_telegram\":true}") { _, _ -> }
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("No Telegram chats", ignoreCase = true))
        assertEquals(0, result.messages)
    }

    @Test
    fun emptyArchiveReturnsExplicitError() {
        val result = db.importJson("{\"name\":\"Empty\",\"messages\":[]}") { _, _ -> }
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("no messages", ignoreCase = true))
        assertEquals(0, result.messages)
    }
}
