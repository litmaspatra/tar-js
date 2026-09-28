from pathlib import Path
import re

root = Path('source/TAR-JS')

# --- 1. Make TelegramImporter accept both Telegram export shapes -----------------
# Full account export: { personal_information, chats: { list: [...] } }
# Single chat export:  { name, type, id, messages: [...] }
imp = root / 'app/src/main/java/com/tarjs/archive/TelegramImporter.kt'
text = imp.read_text(encoding='utf-8')
pattern = re.compile(r'''    fun import\(archiveId: Long, input: InputStream, progress: \(String\) -> Unit = \{\}\): Result \{.*?\n    \}\n\n    private fun readPersonalInformation''', re.S)
replacement = '''    fun import(archiveId: Long, input: InputStream, progress: (String) -> Unit = {}): Result {
        db.clearArchive(archiveId)
        var chatCount = 0
        var messageCount = 0
        var ownUserId: String? = null

        // Telegram Desktop has two legitimate result.json layouts:
        //  1) full-account export: chats.list[]
        //  2) single-chat export: name/type/id/messages directly at the root
        // The old importer only handled (1), which silently produced 0 indexed
        // messages for a single-chat export.
        var rootTelegramChatId: String? = null
        var rootName = "Telegram Chat"
        var rootType: String? = null
        var rootAvatarPath: String? = null
        var rootLocalChatId: Long? = null
        var rootMessageCount = 0
        var rootLastDate = 0L

        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "personal_information" -> ownUserId = readPersonalInformation(r)
                    "chats" -> {
                        if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) {
                            r.skipValue()
                        } else {
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "list" -> {
                                        if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) {
                                            r.skipValue()
                                        } else {
                                            r.beginArray()
                                            while (r.hasNext()) {
                                                val added = parseChat(r, archiveId, ownUserId, progress)
                                                if (added >= 0) {
                                                    chatCount++
                                                    messageCount += added
                                                }
                                            }
                                            r.endArray()
                                        }
                                    }
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                        }
                    }
                    "id" -> rootTelegramChatId = readScalarAsString(r)
                    "name" -> rootName = readScalarAsString(r) ?: rootName
                    "type" -> rootType = readScalarAsString(r)
                    "photo" -> rootAvatarPath = parsePhotoObject(r)
                    "messages" -> {
                        if (rootLocalChatId == null) {
                            rootLocalChatId = db.insertChat(archiveId, rootTelegramChatId, rootName, rootType, rootAvatarPath)
                            progress("Indexing $rootName")
                        }
                        if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) {
                            r.skipValue()
                        } else {
                            r.beginArray()
                            while (r.hasNext()) {
                                val m = parseMessage(r, archiveId, rootLocalChatId!!, rootName, ownUserId)
                                if (m != null) {
                                    db.insertMessage(m)
                                    rootMessageCount++
                                    if (m.dateUnix > rootLastDate) rootLastDate = m.dateUnix
                                }
                            }
                            r.endArray()
                        }
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }

        if (rootLocalChatId != null) {
            db.updateChatStats(rootLocalChatId!!, rootMessageCount, rootLastDate)
            if (ownUserId == null) db.applyMineFallback(rootLocalChatId!!, rootName)
            chatCount++
            messageCount += rootMessageCount
        }

        if (chatCount == 0) {
            throw IllegalArgumentException("No Telegram chats were found in result.json. The file may be empty or use an unsupported export format.")
        }
        db.updateArchiveStats(archiveId, chatCount, messageCount)
        return Result(chatCount, messageCount)
    }

    private fun readPersonalInformation'''
new_text, n = pattern.subn(replacement, text, count=1)
if n != 1:
    raise SystemExit('Could not patch TelegramImporter.import')
imp.write_text(new_text, encoding='utf-8')

# --- 2. Make Settings/profile customization explicitly belong to the open chat ----
js = root / 'app/src/main/assets/web/app.js'
jst = js.read_text(encoding='utf-8')
old_chat = '''function chatView(){const meta=(S.chats||[]).find(x=>x.id===S.chat.id)||{id:S.chat.id,name:S.chat.name,hasCustomAvatar:false,avatarVersion:0};setHead(meta.name||S.chat.name,swapState()?'Other side perspective':'My perspective',true,true,meta);main.innerHTML=`<div class="chat-tools"><button id="swapBtn" class="swap-btn" onclick="swapSides()">↔ Swap sides</button><span>${swapState()?'Viewing from the other participant’s side':'Your messages are on the right'}</span></div><div class="chat"><button class="load-more" onclick="older()">Load older messages</button><div id="msgs">${renderMessages(S.lastMessages)}</div></div>`; mountMedia(); setTimeout(()=>main.scrollTop=main.scrollHeight,20)}'''
new_chat = '''function chatView(){const meta=(S.chats||[]).find(x=>x.id===S.chat.id)||{id:S.chat.id,name:S.chat.name,hasCustomAvatar:false,avatarVersion:0};setHead(meta.name||S.chat.name,swapState()?'Other side perspective':'My perspective',true,true,meta);main.innerHTML=`<div class="chat-tools"><button id="swapBtn" class="swap-btn" onclick="swapSides()">↔ Swap sides</button><button id="chatPhotoBtn" class="swap-btn" onclick="TARJS.setChatAvatar(${meta.id})">📷 Chat photo</button><span>${swapState()?'Viewing from the other participant’s side':'Your messages are on the right'}</span></div><div class="chat"><button class="load-more" onclick="older()">Load older messages</button><div id="msgs">${renderMessages(S.lastMessages)}</div></div>`; mountMedia(); setTimeout(()=>main.scrollTop=main.scrollHeight,20)}'''
if old_chat not in jst:
    raise SystemExit('chatView block not found')
