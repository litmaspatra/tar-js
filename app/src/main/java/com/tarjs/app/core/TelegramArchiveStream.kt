package com.tarjs.app.core

import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.PushbackReader

internal data class TelegramArchiveInfo(
    val ownerId: String?,
    val ownerName: String?,
    val chats: List<TelegramChatInfo>,
    val totalMessages: Int
)

internal data class TelegramChatInfo(
    val id: Long,
    val title: String,
    val type: String,
    val messageCount: Int
)

internal data class TelegramMessageRecord(
    val id: Long,
    val senderId: String,
    val senderName: String,
    val text: String,
    val date: String,
    val dateUnix: Long,
    val replyToId: Long?,
    val isForwarded: Boolean,
    val isEdited: Boolean,
    val isService: Boolean,
    val mediaPath: String?,
    val mediaType: String?,
    val fileName: String?,
    val mimeType: String?,
    val durationSeconds: Long?
)

/**
 * Two-pass streaming Telegram JSON reader. It never builds the archive or a
 * chat's messages in memory, so multi-gigabyte exports stay within Android's
 * heap limit. The first pass gathers small chat metadata and an exact message
 * count; the second pass emits one message at a time.
 */
internal object TelegramArchiveStream {
    fun inspect(file: File): TelegramArchiveInfo {
        require(file.isFile && file.length() > 0L) { "result.json is missing or empty" }
        var ownerId: String? = null
        var ownerName: String? = null
        val chats = mutableListOf<TelegramChatInfo>()
        var rootId = 1L
        var rootTitle = "Telegram chat"
        var rootType = "personal_chat"
        var rootMessageCount: Int? = null

        open(file).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "personal_information" -> {
                        val personal = readPersonalInformation(reader)
                        ownerId = personal.first ?: ownerId
                        ownerName = personal.second ?: ownerName
                    }
                    "chats" -> readChatsForInspection(reader, chats)
                    "messages" -> rootMessageCount = countArray(reader)
                    "id" -> rootId = readLong(reader, rootId)
                    "name" -> rootTitle = readString(reader) ?: rootTitle
                    "type" -> rootType = readString(reader) ?: rootType
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        if (chats.isEmpty() && rootMessageCount != null) {
            chats += TelegramChatInfo(rootId, rootTitle, rootType, rootMessageCount!!)
        }
        require(chats.isNotEmpty()) { "This JSON does not contain Telegram chats or messages" }
        val total = chats.sumOf(TelegramChatInfo::messageCount)
        require(total > 0) { "Telegram export contains no messages" }
        return TelegramArchiveInfo(ownerId, ownerName, chats, total)
    }

    fun stream(
        file: File,
        info: TelegramArchiveInfo,
        onChatStart: (TelegramChatInfo) -> Unit,
        onMessage: (TelegramChatInfo, TelegramMessageRecord) -> Unit,
        onChatEnd: (TelegramChatInfo) -> Unit
    ) {
        var chatIndex = 0
        open(file).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "chats" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "list") {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val chat = info.chats.getOrNull(chatIndex++)
                                        ?: error("Archive chat metadata changed while importing")
                                    streamChat(reader, chat, onChatStart, onMessage, onChatEnd)
                                }
                                reader.endArray()
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    "messages" -> {
                        val chat = info.chats.first()
                        onChatStart(chat)
                        streamMessages(reader, chat, onMessage)
                        onChatEnd(chat)
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
    }

    private fun readChatsForInspection(reader: JsonReader, chats: MutableList<TelegramChatInfo>) {
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "list") {
                reader.beginArray()
                while (reader.hasNext()) chats += inspectChat(reader, chats.size)
                reader.endArray()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun inspectChat(reader: JsonReader, index: Int): TelegramChatInfo {
        var id = index.toLong() + 1L
        var title = "Chat $id"
        var type = ""
        var count = 0
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = readLong(reader, id)
                "name" -> title = readString(reader) ?: title
                "type" -> type = readString(reader) ?: type
                "messages" -> count = countArray(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return TelegramChatInfo(id, title, type, count)
    }

    private fun streamChat(
        reader: JsonReader,
        chat: TelegramChatInfo,
        onChatStart: (TelegramChatInfo) -> Unit,
        onMessage: (TelegramChatInfo, TelegramMessageRecord) -> Unit,
        onChatEnd: (TelegramChatInfo) -> Unit
    ) {
        onChatStart(chat)
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "messages") streamMessages(reader, chat, onMessage)
            else reader.skipValue()
        }
        reader.endObject()
        onChatEnd(chat)
    }

    private fun streamMessages(
        reader: JsonReader,
        chat: TelegramChatInfo,
        onMessage: (TelegramChatInfo, TelegramMessageRecord) -> Unit
    ) {
        reader.beginArray()
        var index = 0L
        while (reader.hasNext()) {
            onMessage(chat, readMessage(reader, ++index))
        }
        reader.endArray()
    }

