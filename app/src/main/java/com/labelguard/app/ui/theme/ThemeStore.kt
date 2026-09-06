package com.labelguard.app.ui.theme

import android.content.Context

enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("labelguard_theme", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
