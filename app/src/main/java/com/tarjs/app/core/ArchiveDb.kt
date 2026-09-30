package com.tarjs.app.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class Chat(val id: Long, val title: String, val preview: String, val count: Int)
data class Message(val id: Long, val chatId: Long, val sender: String, val text: String, val date: String, val mine: Boolean)

data class ImportResult(val chats: Int, val messages: Int, val ownerId: String?, val error: String? = null)

class ArchiveDb(context: Context) : SQLiteOpenHelper(context, "tarjs_archive.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats(id INTEGER PRIMARY KEY,title TEXT NOT NULL,preview TEXT NOT NULL,count INTEGER NOT NULL,owner TEXT)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY,chat_id INTEGER NOT NULL,sender TEXT NOT NULL,text TEXT NOT NULL,date TEXT NOT NULL,mine INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX messages_chat_date ON messages(chat_id,date)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { db.execSQL("DROP TABLE IF EXISTS messages"); db.execSQL("DROP TABLE IF EXISTS chats"); onCreate(db) }
    fun chats(): List<Chat> = readableDatabase.rawQuery("SELECT id,title,preview,count FROM chats ORDER BY id", null).use { c -> buildList { while(c.moveToNext()) add(Chat(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3))) } }
    fun messages(chatId: Long, query: String = ""): List<Message> = readableDatabase.rawQuery("SELECT id,chat_id,sender,text,date,mine FROM messages WHERE chat_id=? AND text LIKE ? ORDER BY date,id", arrayOf(chatId.toString(), "%$query%")).use { c -> buildList { while(c.moveToNext()) add(Message(c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5)==1)) } }
    fun clear() = writableDatabase.apply { beginTransaction(); try { execSQL("DELETE FROM messages"); execSQL("DELETE FROM chats"); setTransactionSuccessful() } finally { endTransaction() } }
    fun importJson(text: String, progress: (Int,Int)->Unit): ImportResult {
        val root = try { JSONObject(text) } catch (e: Exception) { return ImportResult(0,0,null,"This is not valid JSON") }
        val chats = mutableListOf<JSONObject>()
        root.optJSONObject("chats")?.optJSONArray("list")?.let { for(i in 0 until it.length()) chats += it.optJSONObject(i) ?: JSONObject() }
        if (chats.isEmpty() && root.has("messages")) chats += root
        if (chats.isEmpty()) return ImportResult(0,0,null,"No Telegram chats were found in this export")
        val ownerId = root.optJSONObject("personal_information")?.optJSONObject("user_information")?.optString("user_id")?.takeIf { it.isNotBlank() }
        val total = chats.sumOf { it.optJSONArray("messages")?.length() ?: 0 }; var done=0
        writableDatabase.beginTransaction()
        try {
            for(chat in chats) {
                val id = chat.optLong("id", chat.optLong("id", chats.indexOf(chat).toLong()+1)); val title=chat.optString("name", "Telegram chat")
                val arr=chat.optJSONArray("messages") ?: JSONArray(); val msgs=mutableListOf<Message>()
                for(i in 0 until arr.length()) { val m=arr.optJSONObject(i) ?: continue; val sender=m.optString("from",m.optString("from_id","Unknown")); val body=when(val x=m.opt("text")){ is String->x; is JSONArray->(0 until x.length()).joinToString(""){ x.optJSONObject(it)?.optString("text") ?: x.optString(it) }; else->"" }; val mid=m.optLong("id", done.toLong()+1); val mine=ownerId != null && (m.optString("from_id")==ownerId || m.optString("from")==ownerId); msgs += Message(mid,id,sender,body,m.optString("date"),mine); done++; if(done%100==0) progress(done,total) }
                val preview=msgs.lastOrNull()?.text.orEmpty(); writableDatabase.execSQL("INSERT OR REPLACE INTO chats(id,title,preview,count,owner) VALUES(?,?,?,?,?)", arrayOf(id,title,preview,msgs.size,ownerId)); msgs.forEach { writableDatabase.execSQL("INSERT OR REPLACE INTO messages(id,chat_id,sender,text,date,mine) VALUES(?,?,?,?,?,?)", arrayOf(it.id,it.chatId,it.sender,it.text,it.date,if(it.mine)1 else 0)) }
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        progress(done,total); return ImportResult(chats.size,done,ownerId)
    }
}
