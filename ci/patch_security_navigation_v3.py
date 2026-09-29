from pathlib import Path

root = Path('source/TAR-JS')
ui = root / 'app/src/main/java/com/tarjs/archive/NativeUi.kt'
ctl = root / 'app/src/main/java/com/tarjs/archive/TarJsController.kt'
db = root / 'app/src/main/java/com/tarjs/archive/ArchiveDatabase.kt'

s = ui.read_text(encoding='utf-8')

def replace_once(old: str, new: str, name: str):
    global s
    if old not in s:
        raise SystemExit(f'{name} source block not found')
    s = s.replace(old, new, 1)

replace_once('private enum class Page { Chats, Chat, Search, Add, Rclone, Browser, Settings, IndexStatus }',
             'private enum class Page { Welcome, Chats, Chat, Search, Add, Rclone, Browser, Settings, IndexStatus }', 'page enum')
replace_once('var page by remember { mutableStateOf(if (archives.isEmpty()) Page.Add else Page.Chats) }',
             'var page by remember { mutableStateOf(Page.Welcome) }', 'initial page')
replace_once('var searchHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }',
             'var searchHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }\n    var focusMessageId by remember { mutableStateOf<Long?>(null) }', 'focus state')
replace_once('''            Page.Chat, Page.Search, Page.Settings, Page.IndexStatus -> page = Page.Chats\n''',
             '''            Page.Chat, Page.Settings, Page.IndexStatus -> page = Page.Chats\n            Page.Search -> page = Page.Chat\n''', 'back search')
replace_once('''            Page.Rclone -> page = Page.Add\n            Page.Add -> page = if (archives.isNotEmpty()) Page.Chats else Page.Add\n            Page.Chats -> if (indexState.active) (context as? Activity)?.moveTaskToBack(true) else (context as? Activity)?.finish()\n''',
             '''            Page.Rclone -> page = Page.Add\n            Page.Add -> page = Page.Welcome\n            Page.Chats -> page = Page.Welcome\n            Page.Welcome -> if (indexState.active) (context as? Activity)?.moveTaskToBack(true) else (context as? Activity)?.finish()\n''', 'back welcome')
replace_once('''    val topTitle = when (page) {\n        Page.Chats -> selectedArchive?.name ?: "TAR-JS"\n''',
             '''    val topTitle = when (page) {\n        Page.Welcome -> "TAR-JS"\n        Page.Chats -> selectedArchive?.name ?: "TAR-JS"\n''', 'title welcome')
replace_once('''        Page.Search -> "Search"\n        Page.Add -> "Add archive"\n''',
             '''        Page.Search -> selectedChat?.let { "Search ${it.name}" } ?: "Search chat"\n        Page.Add -> "Add your chats"\n''', 'search title')
replace_once('''    val topSubtitle = when (page) {\n        Page.Chats -> selectedArchive?.let { "${fmtNum(it.messageCount)} messages" }.orEmpty()\n''',
             '''    val topSubtitle = when (page) {\n        Page.Welcome -> "Private Telegram archive reader"\n        Page.Chats -> selectedArchive?.let { "${fmtNum(it.messageCount)} messages" }.orEmpty()\n''', 'subtitle welcome')
replace_once('if (page != Page.Chats || (archives.isEmpty() && page != Page.Add)) {', 'if (page != Page.Welcome) {', 'nav visibility')
replace_once('''                                    Page.Chat, Page.Search, Page.Settings, Page.IndexStatus -> page = Page.Chats\n''',
             '''                                    Page.Chat, Page.Settings, Page.IndexStatus -> page = Page.Chats\n                                    Page.Search -> page = Page.Chat\n''', 'nav search')
replace_once('''                                    Page.Rclone -> page = Page.Add\n                                    Page.Add -> if (archives.isNotEmpty()) page = Page.Chats\n''',
             '''                                    Page.Rclone -> page = Page.Add\n                                    Page.Add, Page.Chats -> page = Page.Welcome\n''', 'nav welcome')
