from pathlib import Path

p = Path('tarjs/app/src/main/java/com/tarjs/archive/ArchiveDatabase.kt')
s = p.read_text()
needle = '''    fun clearArchive(archiveId: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("message_fts", "message_db_id IN (SELECT id FROM messages WHERE archive_id=?)", arrayOf(archiveId.toString()))
            writableDatabase.delete("messages", "archive_id=?", arrayOf(archiveId.toString()))
            writableDatabase.delete("chats", "archive_id=?", arrayOf(archiveId.toString()))
            writableDatabase.delete("media_cache", "archive_id=?", arrayOf(archiveId.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
'''
if 'fun beginBulkImport()' not in s:
    if needle not in s: raise SystemExit('clearArchive block not found')
    s = s.replace(needle, needle + '''
    fun beginBulkImport() { writableDatabase.beginTransaction() }

    fun endBulkImport(success: Boolean) {
        try { if (success) writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
    }
''')
p.write_text(s)

p = Path('tarjs/app/src/main/java/com/tarjs/archive/TelegramImporter.kt')
s = p.read_text()
old = '''    fun import(archiveId: Long, input: InputStream, progress: (String) -> Unit = {}): Result {
        db.clearArchive(archiveId)
        var chatCount = 0
        var messageCount = 0
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "chats" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "list" -> {
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        val added = parseChat(r, archiveId, progress)
                                        if (added >= 0) {
                                            chatCount++
                                            messageCount += added
                                        }
                                    }
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
        db.updateArchiveStats(archiveId, chatCount, messageCount)
        return Result(chatCount, messageCount)
    }
'''
new = '''    fun import(archiveId: Long, input: InputStream, progress: (String) -> Unit = {}): Result {
        db.clearArchive(archiveId)
        db.beginBulkImport()
        var success = false
        try {
            var chatCount = 0
            var messageCount = 0
            progress("Reading Telegram result.json…")
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                r.isLenient = true
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "chats" -> {
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "list" -> {
                                        r.beginArray()
                                        while (r.hasNext()) {
                                            val added = parseChat(r, archiveId, progress)
                                            if (added >= 0) {
                                                chatCount++
                                                messageCount += added
                                                if (chatCount == 1 || chatCount % 10 == 0) progress("Indexed $chatCount chats · $messageCount messages")
                                            }
                                        }
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
            db.updateArchiveStats(archiveId, chatCount, messageCount)
            success = true
            progress("Indexed $chatCount chats · $messageCount messages")
            return Result(chatCount, messageCount)
        } finally {
            db.endBulkImport(success)
        }
    }
'''
if old not in s: raise SystemExit('TelegramImporter import block not found')
p.write_text(s.replace(old,new))
