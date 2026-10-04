package com.tarjs.app.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresentationTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun firstValidMessageNeedsDateSeparator() {
        val current = Instant.parse("2026-10-04T08:00:00Z").epochSecond
        assertTrue(needsDateSeparator(null, current, utc))
    }

    @Test
    fun messagesOnSameCalendarDayShareDateSeparator() {
        val previous = Instant.parse("2026-10-04T00:01:00Z").epochSecond
        val current = Instant.parse("2026-10-04T23:59:00Z").epochSecond
        assertFalse(needsDateSeparator(previous, current, utc))
    }

    @Test
    fun crossingMidnightNeedsDateSeparator() {
        val previous = Instant.parse("2026-10-04T23:59:59Z").epochSecond
        val current = Instant.parse("2026-10-05T00:00:00Z").epochSecond
        assertTrue(needsDateSeparator(previous, current, utc))
    }

    @Test
    fun invalidCurrentTimestampDoesNotCreateDateSeparator() {
        assertFalse(needsDateSeparator(null, 0L, utc))
    }

    @Test
    fun imageSamplingNeverUpscalesSmallImages() {
        assertEquals(1, sampleSizeFor(800, 600, 1080, 1080))
    }

    @Test
    fun imageSamplingUsesPowerOfTwoForLargeImages() {
        assertEquals(4, sampleSizeFor(8000, 6000, 1000, 1000))
        assertEquals(2, sampleSizeFor(6000, 1000, 1200, 400))
    }
}
