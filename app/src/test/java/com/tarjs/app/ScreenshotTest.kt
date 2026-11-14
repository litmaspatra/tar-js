package com.tarjs.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tarjs.app.core.ArchiveDb
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var vm: TarVm

    @Before
    fun seedArchiveAndLaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("tarjs_archive.db")
        val db = ArchiveDb(context)
        val result = db.importJson(fixtureJson()) { _, _ -> }
        check(result.error == null) { "Fixture import failed: ${result.error}" }
        db.addBookmark(100, 40)
        db.addBookmark(200, 5)
        db.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { vm = ViewModelProvider(it)[TarVm::class.java] }
    }

    @After
    fun closeActivity() {
        scenario.close()
    }

    companion object {
        private fun fixtureJson(): String {
            val messages = StringBuilder()
            for (i in 1..240) {
                if (i > 1) messages.append(',')
                val media = if (i % 15 == 0) """, "photo": "photos/road_trip_$i.jpg", "media_type": "photo", "file_name": "road_trip_$i.jpg"""" else ""
                val text = when {
                    i % 15 == 0 -> "road photo from day $i"
                    i % 7 == 0 -> "planning the road trip, day $i"
                    else -> "catching up about day $i of the drive"
                }
                messages.append("""{"id": $i, "type": "message", "from": "${if (i % 2 == 0) "Kartik" else "Asha"}", "from_id": "${if (i % 2 == 0) "user_owner" else "user_asha"}", "date": "2024-01-01T10:00:00+00:00", "date_unixtime": ${1704067200 + i * 172800}, "text": "$text"$media}""")
            }
            val family = StringBuilder()
            for (i in 1..30) {
                if (i > 1) family.append(',')
                family.append("""{"id": $i, "type": "message", "from": "Mom", "from_id": "user_mom", "date": "2024-06-01T10:00:00+00:00", "date_unixtime": ${1717200000 + i * 86400}, "text": "family update $i"}""")
            }
            return """{"personal_information": {"user_id": "user_owner", "first_name": "Kartik"}, "chats": {"list": [
                {"id": 100, "name": "Road Trip Crew", "type": "basic_group", "messages": [$messages]},
                {"id": 200, "name": "Family", "type": "personal_chat", "messages": [$family]}
            ]}""".replace("\n", "")
        }
    }

    private fun save(name: String) {
        composeRule.waitForIdle()
        val dir = File(System.getenv("SCREENSHOT_DIR") ?: "build/screenshots").also { it.mkdirs() }
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun settle() {
        composeRule.waitForIdle()
        Thread.sleep(400)
        composeRule.waitForIdle()
    }

    @Test
    fun captureKeyScreens() {
        composeRule.waitUntil(10_000) { vm.chats.isNotEmpty() }
        settle()
        save("01-home")

        val chat = vm.chats.first { it.id == 100L }
        vm.selectChat(chat)
        composeRule.waitUntil(10_000) { vm.messages.isNotEmpty() }
        settle()
        save("02-chat")

        vm.jumpToFirst()
        composeRule.waitUntil(10_000) { vm.messages.firstOrNull()?.id == 1L }
        settle()
        save("03-chat-first-message")

        vm.openGlobalSearch()
        vm.updateSearchQuery("road")
        composeRule.waitUntil(10_000) { vm.searchResults.isNotEmpty() && !vm.isSearching }
        settle()
        save("04-global-search")

        vm.openBookmarks()
        composeRule.waitUntil(10_000) { vm.bookmarksList.isNotEmpty() }
        settle()
        save("05-bookmarks")

        vm.selectChat(chat)
        composeRule.waitUntil(10_000) { vm.messages.isNotEmpty() }
        vm.openGallery()
        composeRule.waitUntil(10_000) { vm.galleryMessages.isNotEmpty() }
        settle()
        save("06-gallery")
    }
}
