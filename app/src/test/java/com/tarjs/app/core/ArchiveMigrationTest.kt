package com.tarjs.app.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An existing user's v6 database must migrate to v7 in place: all indexed
 * chats, messages and chat preferences survive, and the new bookmarks and
 * reading-position features become available without re-importing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("tarjs_archive.db")
    }

    @After
    fun tearDown() {
        context.deleteDatabase("tarjs_archive.db")
    }

    private fun createV6Database() {
        val file = context.getDatabasePath("tarjs_archive.db")
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("""CREATE TABLE chats(id INTEGER PRIMARY KEY, title TEXT NOT NULL, preview TEXT NOT NULL, count INTEGER NOT NULL, is_group INTEGER NOT NULL DEFAULT 0, owner TEXT, created_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE messages(row_id INTEGER PRIMARY KEY AUTOINCREMENT, message_id INTEGER NOT NULL, chat_id INTEGER NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, date TEXT NOT NULL, date_unix INTEGER NOT NULL, mine INTEGER NOT NULL, reply_to_id INTEGER, is_forwarded INTEGER NOT NULL DEFAULT 0, is_edited INTEGER NOT NULL DEFAULT 0, is_service INTEGER NOT NULL DEFAULT 0, media_path TEXT, media_type TEXT, file_name TEXT, mime_type TEXT, duration_seconds INTEGER, UNIQUE(chat_id, message_id))""")
        db.execSQL("""CREATE TABLE chat_preferences(chat_id INTEGER PRIMARY KEY, swap_sides INTEGER NOT NULL DEFAULT 0, custom_name TEXT, custom_avatar_path TEXT, avatar_zoom REAL NOT NULL DEFAULT 1.0, avatar_offset_x REAL NOT NULL DEFAULT 0.0, avatar_offset_y REAL NOT NULL DEFAULT 0.0)""")
        db.execSQL("""CREATE INDEX idx_messages_chat_date ON messages(chat_id, date_unix)""")
        db.execSQL("""CREATE INDEX idx_messages_reply ON messages(chat_id, reply_to_id)""")
        db.execSQL("""CREATE VIRTUAL TABLE messages_fts USING fts4(text, sender, file_name, tokenize=unicode61)""")
        db.execSQL("INSERT INTO chats(id,title,preview,count,is_group,owner,created_at) VALUES(42,'Legacy Chat','old preview',2,0,'owner',123)")
        db.execSQL("INSERT INTO messages(message_id,chat_id,sender,text,date,date_unix,mine) VALUES(7,42,'Asha','legacy words survive','2026-01-01T00:00:10+00:00',1000,0)")
        db.execSQL("INSERT INTO messages(message_id,chat_id,sender,text,date,date_unix,mine) VALUES(9,42,'Kartik','second legacy message','2026-01-02T00:00:10+00:00',2000,1)")
        db.execSQL("INSERT INTO chat_preferences(chat_id,swap_sides,custom_name) VALUES(42,1,'Renamed Chat')")
        db.version = 6
        db.close()
    }

    @Test
    fun v6DatabaseMigratesInPlacePreservingData() {
        createV6Database()
        val db = ArchiveDb(context)
        try {
            val chat = db.chats().single { it.id == 42L }
            // The v6 custom name preference survives and still wins over the raw title.
            assertEquals("Renamed Chat", chat.title)
            assertEquals("Legacy Chat", db.getCustomChatName(42))

            val messages = db.messages(42, olderThanDate = Long.MAX_VALUE, limit = 50)
            assertEquals(listOf(7L, 9L), messages.map { it.id })

            // Existing preferences survive the added reading-position columns.
            assertTrue(db.getSwapSides(42))
            db.setReadingPosition(42, 9, 2000)
            assertEquals(9L to 2000L, db.readingPosition(42))

            // Bookmarks become available on the migrated database.
            db.addBookmark(42, 7)
            assertTrue(db.isBookmarked(42, 7))
            assertEquals("legacy words survive", db.bookmarks().single().message.text)

            // The search index can be healed and searched after migration.
            assertTrue(db.ensureSearchIndex())
            assertEquals(1, db.searchMessages(42, "legacy").size)
        } finally {
            db.close()
        }
    }
}
