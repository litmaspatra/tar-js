from pathlib import Path
import re

root = Path('source/TAR-JS')

# -----------------------------------------------------------------------------
# 1) Archive DB: explicit status update used by the background indexer.
# -----------------------------------------------------------------------------
db = root / 'app/src/main/java/com/tarjs/archive/ArchiveDatabase.kt'
s = db.read_text(encoding='utf-8')
if 'fun setArchiveStatus(' not in s:
    marker = '    fun updateArchiveStats(id: Long, chats: Int, messages: Int) {'
    i = s.find(marker)
    if i < 0:
        raise SystemExit('ArchiveDatabase.updateArchiveStats not found')
    s = s[:i] + '''    fun setArchiveStatus(id: Long, status: String) {
        val v = ContentValues().apply { put("status", status) }
        writableDatabase.update("archives", v, "id=?", arrayOf(id.toString()))
    }

''' + s[i:]
db.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 2) Telegram importer: exact message counting pass + frequent count callbacks.
#    The source JSON is never modified. The count pass lets the UI say
#    192,933 / 1,093,230 instead of making the user stare at a spinner.
# -----------------------------------------------------------------------------
imp = root / 'app/src/main/java/com/tarjs/archive/TelegramImporter.kt'
s = imp.read_text(encoding='utf-8')

# Add a structural counting pass if it doesn't exist yet.
if 'fun countMessages(' not in s:
    insert_at = s.find('    fun import(')
    if insert_at < 0:
        raise SystemExit('TelegramImporter.import not found')
    counter = r'''    fun countMessages(input: InputStream, progress: (Int) -> Unit = {}): Int {
        var total = 0
        fun countArray(r: JsonReader): Int {
            var n = 0
            if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) { r.skipValue(); return 0 }
            r.beginArray()
            while (r.hasNext()) {
                r.skipValue()
                n++
                total++
                if (total == 1 || total % 5000 == 0) progress(total)
            }
            r.endArray()
            return n
        }
        fun countChat(r: JsonReader) {
            if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); return }
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "messages" -> countArray(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) return 0
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "messages" -> countArray(r) // single-chat Telegram export
                    "chats" -> {
                        if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "list" -> {
                                    if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) { r.skipValue(); continue }
                                    r.beginArray()
                                    while (r.hasNext()) countChat(r)
                                    r.endArray()
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        progress(total)
        return total
    }

'''
    s = s[:insert_at] + counter + s[insert_at:]

# Add typed count callback to import without removing the existing human-readable callback.
s = s.replace(
    'fun import(archiveId: Long, input: InputStream, progress: (String) -> Unit = {}): Result {',
    'fun import(archiveId: Long, input: InputStream, progress: (String) -> Unit = {}, countProgress: (Int) -> Unit = {}): Result {',
    1
)

# Full-account chat calls need the current global message base.
s = s.replace(
    'parseChat(r, archiveId, ownUserId, progress)',
    'parseChat(r, archiveId, ownUserId, messageCount, progress, countProgress)'
)

# Patch parseChat signature and emit every 1000 valid inserted messages.
s = s.replace(
    'private fun parseChat(r: JsonReader, archiveId: Long, ownUserId: String?, progress: (String) -> Unit): Int {',
    'private fun parseChat(r: JsonReader, archiveId: Long, ownUserId: String?, baseMessageCount: Int, progress: (String) -> Unit, countProgress: (Int) -> Unit): Int {'
)
needle = '''                            db.insertMessage(m)
                            messages++
                            if (m.dateUnix > lastDate) lastDate = m.dateUnix'''
repl = '''                            db.insertMessage(m)
                            messages++
                            val absoluteCount = baseMessageCount + messages
                            if (absoluteCount == 1 || absoluteCount % 1000 == 0) {
                                countProgress(absoluteCount)
                                progress("Indexed $absoluteCount messages · $name")
                            }
                            if (m.dateUnix > lastDate) lastDate = m.dateUnix'''
if needle in s:
    s = s.replace(needle, repl, 1)

# Single-chat root loop from the previous patch: emit counts there too.
needle2 = '''                                    db.insertMessage(m)
                                    rootMessageCount++
                                    if (m.dateUnix > rootLastDate) rootLastDate = m.dateUnix'''
