package com.ctslab.ptalk_signature

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * In-app language preference: Vietnamese / English / follow system.
 *
 * Backed by AppCompat per-app locales. Persistence + re-applying before any Activity
 * starts is handled automatically by the [androidx.appcompat.app.AppLocalesMetadataHolderService]
 * declared in the manifest with `autoStoreLocales=true`, so the choice survives process
 * death without a manual SharedPreferences (mirrors how [ThemePrefs] handles the theme).
 *
 * Calling [set] triggers AppCompat to recreate the running activities, so the whole UI
 * repaints in the new language immediately.
 */
object LocalePrefs {
    const val SYSTEM = "system"
    const val VIETNAMESE = "vi"
    const val ENGLISH = "en"

    /** Current selection derived from the active app locales. */
    fun current(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return SYSTEM
        return when (locales[0]?.language) {
            "vi" -> VIETNAMESE
            "en" -> ENGLISH
            else -> SYSTEM
        }
    }

    /** Apply + persist a language. Activities are recreated automatically by AppCompat. */
    fun set(value: String) {
        AppCompatDelegate.setApplicationLocales(
            when (value) {
                VIETNAMESE -> LocaleListCompat.forLanguageTags("vi")
                ENGLISH -> LocaleListCompat.forLanguageTags("en")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
        )
    }
}
