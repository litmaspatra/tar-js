from pathlib import Path

root = Path('source/TAR-JS')
ui_file = root / 'app/src/main/java/com/tarjs/archive/NativeUi.kt'
db_file = root / 'app/src/main/java/com/tarjs/archive/ArchiveDatabase.kt'
ctl_file = root / 'app/src/main/java/com/tarjs/archive/TarJsController.kt'

ui = ui_file.read_text(encoding='utf-8')
db = db_file.read_text(encoding='utf-8')
ctl = ctl_file.read_text(encoding='utf-8')

# 1) Search coroutine must never take the process down. Cancellation is normal; any
# database/query failure becomes an empty result set instead of a crash.
old = '''    LaunchedEffect(page, selectedChat?.id, searchQuery) {\n        if (page != Page.Search) return@LaunchedEffect\n        val chatId = selectedChat?.id ?: return@LaunchedEffect\n        val q = searchQuery.trim()\n        if (q.length < 2) { searchHits = emptyList(); return@LaunchedEffect }\n        delay(250)\n        searchHits = withContext(Dispatchers.IO) { controller.searchChat(chatId, q) }\n    }\n'''
new = '''    LaunchedEffect(page, selectedChat?.id, searchQuery) {\n        if (page != Page.Search) return@LaunchedEffect\n        val chatId = selectedChat?.id ?: return@LaunchedEffect\n        val q = searchQuery.trim()\n        if (q.length < 2) { searchHits = emptyList(); return@LaunchedEffect }\n        delay(300)\n        val results = try {\n            withContext(Dispatchers.IO) { controller.searchChat(chatId, q) }\n        } catch (cancelled: kotlinx.coroutines.CancellationException) {\n            throw cancelled\n        } catch (_: Throwable) {\n            emptyList()\n        }\n        searchHits = results.distinctBy { it.messageDbId }\n    }\n'''
if old not in ui:
    raise SystemExit('v4 search coroutine block not found')
ui = ui.replace(old, new, 1)

# 2) Compose LazyColumn keys must not crash on legacy/dirty FTS indexes that contain
# duplicate rows. SQL will de-duplicate too, but the UI must remain safe regardless.
old = '''        LazyColumn(Modifier.fillMaxSize()) {\n            items(hits, key = { it.messageDbId }) { hit ->\n                ListItem(\n'''
new = '''        LazyColumn(Modifier.fillMaxSize()) {\n            items(hits.distinctBy { it.messageDbId }) { hit ->\n                ListItem(\n'''
if old not in ui:
    raise SystemExit('search results keyed list block not found')
ui = ui.replace(old, new, 1)
ui_file.write_text(ui, encoding='utf-8')

# 3) Harden FTS input. Only actual word/number tokens are passed into MATCH, and
# duplicate FTS rows are collapsed by message ID. If MATCH is unavailable/corrupt on
# an upgraded device database, fall back to a chat-scoped LIKE query rather than fail.
start = db.index('    fun searchChatJson(')
end = db.index('    fun newerMessagesJson(', start)
search_impl = r'''    fun searchChatJson(chatId: Long, rawQuery: String, limit: Int = 120): String {
        val cappedLimit = limit.coerceIn(1, 300)
        val terms = Regex("[\\p{L}\\p{N}_]+")
            .findAll(rawQuery.trim())
            .map { it.value }
            .filter { it.isNotBlank() }
            .take(8)
            .toList()
        if (terms.isEmpty()) return "[]"

        fun rowsFromCursor(c: Cursor): String {
            val out = JSONArray()
            val seen = HashSet<Long>()
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!seen.add(id)) continue
                out.put(JSONObject().apply {
                    put("messageDbId", id)
                    put("chatId", c.getLong(1))
                    put("chatName", c.getString(2) ?: "")
                    put("sender", c.getString(3) ?: "")
                    put("text", c.getString(4) ?: "")
                    put("dateUnix", c.getLong(5))
                    put("mediaType", c.getString(6))
                    put("fileName", c.getString(7))
                })
            }
            return out.toString()
        }

        val ftsQuery = terms.joinToString(" AND ") { term ->
            val safe = term.replace("\"", "\"\"")
            "\"${safe}*\""
        }
        val ftsSql = """
            SELECT DISTINCT m.id,m.chat_id,c.name,m.sender,m.text,m.date_unix,m.media_type,m.file_name
            FROM message_fts f
            JOIN messages m ON m.id=CAST(f.message_db_id AS INTEGER)
            JOIN chats c ON c.id=m.chat_id
            WHERE m.chat_id=? AND message_fts MATCH ?
            ORDER BY m.date_unix DESC,m.id DESC LIMIT ?
        """.trimIndent()

        try {
            readableDatabase.rawQuery(
                ftsSql,
                arrayOf(chatId.toString(), ftsQuery, cappedLimit.toString())
            ).use { return rowsFromCursor(it) }
        } catch (_: Exception) {
            // Upgraded/dirty databases must still remain searchable without crashing.
            val needle = "%${terms.joinToString(" ")}%"
            val likeSql = """
                SELECT m.id,m.chat_id,c.name,m.sender,m.text,m.date_unix,m.media_type,m.file_name
                FROM messages m JOIN chats c ON c.id=m.chat_id
                WHERE m.chat_id=? AND (COALESCE(m.text,'') LIKE ? COLLATE NOCASE OR COALESCE(m.sender,'') LIKE ? COLLATE NOCASE)
                ORDER BY m.date_unix DESC,m.id DESC LIMIT ?
            """.trimIndent()
            readableDatabase.rawQuery(
                likeSql,
                arrayOf(chatId.toString(), needle, needle, cappedLimit.toString())
            ).use { return rowsFromCursor(it) }
        }
    }

'''
db = db[:start] + search_impl + db[end:]
db_file.write_text(db, encoding='utf-8')