s = s.replace('ChatAvatar(controller, selectedChat!!, 36.dp, onClick = { avatarLauncher.launch(arrayOf("image/*")) })', 'ChatAvatar(controller, selectedChat!!, 36.dp, onClick = null)', 1)
s = s.replace('                        if (page == Page.Chats) IconButton(onClick = { page = Page.Search }) { Icon(Icons.Default.Search, "Search") }\n', '', 1)
replace_once('''                            if (page == Page.Chat && selectedChat != null) {\n                                DropdownMenuItem(text = { Text("Swap sides") }, leadingIcon = { Icon(Icons.Default.SwapHoriz, null) }, onClick = {\n''',
             '''                            if (page == Page.Chat && selectedChat != null) {\n                                DropdownMenuItem(text = { Text("Search this chat") }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = {\n                                    searchQuery = ""\n                                    searchHits = emptyList()\n                                    menuOpen = false\n                                    page = Page.Search\n                                })\n                                DropdownMenuItem(text = { Text("Swap sides") }, leadingIcon = { Icon(Icons.Default.SwapHoriz, null) }, onClick = {\n''', 'chat menu search')
s = s.replace('Text("Adjust chat photo")', 'Text("Set profile photo")', 1)
replace_once('''                    when (page) {\n                        Page.Chats -> ChatListScreen''',
             '''                    when (page) {\n                        Page.Welcome -> WelcomeScreen(\n                            hasArchives = archives.isNotEmpty(),\n                            rcloneLocked = rcloneSetup?.passwordRequired == true,\n                            indexState = indexState,\n                            onAdd = { page = Page.Add },\n                            onOpenChats = { if (rcloneSetup?.passwordRequired == true) rclonePasswordDialog = true else page = Page.Chats },\n                            onIndexStatus = { page = Page.IndexStatus },\n                        )\n                        Page.Chats -> ChatListScreen''', 'welcome route')
replace_once('''                        Page.Chats -> ChatListScreen(chats, indexState, onOpen = { c ->\n                            selectedChat = c\n                            messages = controller.messages(c.id)\n''',
             '''                        Page.Chats -> ChatListScreen(chats, indexState, onOpen = { c ->\n                            selectedChat = c\n                            focusMessageId = null\n                            messages = controller.messages(c.id)\n''', 'chat focus reset')
replace_once('''                                chat = chat,\n                                messages = messages,\n                                onMessages = { messages = it },\n''',
             '''                                chat = chat,\n                                messages = messages,\n                                focusMessageId = focusMessageId,\n                                onMessages = { messages = it },\n''', 'chat focus parameter')
replace_once('''                        Page.Search -> SearchScreen(searchQuery, searchHits, onQuery = { q ->\n                            searchQuery = q\n                            searchHits = selectedArchive?.let { if (q.trim().length >= 2) controller.search(it.id, q) else emptyList() }.orEmpty()\n                        }, onHit = { hit ->\n                            val c = chats.firstOrNull { it.id == hit.chatId }\n                            if (c != null) {\n                                selectedChat = c\n                                messages = controller.messageWindow(c.id, hit.messageDbId)\n                                page = Page.Chat\n                            }\n                        })\n''',
             '''                        Page.Search -> SearchScreen(searchQuery, searchHits, onQuery = { q ->\n                            searchQuery = q\n                            val c = selectedChat\n                            searchHits = if (c != null && q.trim().length >= 2) controller.searchChat(c.id, q) else emptyList()\n                        }, onHit = { hit ->\n                            val c = selectedChat\n                            if (c != null && c.id == hit.chatId) {\n                                focusMessageId = hit.messageDbId\n                                messages = controller.messageWindow(c.id, hit.messageDbId)\n                                page = Page.Chat\n                            }\n                        })\n''', 'scoped search route')
s = s.replace('onDismiss = { rclonePasswordDialog = false },', 'onDismiss = { rclonePasswordDialog = false; page = Page.Welcome },', 1)

