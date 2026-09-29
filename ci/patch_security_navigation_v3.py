from pathlib import Path
import subprocess

PROVEN_COMMIT = "34673f4096f43bc874dc201a6e1e693d10adfeb2"
SCRIPT_PATH = "ci/patch_security_navigation_v3.py"

# Execute the exact v4 transform that already produced the desired async-search
# and silent-rclone source snapshot. Then fix generator-only issues discovered
# by compilation and replace the search paging fixture with a real importer-backed test.
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

controller = Path("source/TAR-JS/app/src/main/java/com/tarjs/archive/TarJsController.kt")
text = controller.read_text(encoding="utf-8")
duplicate = '    fun messageWindow(chatId: Long, centerDbId: Long): List<MessageItem> = runCatching { JsonModels.messages(db.messageWindowJson(chatId, centerDbId, 80, 80)) }.getOrDefault(emptyList())\n'
if duplicate not in text:
    raise SystemExit("Expected generated duplicate messageWindow overload not found")
controller.write_text(text.replace(duplicate, "", 1), encoding="utf-8")

# Seed the regression test through TelegramImporter instead of inventing DB-only
# helper APIs. This exercises the same SQLite rows the real app searches.
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
        val lastDbId = window.getJSONObject(window.length() - 1).getLong("dbId")
        assertTrue(db.hasNewer(aliceId, lastDbId))

        val newer = JSONArray(db.newerMessagesJson(aliceId, 50, lastDbId))
        assertTrue(newer.length() > 0)
        assertTrue(newer.getJSONObject(newer.length() - 1).getLong("dbId") > lastDbId)
    }
}
''', encoding="utf-8")

print("V4_DUPLICATE_CONTROLLER_OVERLOAD_REMOVED")
print("V4_SEARCH_PAGING_REAL_IMPORTER_QA_WRITTEN")
