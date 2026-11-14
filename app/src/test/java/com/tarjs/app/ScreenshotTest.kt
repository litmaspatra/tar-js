package com.tarjs.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import java.util.concurrent.TimeUnit
import org.robolectric.Shadows
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
        val json = fixtureJson()
        val probe = runCatching { org.json.JSONObject(json) }
        if (probe.isFailure) {
            println("FIXTURE HEAD: " + json.take(400))
            println("FIXTURE TAIL: " + json.takeLast(400))
        }
        check(probe.isSuccess) { "Fixture JSON malformed: ${probe.exceptionOrNull()?.message}" }
        val result = db.importJson(json) { _, _ -> }
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
                val media = if (i % 15 == 0) ", \"photo\": \"photos/road_trip_$i.jpg\", \"media_type\": \"photo\", \"file_name\": \"road_trip_$i.jpg\"" else ""
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
            ]}}""".replace("\n", "")
        }
    }

    private fun save(name: String) {
        composeRule.waitForIdle()
        val dir = File(System.getenv("SCREENSHOT_DIR") ?: "build/screenshots").also { it.mkdirs() }
        val file = File(dir, "$name.png")
        // captureToImage() relies on hardware window capture, which never completes under
        // Robolectric; drawing the content view into a software bitmap works with
        // GraphicsMode NATIVE (real Skia rendering).
        scenario.onActivity { activity ->
            val view = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            check(view.width > 0 && view.height > 0) { "content view not laid out for $name" }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        check(file.length() > 0) { "screenshot $name empty" }
    }

    private fun settle() {
        pumpLooper()
        Thread.sleep(400)
        pumpLooper()
    }

    private fun pumpLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        composeRule.waitForIdle()
    }

    // composeRule.waitUntil does not reliably run viewModelScope coroutines posted to the
    // main looper under Robolectric, and plain idle() never advances the paused looper
    // clock, so debounce delay() callbacks would never fire. Advance the clock while polling.
    private fun pumpUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        var waited = 0L
        while (waited < timeoutMillis) {
            looper.idleFor(100, TimeUnit.MILLISECONDS)
            if (condition()) return
            waited += 100
        }
        check(condition()) { "condition not satisfied after $timeoutMillis ms" }
    }

    @Test
    fun captureKeyScreens() {
        pumpUntil(10_000) { vm.chats.isNotEmpty() }
        settle()
        save("01-home")

        val chat = vm.chats.first { it.id == 100L }
        vm.selectChat(chat)
        pumpUntil(10_000) { vm.messages.isNotEmpty() }
        settle()
        save("02-chat")

        vm.jumpToFirst()
        pumpUntil(10_000) { vm.messages.firstOrNull()?.id == 1L }
        settle()
        save("03-chat-first-message")

        vm.openGlobalSearch()
        vm.updateSearchQuery("road")
        pumpUntil(10_000) { vm.searchResults.isNotEmpty() && !vm.isSearching }
        settle()
        save("04-global-search")

        vm.openBookmarks()
        pumpUntil(10_000) { vm.bookmarksList.isNotEmpty() }
        settle()
        save("05-bookmarks")

        vm.selectChat(chat)
        pumpUntil(10_000) { vm.messages.isNotEmpty() }
        vm.openGallery()
        pumpUntil(10_000) { vm.galleryMessages.isNotEmpty() }
        settle()
        save("06-gallery")
    }
}
