from pathlib import Path
import subprocess

PROVEN_COMMIT = "34673f4096f43bc874dc201a6e1e693d10adfeb2"
SCRIPT_PATH = "ci/patch_security_navigation_v3.py"

# Execute the exact v4 transform that already produced the desired async-search
# and silent-rclone source snapshot. Then repair only the pieces exposed by CI.
subprocess.run(
    ["git", "fetch", "--depth=1", "origin", PROVEN_COMMIT],
    check=True,
    stdout=subprocess.DEVNULL,
)
code = subprocess.check_output(
    ["git", "show", f"{PROVEN_COMMIT}:{SCRIPT_PATH}"],
    text=True,
)
exec(compile(code, f"{SCRIPT_PATH}@{PROVEN_COMMIT}", "exec"), {"__name__": "__main__"})

# Native Material3 v2 already has messageWindow(chatId, messageDbId). The historical
# v4 transform added an identical JVM signature; remove only that generated duplicate.
controller = Path("source/TAR-JS/app/src/main/java/com/tarjs/archive/TarJsController.kt")
text = controller.read_text(encoding="utf-8")
duplicate = '    fun messageWindow(chatId: Long, centerDbId: Long): List<MessageItem> = runCatching { JsonModels.messages(db.messageWindowJson(chatId, centerDbId, 80, 80)) }.getOrDefault(emptyList())\n'
if duplicate not in text:
    raise SystemExit("Expected generated duplicate messageWindow overload not found")
controller.write_text(text.replace(duplicate, "", 1), encoding="utf-8")

# Replace the generated scoped-search/forward-paging DB block with implementations
# that use TAR-JS's existing FTS index and existing messageObject()/is_mine serializer.
# This avoids both a million-row LIKE scan and inventing an owner_user_id column.
db_file = Path("source/TAR-JS/app/src/main/java/com/tarjs/archive/ArchiveDatabase.kt")
db_text = db_file.read_text(encoding="utf-8")
start = db_text.index("    fun searchChatJson(")
end = db_text.index("    fun archiveJson(): String {", start)
db_block = r'''    fun searchChatJson(chatId: Long, rawQuery: String, limit: Int = 120): String {
        val query = rawQuery.trim().replace("\"", " ").split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" AND ") { "\"${it.replace("*", "")}*\"" }
        if (query.isBlank()) return "[]"
        val out = JSONArray()
        val sql = """
            SELECT m.id,m.chat_id,c.name,m.sender,m.text,m.date_unix,m.media_type,m.file_name
            FROM message_fts f JOIN messages m ON m.id=CAST(f.message_db_id AS INTEGER)
            JOIN chats c ON c.id=m.chat_id
            WHERE m.chat_id=? AND message_fts MATCH ?
            ORDER BY m.date_unix DESC,m.id DESC LIMIT ?
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(chatId.toString(), query, limit.coerceIn(1,300).toString())).use { c ->
            while (c.moveToNext()) out.put(JSONObject().apply {
                put("messageDbId", c.getLong(0)); put("chatId", c.getLong(1)); put("chatName", c.getString(2)); put("sender", c.getString(3) ?: "")
                put("text", c.getString(4) ?: ""); put("dateUnix", c.getLong(5)); put("mediaType", c.getString(6)); put("fileName", c.getString(7))
            })
        }
        return out.toString()
    }

    fun newerMessagesJson(chatId: Long, limit: Int = 160, afterDbId: Long): String {
        val out = JSONArray()
        val sql = "SELECT ${messageColumns()} FROM messages WHERE chat_id=? AND id>? ORDER BY id ASC LIMIT ?"
        readableDatabase.rawQuery(sql, arrayOf(chatId.toString(), afterDbId.toString(), limit.coerceIn(1,300).toString())).use { c ->
            while (c.moveToNext()) out.put(messageObject(c))
        }
        return out.toString()
    }

    fun messageWindowJson(chatId: Long, centerDbId: Long, before: Int, after: Int): String {
        val rows = ArrayList<JSONObject>()
        readableDatabase.rawQuery(
            "SELECT ${messageColumns()} FROM messages WHERE chat_id=? AND id<=? ORDER BY id DESC LIMIT ?",
            arrayOf(chatId.toString(), centerDbId.toString(), (before + 1).coerceIn(1,300).toString())
        ).use { c -> while (c.moveToNext()) rows.add(messageObject(c)) }
        rows.reverse()
        readableDatabase.rawQuery(
            "SELECT ${messageColumns()} FROM messages WHERE chat_id=? AND id>? ORDER BY id ASC LIMIT ?",
            arrayOf(chatId.toString(), centerDbId.toString(), after.coerceIn(1,300).toString())
        ).use { c -> while (c.moveToNext()) rows.add(messageObject(c)) }
        val out = JSONArray(); rows.forEach { out.put(it) }; return out.toString()
    }

    fun hasOlder(chatId: Long, dbId: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM messages WHERE chat_id=? AND id<? LIMIT 1", arrayOf(chatId.toString(), dbId.toString())
    ).use { it.moveToFirst() }

    fun hasNewer(chatId: Long, dbId: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM messages WHERE chat_id=? AND id>? LIMIT 1", arrayOf(chatId.toString(), dbId.toString())
    ).use { it.moveToFirst() }

'''
db_file.write_text(db_text[:start] + db_block + db_text[end:], encoding="utf-8")