# 4) Controller remains a safety boundary: no search exception may escape into Compose.
needle = '    fun searchChat(chatId: Long, query: String): List<SearchHit> = runCatching { JsonModels.search(db.searchChatJson(chatId, query, 120)) }.getOrDefault(emptyList())\n'
if needle not in ctl:
    raise SystemExit('controller searchChat safety boundary missing')

# 5) Dirty-index + punctuation regression test. It deliberately inserts duplicate FTS
# rows for the same message, then verifies search returns exactly one hit and never throws.
test_file = root / 'app/src/test/java/com/tarjs/archive/SearchCrashRegressionTest.kt'
test_file.parent.mkdir(parents=True, exist_ok=True)
test_file.write_text(r'''package com.tarjs.archive

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SearchCrashRegressionTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: ArchiveDatabase

    @Before fun setup() {
        ctx.deleteDatabase("tarjs.db")
        db = ArchiveDatabase(ctx)
    }

    @After fun teardown() {
        db.close()
        ctx.deleteDatabase("tarjs.db")
    }

    @Test fun duplicateFtsRowsAndMessyInputNeverCrashOrDuplicateComposeRows() {
        val archiveId = db.createArchive("Search", "test", null)
        val fixture = """
        {
          "personal_information":{"user_id":999,"first_name":"Kartik","last_name":""},
          "chats":{"about":"","list":[
            {"name":"Alice","type":"personal_chat","id":100,"messages":[
              {"id":1,"type":"message","date_unixtime":"1790500001","from":"Alice","from_id":"user100","text":"hello archived world"},
              {"id":2,"type":"message","date_unixtime":"1790500002","from":"Kartik","from_id":"user999","text":"another message"}
            ]}
          ]}
        }
        """.trimIndent()
        val imported = TelegramImporter(db).import(archiveId, ByteArrayInputStream(fixture.toByteArray()))
        assertEquals(2, imported.messages)
        val chats = JSONArray(db.chatsJson(archiveId))
        val chatId = chats.getJSONObject(0).getLong("id")
        val clean = JSONArray(db.searchChatJson(chatId, "hello", 20))
        assertEquals(1, clean.length())
        val messageId = clean.getJSONObject(0).getLong("messageDbId")

        // Simulate a legacy/interrupted index containing the same FTS record twice.
        db.writableDatabase.execSQL(
            "INSERT INTO message_fts(message_db_id,chat_id,text,sender,chat_name) VALUES(?,?,?,?,?)",
            arrayOf(messageId.toString(), chatId.toString(), "hello archived world", "Alice", "Alice")
        )
        val dirty = JSONArray(db.searchChatJson(chatId, "hello", 20))
        assertEquals(1, dirty.length())
        assertEquals(messageId, dirty.getJSONObject(0).getLong("messageDbId"))

        // Incremental typing / punctuation / emoji-like noise must never throw.
        for (query in listOf("h", "he", "hello?", "(hello)", "*hello*", "\"hello\"", "!!!", "hello world")) {
            val result = JSONArray(db.searchChatJson(chatId, query, 20))
            assertTrue(result.length() >= 0)
        }
    }
}
''', encoding='utf-8')

# 6) Security/navigation source contract: indexed chats remain behind the app-passcode
# gate; rclone is remembered; successful storage unlock returns to its intended target,
# not the remote browser; there is no "Not now" bypass.
ui_final = ui_file.read_text(encoding='utf-8')
ctl_final = ctl_file.read_text(encoding='utf-8')
assert 'TarJsAppPasscodeGate { TarJsUnlockedRoot(controller) }' in ui_final
assert 'Not now' not in ui_final
assert 'controller.unlockRclone(password, true)' in ui_final
assert 'page = rcloneUnlockTarget' in ui_final
assert 'secureRclone.load()' in ctl_final and 'secureRclone.save(password)' in ctl_final
assert 'results.distinctBy { it.messageDbId }' in ui_final
assert 'items(hits.distinctBy { it.messageDbId })' in ui_final
assert 'SELECT DISTINCT m.id' in db_file.read_text(encoding='utf-8')
print('SEARCH_SECURITY_V5_HARDENING_APPLIED')
