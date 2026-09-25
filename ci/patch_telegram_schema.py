from pathlib import Path

p = Path("tarjs/app/src/main/java/com/tarjs/archive/TelegramImporter.kt")
s = p.read_text()

start = s.index("    fun import(archiveId: Long, input: InputStream")
end = s.index("\n    private fun parseChat", start)

new_import = r'''    fun import(archiveId: Long, input: InputStream, progress: (String) -> Unit = {}): Result {
        db.clearArchive(archiveId)
        db.beginBulkImport()
        var success = false
        try {
            var chatCount = 0
            var messageCount = 0
            var sawChatsContainer = false
            var topId: String? = null
            var topName = "Telegram chat"
            var topType: String? = null
            var topAvatar: String? = null
            var topChatId: Long? = null
            var topMessageCount = 0
            var topLastDate = 0L

            progress("Reading Telegram result.json…")
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                r.isLenient = true
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "chats" -> {
                            sawChatsContainer = true
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
                                                if (chatCount == 1 || chatCount % 10 == 0) {
                                                    progress("Indexed $chatCount chats · $messageCount messages")
                                                }
                                            }
                                        }
                                        r.endArray()
                                    }
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                        }

                        // Telegram Desktop single-chat export:
                        // { "name": ..., "type": ..., "id": ..., "messages": [...] }
                        "id" -> topId = readScalarAsString(r)
                        "name" -> topName = readScalarAsString(r) ?: topName
                        "type" -> topType = readScalarAsString(r)
                        "photo" -> topAvatar = parsePhotoObject(r)
                        "messages" -> {
                            if (topChatId == null) {
                                topChatId = db.insertChat(archiveId, topId, topName, topType, topAvatar)
                                progress("Indexing $topName")
                            }
                            r.beginArray()
                            while (r.hasNext()) {
                                val m = parseMessage(r, archiveId, topChatId!!, topName)
                                if (m != null) {
                                    db.insertMessage(m)
                                    topMessageCount++
                                    if (m.dateUnix > topLastDate) topLastDate = m.dateUnix
                                    if (topMessageCount == 1 || topMessageCount % 1000 == 0) {
                                        progress("Indexed $topMessageCount messages")
                                    }
                                }
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }

            if (!sawChatsContainer && topChatId != null) {
                db.updateChatStats(topChatId!!, topMessageCount, topLastDate)
                chatCount = 1
                messageCount = topMessageCount
            }

            if (chatCount == 0 && messageCount == 0) {
                throw IllegalArgumentException(
                    "Telegram JSON was read but no chats/messages were found. " +
                    "This export layout is not supported yet."
                )
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
s = s[:start] + new_import + s[end:]
p.write_text(s)
