from pathlib import Path

root = Path('source/TAR-JS')
build = root / 'app/build.gradle.kts'
bt = build.read_text(encoding='utf-8')

# The earlier patch added instrumentation dependencies. Keep them harmlessly if present,
# but add Robolectric so the actual Android JsonReader + SQLiteOpenHelper code runs in
# deterministic local JVM CI without requiring a fragile emulator image.
needle = 'dependencies {'
adds = '''dependencies {\n    testImplementation("org.robolectric:robolectric:4.16")\n    testImplementation("androidx.test:core:1.6.1")'''
if 'org.robolectric:robolectric' not in bt:
    bt = bt.replace(needle, adds, 1)
build.write_text(bt, encoding='utf-8')

test = root / 'app/src/test/java/com/tarjs/archive/TelegramImporterIntegrationTest.kt'
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text(r'''package com.tarjs.archive

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TelegramImporterIntegrationTest {
    private lateinit var db: ArchiveDatabase
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun setup() {
        ctx.deleteDatabase("tarjs.db")
        db = ArchiveDatabase(ctx)
    }

    @After fun teardown() {
        db.close()
        ctx.deleteDatabase("tarjs.db")
    }

    private fun input(s: String) = ByteArrayInputStream(s.trimIndent().toByteArray())

    @Test fun indexesSingleChatTelegramExportIntoRealDatabase() {
        val archiveId = db.createArchive("Single chat", "test", null)
        val fixture = """
        {
          "name": "Dummy Alice",
          "type": "personal_chat",
          "id": 777,
          "messages": [
            {"id":1,"type":"message","date":"2026-09-27T10:00:00+00:00","date_unixtime":"1790503200","from":"Dummy Alice","from_id":"user111","text":"Hey"},
            {"id":2,"type":"message","date":"2026-09-27T10:01:00+00:00","date_unixtime":"1790503260","from":"Kartik","from_id":"user999","text":["Hello ",{"type":"bold","text":"Alice"}]},
            {"id":3,"type":"message","date":"2026-09-27T10:02:00+00:00","date_unixtime":"1790503320","from":"Kartik","from_id":"user999","text":"How are you?","reply_to_message_id":1},
            {"id":4,"type":"message","date":"2026-09-27T10:03:00+00:00","date_unixtime":"1790503380","from":"Dummy Alice","from_id":"user111","text":"Good","photo":"photos/photo_1.jpg"},
            {"id":5,"type":"service","date":"2026-09-27T10:04:00+00:00","date_unixtime":"1790503440","actor":"Dummy Alice","actor_id":"user111","action":"phone_call","text":"Call"},
            {"id":6,"type":"message","date":"2026-09-27T10:05:00+00:00","date_unixtime":"1790503500","from":"Kartik","from_id":"user999","text":"Great"}
          ]
        }
        """
        val result = TelegramImporter(db).import(archiveId, input(fixture))
        assertEquals(1, result.chats)
        assertEquals(6, result.messages)
        val archives = JSONArray(db.archiveJson())
        assertEquals(6, archives.getJSONObject(0).getInt("messageCount"))
        val chats = JSONArray(db.chatsJson(archiveId))
        assertEquals(1, chats.length())
        assertEquals(6, chats.getJSONObject(0).getInt("messageCount"))
        val chatId = chats.getJSONObject(0).getLong("id")
        val messages = JSONArray(db.messagesJson(chatId, 120, null))
        assertEquals(6, messages.length())
        assertTrue(messages.getJSONObject(1).getString("text").contains("Hello Alice"))
        val mine = (0 until messages.length()).count { messages.getJSONObject(it).getBoolean("mine") }
        assertTrue("single-chat fallback must split the two sides", mine in 1..5)
    }

    @Test fun indexesFullAccountTelegramExportAndMarksOwnerSide() {
        val archiveId = db.createArchive("Full export", "test", null)
        val fixture = """
        {
          "personal_information":{"user_id":999,"first_name":"Kartik","last_name":""},
          "chats":{"about":"About chats","list":[
            {"name":"Dummy Alice","type":"personal_chat","id":777,"messages":[
              {"id":10,"type":"message","date_unixtime":"1790503200","from":"Dummy Alice","from_id":"user111","text":"Incoming"},
              {"id":11,"type":"message","date_unixtime":"1790503260","from":"Kartik","from_id":"user999","text":"Outgoing"},
              {"id":12,"type":"message","date_unixtime":"1790503320","from":"Dummy Alice","from_id":"user111","text":"Incoming again"}
            ]}
          ]}
        }
        """
        val result = TelegramImporter(db).import(archiveId, input(fixture))
        assertEquals(1, result.chats)
        assertEquals(3, result.messages)
        val chatId = JSONArray(db.chatsJson(archiveId)).getJSONObject(0).getLong("id")
        val messages = JSONArray(db.messagesJson(chatId, 120, null))
        assertFalse(messages.getJSONObject(0).getBoolean("mine"))
        assertTrue(messages.getJSONObject(1).getBoolean("mine"))
        assertFalse(messages.getJSONObject(2).getBoolean("mine"))
    }

    @Test fun rejectsJsonThatContainsNoTelegramChatsInsteadOfReportingZeroSuccess() {
        val archiveId = db.createArchive("Invalid", "test", null)
        try {
            TelegramImporter(db).import(archiveId, input("""{"about":"not a Telegram chat export"}"""))
            throw AssertionError("Importer should reject an unsupported/empty export")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("No Telegram chats"))
        }
    }
}
''', encoding='utf-8')

print('ROBOLECTRIC_IMPORTER_SQLITE_QA_APPLIED')
