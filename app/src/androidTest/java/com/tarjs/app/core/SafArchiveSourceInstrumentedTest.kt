package com.tarjs.app.core

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafArchiveSourceInstrumentedTest {
    @Test(timeout = 5_000)
    fun recursiveFixtureFindsAndReadsResultJsonWithoutMutation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://com.tarjs.app.fixture.documents/tree/root/document/root")
        val result = SafArchiveSource.findResultJson(context, tree)
        assertNotNull(result)
        assertEquals("{\"phase\":\"2\",\"source\":\"saf-fixture\"}\n", SafArchiveSource.openResultJson(context.contentResolver, result!!))
    }
}