start = s.index('private fun ChatScreen(')
end = s.index('\n@Composable\nprivate fun MessageRow', start)
new_chat = '''private fun ChatScreen(controller: TarJsController, chat: ChatItem, messages: List<MessageItem>, focusMessageId: Long?, onMessages: (List<MessageItem>) -> Unit, onPickAvatar: () -> Unit) {\n    val swapped = controller.isSwapped(chat.id)\n    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, messages.size - 1))\n    val replies = remember(messages) { messages.mapNotNull { m -> m.telegramId?.let { it to m } }.toMap() }\n    val hasOlder = remember(messages) { messages.firstOrNull()?.let { controller.hasOlder(chat.id, it.dbId) } ?: false }\n    val hasNewer = remember(messages) { messages.lastOrNull()?.let { controller.hasNewer(chat.id, it.dbId) } ?: false }\n\n    LaunchedEffect(messages, focusMessageId) {\n        val target = focusMessageId ?: return@LaunchedEffect\n        val index = messages.indexOfFirst { it.dbId == target }\n        if (index >= 0) listState.scrollToItem(index + if (hasOlder) 1 else 0)\n    }\n\n    LazyColumn(\n        state = listState,\n        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest),\n        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),\n        verticalArrangement = Arrangement.spacedBy(3.dp),\n    ) {\n        if (hasOlder) item {\n            TextButton(onClick = {\n                val before = messages.firstOrNull()?.dbId ?: return@TextButton\n                val old = controller.messages(chat.id, before)\n                if (old.isNotEmpty()) onMessages((old + messages).distinctBy { it.dbId })\n            }, modifier = Modifier.fillMaxWidth()) { Text("Load older messages") }\n        }\n        items(messages, key = { it.dbId }) { message ->\n            MessageRow(controller, message, shouldRenderOnRight(message.mine, swapped), replies[message.replyTo])\n        }\n        if (hasNewer) item {\n            TextButton(onClick = {\n                val after = messages.lastOrNull()?.dbId ?: return@TextButton\n                val newer = controller.newerMessages(chat.id, after)\n                if (newer.isNotEmpty()) onMessages((messages + newer).distinctBy { it.dbId })\n            }, modifier = Modifier.fillMaxWidth()) { Text("Load newer messages") }\n        }\n    }\n}\n'''
s = s[:start] + new_chat + s[end:]
s = s.replace('placeholder = { Text("Search all messages") }', 'placeholder = { Text("Search this chat") }', 1)
s = s.replace('headlineContent = { Text(hit.chatName, maxLines = 1) },', 'headlineContent = { Text(hit.sender.ifBlank { hit.chatName }, maxLines = 1) },', 1)

