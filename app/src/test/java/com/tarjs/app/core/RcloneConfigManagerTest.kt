package com.tarjs.app.core

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RcloneConfigManagerTest {
    private lateinit var context: Context
    private lateinit var manager: RcloneConfigManager
    private lateinit var scratch: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = RcloneConfigManager(context)
        scratch = File(context.cacheDir, "rclone-manager-test").apply { deleteRecursively(); mkdirs() }
        File(context.filesDir, "rclone-configs").deleteRecursively()
    }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
        File(context.filesDir, "rclone-configs").deleteRecursively()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { "Missing fixture: $name" }.readText()

    @Test
    fun encryptedDetectionHandlesBomAndComments() {
        val encrypted = fixture("dummy_rclone_encrypted.conf")
        assertTrue(manager.isEncrypted(encrypted))
        assertFalse(manager.isEncrypted(fixture("dummy_rclone.conf")))
    }

    @Test
    fun malformedPathsRejected() {
        assertTrue(manager.validateRemotePath("dummyCrypt::", "Telegram").isFailure)
        assertTrue(manager.validateRemotePath("dummyCrypt", "../Telegram").isFailure)
        assertTrue(manager.validateRemotePath("dummyCrypt", "Telegram:bad").isFailure)

        val valid = manager.validateRemotePath("dummyCrypt:", "/Telegram/Archive One/").getOrThrow()
        assertEquals("dummyCrypt:Telegram/Archive One", valid.toString())
    }

    @Test
    fun privateConfigNeverModifiesOriginal() {
        val originalText = fixture("dummy_rclone.conf")
        val original = File(scratch, "user-rclone.conf").apply { writeText(originalText) }

        val imported = manager.importConfigFromUri(Uri.fromFile(original), "rclone.conf").getOrThrow()
        assertNotEquals(original.canonicalPath, imported.canonicalPath)
        assertTrue(imported.canonicalPath.startsWith(context.filesDir.canonicalPath))
        assertEquals(originalText, imported.readText())

        imported.appendText("\n# private-copy mutation\n")
        assertEquals(originalText, original.readText())
        assertFalse(original.readText().contains("private-copy mutation"))
    }

    @Test
    fun oversizedConfigIsRejectedAndTemporaryCopyIsRemoved() {
        val source = File(context.cacheDir, "oversized-rclone.conf").apply {
            writeBytes(ByteArray(2 * 1024 * 1024 + 1) { 'x'.code.toByte() })
        }
        val manager = RcloneConfigManager(context)

        assertTrue(manager.importConfigFromUri(Uri.fromFile(source), "large.conf").isFailure)
        assertFalse(manager.privateConfig("large.conf").exists())
        assertFalse(manager.privateConfig("large.conf.importing").exists())
    }

    @Test
    fun dummyConfigParsesLocalAndCryptRemotes() {
        val source = File(scratch, "dummy.conf").apply { writeText(fixture("dummy_rclone.conf")) }
        val privateCopy = manager.importConfigFromUri(Uri.fromFile(source), "dummy.conf").getOrThrow()
        val remotes = manager.listRemotes(privateCopy)

        assertEquals(listOf("dummyLocal", "dummyCrypt"), remotes.map { it.name })
        assertEquals("local", remotes.first { it.name == "dummyLocal" }.type)
        val crypt = remotes.first { it.name == "dummyCrypt" }
        assertTrue(crypt.isEncrypted)
        assertEquals("dummyLocal", crypt.backend)
    }
}
