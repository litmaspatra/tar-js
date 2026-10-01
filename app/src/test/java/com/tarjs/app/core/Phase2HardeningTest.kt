package com.tarjs.app.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase2HardeningTest {
    private lateinit var context: Context
    private lateinit var db: ArchiveDb

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("tarjs_archive.db")
        db = ArchiveDb(context)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase("tarjs_archive.db")
    }

    @Test fun sameMessageIdCanExistInTwoChatsAndPagingWorksBothWays() {
        val export = """
            {
              "chats":{"list":[
                {"id":1,"name":"Alice","messages":[
                  {"id":7,"from":"Alice","date":"2026-01-01T00:00:01+00:00","date_unixtime":1,"text":"old"},
                  {"id":8,"from":"Alice","date":"2026-01-01T00:00:02+00:00","date_unixtime":2,"text":"new"}
                ]},
                {"id":2,"name":"Bob","messages":[
                  {"id":7,"from":"Bob","date":"2026-01-01T00:00:03+00:00","date_unixtime":3,"text":"same id other chat"}
                ]}
              ]}
            }
        """.trimIndent()
        val result = db.importJson(export) { _, _ -> }
        assertNull(result.error)
        assertEquals(3, result.messages)
        assertEquals(2, db.messages(1, olderThanDate = Long.MAX_VALUE, limit = 10).size)
        assertEquals(1, db.messages(2, olderThanDate = Long.MAX_VALUE, limit = 10).size)
        assertTrue(db.hasNewerMessages(1, 1))
        assertTrue(db.hasOlderMessages(1, 2))
        assertEquals("new", db.messages(1, newerThanDate = 1, limit = 10).single().text)
    }

    @Test fun chatSearchIsScopedAndJumpWindowCanMoveNewer() = runTest {
        val export = """
            {"chats":{"list":[
              {"id":10,"name":"Alice","messages":[
                {"id":1,"from":"Alice","date_unixtime":10,"text":"needle old"},
                {"id":2,"from":"Alice","date_unixtime":20,"text":"middle"},
                {"id":3,"from":"Alice","date_unixtime":30,"text":"latest"}
              ]},
              {"id":20,"name":"Bob","messages":[{"id":1,"from":"Bob","date_unixtime":40,"text":"needle other chat"}]}
            ]}}
        """.trimIndent()
        db.importJson(export) { _, _ -> }
        val search = ChatSearch(db)
        val stateDeferred = async(StandardTestDispatcher(testScheduler)) { search.search(10, "needle", this) }
        advanceTimeBy(ChatSearch.DEBOUNCE_MS + 1)
        val state = stateDeferred.await()
        assertNull(state.error)
        assertEquals(1, state.results.size)
        assertEquals(10L, state.results.single().chatId)
        val window = search.jumpToMessage(10, 1, this)
        assertEquals(3, window.size)
        assertTrue(db.hasNewerMessages(10, window.first().dateUnix))
        val newer = search.loadNewer(10, 10, this)
        assertEquals(listOf("middle", "latest"), newer.map { it.text })
    }

    @Test fun swapAndAvatarPreferencesPersistWithoutChangingMessageIdentity() {
        db.setSwapSides(99, true)
        assertTrue(db.getSwapSides(99))
        assertTrue(MessageDirection.displayOnRight(mine = false, swapped = true))
        assertFalse(MessageDirection.displayOnRight(mine = true, swapped = true))
        db.setCustomChatName(99, "Custom Alice")
        assertEquals("Custom Alice", db.getCustomChatName(99))
        val pref = AvatarPreference("chat-avatars/99.img", 1.7f, .25f, -.4f)
        db.setAvatarPreference(99, pref)
        assertEquals(pref, db.getAvatarPreference(99))
        db.clearAvatarPreference(99)
        assertNull(db.getAvatarPreference(99))
    }

    @Test fun safValidationAcceptsFullAndSingleButRejectsUnrelatedJson() {
        assertTrue(SafArchiveSource.validateTelegramExport("{\"messages\":[{\"id\":1}]}" ).isSuccess)
        assertTrue(SafArchiveSource.validateTelegramExport("{\"chats\":{\"list\":[{\"messages\":[{\"id\":1}]}]}}" ).isSuccess)
        assertTrue(SafArchiveSource.validateTelegramExport("{\"hello\":\"world\"}").isFailure)
        assertTrue(SafArchiveSource.validateTelegramExport("{\"messages\":[]}").isFailure)
    }

    @Test fun rcloneEncryptedDetectionHandlesBomCommentsAndRejectsMalformedPath() {
        val manager = RcloneConfigManager(context)
        val encrypted = "\uFEFF# generated\n; comment\n\nRCLONE_ENCRYPT_V0:\nabc"
        assertTrue(manager.isEncrypted(encrypted))
        assertFalse(manager.isEncrypted("# comment\n[drive]\ntype = drive\n"))
        assertEquals("b2crypt:exports/2026", manager.validateRemotePath("b2crypt:", "exports/2026").getOrThrow().toString())
        assertTrue(manager.validateRemotePath("b2crypt::", "exports").isFailure)
        assertTrue(manager.validateRemotePath("b2crypt", "../exports").isFailure)
    }

    @Test fun mediaResolverHandlesTgsStickerMetadataAndMissingFiles() {
        val dir = File(context.cacheDir, "phase2-media-test").apply { mkdirs() }
        val tgs = File(dir, "animated.tgs")
        GZIPOutputStream(FileOutputStream(tgs)).use { it.write("{\"v\":\"5.7.0\"}".toByteArray()) }
        val resolver = MediaResolver(File(dir, "cache"))
        val meta = resolver.inspectMedia(tgs, "stickers/animated.tgs")
        assertNotNull(meta)
        assertEquals("sticker_animated", meta!!.kind)
        assertEquals(170, resolver.getStickerDisplaySize())
        assertTrue(resolver.isValidMediaFile(tgs, "sticker_animated"))
        assertTrue(resolver.decompressTgs(tgs)!!.startsWith("{"))
        assertNull(resolver.inspectMedia(File(dir, "missing.webp"), "stickers/missing.webp"))
    }
}