insert = s.index('@Composable\nprivate fun AddArchiveScreen')
welcome = '''@Composable\nprivate fun WelcomeScreen(\n    hasArchives: Boolean,\n    rcloneLocked: Boolean,\n    indexState: IndexState,\n    onAdd: () -> Unit,\n    onOpenChats: () -> Unit,\n    onIndexStatus: () -> Unit,\n) {\n    Column(\n        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),\n        horizontalAlignment = Alignment.CenterHorizontally,\n        verticalArrangement = Arrangement.Center,\n    ) {\n        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {\n            Icon(Icons.Default.Forum, null, Modifier.padding(22.dp).size(54.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)\n        }\n        Spacer(Modifier.height(22.dp))\n        Text("Your Telegram archive, readable again", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)\n        Spacer(Modifier.height(10.dp))\n        Text("TAR-JS privately indexes Telegram exports on your device, keeps the original result.json read-only, and lets you browse chats, search messages and load archived media.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)\n        Spacer(Modifier.height(28.dp))\n        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add your chats") }\n        if (hasArchives) {\n            Spacer(Modifier.height(10.dp))\n            OutlinedButton(onClick = onOpenChats, modifier = Modifier.fillMaxWidth()) {\n                Icon(if (rcloneLocked) Icons.Default.Lock else Icons.Default.Chat, null); Spacer(Modifier.width(8.dp)); Text(if (rcloneLocked) "Unlock to open indexed chats" else "Open indexed chats")\n            }\n        }\n        if (indexState.active) { Spacer(Modifier.height(10.dp)); TextButton(onClick = onIndexStatus) { Text("View indexing progress") } }\n    }\n}\n\n'''
s = s[:insert] + welcome + s[insert:]
replace_once('''private fun AddArchiveScreen(onLocal: (String) -> Unit, onRclone: () -> Unit) {\n    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {\n        item { Text("Add Telegram archive", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }\n        item { Text("TAR-JS reads your export and builds a local searchable index. Original files stay read-only.", color = MaterialTheme.colorScheme.onSurfaceVariant) }\n        item { SourceCard(Icons.Default.Folder, "Local folder", "Phone, SD card or USB") { onLocal("local") } }\n        item { SourceCard(Icons.Default.Cloud, "Cloud document provider", "Google Drive, OneDrive or another Android provider") { onLocal("cloud") } }\n        item { SourceCard(Icons.Default.Lock, "rclone config", "B2, crypt and other rclone remotes") { onRclone() } }\n    }\n}\n''',
             '''private fun AddArchiveScreen(onLocal: (String) -> Unit, onRclone: () -> Unit) {\n    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {\n        item { Text("Choose where your export is stored", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }\n        item { Text("Select the folder that contains result.json. TAR-JS only reads the source and builds its own local index.", color = MaterialTheme.colorScheme.onSurfaceVariant) }\n        item { SourceCard(Icons.Default.Folder, "Local folder", "Phone, SD card or USB") { onLocal("local") } }\n        item { SourceCard(Icons.Default.Cloud, "Google Drive", "Choose a Drive folder through Android's document picker") { onLocal("google_drive") } }\n        item { SourceCard(Icons.Default.Cloud, "OneDrive", "Choose a OneDrive folder through Android's document picker") { onLocal("onedrive") } }\n        item { SourceCard(Icons.Default.Lock, "rclone config", "B2, crypt and other rclone remotes") { onRclone() } }\n    }\n}\n''', 'source picker')
s = s.replace('dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },', 'dismissButton = { TextButton(onClick = onDismiss) { Text("Back to home") } },', 1)
replace_once('@Composable\nfun TarJsRoot(controller: TarJsController) {', '@Composable\nprivate fun TarJsUnlockedRoot(controller: TarJsController) {', 'root rename')
insert = s.index('@Composable\nprivate fun TarJsUnlockedRoot')
s = s[:insert] + '''@Composable\nfun TarJsRoot(controller: TarJsController) {\n    TarJsAppPasscodeGate { TarJsUnlockedRoot(controller) }\n}\n\n''' + s[insert:]
ui.write_text(s, encoding='utf-8')

c = ctl.read_text(encoding='utf-8')
needle = '    fun search(archiveId: Long, query: String): List<SearchHit> = runCatching { JsonModels.search(db.searchJson(archiveId, query, 120)) }.getOrDefault(emptyList())\n'
if needle not in c: raise SystemExit('controller search block not found')
c = c.replace(needle, needle + '    fun searchChat(chatId: Long, query: String): List<SearchHit> = runCatching { JsonModels.search(db.searchChatJson(chatId, query, 120)) }.getOrDefault(emptyList())\n    fun newerMessages(chatId: Long, afterDbId: Long): List<MessageItem> = runCatching { JsonModels.messages(db.newerMessagesJson(chatId, 160, afterDbId)) }.getOrDefault(emptyList())\n    fun hasOlder(chatId: Long, dbId: Long): Boolean = db.hasOlder(chatId, dbId)\n    fun hasNewer(chatId: Long, dbId: Long): Boolean = db.hasNewer(chatId, dbId)\n', 1)
ctl.write_text(c, encoding='utf-8')

