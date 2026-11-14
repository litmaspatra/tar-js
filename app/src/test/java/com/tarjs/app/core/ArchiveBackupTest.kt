package com.tarjs.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveBackupTest {

    private fun fakeDb(): File {
        val file = File.createTempFile("fake-db", ".db")
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        "SQLite format 3".toByteArray(Charsets.US_ASCII).copyInto(payload)
        file.writeBytes(payload)
        file.deleteOnExit()
        return file
    }

    @Test
    fun plainRoundTrip() {
        val source = fakeDb()
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(source, out, null)
        val target = File.createTempFile("restore", ".db")
        ArchiveBackup.unpackBackup(ByteArrayInputStream(out.toByteArray()), null, target)
        assertArrayEquals(source.readBytes(), target.readBytes())
    }

    @Test
    fun encryptedRoundTrip() {
        val source = fakeDb()
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(source, out, "correct horse battery")
        val target = File.createTempFile("restore", ".db")
        ArchiveBackup.unpackBackup(ByteArrayInputStream(out.toByteArray()), "correct horse battery", target)
        assertArrayEquals(source.readBytes(), target.readBytes())
    }

    @Test
    fun wrongPassphraseRejected() {
        val source = fakeDb()
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(source, out, "right")
        val target = File.createTempFile("restore", ".db")
        assertThrows(Exception::class.java) {
            ArchiveBackup.unpackBackup(ByteArrayInputStream(out.toByteArray()), "wrong", target)
        }
        assertTrue(!target.exists() || target.length() == 0L)
    }

    @Test
    fun missingPassphraseRejected() {
        val source = fakeDb()
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(source, out, "needed")
        assertThrows(Exception::class.java) {
            ArchiveBackup.unpackBackup(ByteArrayInputStream(out.toByteArray()), null, File.createTempFile("restore", ".db"))
        }
    }

    @Test
    fun encryptedHeaderCarriesVersionSaltAndFreshIv() {
        val source = fakeDb()
        val out1 = ByteArrayOutputStream()
        val out2 = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(source, out1, "pw")
        ArchiveBackup.exportBackup(source, out2, "pw")
        val b1 = out1.toByteArray()
        val b2 = out2.toByteArray()
        assertEquals("TARJSBAK", String(b1.copyOfRange(0, 8), Charsets.US_ASCII))
        assertEquals(2, b1[8].toInt()) // encrypted format version
        // 16-byte salt then 12-byte IV, both random per export.
        assertFalse(b1.copyOfRange(9, 25).all { it == 0.toByte() })
        assertFalse(b1.copyOfRange(25, 37).contentEquals(b2.copyOfRange(25, 37)))
        assertFalse(b1.copyOfRange(9, 25).contentEquals(b2.copyOfRange(9, 25)))
        // Ciphertext differs between exports of the same content.
        assertFalse(b1.contentEquals(b2))
    }

    @Test
    fun plainHeaderCarriesVersion() {
        val source = fakeDb()
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(source, out, null)
        val bytes = out.toByteArray()
        assertEquals(1, bytes[8].toInt())
        // Payload starts immediately after the 9-byte header.
        assertEquals("SQLite format 3", String(bytes.copyOfRange(9, 24), Charsets.US_ASCII))
    }

    @Test
    fun unsupportedVersionRejectedAndTargetPreserved() {
        val forged = "TARJSBAK".toByteArray(Charsets.US_ASCII) + byteArrayOf(99) + ByteArray(2048)
        val target = File.createTempFile("restore", ".db")
        assertThrows(Exception::class.java) {
            ArchiveBackup.unpackBackup(ByteArrayInputStream(forged), "pw", target)
        }
        assertTrue(!target.exists() || target.length() == 0L)
    }

    @Test
    fun corruptedCiphertextRejectedAndTargetPreserved() {
        val source = fakeDb()
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(source, out, "pw")
        val tampered = out.toByteArray()
        tampered[tampered.size - 5] = (tampered[tampered.size - 5] + 1).toByte()
        val target = File.createTempFile("restore", ".db")
        assertThrows(Exception::class.java) {
            ArchiveBackup.unpackBackup(ByteArrayInputStream(tampered), "pw", target)
        }
        assertTrue(!target.exists() || target.length() == 0L)
    }

    @Test
    fun passphraseNeverAppearsInEncryptedBackup() {
        val source = fakeDb()
        val passphrase = "sup3r-secret-passphrase"
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(source, out, passphrase)
        val bytes = out.toByteArray()
        val needle = passphrase.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            throw AssertionError("passphrase found in backup at offset " + i)
        }
    }

    @Test
    fun largeArchiveRoundTripEncrypted() {
        val file = File.createTempFile("big-db", ".db")
        val payload = ByteArray(6 * 1024 * 1024) { ((it * 31) % 253).toByte() }
        "SQLite format 3".toByteArray(Charsets.US_ASCII).copyInto(payload)
        file.writeBytes(payload)
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(file, out, "big")
        val target = File.createTempFile("restore", ".db")
        ArchiveBackup.unpackBackup(ByteArrayInputStream(out.toByteArray()), "big", target)
        assertArrayEquals(payload, target.readBytes())
    }

    @Test
    fun nonDatabaseContentRejected() {
        val notDb = File.createTempFile("notdb", ".db")
        notDb.writeText("this is definitely not a sqlite database file at all")
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(notDb, out, null)
        val target = File.createTempFile("restore", ".db")
        assertThrows(Exception::class.java) {
            ArchiveBackup.unpackBackup(ByteArrayInputStream(out.toByteArray()), null, target)
        }
        // A failed unpack must not leave a partial file behind.
        assertTrue(!target.exists() || target.length() == 0L)
    }
}