repl2 = '''                                    db.insertMessage(m)
                                    rootMessageCount++
                                    if (rootMessageCount == 1 || rootMessageCount % 1000 == 0) {
                                        countProgress(rootMessageCount)
                                        progress("Indexed $rootMessageCount messages · $rootName")
                                    }
                                    if (m.dateUnix > rootLastDate) rootLastDate = m.dateUnix'''
if needle2 in s:
    s = s.replace(needle2, repl2, 1)

# Always publish the final exact count.
s = s.replace(
    'db.updateArchiveStats(archiveId, chatCount, messageCount)\n        return Result(chatCount, messageCount)',
    'db.updateArchiveStats(archiveId, chatCount, messageCount)\n        countProgress(messageCount)\n        return Result(chatCount, messageCount)',
    1
)
imp.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 3) Foreground service. It copies result.json to app-private temporary storage,
#    counts it, then indexes the private snapshot. Source result.json is read-only.
#    The original user-selected rclone config is never opened for writing either.
# -----------------------------------------------------------------------------
service = root / 'app/src/main/java/com/tarjs/archive/IndexingService.kt'
service.write_text(r'''package com.tarjs.archive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class IndexingService : Service() {
    companion object {
        private const val CHANNEL = "tarjs_indexing"
        private const val NOTIFICATION_ID = 4107
        private const val PREFS = "tarjs_index_state"
        private val workerRunning = AtomicBoolean(false)

        const val EXTRA_ARCHIVE_ID = "archiveId"
        const val EXTRA_ARCHIVE_NAME = "archiveName"
        const val EXTRA_SOURCE_KIND = "sourceKind"
        const val EXTRA_RESULT_URI = "resultUri"
        const val EXTRA_REMOTE = "remote"
        const val EXTRA_REMOTE_PATH = "remotePath"
        const val EXTRA_CONFIG_PATH = "configPath"
        const val EXTRA_CONFIG_PASSWORD = "configPassword"
        const val EXTRA_SOURCE_KEY = "sourceKey"

        private val activePhases = setOf("preparing", "counting", "indexing")

        fun statusJson(context: Context): String {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return JSONObject().apply {
                put("phase", p.getString("phase", "idle"))
                put("archiveId", p.getLong("archiveId", -1L))
                put("archiveName", p.getString("archiveName", ""))
                put("sourceKey", p.getString("sourceKey", ""))
                put("processed", p.getInt("processed", 0))
                put("total", p.getInt("total", 0))
                put("detected", p.getInt("detected", 0))
                put("detail", p.getString("detail", ""))
                put("updatedAt", p.getLong("updatedAt", 0L))
            }.toString()
        }

        fun isActive(context: Context): Boolean {
            val phase = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("phase", "idle") ?: "idle"
            return phase in activePhases
        }

        fun activeSource(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sourceKey", "") ?: ""

        fun startSaf(context: Context, archiveId: Long, archiveName: String, resultUri: Uri, sourceKey: String) {
            val i = Intent(context, IndexingService::class.java).apply {
                putExtra(EXTRA_ARCHIVE_ID, archiveId)
                putExtra(EXTRA_ARCHIVE_NAME, archiveName)
                putExtra(EXTRA_SOURCE_KIND, "saf")
                putExtra(EXTRA_RESULT_URI, resultUri.toString())
                putExtra(EXTRA_SOURCE_KEY, sourceKey)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun startRclone(context: Context, archiveId: Long, archiveName: String, remote: String, remotePath: String, configPath: String, password: String?, sourceKey: String) {
            val i = Intent(context, IndexingService::class.java).apply {
                putExtra(EXTRA_ARCHIVE_ID, archiveId)
                putExtra(EXTRA_ARCHIVE_NAME, archiveName)
                putExtra(EXTRA_SOURCE_KIND, "rclone")
                putExtra(EXTRA_REMOTE, remote)
                putExtra(EXTRA_REMOTE_PATH, remotePath)
                putExtra(EXTRA_CONFIG_PATH, configPath)
                if (!password.isNullOrEmpty()) putExtra(EXTRA_CONFIG_PASSWORD, password)
                putExtra(EXTRA_SOURCE_KEY, sourceKey)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var db: ArchiveDatabase
    private lateinit var nm: NotificationManager

    override fun onCreate() {
        super.onCreate()
        db = ArchiveDatabase(this)
        nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Archive indexing", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val archiveId = intent.getLongExtra(EXTRA_ARCHIVE_ID, -1L)
        val archiveName = intent.getStringExtra(EXTRA_ARCHIVE_NAME) ?: "Telegram Archive"
        startForeground(NOTIFICATION_ID, notification("Preparing $archiveName", "Starting safe background indexing…", true))
        if (!workerRunning.compareAndSet(false, true)) return START_REDELIVER_INTENT
        io.execute {
            try { runJob(intent) }
            finally { workerRunning.set(false); stopSelf(startId) }
        }
        return START_REDELIVER_INTENT
    }

    private fun runJob(intent: Intent) {
        val archiveId = intent.getLongExtra(EXTRA_ARCHIVE_ID, -1L)
        require(archiveId > 0) { "Missing archive id" }
        val archiveName = intent.getStringExtra(EXTRA_ARCHIVE_NAME) ?: "Telegram Archive"
        val sourceKind = intent.getStringExtra(EXTRA_SOURCE_KIND) ?: error("Missing source kind")
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY) ?: ""
        val tempDir = File(filesDir, "index-jobs").apply { mkdirs() }
        val snapshot = File(tempDir, "$archiveId-result.json")
        var engine: RcloneEngine? = null
        try {
            db.setArchiveStatus(archiveId, "indexing")
            saveState("preparing", archiveId, archiveName, sourceKey, detail = "Preparing a private read-only snapshot of result.json")
            updateNotification("Preparing archive", "Copying result.json safely into TAR-JS…", true)

            if (!snapshot.exists() || snapshot.length() == 0L) {
                when (sourceKind) {
                    "saf" -> {
                        val uri = Uri.parse(intent.getStringExtra(EXTRA_RESULT_URI) ?: error("Missing result.json URI"))
                        contentResolver.openInputStream(uri).use { input ->
                            require(input != null) { "Could not open result.json" }
                            FileOutputStream(snapshot).use { output ->
                                val buf = ByteArray(256 * 1024)
                                var copied = 0L
                                var lastUi = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    copied += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastUi >= 1000) {
                                        val mb = copied / (1024 * 1024)
                                        saveState("preparing", archiveId, archiveName, sourceKey, detail = "Copied $mb MB of result.json")
                                        updateNotification("Preparing archive", "Copied $mb MB safely…", true)
                                        lastUi = now
                                    }
                                }
                            }
                        }
                    }
                    "rclone" -> {
                        val remote = intent.getStringExtra(EXTRA_REMOTE) ?: error("Missing rclone remote")
                        val path = intent.getStringExtra(EXTRA_REMOTE_PATH) ?: ""
                        val config = File(intent.getStringExtra(EXTRA_CONFIG_PATH) ?: error("Missing rclone config"))
                        require(config.exists()) { "Saved rclone config is missing" }
                        engine = RcloneEngine(this)
                        engine!!.initialize(config, intent.getStringExtra(EXTRA_CONFIG_PASSWORD))
                        val remoteResult = if (path.isBlank()) "result.json" else path.trimEnd('/') + "/result.json"
                        engine!!.copyFileToLocal(remote, remoteResult, snapshot)
                    }
                    else -> error("Unsupported indexing source")
                }
            }
            require(snapshot.exists() && snapshot.length() > 0L) { "Private result.json snapshot is empty" }

            var detected = 0
            saveState("counting", archiveId, archiveName, sourceKey, detail = "Detecting messages…")
            updateNotification("Detecting messages", "Scanning result.json…", true)
            val importer = TelegramImporter(db)
            val total = FileInputStream(snapshot).use { input ->
                importer.countMessages(input) { n ->
                    detected = n
                    if (n == 1 || n % 5000 == 0) {
                        saveState("counting", archiveId, archiveName, sourceKey, detected = n, detail = "$n messages detected so far")
                        updateNotification("Detecting messages", "${fmt(n)} found so far…", true)
                    }
                }
            }
            require(total > 0) { "No Telegram messages were found in result.json" }

            var lastNoticeCount = -1
            saveState("indexing", archiveId, archiveName, sourceKey, processed = 0, total = total, detected = total, detail = "Indexing 0 / $total messages")
            updateNotification("Indexing $archiveName", "0 / ${fmt(total)} messages", true)
            val result = FileInputStream(snapshot).use { input ->
                importer.import(archiveId, input, { _ -> }, { processed ->
                    val pct = if (total > 0) (processed * 100.0 / total) else 0.0
                    saveState("indexing", archiveId, archiveName, sourceKey, processed = processed, total = total, detected = total, detail = "${fmt(processed)} / ${fmt(total)} messages (${String.format("%.1f", pct)}%)")
                    if (lastNoticeCount < 0 || processed - lastNoticeCount >= 5000 || processed == total) {
                        updateNotification("Indexing $archiveName", "${fmt(processed)} / ${fmt(total)} messages", true, processed, total)
                        lastNoticeCount = processed
                    }
                })
            }
            db.setArchiveStatus(archiveId, "ready")
            saveState("complete", archiveId, archiveName, sourceKey, processed = result.messages, total = total, detected = total, detail = "Indexed ${fmt(result.messages)} messages")
            updateNotification("TAR-JS indexing complete", "${fmt(result.messages)} messages indexed", false)
            snapshot.delete()
        } catch (e: Exception) {
            try { db.setArchiveStatus(archiveId, "error") } catch (_: Exception) {}
            saveState("error", archiveId, archiveName, sourceKey, detail = e.message ?: "Indexing failed")
            updateNotification("TAR-JS indexing stopped", e.message ?: "Indexing failed", false)
        } finally {
            try { engine?.close() } catch (_: Exception) {}
        }
    }

    private fun saveState(phase: String, archiveId: Long, archiveName: String, sourceKey: String, processed: Int = 0, total: Int = 0, detected: Int = 0, detail: String = "") {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("phase", phase).putLong("archiveId", archiveId).putString("archiveName", archiveName)
            .putString("sourceKey", sourceKey).putInt("processed", processed).putInt("total", total).putInt("detected", detected)
            .putString("detail", detail).putLong("updatedAt", System.currentTimeMillis()).apply()
    }

    private fun notification(title: String, text: String, ongoing: Boolean, processed: Int = 0, total: Int = 0) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title).setContentText(text).setOnlyAlertOnce(ongoing).setOngoing(ongoing).setAutoCancel(!ongoing)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .apply { if (ongoing) { if (total > 0) setProgress(total, processed.coerceAtMost(total), false) else setProgress(0, 0, true) } }
        .build()

    private fun updateNotification(title: String, text: String, ongoing: Boolean, processed: Int = 0, total: Int = 0) {
        nm.notify(NOTIFICATION_ID, notification(title, text, ongoing, processed, total))
    }

    private fun fmt(n: Int): String = String.format("%,d", n)

    override fun onDestroy() {
        io.shutdownNow()
        try { db.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
''', encoding='utf-8')