b = db.read_text(encoding='utf-8')
marker = '    fun searchJson(archiveId: Long, rawQuery: String, limit: Int = 100): String {\n'
if marker not in b: raise SystemExit('database search marker not found')
addition = '''    fun newerMessagesJson(chatId: Long, limit: Int = 120, afterDbId: Long): String {\n        val out = JSONArray()\n        readableDatabase.rawQuery("SELECT ${messageColumns()} FROM messages WHERE chat_id=? AND id>? ORDER BY date_unix ASC,id ASC LIMIT ?", arrayOf(chatId.toString(), afterDbId.toString(), limit.coerceIn(20, 300).toString())).use { c -> while (c.moveToNext()) out.put(messageObject(c)) }\n        return out.toString()\n    }\n\n    fun hasOlder(chatId: Long, dbId: Long): Boolean = readableDatabase.rawQuery("SELECT 1 FROM messages WHERE chat_id=? AND id<? LIMIT 1", arrayOf(chatId.toString(), dbId.toString())).use { it.moveToFirst() }\n    fun hasNewer(chatId: Long, dbId: Long): Boolean = readableDatabase.rawQuery("SELECT 1 FROM messages WHERE chat_id=? AND id>? LIMIT 1", arrayOf(chatId.toString(), dbId.toString())).use { it.moveToFirst() }\n\n    fun searchChatJson(chatId: Long, rawQuery: String, limit: Int = 100): String {\n        val query = rawQuery.trim().replace("\\\"", " ").split(Regex("\\\\s+")).filter { it.isNotBlank() }.joinToString(" AND ") { "\\\"${it.replace("*", "")}*\\\"" }\n        if (query.isBlank()) return "[]"\n        val out = JSONArray()\n        val sql = """\n            SELECT m.id,m.chat_id,c.name,m.sender,m.text,m.date_unix,m.media_type,m.file_name\n            FROM message_fts f JOIN messages m ON m.id=CAST(f.message_db_id AS INTEGER)\n            JOIN chats c ON c.id=m.chat_id\n            WHERE m.chat_id=? AND message_fts MATCH ?\n            ORDER BY m.date_unix DESC,m.id DESC LIMIT ?\n        """.trimIndent()\n        readableDatabase.rawQuery(sql, arrayOf(chatId.toString(), query, limit.coerceIn(1,300).toString())).use { c ->\n            while (c.moveToNext()) out.put(JSONObject().apply { put("messageDbId", c.getLong(0)); put("chatId", c.getLong(1)); put("chatName", c.getString(2)); put("sender", c.getString(3) ?: ""); put("text", c.getString(4) ?: ""); put("dateUnix", c.getLong(5)); put("mediaType", c.getString(6)); put("fileName", c.getString(7)) })\n        }\n        return out.toString()\n    }\n\n'''
b = b.replace(marker, addition + marker, 1)
db.write_text(b, encoding='utf-8')

