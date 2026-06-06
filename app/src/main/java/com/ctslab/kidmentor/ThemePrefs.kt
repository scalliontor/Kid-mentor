package com.ctslab.kidmentor

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Light / Dark / Follow-system theme preference for Kid Mentor.
 *
 * The voice screen has two background "worlds" (mint garden = light, purple night =
 * dark). The user picks one from the hamburger menu; the choice is persisted and
 * re-applied on every launch (see [KidMentorApp]).
 */
object ThemePrefs {
    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"

    private const val PREFS = "km_theme_prefs"
    private const val KEY_MODE = "theme_mode"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The saved mode: [LIGHT], [DARK] or [SYSTEM] (default). */
    fun read(context: Context): String =
        prefs(context).getString(KEY_MODE, SYSTEM) ?: SYSTEM

    /** Apply the saved mode (call once at app startup). */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(modeFor(read(context)))
    }

    /** Persist [mode] and apply it immediately (recreates running activities). */
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
