package com.tarjs.app.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chats are never deletable in TAR-JS. Restoring a backup must therefore
 * merge into the on-device archive instead of replacing it: existing chats,
 * messages, bookmarks and preferences always survive a restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveRestoreMergeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("tarjs_archive.db")
        context.deleteDatabase("backup-source.db")
    }

    @After
    fun tearDown() {
        context.deleteDatabase("tarjs_archive.db")
        context.deleteDatabase("backup-source.db")
    }

    private fun chatJson(chatId: Int, name: String, count: Int, textPrefix: String): String {
        val msgs = StringBuilder()
        for (i in 1..count) {
            if (i > 1) msgs.append(',')
            msgs.append("""{"id": $i, "type": "message", "from": "$name", "from_id": "user_$chatId", "date": "2026-01-01T00:00:10+00:00", "date_unixtime": ${1000 + i}, "text": "$textPrefix $i"}""")
        }
        return """{"chats": {"list": [{"id": $chatId, "name": "$name", "type": "personal_chat", "messages": [$msgs]}]}}"""
    }

    private fun writeBackupDb(name: String, chatId: Int, chatName: String, count: Int, textPrefix: String): File {
        val source = ArchiveDb(context, name)
        val result = source.importJson(chatJson(chatId, chatName, count, textPrefix)) { _, _ -> }
        check(result.error == null) { result.error ?: "backup source import failed" }
        source.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { }
        source.close()
        return context.getDatabasePath(name)
    }

    private fun packedBackup(dbFile: File, passphrase: String?): ByteArray {
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(dbFile, out, passphrase)
        return out.toByteArray()
    }

    private fun unpack(bytes: ByteArray, passphrase: String?): File {
        val temp = File.createTempFile("unpack", ".db", context.cacheDir)
        ArchiveBackup.unpackBackup(ByteArrayInputStream(bytes), passphrase, temp)
        return temp
    }

    @Test
    fun restoreMergesWithoutDeletingExistingChats() {
        val current = ArchiveDb(context)
        current.importJson(chatJson(200, "Current", 30, "current day")) { _, _ -> }

        val backupDb = writeBackupDb("backup-source.db", 100, "Backed up", 25, "backup day")
        val source = ArchiveDb(context, "backup-source.db")
        source.addBookmark(100, 3)
        source.setReadingPosition(100, 7, 1007)
        source.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { }
        source.close()

        val temp = unpack(packedBackup(backupDb, "pw"), "pw")
        val merge = current.mergeBackup(temp)
        temp.delete()

        assertEquals(1, merge.chatsAdded)
        assertEquals(25, merge.messagesAdded)
        assertEquals(1, merge.bookmarksAdded)
        // Everything already on the device survived.
        assertEquals(30L, current.messageCount(200))
        assertEquals(25L, current.messageCount(100))
        assertTrue(current.isBookmarked(100, 3))
        assertEquals(7L to 1007L, current.readingPosition(100))
        // The merged history is searchable in one archive.
        assertEquals(25, current.searchMessages(100, "backup").size)
        assertEquals(30, current.searchMessages(200, "current").size)
        current.close()
    }

    @Test
    fun mergingSameBackupTwiceChangesNothing() {
        val current = ArchiveDb(context)
        current.importJson(chatJson(200, "Current", 10, "current day")) { _, _ -> }
        val backupDb = writeBackupDb("backup-source.db", 100, "Backed up", 5, "backup day")

        val temp1 = unpack(packedBackup(backupDb, null), null)
        val first = current.mergeBackup(temp1)
        temp1.delete()
        assertEquals(5, first.messagesAdded)

        val temp2 = unpack(packedBackup(backupDb, null), null)
        val second = current.mergeBackup(temp2)
        temp2.delete()
        assertEquals(0, second.chatsAdded)
        assertEquals(0, second.messagesAdded)
        assertEquals(0, second.bookmarksAdded)
        assertEquals(10L, current.messageCount(200))
        assertEquals(5L, current.messageCount(100))
        current.close()
    }

    @Test
    fun v6BackupWithoutBookmarksMergesCleanly() {
        val file = context.getDatabasePath("backup-source.db")
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL("""CREATE TABLE chats(id INTEGER PRIMARY KEY, title TEXT NOT NULL, preview TEXT NOT NULL, count INTEGER NOT NULL, is_group INTEGER NOT NULL DEFAULT 0, owner TEXT, created_at INTEGER NOT NULL)""")
        raw.execSQL("""CREATE TABLE messages(row_id INTEGER PRIMARY KEY AUTOINCREMENT, message_id INTEGER NOT NULL, chat_id INTEGER NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, date TEXT NOT NULL, date_unix INTEGER NOT NULL, mine INTEGER NOT NULL, reply_to_id INTEGER, is_forwarded INTEGER NOT NULL DEFAULT 0, is_edited INTEGER NOT NULL DEFAULT 0, is_service INTEGER NOT NULL DEFAULT 0, media_path TEXT, media_type TEXT, file_name TEXT, mime_type TEXT, duration_seconds INTEGER, UNIQUE(chat_id, message_id))""")
        raw.execSQL("""CREATE TABLE chat_preferences(chat_id INTEGER PRIMARY KEY, swap_sides INTEGER NOT NULL DEFAULT 0, custom_name TEXT, custom_avatar_path TEXT, avatar_zoom REAL NOT NULL DEFAULT 1.0, avatar_offset_x REAL NOT NULL DEFAULT 0.0, avatar_offset_y REAL NOT NULL DEFAULT 0.0)""")
        raw.execSQL("""CREATE VIRTUAL TABLE messages_fts USING fts4(text, sender, file_name, tokenize=unicode61)""")
        raw.execSQL("INSERT INTO chats(id,title,preview,count,is_group,owner,created_at) VALUES(300,'Old Backup','preview',1,0,'',1)")
        raw.execSQL("INSERT INTO messages(message_id,chat_id,sender,text,date,date_unix,mine) VALUES(1,300,'Asha','old schema message','2026-01-01T00:00:10+00:00',1000,0)")
        raw.version = 6
        raw.close()

        val current = ArchiveDb(context)
        current.importJson(chatJson(200, "Current", 4, "current day")) { _, _ -> }
        val temp = unpack(packedBackup(file, null), null)
        val merge = current.mergeBackup(temp)
        temp.delete()

        assertEquals(1, merge.chatsAdded)
        assertEquals(1, merge.messagesAdded)
        assertEquals(0, merge.bookmarksAdded)
        assertEquals(4L, current.messageCount(200))
        assertEquals(1, current.searchMessages(300, "schema").size)
        current.close()
    }
}
