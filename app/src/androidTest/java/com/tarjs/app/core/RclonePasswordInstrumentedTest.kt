package com.tarjs.app.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RclonePasswordInstrumentedTest {
    @Test(timeout = 30_000)
    fun encryptedConfigRejectsWrongPasswordThenAcceptsCorrectPassword() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = context.cacheDir.resolve("rclone-encrypted-asdf.conf")
        instrumentation.context.assets.open("rclone-encrypted-asdf.conf").use { source ->
            config.outputStream().use(source::copyTo)
        }

        assertTrue(runCatching { RcloneRuntime.unlock(config, "wrong-password") }.isFailure)
        RcloneRuntime.unlock(config, "asdf")
        assertEquals(listOf("nounc", "unc"), RcloneRuntime.listRemotes())
    }
}
