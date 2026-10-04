package com.tarjs.app.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaRequestCoordinatorTest {
    @Test
    fun concurrentSameKeyCallersShareOneLoad() = runTest {
        val coordinator = MediaRequestCoordinator<String>()
        val release = CompletableDeferred<Unit>()
        var loads = 0

        val first = async { coordinator.resolve("photo") { loads += 1; release.await(); "ready" } }
        val second = async { coordinator.resolve("photo") { loads += 1; "duplicate" } }
        runCurrent()

        assertEquals(1, loads)
        release.complete(Unit)
        assertEquals("ready", first.await())
        assertEquals("ready", second.await())
        assertEquals(1, loads)
    }

    @Test
    fun differentKeysDoNotBlockEachOther() = runTest {
        val coordinator = MediaRequestCoordinator<String>()
        val releaseSlow = CompletableDeferred<Unit>()

        val slow = async { coordinator.resolve("slow") { releaseSlow.await(); "slow-ready" } }
        val fast = async { coordinator.resolve("fast") { "fast-ready" } }
        runCurrent()

        assertEquals("fast-ready", fast.await())
        assertFalse(slow.isCompleted)
        releaseSlow.complete(Unit)
        assertEquals("slow-ready", slow.await())
    }
}
