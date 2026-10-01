package com.tarjs.app.core

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
    val mine: Boolean,
    val replyToId: Long? = null,
    val isForwarded: Boolean = false,
    val isEdited: Boolean = false,
    val isService: Boolean = false
)

data class ImportResult(
    val chats: Int,
    val messages: Int,
    val ownerId: String?,
    val error: String? = null,
    val deduped: Int = 0
)

/**
 * SQLite database for persistent Telegram archive indexing.
 * Handles full-account and single-chat exports with proper message semantics.
 * Supports resume/deduplication via message ID + chat ID uniqueness.
 */
class ArchiveDb(context: Context) : SQLiteOpenHelper(context, "tarjs_archive.db", null, 2) {
    
    override fun onCreate(db: SQLiteDatabase) {
        // Chats table: persistent chat list with group flag and owner metadata
        db.execSQL("""
            CREATE TABLE chats(
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                preview TEXT NOT NULL,
                count INTEGER NOT NULL,
                is_group INTEGER NOT NULL DEFAULT 0,
                owner TEXT,
                created_at INTEGER NOT NULL
            )
        """)
        
        // Messages table: full message semantics with reply/forward/edited/service flags
        db.execSQL("""
            CREATE TABLE messages(
                id INTEGER PRIMARY KEY,
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
                UNIQUE(chat_id, id)
            )
        """)
        
        // Indices for efficient chat-scoped queries and date sorting
        db.execSQL("CREATE INDEX idx_messages_chat_date ON messages(chat_id, date_unix)")
        db.execSQL("CREATE INDEX idx_messages_chat_search ON messages(chat_id, text)")
        db.execSQL("CREATE INDEX idx_messages_reply ON messages(chat_id, reply_to_id)")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Migrate to v2: add new columns
            try {
                db.execSQL("ALTER TABLE chats ADD COLUMN is_group INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chats ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN date_unix INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN reply_to_id INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN is_forwarded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN is_edited INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN is_service INTEGER NOT NULL DEFAULT 0")
            } catch (e: Exception) {
                // If columns already exist, continue
            }
        }
    }
    
    fun chats(): List<Chat> = readableDatabase.rawQuery(
        "SELECT id, title, preview, count, is_group, owner FROM chats ORDER BY id",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Chat(
                        id = c.getLong(0),
                        title = c.getString(1),
                        preview = c.getString(2),
                        count = c.getInt(3),
                        isGroup = c.getInt(4) != 0,
                        owner = c.getString(5)
                    )
                )
            }
        }
    }
    
    /**
     * Get messages in a chat, optionally filtered by text search.
     * Supports pagination via date_unix cursor for bidirectional loading.
     */
    fun messages(
        chatId: Long,
        query: String = "",
        olderThanDate: Long? = null,
        newerThanDate: Long? = null,
        limit: Int = 50
    ): List<Message> {
        val whereBuilder = mutableListOf("chat_id = ?")
        val args = mutableListOf(chatId.toString())
        
        if (query.isNotEmpty()) {
            whereBuilder.add("text LIKE ?")
            args.add("%$query%")
        }
        
        if (olderThanDate != null) {
            whereBuilder.add("date_unix < ?")
            args.add(olderThanDate.toString())
        }
        
        if (newerThanDate != null) {
            whereBuilder.add("date_unix > ?")
            args.add(newerThanDate.toString())
        }
        
        val where = whereBuilder.joinToString(" AND ")
        val orderBy = if (olderThanDate != null) "ORDER BY date_unix DESC" else "ORDER BY date_unix ASC"
        
        return readableDatabase.rawQuery(
            "SELECT id, chat_id, sender, text, date, mine, reply_to_id, is_forwarded, is_edited, is_service FROM messages WHERE $where $orderBy LIMIT ?",
            args.toTypedArray() + arrayOf(limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Message(
                            id = c.getLong(0),
                            chatId = c.getLong(1),
                            sender = c.getString(2),
                            text = c.getString(3),
                            date = c.getString(4),
                            mine = c.getInt(5) != 0,
                            replyToId = c.getLong(6).let { if (it == 0L) null else it },
                            isForwarded = c.getInt(7) != 0,
                            isEdited = c.getInt(8) != 0,
                            isService = c.getInt(9) != 0
                        )
                    )
                }
            }
        }
    }
    
    /**
     * Check if we have older messages available for a chat (for pagination).
     */
    fun hasOlderMessages(chatId: Long, beforeDate: Long): Boolean {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE chat_id = ? AND date_unix < ? LIMIT 1",
            arrayOf(chatId.toString(), beforeDate.toString())
        ).use { c ->
            c.moveToFirst() && c.getLong(0) > 0
        }
    }
    
    /**
     * Check if we have newer messages available for a chat (for pagination).
     */
    fun hasNewerMessages(chatId: Long, afterDate: Long): Boolean {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE chat_id = ? AND date_unix > ? LIMIT 1",
            arrayOf(chatId.toString(), afterDate.toString())
        ).use { c ->
            c.moveToFirst() && c.getLong(0) > 0
        }
    }
    
    fun clear() = writableDatabase.apply {
        beginTransaction()
        try {
            execSQL("DELETE FROM messages")
            execSQL("DELETE FROM chats")
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }
    
    /**
     * Import Telegram result.json, handling full-account and single-chat exports.
     * Deduplicates messages by (chat_id, message_id) to support resume on re-import.
     * Validates archive structure and returns explicit errors for invalid/empty archives.
     */
    fun importJson(text: String, progress: (done: Int, total: Int) -> Unit): ImportResult {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return ImportResult(0, 0, null, "Invalid JSON: ${e.message}")
        }
        
        // Detect archive type: full-account or single-chat
        val chatsToProcess = mutableListOf<JSONObject>()
        
        // Full account export: chats.list
        root.optJSONObject("chats")?.optJSONArray("list")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { chatsToProcess.add(it) }
            }
        }
        
        // Single-chat export: root has messages + id/name at top level
        if (chatsToProcess.isEmpty() && root.has("messages")) {
            chatsToProcess.add(root)
        }
        
        // Reject empty/invalid archives
        if (chatsToProcess.isEmpty()) {
            return ImportResult(
                0, 0, null,
                "No Telegram chats found. Check that this is a valid result.json export."
            )
        }
        
        // Extract owner ID if available (full-account only)
        val ownerId = root.optJSONObject("personal_information")
            ?.optJSONObject("user_information")
            ?.optString("user_id")
            ?.takeIf { it.isNotBlank() }
        
        val totalMessages = chatsToProcess.sumOf { it.optJSONArray("messages")?.length() ?: 0 }
        var processedMessages = 0
        var deduped = 0
        
        writableDatabase.beginTransaction()
        try {
            for (chatObj in chatsToProcess) {
                val chatId = chatObj.optLong("id", chatsToProcess.indexOf(chatObj).toLong() + 1)
                val chatTitle = chatObj.optString("name", "Chat $chatId")
                val isGroup = chatObj.optString("type") == "private_supergroup" || 
                              chatObj.optString("type") == "supergroup"
                
                val messagesArr = chatObj.optJSONArray("messages") ?: JSONArray()
                val messages = mutableListOf<Message>()
                
                for (i in 0 until messagesArr.length()) {
                    val msgObj = messagesArr.optJSONObject(i) ?: continue
                    
                    val messageId = msgObj.optLong("id", i.toLong())
                    val senderId = msgObj.optString("from_id", msgObj.optString("from", "Unknown"))
                    val senderName = msgObj.optString("from", "Unknown")
                    val text = extractMessageText(msgObj)
                    val date = msgObj.optString("date", "")
                    val dateUnix = msgObj.optLong("date_unixtime", 0L)
                    
                    val isMine = when {
                        ownerId != null -> senderId == ownerId
                        else -> senderName == chatTitle // Fallback: assume chat name = owner in single export
                    }
                    
                    val replyToId = msgObj.optJSONObject("reply_to_message")
                        ?.optLong("id")?.let { if (it > 0) it else null }
                    
                    val isForwarded = msgObj.has("forwarded_from") || msgObj.has("forward_from")
                    val isEdited = msgObj.has("edit_date")
                    val isService = msgObj.optString("type") == "service"
                    
                    messages.add(
                        Message(
                            id = messageId,
                            chatId = chatId,
                            sender = senderName,
                            text = text,
                            date = date,
                            mine = isMine,
                            replyToId = replyToId,
                            isForwarded = isForwarded,
                            isEdited = isEdited,
                            isService = isService
                        )
                    )
                }
                
                val preview = messages.lastOrNull()?.text?.take(100) ?: ""
                
                // Use INSERT OR REPLACE to handle deduplication via UNIQUE(chat_id, id)
                writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO chats(id, title, preview, count, is_group, owner, created_at) VALUES(?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(chatId, chatTitle, preview, messages.size, if (isGroup) 1 else 0, ownerId ?: "", System.currentTimeMillis())
                )
                
                for (msg in messages) {
                    try {
                        writableDatabase.execSQL(
                            """INSERT OR IGNORE INTO messages(
                                id, chat_id, sender, text, date, date_unix, mine,
                                reply_to_id, is_forwarded, is_edited, is_service
                            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                            arrayOf(
                                msg.id, msg.chatId, msg.sender, msg.text, msg.date, msg.date,
                                if (msg.mine) 1 else 0, msg.replyToId ?: 0, 
                                if (msg.isForwarded) 1 else 0,
                                if (msg.isEdited) 1 else 0,
                                if (msg.isService) 1 else 0
                            )
                        )
                        processedMessages++
                    } catch (e: Exception) {
                        deduped++
                    }
                }
                
                progress(processedMessages, totalMessages)
            }
            
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        
        return ImportResult(
            chats = chatsToProcess.size,
            messages = processedMessages,
            ownerId = ownerId,
            deduped = deduped
        )
    }
    
    private fun extractMessageText(msgObj: JSONObject): String {
        // Handle rich text arrays (Telegram exports use text_entities)
        val text = msgObj.opt("text")
        return when (text) {
            is String -> text
            is JSONArray -> text.buildString {
                for (i in 0 until text.length()) {
                    val part = text.optJSONObject(i) ?: continue
                    append(part.optString("text", ""))
                }
            }
            else -> ""
        }
    }
}