    private fun readMessage(reader: JsonReader, fallbackId: Long): TelegramMessageRecord {
        var id = fallbackId
        var senderId = ""
        var senderName = "Unknown"
        var text = ""
        var stickerEmoji = ""
        var date = ""
        var dateUnix = 0L
        var replyToId: Long? = null
        var forwarded = false
        var edited = false
        var service = false
        var photo: String? = null
        var file: String? = null
        var mediaType: String? = null
        var fileName: String? = null
        var mimeType: String? = null
        var duration: Long? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = readLong(reader, id)
                "from_id" -> senderId = readString(reader).orEmpty()
                "from" -> senderName = readString(reader) ?: senderName
                "text" -> text = readMessageText(reader)
                "sticker_emoji" -> stickerEmoji = readString(reader).orEmpty()
                "date" -> date = readString(reader).orEmpty()
                "date_unixtime" -> dateUnix = readLong(reader, 0L)
                "reply_to_message_id" -> replyToId = readLong(reader, 0L).takeIf { it > 0L }
                "reply_to_message" -> replyToId = readNestedReplyId(reader) ?: replyToId
                "forwarded_from", "forward_from" -> { forwarded = true; reader.skipValue() }
                "edit_date", "edited" -> { edited = true; reader.skipValue() }
                "type" -> service = readString(reader).equals("service", ignoreCase = true)
                "photo" -> photo = readString(reader)?.takeIf(String::isNotBlank)
                "file" -> file = readString(reader)?.takeIf(String::isNotBlank)
                "media_type" -> mediaType = readString(reader)?.takeIf(String::isNotBlank)
                "file_name" -> fileName = readString(reader)?.takeIf(String::isNotBlank)
                "mime_type" -> mimeType = readString(reader)?.takeIf(String::isNotBlank)
                "duration_seconds" -> duration = readLong(reader, 0L).takeIf { it > 0L }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (dateUnix <= 0L) dateUnix = parseDateUnix(date)
        if (text.isBlank() && stickerEmoji.isNotBlank()) text = stickerEmoji
        val path = photo ?: file
        return TelegramMessageRecord(
            id, senderId, senderName, text, date, dateUnix, replyToId,
            forwarded, edited, service, path,
            mediaType ?: inferMediaType(path, mimeType),
            fileName ?: path?.substringAfterLast('/'), mimeType, duration
        )
    }

    private fun readMessageText(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BEGIN_ARRAY -> buildString {
            reader.beginArray()
            while (reader.hasNext()) {
                when (reader.peek()) {
                    JsonToken.STRING, JsonToken.NUMBER -> append(reader.nextString())
                    JsonToken.BEGIN_OBJECT -> append(readTextEntity(reader))
                    else -> reader.skipValue()
                }
            }
            reader.endArray()
        }
        JsonToken.NULL -> { reader.nextNull(); "" }
        else -> { reader.skipValue(); "" }
    }

    private fun readTextEntity(reader: JsonReader): String {
        var text = ""
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "text") text = readString(reader).orEmpty()
            else reader.skipValue()
        }
        reader.endObject()
        return text
    }

    private fun readNestedReplyId(reader: JsonReader): Long? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var id: Long? = null
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "id") id = readLong(reader, 0L).takeIf { it > 0L }
            else reader.skipValue()
        }
        reader.endObject()
        return id
    }

    private fun readPersonalInformation(reader: JsonReader): Pair<String?, String?> {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null to null
        }
        var id: String? = null
        var name: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "user_id" -> id = readString(reader)?.takeIf(String::isNotBlank)
                "first_name" -> name = readString(reader)?.takeIf(String::isNotBlank)
                "user_information" -> {
                    val nested = readPersonalInformation(reader)
                    id = nested.first ?: id
                    name = nested.second ?: name
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return id to name
    }

    private fun countArray(reader: JsonReader): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            reader.skipValue()
            count++
        }
        reader.endArray()
        return count
    }

    private fun readString(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        JsonToken.NULL -> { reader.nextNull(); null }
        else -> { reader.skipValue(); null }
    }

    private fun readLong(reader: JsonReader, fallback: Long): Long =
        readString(reader)?.toLongOrNull() ?: fallback

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
            m.startsWith("audio/") || p.endsWith(".ogg") || p.endsWith(".opus") || p.endsWith(".mp3") ->
                if (p.contains("voice")) "voice" else "audio"
            path != null -> "file"
            else -> null
        }
    }

    private fun open(file: File): JsonReader {
        val source = PushbackReader(file.inputStream().buffered(256 * 1024).reader(Charsets.UTF_8), 1)
        val first = source.read()
        if (first != -1 && first != 0xFEFF) source.unread(first)
        return JsonReader(source).apply { isLenient = true }
    }
}