# -----------------------------------------------------------------------------
# 4) MainActivity: start the foreground service instead of owning the import.
#    Also expose persistent status and make Android Back minimize during indexing.
# -----------------------------------------------------------------------------
main = root / 'app/src/main/java/com/tarjs/archive/MainActivity.kt'
s = main.read_text(encoding='utf-8')

# Imports needed by status-bar inset handling / notification permission.
if 'import android.os.Build' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Bundle\nimport android.os.Build\nimport android.view.WindowInsets\n')

# Remember the unlocked password in process memory for a background rclone service.
if 'sessionRclonePassword' not in s:
    s = s.replace('    private var pendingAvatarChatId: Long? = null\n', '    private var pendingAvatarChatId: Long? = null\n    @Volatile private var sessionRclonePassword: String? = null\n')

# Native insets: Android 15/16 target-SDK edge-to-edge can otherwise put WebView under status bar.
needle = '        web = WebView(this)\n        setContentView(web)'
if needle in s and 'setOnApplyWindowInsetsListener' not in s:
    s = s.replace(needle, '''        web = WebView(this)
        if (Build.VERSION.SDK_INT >= 30) {
            web.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        setContentView(web)''', 1)

# Expose persistent indexing state to JS.
bridge_marker = '        @JavascriptInterface fun search(archiveId: Long, query: String): String = db.searchJson(archiveId, query)\n'
if bridge_marker in s and 'indexStatus()' not in s:
    s = s.replace(bridge_marker, bridge_marker + '        @JavascriptInterface fun indexStatus(): String = IndexingService.statusJson(this@MainActivity)\n', 1)

