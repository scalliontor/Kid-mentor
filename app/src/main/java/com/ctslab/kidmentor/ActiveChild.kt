package com.ctslab.kidmentor

import android.content.Context
import android.content.SharedPreferences

/**
 * The "bé đang dùng app" currently selected by the parent. Persisted locally so the
 * choice survives restarts. The child's [username] is sent as `device_id` in the WS
 * handshake (StreamingVoiceClient) → CloudPTalk attributes RAG personalization +
 * chat history to this child.
 *
 * Singleton (like [TokenManager]) so it's reachable from the WS client without a
 * Context. Call [init] once early (MainActivity.onCreate).
 */
object ActiveChild {

    private const val PREF_FILE = "ptalk_active_child"
    private const val KEY_ID = "child_id"
    private const val KEY_USERNAME = "child_username"
    private const val KEY_NAME = "child_name"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        }
    }

    fun set(id: String, username: String, name: String?) {
        prefs?.edit()?.apply {
            putString(KEY_ID, id)
            putString(KEY_USERNAME, username)
            putString(KEY_NAME, name)
            apply()
        }
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    fun getId(): String? = prefs?.getString(KEY_ID, null)
    fun getUsername(): String? = prefs?.getString(KEY_USERNAME, null)
    fun getName(): String? = prefs?.getString(KEY_NAME, null)
    fun isSet(): Boolean = !getUsername().isNullOrEmpty()
}