(root / 'app/src/main/java/com/tarjs/archive/AppPasscode.kt').write_text(r'''package com.tarjs.archive

import android.app.Activity
import android.content.Context
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private class AppPasscodeStore(context: Context) {
    private val prefs = context.getSharedPreferences("tarjs_app_lock", Context.MODE_PRIVATE)
    fun isConfigured() = prefs.contains("hash") && prefs.contains("salt")
    fun save(passcode: String) { val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }; val hash = derive(passcode, salt); prefs.edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP)).putString("hash", Base64.encodeToString(hash, Base64.NO_WRAP)).apply() }
    fun verify(passcode: String): Boolean { val salt64=prefs.getString("salt",null)?:return false; val hash64=prefs.getString("hash",null)?:return false; return runCatching { val salt=Base64.decode(salt64,Base64.NO_WRAP); val expected=Base64.decode(hash64,Base64.NO_WRAP); val actual=derive(passcode,salt); if(expected.size!=actual.size)return@runCatching false; var diff=0; for(i in expected.indices) diff=diff or (expected[i].toInt() xor actual[i].toInt()); diff==0 }.getOrDefault(false) }
    private fun derive(passcode:String,salt:ByteArray)=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passcode.toCharArray(),salt,120_000,256)).encoded
}

@Composable
fun TarJsAppPasscodeGate(content: @Composable () -> Unit) {
    val context=LocalContext.current; val store=remember{AppPasscodeStore(context.applicationContext)}; var configured by remember{mutableStateOf(store.isConfigured())}; var unlocked by remember{mutableStateOf(false)}; var passcode by remember{mutableStateOf("")}; var confirm by remember{mutableStateOf("")}; var error by remember{mutableStateOf<String?>(null)}
    if(unlocked){content();return}; BackHandler{(context as? Activity)?.finish()}
    Surface(Modifier.fillMaxSize()){ Box(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues()).padding(24.dp),contentAlignment=Alignment.Center){ Card(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.extraLarge){ Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){ Text(if(configured)"Unlock TAR-JS" else "Create app passcode",style=MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(8.dp)); Text(if(configured)"Enter your app passcode to view archived chats." else "This passcode protects your indexed chats. It is separate from your rclone configuration password.",color=MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(18.dp)); OutlinedTextField(passcode,{passcode=it.filter(Char::isDigit);error=null},label={Text("Passcode")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth()); if(!configured){Spacer(Modifier.height(10.dp));OutlinedTextField(confirm,{confirm=it.filter(Char::isDigit);error=null},label={Text("Confirm passcode")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth())}; if(error!=null){Spacer(Modifier.height(8.dp));Text(error!!,color=MaterialTheme.colorScheme.error)}; Spacer(Modifier.height(18.dp)); Button(onClick={ if(configured){if(store.verify(passcode)){unlocked=true;passcode=""}else error="Incorrect app passcode"}else when{passcode.length<4->error="Use at least 4 digits";passcode!=confirm->error="Passcodes do not match";else->{store.save(passcode);configured=true;unlocked=true;passcode="";confirm=""}} },enabled=passcode.isNotBlank()&&(configured||confirm.isNotBlank()),modifier=Modifier.fillMaxWidth()){Text(if(configured)"Unlock" else "Create passcode")}; TextButton(onClick={(context as? Activity)?.finish()}){Text("Close app")} } } } }
}
''', encoding='utf-8')

test = root / 'app/src/test/java/com/tarjs/archive/SecurityNavigationIntegrationTest.kt'
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text(r'''package com.tarjs.archive

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class SecurityNavigationIntegrationTest {
    private lateinit var db: ArchiveDatabase
    private val ctx get()=ApplicationProvider.getApplicationContext<android.content.Context>()
    @Before fun setup(){ctx.deleteDatabase("tarjs.db");db=ArchiveDatabase(ctx)}
    @After fun teardown(){db.close();ctx.deleteDatabase("tarjs.db")}
    @Test fun searchIsScopedAndOldResultCanPageForward(){
        val archive=db.createArchive("QA","test",null); val alice=db.insertChat(archive,"1","Alice","personal_chat",null); val bob=db.insertChat(archive,"2","Bob","personal_chat",null)
        fun put(chat:Long,name:String,text:String,sec:Long)=db.insertMessage(ArchiveDatabase.MessageInsert(archive,chat,sec,"message",null,sec,name,"u$chat",text,null,null,null,null,null,null,null,null,null,name))
        val old=put(alice,"Alice","needle old",1); put(alice,"Alice","middle",2); val newest=put(alice,"Alice","newest",3); put(bob,"Bob","needle other chat",4)
        val hits=JSONArray(db.searchChatJson(alice,"needle",20)); Assert.assertEquals(1,hits.length()); Assert.assertEquals(alice,hits.getJSONObject(0).getLong("chatId")); Assert.assertEquals(old,hits.getJSONObject(0).getLong("messageDbId")); Assert.assertFalse(db.hasOlder(alice,old)); Assert.assertTrue(db.hasNewer(alice,old)); val newer=JSONArray(db.newerMessagesJson(alice,20,old)); Assert.assertEquals(2,newer.length()); Assert.assertEquals(newest,newer.getJSONObject(1).getLong("dbId")); Assert.assertFalse(db.hasNewer(alice,newest))
    }
}
''', encoding='utf-8')

for token in ['Page.Welcome','WelcomeScreen(','Search this chat','Load newer messages','Back to home','Set profile photo','focusMessageId']:
    assert token in ui.read_text(encoding='utf-8'), token
print('SECURITY_NAVIGATION_V3_APPLIED')