# Keep session password when user unlocks; SecureRclonePassword still controls durable Remember Me.
s = s.replace('                    rclone.initialize(f, password)\n', '                    rclone.initialize(f, password)\n                    sessionRclonePassword = password\n', 1)

# Replace rclone import method.
pat = re.compile(r'''        @JavascriptInterface fun importRcloneFolder\(remote: String, path: String\) \{.*?\n        \}\n\n        @JavascriptInterface fun chooseRcloneConfig''', re.S)
rep = '''        @JavascriptInterface fun importRcloneFolder(remote: String, path: String) {
            io.execute {
                try {
                    if (IndexingService.isActive(this@MainActivity)) {
                        event("indexState", JSONObject(IndexingService.statusJson(this@MainActivity)))
                        return@execute
                    }
                    val entries = rclone.list(remote, path)
                    var found = false
                    for (i in 0 until entries.length()) if (!entries.getJSONObject(i).optBoolean("IsDir", false) && entries.getJSONObject(i).optString("Name") == "result.json") { found = true; break }
                    require(found) { "No result.json found in this rclone folder" }
                    val archiveName = if (path.isBlank()) remote.removeSuffix(":") else path.substringAfterLast('/')
                    val sourceKey = "rclone://$remote/${path.trimStart('/')}"
                    val id = db.createArchive(archiveName.ifBlank { "Telegram Archive" }, "rclone", sourceKey)
                    db.setArchiveStatus(id, "indexing")
                    val config = rcloneConfigFile ?: error("Saved rclone config is missing")
                    val password = sessionRclonePassword ?: secureRclone.load()
                    IndexingService.startRclone(this@MainActivity, id, archiveName.ifBlank { "Telegram Archive" }, remote, path, config.absolutePath, password, sourceKey)
                    event("archivesChanged", JSONArray(db.archiveJson()))
                    event("indexState", JSONObject(IndexingService.statusJson(this@MainActivity)))
                } catch (e: Exception) { event("importError", JSONObject().put("message", e.message ?: "rclone import failed")) }
            }
        }

        @JavascriptInterface fun chooseRcloneConfig'''
