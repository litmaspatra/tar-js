package com.tarjs.app.core

import android.content.Context

enum class ChatTheme {
    TELEGRAM_LIGHT,
    TELEGRAM_DARK,
    TARJS_VIOLET
}

val ChatTheme.usesDarkSystemBars: Boolean
    get() = this == ChatTheme.TELEGRAM_DARK

class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val theme: ChatTheme
        get() = preferences.getString(THEME_KEY, null)
            ?.let { stored -> ChatTheme.entries.firstOrNull { it.name == stored } }
            ?: ChatTheme.TELEGRAM_LIGHT

    fun setTheme(theme: ChatTheme) {
        preferences.edit().putString(THEME_KEY, theme.name).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "appearance"
        const val THEME_KEY = "chat_theme"
    }
}
