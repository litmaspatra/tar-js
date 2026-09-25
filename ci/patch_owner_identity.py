from pathlib import Path

# Archive owner identity helpers
p=Path('tarjs/app/src/main/java/com/tarjs/archive/ArchiveDatabase.kt')
s=p.read_text()
anchor='    fun cacheMediaUri(archiveId: Long, relative: String, uri: String?, localPath: String?) {'
if 'fun setOwnerIdentity(' not in s:
    insert='''    fun setOwnerIdentity(archiveId: Long, ownerId: String?, ownerName: String?) {
        val db = writableDatabase
        if (!ownerId.isNullOrBlank()) db.insertWithOnConflict("settings", null, ContentValues().apply { put("k", "owner_id_$archiveId"); put("v", ownerId) }, SQLiteDatabase.CONFLICT_REPLACE)
        if (!ownerName.isNullOrBlank()) db.insertWithOnConflict("settings", null, ContentValues().apply { put("k", "owner_name_$archiveId"); put("v", ownerName) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun ownerJson(archiveId: Long): String {
        fun read(k: String): String? = readableDatabase.rawQuery("SELECT v FROM settings WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }
        return JSONObject().apply { put("id", read("owner_id_$archiveId")); put("name", read("owner_name_$archiveId")) }.toString()
    }

'''
    s=s.replace(anchor,insert+anchor)
p.write_text(s)

# Web bridge owner lookup
p=Path('tarjs/app/src/main/java/com/tarjs/archive/MainActivity.kt')
s=p.read_text()
if '@JavascriptInterface fun getOwner' not in s:
    s=s.replace('@JavascriptInterface fun listChats(archiveId: Long): String = db.chatsJson(archiveId)', '@JavascriptInterface fun listChats(archiveId: Long): String = db.chatsJson(archiveId)\n        @JavascriptInterface fun getOwner(archiveId: Long): String = db.ownerJson(archiveId)')
p.write_text(s)

# Parse Telegram personal_information if present
p=Path('tarjs/app/src/main/java/com/tarjs/archive/TelegramImporter.kt')
s=p.read_text()
if '"personal_information" ->' not in s:
    s=s.replace('                    when (r.nextName()) {\n                        "chats" -> {', '''                    when (r.nextName()) {
                        "personal_information" -> {
                            var ownerId: String? = null
                            var first: String? = null
                            var last: String? = null
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "user_id", "id" -> ownerId = readScalarAsString(r)
                                    "first_name" -> first = readScalarAsString(r)
                                    "last_name" -> last = readScalarAsString(r)
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                            val ownerName = listOfNotNull(first, last).joinToString(" ").trim().ifBlank { null }
                            db.setOwnerIdentity(archiveId, ownerId, ownerName)
                        }
                        "chats" -> {''')
p.write_text(s)
