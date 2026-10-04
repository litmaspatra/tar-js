package com.tarjs.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatThemeTest {
    @Test
    fun onlyTelegramDarkUsesDarkSystemBars() {
        assertTrue(ChatTheme.TELEGRAM_DARK.usesDarkSystemBars)
        assertFalse(ChatTheme.TELEGRAM_LIGHT.usesDarkSystemBars)
        assertFalse(ChatTheme.TARJS_VIOLET.usesDarkSystemBars)
    }
}