s, n = pat.subn(rep, s, count=1)
if n != 1:
    raise SystemExit('Could not replace MainActivity.importRcloneFolder')

# Replace SAF tree import method.
pat = re.compile(r'''    private fun handleTree\(uri: Uri\) \{.*?\n    \}\n\n    private fun handleRcloneConfig''', re.S)
rep = '''    private fun handleTree(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        io.execute {
            try {
                if (IndexingService.isActive(this@MainActivity)) {
                    event("indexState", JSONObject(IndexingService.statusJson(this@MainActivity)))
                    return@execute
                }
                val resultUri = tree.findInTree(uri, "result.json")
                if (resultUri == null) {
                    event("importError", JSONObject().put("message", "No result.json found in the selected folder.")); return@execute
                }
                val name = displayName(uri) ?: "Telegram Archive"
                val id = db.createArchive(name, pendingSourceKind, uri.toString())
                db.setArchiveStatus(id, "indexing")
                IndexingService.startSaf(this@MainActivity, id, name, resultUri, uri.toString())
                event("archivesChanged", JSONArray(db.archiveJson()))
                event("indexState", JSONObject(IndexingService.statusJson(this@MainActivity)))
            } catch (e: Exception) {
                event("importError", JSONObject().put("message", e.message ?: "Import failed"))
            }
        }
    }

    private fun handleRcloneConfig'''
s, n = pat.subn(rep, s, count=1)
if n != 1:
    raise SystemExit('Could not replace MainActivity.handleTree')

# Do not let selecting another config interrupt/confuse an active index job.
s = s.replace('        @JavascriptInterface fun chooseRcloneConfig() {\n            runOnUiThread {', '''        @JavascriptInterface fun chooseRcloneConfig() {
            if (IndexingService.isActive(this@MainActivity)) {
                event("indexState", JSONObject(IndexingService.statusJson(this@MainActivity)))
                return
            }
            runOnUiThread {''', 1)

