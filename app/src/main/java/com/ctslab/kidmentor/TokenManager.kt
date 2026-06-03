package com.buivan.ptalk_child

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages JWT tokens securely using EncryptedSharedPreferences.
 * Tokens are encrypted at rest — not accessible via root or backup.
 */
object TokenManager {

    private const val PREF_FILE = "ptalk_auth_tokens"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USERNAME = "username"
    private const val KEY_USER_TYPE = "user_type"
    private const val KEY_EXPIRES_AT = "expires_at"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return

        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveTokens(
        accessToken: String,
        refreshToken: String,
        expiresIn: Int,
        username: String? = null,
        userType: String? = null
    ) {
        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)
        prefs?.edit()?.apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_EXPIRES_AT, expiresAt)
            username?.let { putString(KEY_USERNAME, it) }
            userType?.let { putString(KEY_USER_TYPE, it) }
            apply()
        }
    }

    fun getAccessToken(): String? = prefs?.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs?.getString(KEY_REFRESH_TOKEN, null)

    fun getUsername(): String? = prefs?.getString(KEY_USERNAME, null)

    fun getUserType(): String? = prefs?.getString(KEY_USER_TYPE, null)

    fun isLoggedIn(): Boolean {
        val token = getAccessToken()
        return !token.isNullOrEmpty()
    }

    fun isTokenExpired(): Boolean {
        val expiresAt = prefs?.getLong(KEY_EXPIRES_AT, 0L) ?: 0L
        // Add 60s buffer for clock skew
        return System.currentTimeMillis() > (expiresAt - 60_000)
    }

    fun clearTokens() {
        prefs?.edit()?.clear()?.apply()
    }
}
