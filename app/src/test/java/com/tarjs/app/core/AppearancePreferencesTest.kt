package com.tarjs.app.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearancePreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun firstLaunchUsesTelegramLight() {
        assertEquals(ChatTheme.TELEGRAM_LIGHT, AppearancePreferences(context).theme)
    }

    @Test
    fun everyThemePersistsAcrossInstances() {
        ChatTheme.entries.forEach { theme ->
            AppearancePreferences(context).setTheme(theme)
            assertEquals(theme, AppearancePreferences(context).theme)
        }
    }

    @Test
    fun unknownStoredThemeFallsBackToTelegramLight() {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .edit()
            .putString("chat_theme", "REMOVED_THEME")
            .commit()

        assertEquals(ChatTheme.TELEGRAM_LIGHT, AppearancePreferences(context).theme)
    }
}