# Seed the regression through TelegramImporter, so search/paging is verified against
# the same SQLite rows and FTS index produced by a real Telegram import.
search_test = Path("source/TAR-JS/app/src/test/java/com/tarjs/archive/SearchPagingIntegrationTest.kt")
search_test.parent.mkdir(parents=True, exist_ok=True)
search_test.write_text(r'''package com.tarjs.archive

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
class SearchPagingIntegrationTest {
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

    private fun input(s: String) = ByteArrayInputStream(s.toByteArray())

    private fun message(id: Int, sender: String, fromId: String, text: String): String {
        val unix = 1_790_500_000L + id
        return """{"id":$id,"type":"message","date_unixtime":"$unix","from":"$sender","from_id":"$fromId","text":"$text"}"""
    }

    @Test fun chatSearchIsScopedAndOldResultCanPageForwardToNewest() {
        val archiveId = db.createArchive("Search fixture", "test", null)
        val aliceMessages = (1..240).joinToString(",") { i ->
            val sender = if (i % 2 == 0) "Kartik" else "Alice"
            val senderId = if (i % 2 == 0) "user999" else "user100"
            val text = if (i == 10) "needle old" else "Alice message $i"
            message(i, sender, senderId, text)
        }
        val bobMessage = message(1, "Bob", "user200", "needle other chat")
        val fixture = """
        {
          "personal_information":{"user_id":999,"first_name":"Kartik","last_name":""},
          "chats":{"about":"","list":[
            {"name":"Alice","type":"personal_chat","id":100,"messages":[$aliceMessages]},
            {"name":"Bob","type":"personal_chat","id":200,"messages":[$bobMessage]}
          ]}
        }
        """.trimIndent()

        val imported = TelegramImporter(db).import(archiveId, input(fixture))
        assertEquals(2, imported.chats)
        assertEquals(241, imported.messages)

        val chats = JSONArray(db.chatsJson(archiveId))
        var aliceId = -1L
        for (i in 0 until chats.length()) {
            val chat = chats.getJSONObject(i)
            if (chat.getString("name") == "Alice") aliceId = chat.getLong("id")
        }
        assertTrue(aliceId > 0)

        val hits = JSONArray(db.searchChatJson(aliceId, "needle", 20))
        assertEquals(1, hits.length())
        assertEquals(aliceId, hits.getJSONObject(0).getLong("chatId"))

        val target = hits.getJSONObject(0).getLong("messageDbId")
        val window = JSONArray(db.messageWindowJson(aliceId, target, 5, 5))
        assertTrue(window.length() > 1)
        assertTrue(window.any { it.getLong("dbId") == target })
        val lastDbId = window.getJSONObject(window.length() - 1).getLong("dbId")
        assertTrue(db.hasNewer(aliceId, lastDbId))

        val newer = JSONArray(db.newerMessagesJson(aliceId, 50, lastDbId))
        assertTrue(newer.length() > 0)
        assertTrue(newer.getJSONObject(newer.length() - 1).getLong("dbId") > lastDbId)
        assertTrue(newer.getJSONObject(0).has("mine"))
    }

    private fun JSONArray.any(predicate: (org.json.JSONObject) -> Boolean): Boolean {
        for (i in 0 until length()) if (predicate(getJSONObject(i))) return true
        return false
    }
}
''', encoding="utf-8")

# Source contracts for the exact on-device regressions.
final_ui = Path("source/TAR-JS/app/src/main/java/com/tarjs/archive/NativeUi.kt").read_text(encoding="utf-8")
final_db = db_file.read_text(encoding="utf-8")
assert 'delay(250)' in final_ui and 'withContext(Dispatchers.IO) { controller.searchChat(chatId, q) }' in final_ui
assert 'controller.unlockRclone(password, true)' in final_ui and 'page = rcloneUnlockTarget' in final_ui
assert 'message_fts MATCH ?' in final_db
assert 'owner_user_id' not in final_db
assert 'messageObject(c)' in final_db
print("V4_ASYNC_FTS_SEARCH_AND_NATIVE_PAGING_APPLIED")
