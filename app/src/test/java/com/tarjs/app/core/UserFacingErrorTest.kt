package com.tarjs.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UserFacingErrorTest {
    @Test
    fun internalExceptionDetailsAreNotShownToTheUser() {
        val message = IllegalStateException("/data/user/0/com.tarjs.app/files/private.conf").userFacingMessage("Import failed")

        assertEquals("Import failed", message)
        assertFalse(message.contains("/data/user"))
    }
}
