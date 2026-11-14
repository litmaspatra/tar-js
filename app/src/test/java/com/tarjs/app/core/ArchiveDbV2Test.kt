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
class ArchiveDbV2Test {
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

    private fun importJson(json: String) = db.importJson(json) { _, _ -> }

    private fun sameSecondChatJson(count: Int): String {
        val msgs = StringBuilder()
        for (i in 1..count) {
            if (i > 1) msgs.append(',')
            msgs.append("""{"id": $i, "type": "message", "from": "Alice", "from_id": "user_alice", "date": "2026-01-01T00:00:10+00:00", "date_unixtime": 1000, "text": "same second message $i"}""")
        }
        return """{"chats": {"list": [{"id": 100, "name": "Alice", "type": "personal_chat", "messages": [$msgs]}]}}"""
    }

    private fun twoChatsJson(): String = """
    {"personal_information": {"user_id": "user_owner", "first_name": "Kartik"}, "chats": {"list": [
      {"id": 100, "name": "Alice", "type": "personal_chat", "messages": [
        {"id": 1, "type": "message", "from": "Alice", "from_id": "a", "date": "2026-01-01T00:00:10+00:00", "date_unixtime": 1000, "text": "road trip with alice"},
        {"id": 2, "type": "message", "from": "Kartik", "from_id": "user_owner", "date": "2026-01-02T00:00:10+00:00", "date_unixtime": 2000, "text": "see you"}
      ]},
      {"id": 200, "name": "Bob", "type": "personal_chat", "messages": [
        {"id": 1, "type": "message", "from": "Bob", "from_id": "b", "date": "2026-01-03T00:00:10+00:00", "date_unixtime": 3000, "text": "road photo", "photo": "photos/road.jpg"}
      ]}
    ]}}
    """.trimIndent()

    @Test
    fun tieSafePagingReachesFirstMessageInSameSecondCluster() {
        val result = importJson(sameSecondChatJson(250))
        assertNull(result.error)

        val firstPage = db.messages(100, olderThanDate = Long.MAX_VALUE, limit = 120)
        assertEquals(120, firstPage.size)
        assertEquals(131L, firstPage.first().id)

        // The old strict-date paging stopped here: every message shares date_unix 1000.
        val secondPage = db.messages(100, olderThanDate = firstPage.first().dateUnix, olderThanMessageId = firstPage.first().id, limit = 120)
        assertEquals(120, secondPage.size)
        assertEquals(11L, secondPage.first().id)

        val thirdPage = db.messages(100, olderThanDate = secondPage.first().dateUnix, olderThanMessageId = secondPage.first().id, limit = 120)
        assertEquals(10, thirdPage.size)
        assertEquals(1L, thirdPage.first().id)

        assertTrue(db.hasOlderMessages(100, secondPage.first().dateUnix, secondPage.first().id))
        assertFalse(db.hasOlderMessages(100, 1000, 1))
    }

    @Test
    fun reimportAfterInterruptionRestoresMissingRowsAndDedupes() {
        val json = sameSecondChatJson(200)
        importJson(json)
        // Simulate an interrupted import: the newest 40 message rows (and their
        // index entries) never made it before the process stopped.
        db.writableDatabase.execSQL("DELETE FROM messages WHERE chat_id=100 AND message_id>160")
        db.writableDatabase.execSQL("DELETE FROM messages_fts WHERE docid NOT IN (SELECT row_id FROM messages)")
        assertEquals(160L, db.messageCount(100))

        val second = importJson(json)
        assertNull(second.error)
        assertEquals(160, second.deduped)
        assertEquals(200L, db.messageCount(100))
        // No duplicates were created by the recovery import.
        assertEquals(200, db.messages(100, olderThanDate = Long.MAX_VALUE, limit = 300).size)
        // The restored tail is searchable again.
        assertEquals(1, db.searchMessages(100, "message 199").size)
    }

    @Test
    fun importingAnotherExportNeverDeletesExistingChats() {
        importJson(sameSecondChatJson(50))
        importJson(twoChatsJson())
        // The first import's chat survives the second import untouched.
        assertEquals(50L, db.messageCount(100))
        assertEquals(1L, db.messageCount(200))
        assertEquals(2, db.chats().size)
        assertTrue(db.searchMessages(100, "second").isNotEmpty())
    }