# Android Back: JS gets first chance. If on the home screen while indexing, minimize rather than destroy.
if 'override fun onBackPressed()' not in s:
    pos = s.rfind('    override fun onDestroy()')
    if pos < 0:
        raise SystemExit('MainActivity.onDestroy not found')
    back = '''    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.TARJSNativeBack ? String(window.TARJSNativeBack()) : 'false'") { result ->
            val handled = result?.contains("true") == true
            if (!handled) {
                if (IndexingService.isActive(this)) moveTaskToBack(true) else finish()
            }
        }
    }

'''
    s = s[:pos] + back + s[pos:]

main.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 5) Manifest: foreground data-sync service + completion notification permission.
# -----------------------------------------------------------------------------
manifest = root / 'app/src/main/AndroidManifest.xml'
s = manifest.read_text(encoding='utf-8')
if 'android.permission.FOREGROUND_SERVICE' not in s:
    s = s.replace('<uses-permission android:name="android.permission.INTERNET" />', '<uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />\n    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />')
if 'android:name=".IndexingService"' not in s:
    s = s.replace('        <activity\n            android:name=".MainActivity"', '        <service android:name=".IndexingService" android:exported="false" android:stopWithTask="false" android:foregroundServiceType="dataSync" />\n        <activity\n            android:name=".MainActivity"')
manifest.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 6) Web UI: persistent live progress card, real three-dot menu, safe Back handling.
# -----------------------------------------------------------------------------
js = root / 'app/src/main/assets/web/app.js'
s = js.read_text(encoding='utf-8')

# Append helpers instead of destabilizing the existing navigation functions.
if 'function indexStateCard(' not in s:
    s += r'''

// Persistent background-indexing UI -------------------------------------------------
let _lastIndexPhase='';
function indexState(){return j(TARJS.indexStatus(),{phase:'idle',processed:0,total:0,detected:0,detail:''})}
function indexStateCard(st){
  if(!st||st.phase==='idle')return'';
  const active=['preparing','counting','indexing'].includes(st.phase);
  const pct=st.total>0?Math.min(100,(st.processed*100/st.total)):0;
  const title=st.phase==='preparing'?'Preparing archive':st.phase==='counting'?'Detecting messages':st.phase==='indexing'?'Indexing messages':st.phase==='complete'?'Indexing complete':'Indexing stopped';
  const line=st.phase==='counting'?`${Number(st.detected||0).toLocaleString()} messages detected so far…`:st.phase==='indexing'?`${Number(st.processed||0).toLocaleString()} / ${Number(st.total||0).toLocaleString()} messages · ${pct.toFixed(1)}%`:(st.detail||'');
  return `<div class="index-card ${active?'active':''}"><div class="index-card-head"><b>${esc(title)}</b>${active?'<span class="spinner"></span>':''}</div><div class="index-detail">${esc(line)}</div>${st.phase==='indexing'&&st.total>0?`<div class="index-track"><div class="index-fill" style="width:${pct}%"></div></div>`:''}<small>${active?'You can leave TAR-JS; indexing continues in the background.':' '}</small></div>`
}
function mountIndexState(){
  const st=indexState();
  let host=document.getElementById('indexPersistent');
  if(!host){host=document.createElement('div');host.id='indexPersistent';document.body.appendChild(host)}
  host.innerHTML=indexStateCard(st);
  if(st.phase==='complete'&&_lastIndexPhase&&_lastIndexPhase!=='complete'){
    S.archives=j(TARJS.listArchives());
    if(st.archiveId>0)S.archive=S.archives.find(a=>a.id===st.archiveId)||S.archive;
    toast(st.detail||'Indexing complete');
    if(S.view==='home')render();
  }
  if(st.phase==='error'&&_lastIndexPhase&&_lastIndexPhase!=='error')toast(st.detail||'Indexing stopped');
  _lastIndexPhase=st.phase;
}
setInterval(mountIndexState,1000);setTimeout(mountIndexState,150);

function closeOverflow(){const x=document.getElementById('overflowMenu');if(x)x.remove()}
function openArchiveSettings(){closeOverflow();if(S.archive){S.view='settings';render()}else showAdd()}
function showOverflow(){
  closeOverflow();
  const st=indexState();
  const wrap=document.createElement('div');wrap.id='overflowMenu';wrap.className='overflow-backdrop';
  wrap.onclick=e=>{if(e.target===wrap)closeOverflow()};
  wrap.innerHTML=`<div class="overflow-sheet"><div class="overflow-grab"></div><button onclick="closeOverflow();mountIndexState()"><span>Indexing status</span><span>${['preparing','counting','indexing'].includes(st.phase)?'●':'›'}</span></button>${S.archive?'<button onclick="openArchiveSettings()"><span>Archive settings</span><span>›</span></button>':''}<button onclick="closeOverflow();showAdd()"><span>Add archive</span><span>＋</span></button><button onclick="closeOverflow();TARJS.chooseRcloneConfig()"><span>rclone config</span><span>›</span></button></div>`;
  document.body.appendChild(wrap)
}
const _menuBtn=document.getElementById('menuBtn');if(_menuBtn)_menuBtn.onclick=showOverflow;

window.TARJSNativeBack=function(){
  if(document.getElementById('overflowMenu')){closeOverflow();return true}
  if(S.view==='rcloneBrowser'&&S.rclone.path){const p=S.rclone.path.split('/').filter(Boolean);p.pop();openRclone(S.rclone.remote,p.join('/'));return true}
  if(S.view==='rclone'||S.view==='rcloneBrowser'||S.view==='rclonePassword'){S.view=S.archives.length?'add':'home';render();return true}
  if(S.view==='chat'||S.view==='search'||S.view==='settings'||S.view==='add'){S.view='home';render();return true}
  return false
};
const _backBtn=document.getElementById('backBtn');if(_backBtn)_backBtn.onclick=()=>window.TARJSNativeBack();
'''

