package com.ctslab.ptalk_signature

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*

/**
 * Manages request quota tracking for P-Talk Signature
 * Tracks guest mode and authenticated user quotas
 */
object QuotaManager {
    private const val PREF_NAME = "ptalk_signature_quota"
    private const val KEY_GUEST_REQUEST_COUNT = "guest_request_count"
    private const val KEY_LAST_COUNT_RESET = "last_count_reset_date"
    private const val GUEST_DAILY_LIMIT = 20
    private lateinit var prefs: SharedPreferences
    private var currentUsername: String? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun setCurrentUser(username: String?) {
        currentUsername = username
    }

    fun getCurrentUsername(): String? = currentUsername

    fun isLoggedIn(): Boolean = currentUsername != null

    /**
     * Increment request count for guest or logged-in user
     * @return true if request allowed, false if quota exhausted
     */
    fun incrementRequest(): Boolean {
        if (isLoggedIn()) {
            return true // Logged-in users have unlimited requests (for now)
        }

        val today = getTodayDateString()
        val lastReset = prefs.getString(KEY_LAST_COUNT_RESET, "")
        val count = if (lastReset == today) {
            prefs.getInt(KEY_GUEST_REQUEST_COUNT, 0)
        } else {
            0
        }

        if (count >= GUEST_DAILY_LIMIT) {
            return false // Quota exhausted
        }

        prefs.edit().apply {
            putInt(KEY_GUEST_REQUEST_COUNT, count + 1)
            putString(KEY_LAST_COUNT_RESET, today)
            apply()
        }
        return true
    }

    /**
     * Get current quota status
     * @return QuotaStatus with used/limit info
     */
    fun getQuotaStatus(): QuotaStatus {
        return if (isLoggedIn()) {
            QuotaStatus(used = 0, limit = -1, isUnlimited = true) // Unlimited for logged-in
        } else {
            val today = getTodayDateString()
            val lastReset = prefs.getString(KEY_LAST_COUNT_RESET, "")
            val used = if (lastReset == today) {
                prefs.getInt(KEY_GUEST_REQUEST_COUNT, 0)
            } else {
                0
            }
            QuotaStatus(used = used, limit = GUEST_DAILY_LIMIT, isUnlimited = false)
        }
    }

    /**
     * Get usage percentage (0-100)
     */
    fun getUsagePercentage(): Int {
        val status = getQuotaStatus()
        if (status.limit <= 0) return 0
        return ((status.used * 100) / status.limit).coerceIn(0, 100)
    }

    /**
     * Check if user has reached quota limit
     */
    fun isQuotaExhausted(): Boolean {
        if (isLoggedIn()) return false
        val status = getQuotaStatus()
        return status.used >= status.limit
    }

    /**
     * Reset quota (for testing or date change)
     */
    fun resetQuota() {
        prefs.edit().apply {
            putInt(KEY_GUEST_REQUEST_COUNT, 0)
            putString(KEY_LAST_COUNT_RESET, getTodayDateString())
            apply()
        }
    }

    private fun getTodayDateString(): String {
        val calendar = java.util.Calendar.getInstance()
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", year, month, day)
    }

    data class QuotaStatus(
        val used: Int,
        val limit: Int,
        val isUnlimited: Boolean = false
    )
}