    @Test
    fun messageWindowIncludesSameSecondNeighbours() {
        importJson(sameSecondChatJson(250))
        val window = db.messageWindow(100, 125, before = 10, after = 10)
        assertEquals(21, window.size)
        assertEquals(115L, window.first().id)
        assertEquals(135L, window.last().id)
    }

    @Test
    fun searchIndexSelfHealAfterInterruptedImport() {
        importJson(sameSecondChatJson(10))
        // Simulate an interrupted import: a new message row with no FTS entry.
        db.writableDatabase.execSQL(
            "INSERT INTO messages(message_id,chat_id,sender,text,date,date_unix,mine) VALUES(?,?,?,?,?,?,0)",
            arrayOf(500L, 100L, "Alice", "lost words after interruption", "2026-01-02T00:00:00+00:00", 2000L)
        )
        assertTrue(db.searchMessages(100, "interruption").isEmpty())
        assertTrue(db.ensureSearchIndex())
        assertFalse(db.ensureSearchIndex())
        val found = db.searchMessages(100, "interruption")
        assertEquals(1, found.size)
        assertEquals(500L, found[0].id)
    }

    @Test
    fun globalSearchAndFilters() {
        importJson(twoChatsJson())
        val all = db.searchMessages(null, "road")
        assertEquals(2, all.size)
        val scoped = db.searchMessages(200, "road")
        assertEquals(1, scoped.size)
        val bySender = db.searchMessages(null, "road", SearchFilter(sender = "bob"))
        assertEquals(1, bySender.size)
        assertEquals("Bob", bySender[0].sender)
        val byDate = db.searchMessages(null, "road", SearchFilter(afterDate = 3000))
        assertEquals(1, byDate.size)
        val byMedia = db.searchMessages(null, "road", SearchFilter(mediaType = "photo"))
        assertEquals(1, byMedia.size)
        val mediaOnly = db.searchMessages(null, "road", SearchFilter(mediaOnly = true))
        assertEquals(1, mediaOnly.size)
        val newestFirst = db.searchMessages(null, "road", newestFirst = true)
        assertTrue(newestFirst[0].dateUnix > newestFirst[1].dateUnix)
    }

    @Test
    fun searchPagingWithOffset() {
        importJson(sameSecondChatJson(200))
        val page1 = db.searchMessages(100, "second", limit = 150, offset = 0)
        assertEquals(150, page1.size)
        val page2 = db.searchMessages(100, "second", limit = 150, offset = 150)
        assertEquals(50, page2.size)
        assertEquals(emptySet<Long>(), page1.map { it.id }.toSet() intersect page2.map { it.id }.toSet())
    }

    @Test
    fun bookmarksCrud() {
        importJson(sameSecondChatJson(10))
        db.addBookmark(100, 3)
        db.addBookmark(100, 7)
        assertTrue(db.isBookmarked(100, 3))
        assertEquals(setOf(3L, 7L), db.bookmarkedIds(100))
        val list = db.bookmarks()
        assertEquals(2, list.size)
        assertEquals("Alice", list[0].chatTitle)
        db.removeBookmark(100, 3)
        assertFalse(db.isBookmarked(100, 3))
    }

    @Test
    fun readingPositionRoundTrip() {
        importJson(sameSecondChatJson(10))
        assertNull(db.readingPosition(100))
        db.setReadingPosition(100, 4, 1000)
        assertEquals(4L to 1000L, db.readingPosition(100))
    }

    @Test
    fun mediaMessagesForGallery() {
        importJson(twoChatsJson())
        val media = db.mediaMessages(200, 60, 0)
        assertEquals(1, media.size)
        assertEquals("photo", media[0].mediaType)
        assertEquals(2L, db.messageCount(100))
    }

    @Test
    fun jumpToDateTargetsFirstMessageOnOrAfter() {
        importJson(twoChatsJson())
        assertEquals(1L, db.firstMessageOnOrAfter(100, 0)!!.id)
        assertNull(db.firstMessageOnOrAfter(100, 99999))
        assertEquals(2L, db.lastMessage(100)!!.id)
        assertEquals(1L, db.firstMessage(100)!!.id)
    }
}
