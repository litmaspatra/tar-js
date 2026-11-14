package com.tarjs.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
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
        ArchiveBackup.restoreBackup(ByteArrayInputStream(out.toByteArray()), null, target)
        assertArrayEquals(source.readBytes(), target.readBytes())
    }

    @Test
    fun encryptedRoundTrip() {
        val source = fakeDb()
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(source, out, "correct horse battery")
        val target = File.createTempFile("restore", ".db")
        ArchiveBackup.restoreBackup(ByteArrayInputStream(out.toByteArray()), "correct horse battery", target)
        assertArrayEquals(source.readBytes(), target.readBytes())
    }

    @Test
    fun wrongPassphraseRejected() {
        val source = fakeDb()
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(source, out, "right")
        assertThrows(Exception::class.java) {
            ArchiveBackup.restoreBackup(ByteArrayInputStream(out.toByteArray()), "wrong", File.createTempFile("restore", ".db"))
        }
    }

    @Test
    fun missingPassphraseRejected() {
        val source = fakeDb()
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(source, out, "needed")
        assertThrows(Exception::class.java) {
            ArchiveBackup.restoreBackup(ByteArrayInputStream(out.toByteArray()), null, File.createTempFile("restore", ".db"))
        }
    }

    @Test
    fun nonDatabaseContentRejected() {
        val notDb = File.createTempFile("notdb", ".db")
        notDb.writeText("this is definitely not a sqlite database file at all")
        val out = ByteArrayOutputStream()
        ArchiveBackup.exportBackup(notDb, out, null)
        val target = File.createTempFile("restore", ".db")
        target.writeText("pre-existing archive")
        assertThrows(Exception::class.java) {
            ArchiveBackup.restoreBackup(ByteArrayInputStream(out.toByteArray()), null, target)
        }
        // The failed restore must not destroy the existing archive.
        assertTrue(target.readText().startsWith("pre-existing archive"))
    }
}