jst = jst.replace(old_chat, new_chat, 1)

settings_pattern = re.compile(r'''function archiveSettings\(\)\{setHead\('Settings',S\.archive\.name,true,false\);const remembered=!!TARJS\.rclonePasswordRemembered\(\);const prof=j\(TARJS\.ownerProfile\(\),\{\}\);S\.ownerProfile=prof;main\.innerHTML=`.*?`\}''', re.S)
settings_replacement = '''function archiveSettings(){setHead('Settings',S.archive.name,true,false);const remembered=!!TARJS.rclonePasswordRemembered();const cm=S.chat?(S.chats||[]).find(x=>x.id===S.chat.id):null;const chatAppearance=cm?`<div class="panel"><h2>Chat appearance</h2><p>Customize how <b>${esc(cm.name)}</b> appears in TAR-JS. This only changes this archived conversation.</p><div class="profile-settings"><div class="owner-avatar">${avatarInnerHtml(cm)}</div><div class="profile-fields"><b>${esc(cm.name)}</b><small>Conversation profile photo</small><button id="settingsChatPhotoBtn" class="mini-btn" onclick="TARJS.setChatAvatar(${cm.id})">Change chat photo</button></div></div></div>`:'';main.innerHTML=`${chatAppearance}<div class="panel"><h2>Rclone security</h2><p>${remembered?'Password is securely remembered on this device.':'Password will be requested on each fresh app launch when remote access is needed.'}</p><button class="action" ${remembered?'':'disabled'} onclick="TARJS.forgetRclonePassword()"><span>Forget saved password</span><span>${remembered?'✓':'—'}</span></button></div><div class="panel"><h2>${esc(S.archive.name)}</h2><p>${S.archive.chatCount.toLocaleString()} chats · ${S.archive.messageCount.toLocaleString()} messages<br><span class="pill">${esc(S.archive.sourceKind)}</span></p><button class="action" onclick="TARJS.rescan(${S.archive.id})"><span>Rebuild archive index</span><span>↻</span></button><button class="action" onclick="TARJS.makeOffline(${S.archive.id})"><span>Download everything for offline use</span><span>›</span></button><button class="action" onclick="showAdd()"><span>Add another archive</span><span>＋</span></button></div><div class="section-title">Archives</div><div class="archive-list">${S.archives.map(a=>`<div class="row" onclick="selectArchive(${a.id})"><div class="avatar">${esc(initials(a.name))}</div><div class="row-main"><div class="row-title">${esc(a.name)}</div><div class="row-preview">${a.chatCount} chats · ${a.messageCount} messages · ${esc(a.sourceKind)}</div></div>${a.id===S.archive.id?'<span class="badge">Current</span>':''}</div>`).join('')}</div><div id="progress"></div>`}'''
jst2, n = settings_pattern.subn(settings_replacement, jst, count=1)
if n != 1:
    raise SystemExit('archiveSettings block not found')
js.write_text(jst2, encoding='utf-8')

# --- 3. Instrumentation tests: actual Android JsonReader + actual SQLite ----------
build = root / 'app/build.gradle.kts'
bt = build.read_text(encoding='utf-8')
if 'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"' not in bt:
    bt = bt.replace('android {', 'android {\n    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }', 1)
if 'androidTestImplementation("androidx.test.ext:junit:1.2.1")' not in bt:
    marker = 'dependencies {'
    bt = bt.replace(marker, marker + '\n    androidTestImplementation("androidx.test.ext:junit:1.2.1")\n    androidTestImplementation("androidx.test:runner:1.6.2")\n    androidTestImplementation("androidx.test:core:1.6.1")', 1)
build.write_text(bt, encoding='utf-8')

test = root / 'app/src/androidTest/java/com/tarjs/archive/TelegramImporterInstrumentedTest.kt'
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text(r'''package com.tarjs.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class TelegramImporterInstrumentedTest {
    private lateinit var db: ArchiveDatabase
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

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
        val messages = JSONArray(db.messagesJson(chats.getJSONObject(0).getLong("id"), 120, null))
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
}
''', encoding='utf-8')

print('SINGLE_CHAT_IMPORT_AND_CHAT_PHOTO_PATCH_APPLIED')
