package com.tarjs.app.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("lock", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("lock", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun optionalPinRemainsNotSetUp() {
        assertEquals(LockState.NOT_SET_UP, AppLock(context) { 1_000L }.state)
    }

    @Test
    fun unlockExpiresAtFifteenMinutes() {
        var now = 1_000L
        val lock = AppLock(context) { now }
        lock.setPasscode("1234")

        now = 1_899L
        assertTrue(lock.state is LockState.UNLOCKED)

        now = 1_900L
        assertEquals(LockState.LOCKED, lock.state)
        assertEquals(0L, context.getSharedPreferences("lock", Context.MODE_PRIVATE).getLong("unlocked_at", -1L))
    }

    @Test
    fun fiveFailuresTemporarilyBlockEvenTheCorrectPin() {
        var now = 1_000L
        val lock = AppLock(context) { now }
        lock.setPasscode("1234")
        lock.lockApp()

        repeat(5) { assertFalse(lock.verify("0000")) }
        assertTrue(lock.isLockedAfterFailures)
        assertFalse(lock.verify("1234"))

        now += 30L
        assertTrue(lock.verify("1234"))
        assertFalse(lock.isLockedAfterFailures)
    }
}
