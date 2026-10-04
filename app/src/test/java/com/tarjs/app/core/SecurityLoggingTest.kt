package com.tarjs.app.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityLoggingTest {
    @Test
    fun missingMediaLogDoesNotRevealArchivePath() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowLog.clear()

        MediaResolver(context.cacheDir).handleMissingMedia("private/chat/photos/family-secret.jpg")

        val messages = ShadowLog.getLogsForTag("TARJS-Media").mapNotNull { it.msg }
        assertFalse(messages.any { it.contains("family-secret") || it.contains("private/chat") })
    }
}