# Handle native immediate index-state event too.
old = "window.TARJSEvent=(name,p)=>{"
if old in s and "name==='indexState'" not in s:
    s = s.replace(old, "window.TARJSEvent=(name,p)=>{if(name==='indexState'){mountIndexState();return}", 1)
js.write_text(s, encoding='utf-8')

css = root / 'app/src/main/assets/web/styles.css'
s = css.read_text(encoding='utf-8')
if '.index-card{' not in s:
    s += r'''

/* Android 15/16-safe header polish. Native WebView padding handles system bars. */
.topbar{height:64px;min-height:64px;padding:8px 12px;flex-shrink:0;box-shadow:0 1px 0 var(--line)}
.brand{overflow:hidden}.brand>div:last-child{min-width:0}.brand strong{line-height:1.2}.brand small{line-height:1.25}
main{height:calc(100% - 64px)}
#indexPersistent{position:fixed;left:12px;right:12px;bottom:calc(12px + env(safe-area-inset-bottom));z-index:70;pointer-events:none}
.index-card{max-width:720px;margin:0 auto;background:var(--surface);border:1px solid var(--line);border-radius:16px;padding:12px 14px;box-shadow:var(--shadow);pointer-events:auto}
.index-card.active{border-color:color-mix(in srgb,var(--accent) 45%,var(--line))}.index-card-head{display:flex;align-items:center;justify-content:space-between;gap:12px}.index-card-head .spinner{margin:0;flex:0 0 20px}.index-detail{margin-top:5px;color:var(--muted);font-variant-numeric:tabular-nums}.index-card small{display:block;margin-top:6px;color:var(--muted)}
.index-track{height:7px;background:var(--surface2);border-radius:999px;overflow:hidden;margin-top:9px}.index-fill{height:100%;background:var(--accent);border-radius:999px;transition:width .3s ease}
.overflow-backdrop{position:fixed;inset:0;z-index:90;background:rgba(0,0,0,.28);display:flex;align-items:flex-end;justify-content:center;padding:12px}.overflow-sheet{width:min(560px,100%);background:var(--surface);border:1px solid var(--line);border-radius:22px;padding:8px;box-shadow:var(--shadow)}.overflow-grab{width:38px;height:4px;border-radius:9px;background:var(--line);margin:3px auto 8px}.overflow-sheet button{width:100%;min-height:52px;border:0;border-top:1px solid var(--line);background:transparent;color:var(--text);display:flex;align-items:center;justify-content:space-between;padding:0 14px;font-size:15px;text-align:left}.overflow-sheet button:first-of-type{border-top:0}
'''
css.write_text(s, encoding='utf-8')

print('BACKGROUND_INDEXING_PROGRESS_HEADER_MENU_PATCH_APPLIED')
