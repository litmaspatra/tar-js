from pathlib import Path

# Add deterministic dummy Telegram export fixture.
root = Path("tarjs/app/src/test/resources/telegram_export")
(root / "photos").mkdir(parents=True, exist_ok=True)
(root / "stickers").mkdir(parents=True, exist_ok=True)
(root / "voice_messages").mkdir(parents=True, exist_ok=True)
(root / "video_files").mkdir(parents=True, exist_ok=True)
(root / "files").mkdir(parents=True, exist_ok=True)

(root / "photos/photo_1.jpg").write_bytes(b"FAKEJPEGPHOTO")
(root / "stickers/sticker_1.webp").write_bytes(b"FAKEWEBPSTICKER")
(root / "voice_messages/audio_1.ogg").write_bytes(b"FAKEOGGVOICE")
(root / "video_files/video_1.mp4").write_bytes(b"FAKEMP4VIDEO")
(root / "files/document_1.pdf").write_bytes(b"%PDF-FAKEDOC")

(root / "result.json").write_text(r'''{
  "about": "Dummy Telegram export for TAR-JS CI",
  "chats": {
    "about": "Dummy chats",
    "list": [
      {
        "name": "Alice Test",
        "type": "personal_chat",
        "id": 1001,
        "messages": [
          {
            "id": 1,
            "type": "message",
            "date": "2026-09-01T10:00:00",
            "date_unixtime": "1788256800",
            "from": "Alice Test",
            "from_id": "user1001",
            "text": "hello archive",
            "text_entities": [{"type":"plain","text":"hello archive"}]
          },
          {
            "id": 2,
            "type": "message",
            "date": "2026-09-01T10:01:00",
            "date_unixtime": "1788256860",
            "from": "Alice Test",
            "from_id": "user1001",
            "photo": "photos/photo_1.jpg",
            "width": 640,
            "height": 480,
            "text": "photo caption",
            "text_entities": [{"type":"plain","text":"photo caption"}]
          },
          {
            "id": 3,
            "type": "message",
            "date": "2026-09-01T10:02:00",
            "date_unixtime": "1788256920",
            "from": "Me",
            "from_id": "user999",
            "file": "stickers/sticker_1.webp",
            "media_type": "sticker",
            "sticker_emoji": "🙂",
            "text": ""
          },
          {
            "id": 4,
            "type": "message",
            "date": "2026-09-01T10:03:00",
            "date_unixtime": "1788256980",
            "from": "Alice Test",
            "from_id": "user1001",
            "file": "voice_messages/audio_1.ogg",
            "media_type": "voice_message",
            "mime_type": "audio/ogg",
            "duration_seconds": 3,
            "text": ""
          },
          {
            "id": 5,
            "type": "message",
            "date": "2026-09-01T10:04:00",
            "date_unixtime": "1788257040",
            "from": "Alice Test",
            "from_id": "user1001",
            "file": "video_files/video_1.mp4",
            "media_type": "video_file",
            "mime_type": "video/mp4",
            "text": "test video"
          },
          {
            "id": 6,
            "type": "message",
            "date": "2026-09-01T10:05:00",
            "date_unixtime": "1788257100",
            "from": "Alice Test",
            "from_id": "user1001",
            "file": "files/document_1.pdf",
            "file_name": "document_1.pdf",
            "mime_type": "application/pdf",
            "text": "important document"
          }
        ]
      }
    ]
  }
}''', encoding="utf-8")

# Add Robolectric/JUnit test dependencies.
p = Path("tarjs/app/build.gradle.kts")
s = p.read_text()
if "testOptions {" not in s:
    s = s.replace(
        '    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }',
        '''    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }\n    testOptions { unitTests.isIncludeAndroidResources = true }'''
    )
if 'testImplementation("junit:junit:4.13.2")' not in s:
    s = s.replace(
        "dependencies {",
        '''dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")'''
    )
p.write_text(s)

# Add importer/index/search/media reference tests.
test = Path("tarjs/app/src/test/java/com/tarjs/archive/TelegramImporterSmokeTest.kt")
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text(r'''package com.tarjs.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TelegramImporterSmokeTest {
    private lateinit var context: Context
    private lateinit var db: ArchiveDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("tarjs.db")
        db = ArchiveDatabase(context)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("tarjs.db")
    }

    @Test
    fun dummyExport_indexesChatsMessagesSearchAndMediaReferences() {
        val id = db.createArchive("Dummy", "local", "file:///dummy")
        val input = requireNotNull(javaClass.classLoader!!.getResourceAsStream("telegram_export/result.json"))
        val progress = mutableListOf<String>()
        val result = TelegramImporter(db).import(id, input) { progress += it }

        assertEquals(1, result.chats)
        assertEquals(6, result.messages)
        assertTrue(progress.isNotEmpty())

        val chats = org.json.JSONArray(db.chatsJson(id))
        assertEquals(1, chats.length())
        assertEquals("Alice Test", chats.getJSONObject(0).getString("name"))

        val search = org.json.JSONArray(db.searchJson(id, "archive", 20))
        assertTrue("FTS should find hello archive", search.length() >= 1)

        val searchDoc = org.json.JSONArray(db.searchJson(id, "important", 20))
        assertTrue("FTS should find document caption", searchDoc.length() >= 1)

        val chatId = chats.getJSONObject(0).getLong("id")
        val messages = org.json.JSONArray(db.messagesJson(chatId, 50, null))
        assertEquals(6, messages.length())

        val all = (0 until messages.length()).map { messages.getJSONObject(it) }
        val media = all.mapNotNull { it.optString("mediaPath").takeIf(String::isNotBlank) }.toSet()

        assertTrue(media.contains("photos/photo_1.jpg"))
        assertTrue(media.contains("stickers/sticker_1.webp"))
        assertTrue(media.contains("voice_messages/audio_1.ogg"))
        assertTrue(media.contains("video_files/video_1.mp4"))
        assertTrue(media.contains("files/document_1.pdf"))
    }

    @Test
    fun fixture_hasEveryReferencedMediaFile() {
        val base = File(requireNotNull(javaClass.classLoader!!.getResource("telegram_export")).toURI())
        listOf(
            "photos/photo_1.jpg",
            "stickers/sticker_1.webp",
            "voice_messages/audio_1.ogg",
            "video_files/video_1.mp4",
            "files/document_1.pdf"
        ).forEach { relative ->
            assertTrue("Missing dummy media: $relative", File(base, relative).isFile)
        }
    }
}
''', encoding="utf-8")

# Add a plain Python fixture verifier too, so failures are readable even before Gradle/JVM tests.
verify = Path("tarjs/ci_verify_dummy_export.py")
verify.write_text(r'''import json
from pathlib import Path

root = Path("app/src/test/resources/telegram_export")
data = json.loads((root / "result.json").read_text("utf-8"))
chats = data["chats"]["list"]
assert len(chats) == 1
messages = chats[0]["messages"]
assert len(messages) == 6

refs = []
for m in messages:
    for key in ("photo", "file", "thumbnail"):
        value = m.get(key)
        if isinstance(value, str) and value:
            refs.append(value)

expected = {
    "photos/photo_1.jpg",
    "stickers/sticker_1.webp",
    "voice_messages/audio_1.ogg",
    "video_files/video_1.mp4",
    "files/document_1.pdf",
}
assert expected.issubset(set(refs)), (expected, refs)
for rel in expected:
    p = root / rel
    assert p.is_file(), f"Missing referenced media {rel}"

print(f"DUMMY_EXPORT_OK chats={len(chats)} messages={len(messages)} media={len(expected)}")
''', encoding="utf-8")
