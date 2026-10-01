package com.tarjs.app.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class Chat(
    val id: Long,
    val title: String,
    val preview: String,
    val count: Int,
    val isGroup: Boolean = false,
    val owner: String? = null
)

data class Message(
    val id: Long,
    val chatId: Long,
    val sender: String,
    val text: String,
    val date: String,
    val dateUnix: Long,
    val mine: Boolean,
    val replyToId: Long? = null,
    val isForwarded: Boolean = false,
    val isEdited: Boolean = false,
    val isService: Boolean = false,
    val mediaPath: String? = null,
    val mediaType: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val durationSeconds: Long? = null
)

data class ImportResult(
    val chats: Int,
    val messages: Int,
    val ownerId: String?,
    val error: String? = null,
    val deduped: Int = 0
)

data class AvatarPreference(
    val imagePath: String,
    val zoom: Float = 1.0f,
    val offsetX: Float = 0.0f,
    val offsetY: Float = 0.0f
)

/**
 * Persistent Telegram archive database.
 *
 * v3 fixes the old global-message-id primary key by storing a row id separately
 * and enforcing uniqueness on (chat_id, message_id), and adds per-chat UI
 * preferences plus media metadata needed by the native renderer.
 */
class ArchiveDb(context: Context) : SQLiteOpenHelper(context, "tarjs_archive.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        createChats(db)
        createMessages(db)
        createPreferences(db)
        createIndexes(db)
    }

    private fun createChats(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chats(
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                preview TEXT NOT NULL,
                count INTEGER NOT NULL,
                is_group INTEGER NOT NULL DEFAULT 0,
                owner TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createMessages(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages(
                row_id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id INTEGER NOT NULL,
                chat_id INTEGER NOT NULL,
                sender TEXT NOT NULL,
                text TEXT NOT NULL,
                date TEXT NOT NULL,
                date_unix INTEGER NOT NULL,
                mine INTEGER NOT NULL,
                reply_to_id INTEGER,
                is_forwarded INTEGER NOT NULL DEFAULT 0,
                is_edited INTEGER NOT NULL DEFAULT 0,
                is_service INTEGER NOT NULL DEFAULT 0,
                media_path TEXT,
                media_type TEXT,
                file_name TEXT,
                mime_type TEXT,
                duration_seconds INTEGER,
                UNIQUE(chat_id, message_id)
            )
            """.trimIndent()
        )
    }

    private fun createPreferences(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_preferences(
                chat_id INTEGER PRIMARY KEY,
                swap_sides INTEGER NOT NULL DEFAULT 0,
                custom_name TEXT,
                custom_avatar_path TEXT,
                avatar_zoom REAL NOT NULL DEFAULT 1.0,
                avatar_offset_x REAL NOT NULL DEFAULT 0.0,
                avatar_offset_y REAL NOT NULL DEFAULT 0.0
            )
            """.trimIndent()
        )
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_chat_date ON messages(chat_id, date_unix)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_chat_search ON messages(chat_id, text)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_reply ON messages(chat_id, reply_to_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            listOf(
                "ALTER TABLE chats ADD COLUMN is_group INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE chats ADD COLUMN owner TEXT",
                "ALTER TABLE chats ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE messages ADD COLUMN date_unix INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE messages ADD COLUMN reply_to_id INTEGER",
                "ALTER TABLE messages ADD COLUMN is_forwarded INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE messages ADD COLUMN is_edited INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE messages ADD COLUMN is_service INTEGER NOT NULL DEFAULT 0"
            ).forEach { sql -> runCatching { db.execSQL(sql) } }
        }

        if (oldVersion < 3) {
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE messages RENAME TO messages_legacy")
                createMessages(db)
                val legacyColumns = mutableSetOf<String>()
                db.rawQuery("PRAGMA table_info(messages_legacy)", null).use { c ->
                    val nameIndex = c.getColumnIndex("name")
                    while (c.moveToNext()) legacyColumns += c.getString(nameIndex)
                }
                fun expr(name: String, fallback: String): String = if (name in legacyColumns) name else fallback
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO messages(
                        message_id, chat_id, sender, text, date, date_unix, mine,
                        reply_to_id, is_forwarded, is_edited, is_service,
                        media_path, media_type, file_name, mime_type, duration_seconds
                    )
                    SELECT
                        ${expr("id", "rowid")}, ${expr("chat_id", "0")}, ${expr("sender", "''")},
                        ${expr("text", "''")}, ${expr("date", "''")}, ${expr("date_unix", "0")},
                        ${expr("mine", "0")}, ${expr("reply_to_id", "NULL")},
                        ${expr("is_forwarded", "0")}, ${expr("is_edited", "0")}, ${expr("is_service", "0")},
                        NULL, NULL, NULL, NULL, NULL
                    FROM messages_legacy
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE messages_legacy")
                createPreferences(db)
                createIndexes(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun chats(): List<Chat> = readableDatabase.rawQuery(
        """
        SELECT c.id,
               COALESCE(NULLIF(p.custom_name, ''), c.title) AS display_title,
               c.preview, c.count, c.is_group, c.owner
        FROM chats c
        LEFT JOIN chat_preferences p ON p.chat_id = c.id
        ORDER BY c.id
        """.trimIndent(), null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(Chat(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4) != 0, c.getString(5)))
            }
        }
    }

    fun messages(
        chatId: Long,
        query: String = "",
        olderThanDate: Long? = null,
        newerThanDate: Long? = null,
        limit: Int = 50
    ): List<Message> {
        require(olderThanDate == null || newerThanDate == null) { "Use only one paging direction at a time" }
        val where = mutableListOf("chat_id = ?")
        val args = mutableListOf(chatId.toString())
        if (query.isNotBlank()) {
            where += "text LIKE ? COLLATE NOCASE"
            args += "%${query.trim()}%"
        }
        olderThanDate?.let { where += "date_unix < ?"; args += it.toString() }
        newerThanDate?.let { where += "date_unix > ?"; args += it.toString() }
        val descending = olderThanDate != null
        val order = if (descending) "date_unix DESC, message_id DESC" else "date_unix ASC, message_id ASC"
        val rows = queryMessages(
            "SELECT ${messageColumns()} FROM messages WHERE ${where.joinToString(" AND ")} ORDER BY $order LIMIT ?",
            args + limit.coerceIn(1, 500).toString()
        )
        return if (descending) rows.reversed() else rows
    }

    fun searchMessages(chatId: Long, query: String, limit: Int = 100): List<Message> {
        if (query.isBlank()) return emptyList()
        return messages(chatId = chatId, query = query, limit = limit.coerceIn(1, 300))
    }

    fun messageDate(chatId: Long, messageId: Long): Long? = readableDatabase.rawQuery(
        "SELECT date_unix FROM messages WHERE chat_id=? AND message_id=? LIMIT 1",
        arrayOf(chatId.toString(), messageId.toString())
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    fun messageWindow(chatId: Long, messageId: Long, before: Int = 40, after: Int = 40): List<Message> {
        val targetDate = messageDate(chatId, messageId) ?: return emptyList()
        val olderAndTarget = queryMessages(
            "SELECT ${messageColumns()} FROM messages WHERE chat_id=? AND date_unix<=? ORDER BY date_unix DESC, message_id DESC LIMIT ?",
            listOf(chatId.toString(), targetDate.toString(), (before + 1).coerceAtLeast(1).toString())
        ).reversed()
        val newer = messages(chatId, newerThanDate = targetDate, limit = after)
        return (olderAndTarget + newer).distinctBy { it.id }
    }

    fun hasOlderMessages(chatId: Long, beforeDate: Long): Boolean = exists(
        "SELECT 1 FROM messages WHERE chat_id=? AND date_unix<? LIMIT 1",
        arrayOf(chatId.toString(), beforeDate.toString())
    )

    fun hasNewerMessages(chatId: Long, afterDate: Long): Boolean = exists(
        "SELECT 1 FROM messages WHERE chat_id=? AND date_unix>? LIMIT 1",
        arrayOf(chatId.toString(), afterDate.toString())
    )

    private fun exists(sql: String, args: Array<String>): Boolean = readableDatabase.rawQuery(sql, args).use { it.moveToFirst() }

    private fun messageColumns(): String = "message_id,chat_id,sender,text,date,date_unix,mine,reply_to_id,is_forwarded,is_edited,is_service,media_path,media_type,file_name,mime_type,duration_seconds"

    private fun queryMessages(sql: String, args: List<String>): List<Message> = readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Message(
                        id = c.getLong(0), chatId = c.getLong(1), sender = c.getString(2), text = c.getString(3),
                        date = c.getString(4), dateUnix = c.getLong(5), mine = c.getInt(6) != 0,
                        replyToId = if (c.isNull(7)) null else c.getLong(7), isForwarded = c.getInt(8) != 0,
                        isEdited = c.getInt(9) != 0, isService = c.getInt(10) != 0,
                        mediaPath = if (c.isNull(11)) null else c.getString(11),
                        mediaType = if (c.isNull(12)) null else c.getString(12),
                        fileName = if (c.isNull(13)) null else c.getString(13),
                        mimeType = if (c.isNull(14)) null else c.getString(14),
                        durationSeconds = if (c.isNull(15)) null else c.getLong(15)
                    )
                )
            }
        }
    }

    fun getSwapSides(chatId: Long): Boolean = readableDatabase.rawQuery(
        "SELECT swap_sides FROM chat_preferences WHERE chat_id=?", arrayOf(chatId.toString())
    ).use { c -> c.moveToFirst() && c.getInt(0) != 0 }

    fun setSwapSides(chatId: Long, swapped: Boolean) {
        upsertPreference(chatId, ContentValues().apply { put("swap_sides", if (swapped) 1 else 0) })
    }

    fun getCustomChatName(chatId: Long): String? = readableDatabase.rawQuery(
        "SELECT custom_name FROM chat_preferences WHERE chat_id=?", arrayOf(chatId.toString())
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

    fun setCustomChatName(chatId: Long, name: String) {
        upsertPreference(chatId, ContentValues().apply { put("custom_name", name.trim().takeIf { it.isNotBlank() }) })
    }

    fun getAvatarPreference(chatId: Long): AvatarPreference? = readableDatabase.rawQuery(
        "SELECT custom_avatar_path,avatar_zoom,avatar_offset_x,avatar_offset_y FROM chat_preferences WHERE chat_id=?",
        arrayOf(chatId.toString())
    ).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) null
        else AvatarPreference(c.getString(0), c.getFloat(1), c.getFloat(2), c.getFloat(3))
    }

    fun setAvatarPreference(chatId: Long, pref: AvatarPreference) {
        upsertPreference(chatId, ContentValues().apply {
            put("custom_avatar_path", pref.imagePath)
            put("avatar_zoom", pref.zoom.coerceAtLeast(1f))
            put("avatar_offset_x", pref.offsetX.coerceIn(-1f, 1f))
            put("avatar_offset_y", pref.offsetY.coerceIn(-1f, 1f))
        })
    }

    fun clearAvatarPreference(chatId: Long) {
        upsertPreference(chatId, ContentValues().apply {
            putNull("custom_avatar_path")
            put("avatar_zoom", 1f)
            put("avatar_offset_x", 0f)
            put("avatar_offset_y", 0f)
        })
    }

    private fun upsertPreference(chatId: Long, values: ContentValues) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO chat_preferences(chat_id) VALUES(?)", arrayOf(chatId))
        db.update("chat_preferences", values, "chat_id=?", arrayOf(chatId.toString()))
    }

    fun clear() = writableDatabase.apply {
        beginTransaction()
        try {
            execSQL("DELETE FROM messages")
            execSQL("DELETE FROM chats")
            execSQL("DELETE FROM chat_preferences")
            setTransactionSuccessful()
        } finally { endTransaction() }
    }

    fun importJson(text: String, progress: (done: Int, total: Int) -> Unit): ImportResult {
        val root = try { JSONObject(text) } catch (e: Exception) {
            return ImportResult(0, 0, null, "Invalid JSON: ${e.message}")
        }
        val chatsToProcess = mutableListOf<JSONObject>()
        root.optJSONObject("chats")?.optJSONArray("list")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(chatsToProcess::add)
        }
        if (chatsToProcess.isEmpty() && root.optJSONArray("messages") != null) chatsToProcess += root
        if (chatsToProcess.isEmpty()) return ImportResult(0, 0, null, "No Telegram chats found. Check that this is a valid result.json export.")

        val personal = root.optJSONObject("personal_information")
        val ownerId = personal?.optString("user_id")?.takeIf(String::isNotBlank)
            ?: personal?.optJSONObject("user_information")?.optString("user_id")?.takeIf(String::isNotBlank)
        val ownerName = personal?.optString("first_name")?.takeIf(String::isNotBlank)
        val totalMessages = chatsToProcess.sumOf { it.optJSONArray("messages")?.length() ?: 0 }
        var processed = 0
        var deduped = 0

        val db = writableDatabase
        db.beginTransaction()
        try {
            chatsToProcess.forEachIndexed { chatIndex, chatObj ->
                val chatId = chatObj.optLong("id", chatIndex.toLong() + 1L)
                val chatTitle = chatObj.optString("name", "Chat $chatId")
                val type = chatObj.optString("type")
                val isGroup = type.contains("group", ignoreCase = true) || type.contains("channel", ignoreCase = true)
                val arr = chatObj.optJSONArray("messages") ?: JSONArray()
                var lastPreview = ""

                for (i in 0 until arr.length()) {
                    val msg = arr.optJSONObject(i) ?: continue
                    val messageId = msg.optLong("id", i.toLong() + 1L)
                    val senderId = msg.optString("from_id", "")
                    val senderName = msg.optString("from", "Unknown")
                    val messageText = extractMessageText(msg)
                    val date = msg.optString("date", "")
                    val dateUnix = msg.optLong("date_unixtime", 0L).takeIf { it > 0L } ?: parseDateUnix(date)
                    val mine = when {
                        ownerId != null -> senderId == ownerId
                        ownerName != null -> senderName.equals(ownerName, ignoreCase = true)
                        else -> senderName.equals(chatTitle, ignoreCase = true)
                    }
                    val replyTo = when {
                        msg.has("reply_to_message_id") -> msg.optLong("reply_to_message_id").takeIf { it > 0 }
                        else -> msg.optJSONObject("reply_to_message")?.optLong("id")?.takeIf { it > 0 }
                    }
                    val mediaPath = msg.optString("photo").takeIf(String::isNotBlank)
                        ?: msg.optString("file").takeIf(String::isNotBlank)
                    val mime = msg.optString("mime_type").takeIf(String::isNotBlank)
                    val mediaType = msg.optString("media_type").takeIf(String::isNotBlank)
                        ?: inferMediaType(mediaPath, mime)
                    val fileName = msg.optString("file_name").takeIf(String::isNotBlank)
                        ?: mediaPath?.substringAfterLast('/')
                    val values = ContentValues().apply {
                        put("message_id", messageId); put("chat_id", chatId); put("sender", senderName); put("text", messageText)
                        put("date", date); put("date_unix", dateUnix); put("mine", if (mine) 1 else 0)
                        if (replyTo != null) put("reply_to_id", replyTo) else putNull("reply_to_id")
                        put("is_forwarded", if (msg.has("forwarded_from") || msg.has("forward_from")) 1 else 0)
                        put("is_edited", if (msg.has("edit_date") || msg.has("edited")) 1 else 0)
                        put("is_service", if (msg.optString("type") == "service") 1 else 0)
                        if (mediaPath != null) put("media_path", mediaPath) else putNull("media_path")
                        if (mediaType != null) put("media_type", mediaType) else putNull("media_type")
                        if (fileName != null) put("file_name", fileName) else putNull("file_name")
                        if (mime != null) put("mime_type", mime) else putNull("mime_type")
                        if (msg.has("duration_seconds")) put("duration_seconds", msg.optLong("duration_seconds")) else putNull("duration_seconds")
                    }
                    val row = db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    if (row == -1L) deduped++
                    processed++
                    if (messageText.isNotBlank()) lastPreview = messageText.take(100)
                    if (processed == totalMessages || processed % 1000 == 0) progress(processed, totalMessages)
                }

                db.execSQL(
                    "INSERT OR REPLACE INTO chats(id,title,preview,count,is_group,owner,created_at) VALUES(?,?,?,?,?,?,?)",
                    arrayOf(chatId, chatTitle, lastPreview, arr.length(), if (isGroup) 1 else 0, ownerId ?: ownerName ?: "", System.currentTimeMillis())
                )
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (totalMessages == 0) return ImportResult(chatsToProcess.size, 0, ownerId, "Telegram export contains no messages", deduped)
        return ImportResult(chatsToProcess.size, processed, ownerId, deduped = deduped)
    }

    private fun parseDateUnix(date: String): Long = runCatching {
        java.time.OffsetDateTime.parse(date).toEpochSecond()
    }.getOrDefault(0L)

    private fun inferMediaType(path: String?, mime: String?): String? {
        val p = path?.lowercase().orEmpty()
        val m = mime?.lowercase().orEmpty()
        return when {
            p.endsWith(".tgs") -> "sticker"
            p.endsWith(".webp") -> "sticker"
            p.endsWith(".webm") && p.contains("sticker") -> "sticker"
            m.startsWith("image/") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") -> "photo"
            m.startsWith("video/") || p.endsWith(".mp4") || p.endsWith(".webm") -> "video"
            m.startsWith("audio/") || p.endsWith(".ogg") || p.endsWith(".mp3") -> if (p.contains("voice")) "voice" else "audio"
            path != null -> "file"
            else -> null
        }
    }

    private fun extractMessageText(msgObj: JSONObject): String = when (val text = msgObj.opt("text")) {
        is String -> text
        is JSONArray -> buildString {
            for (i in 0 until text.length()) {
                when (val part = text.opt(i)) {
                    is String -> append(part)
                    is JSONObject -> append(part.optString("text", ""))
                }
            }
        }
        else -> ""
    }
}
