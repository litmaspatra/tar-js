package com.tarjs.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedIoTest {
    @Test
    fun exactLimitCopySucceeds() {
        val input = ByteArray(32) { it.toByte() }
        val output = ByteArrayOutputStream()

        val copied = copyWithLimit(ByteArrayInputStream(input), output, 32)

        assertEquals(32L, copied)
        assertArrayEquals(input, output.toByteArray())
    }

    @Test
    fun copyFailsAsSoonAsLimitIsExceeded() {
        assertThrows(InputTooLargeException::class.java) {
            copyWithLimit(ByteArrayInputStream(ByteArray(33)), ByteArrayOutputStream(), 32)
        }
    }

    @Test
    fun compressedTextCannotExpandPastLimit() {
        val compressed = ByteArrayOutputStream().also { target ->
            GZIPOutputStream(target).use { gzip -> gzip.write(ByteArray(4 * 1024 * 1024 + 1) { 'x'.code.toByte() }) }
        }.toByteArray()

        assertThrows(InputTooLargeException::class.java) {
            readGzipUtf8WithLimit(ByteArrayInputStream(compressed), 4 * 1024 * 1024)
        }
    }
}
