package com.tarjs.app.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
class ChatSearchTest {
    private lateinit var context: Context
    private lateinit var db: ArchiveDb
    private lateinit var search: ChatSearch

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("tarjs_archive.db")
        db = ArchiveDb(context)
        val json = checkNotNull(javaClass.classLoader?.getResource("fixtures/dummy_telegram_export.json")).readText()
        val result = db.importJson(json) { _, _ -> }
        check(result.error == null) { result.error ?: "fixture import failed" }
        search = ChatSearch(db)
    }

    @After
    fun tearDown() {
        search.cancel()
        db.close()
        context.deleteDatabase("tarjs_archive.db")
    }

    @Test
    fun debounceSkipsIntermediateQueries() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)

        val first = async(dispatcher) { search.search(100, "hello", scope) }
        runCurrent()
        advanceTimeBy(100)
        val last = async(dispatcher) { search.search(100, "newest", scope) }
        runCurrent()
        advanceTimeBy(ChatSearch.DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertTrue(first.await().results.isEmpty())
        assertEquals("newest", last.await().query)
        assertEquals(listOf("newest"), last.await().results.map { it.text })
    }

    @Test
    fun searchCancelsPreviousJob() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)

        val stale = async(dispatcher) { search.search(100, "hello owner", scope) }
        runCurrent()
        val fresh = async(dispatcher) { search.search(100, "middle", scope) }
        runCurrent()
        advanceTimeBy(ChatSearch.DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertTrue(stale.await().results.isEmpty())
        val state = fresh.await()
        assertNull(state.error)
        assertEquals(1, state.results.size)
        assertEquals("middle", state.results.single().text)
    }

    @Test
    fun jumpToMessageLoadsWindowAroundIt() = runTest {
        val window = search.jumpToMessage(100, 2, this)
        assertEquals(listOf(1L, 2L, 3L, 4L), window.map { it.id })
        assertEquals(listOf(10L, 20L, 30L, 40L), window.map { it.dateUnix })
    }

    @Test
    fun hasOlderHasNewerFlagsCorrect() = runTest {
        val oldest = async { search.search(100, "hello owner", this@runTest) }
        advanceTimeBy(ChatSearch.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertFalse(oldest.await().hasOlder)
        assertTrue(oldest.await().hasNewer)

        val middle = async { search.search(100, "middle", this@runTest) }
        advanceTimeBy(ChatSearch.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertTrue(middle.await().hasOlder)
        assertTrue(middle.await().hasNewer)

        val newest = async { search.search(100, "newest", this@runTest) }
        advanceTimeBy(ChatSearch.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertTrue(newest.await().hasOlder)
        assertFalse(newest.await().hasNewer)
    }
}
