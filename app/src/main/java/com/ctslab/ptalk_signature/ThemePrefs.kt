package com.ctslab.ptalk_signature

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Light / Dark / System theme preference, shared app-wide (mirrors KidMentor's mechanism).
 * Applied once in [PtalkApp.onCreate] and updated live from Settings.
 */
object ThemePrefs {
    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"

    private const val PREFS = "ptalk_theme_prefs"
    private const val KEY_MODE = "theme_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Current saved mode (defaults to SYSTEM). */
    fun read(context: Context): String =
        prefs(context).getString(KEY_MODE, SYSTEM) ?: SYSTEM

    /** Apply the saved mode to AppCompatDelegate (call at startup). */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(modeFor(read(context)))
    }

    /** Persist + apply a new mode immediately. */
    fun set(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(modeFor(mode))
    }

    private fun modeFor(mode: String): Int = when (mode) {
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}
