package com.ctslab.ptalk_signature

import android.content.Context

/**
 * Lightweight, mode-independent app preferences (shared across Kid Mentor & Elder Care).
 * Account/auth state lives in [TokenManager]; this only holds user-tunable settings.
 */
object AppSettings {
    private const val PREF_FILE = "ptalk_app_settings"
    private const val KEY_EMERGENCY = "emergency_number"
    const val DEFAULT_EMERGENCY = "113"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun getEmergencyNumber(context: Context): String =
        prefs(context).getString(KEY_EMERGENCY, DEFAULT_EMERGENCY)
            ?.ifBlank { DEFAULT_EMERGENCY } ?: DEFAULT_EMERGENCY

    fun setEmergencyNumber(context: Context, value: String) {
        val cleaned = value.trim().ifBlank { DEFAULT_EMERGENCY }
        prefs(context).edit().putString(KEY_EMERGENCY, cleaned).apply()
    }
}
