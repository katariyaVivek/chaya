package com.chaya.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether Chaya follows the phone's light or dark setting, or keeps one of its own. */
enum class ThemeMode(val label: String) {
    SYSTEM("Same as phone"),
    LIGHT("Light"),
    DARK("Dark");

    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }
}

/** Remembers the person's [ThemeMode] across launches. One small value, so plain SharedPreferences. */
class ThemeSettings(private val prefs: SharedPreferences) {
    private val _mode = MutableStateFlow(read())
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun set(mode: ThemeMode) {
        prefs.edit().putString(KEY, mode.name).apply()
        _mode.value = mode
    }

    private fun read(): ThemeMode =
        prefs.getString(KEY, null)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM

    companion object {
        const val KEY = "theme_mode"

        fun from(context: Context) =
            ThemeSettings(context.getSharedPreferences("chaya_prefs", Context.MODE_PRIVATE))
    }
}
