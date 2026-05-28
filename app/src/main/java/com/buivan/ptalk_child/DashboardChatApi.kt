package com.buivan.ptalk_child

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Dashboard Chat API client for Kid Mentor voice interactions.
 * Persists voice chat sessions and messages to the Dashboard backend.
 *
 * Thread-safe: uses Mutex for session creation and @Volatile for sessionId.
 */
object DashboardChatApi {
    private const val TAG = "DashboardChatApi"
    private const val BASE_URL = "https://auth.ctslab.net"
    private const val SESSIONS_URL = "$BASE_URL/api/v1/chat/sessions"
    private const val MESSAGES_URL = "$BASE_URL/api/v1/chat/messages"

    @Volatile
    private var currentSessionId: String? = null
    private val sessionMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Pending messages sent before session is created
    private val pendingMessages = mutableListOf<PendingMessage>()

    private data class PendingMessage(
        val sender: String,
        val content: String,
        val messageType: String = "text"
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = TokenManager.getAccessToken()
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .build()
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Start a new chat session for voice interaction.
     * Thread-safe: uses Mutex to prevent race conditions.
     */
    fun startNewSession(deviceId: String? = null) {
        scope.launch {
            sessionMutex.withLock {
                try {
                    val body = JSONObject().apply {
                        put("product_source", "kid_mentor")
                        put("channel", "voice")
                        put("title", "Voice Chat Session")
                        deviceId?.let { put("device_id", it) }
                    }

                    val request = Request.Builder()
                        .url(SESSIONS_URL)
                        .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "{}")
                        currentSessionId = json.optString("sessionId")
                        Log.d(TAG,"Session created: $currentSessionId")

                        // Flush any pending messages
                        flushPendingMessages()
                    } else {
                        Log.e(TAG,"Failed to create session: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG,"Error creating session: ${e.message}")
                }
            }
        }
    }

    /**
     * Log a user voice message.
     * If session not ready, queues the message for later.
     */
    fun logUserMessage(content: String) {
        val sessionId = currentSessionId
        if (sessionId == null) {
            synchronized(pendingMessages) {
                pendingMessages.add(PendingMessage("user", content, "voice"))
            }
            return
        }
        sendMessage(sessionId, "user", content, "voice")
    }

    /**
     * Log a robot/assistant response.
     * If session not ready, queues the message for later.
     */
    fun logRobotResponse(content: String) {
        val sessionId = currentSessionId
        if (sessionId == null) {
            synchronized(pendingMessages) {
                pendingMessages.add(PendingMessage("assistant", content, "voice"))
            }
            return
        }
        sendMessage(sessionId, "assistant", content, "voice")
    }

    private fun sendMessage(sessionId: String, sender: String, content: String, messageType: String) {
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("session_id", sessionId)
                    put("sender", sender)
                    put("content", content)
                    put("message_type", messageType)
                }

                val request = Request.Builder()
                    .url(MESSAGES_URL)
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG,"Message logged: $sender - ${content.take(50)}")
                } else {
                    Log.e(TAG,"Failed to log message: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG,"Error logging message: ${e.message}")
            }
        }
    }

    private suspend fun flushPendingMessages() {
        val sessionId = currentSessionId ?: return
        synchronized(pendingMessages) {
            pendingMessages.forEach { msg ->
                sendMessage(sessionId, msg.sender, msg.content, msg.messageType)
            }
            pendingMessages.clear()
        }
    }

    /**
     * End the current session.
     */
    fun endSession() {
        scope.launch {
            sessionMutex.withLock {
                // Flush any remaining messages
                flushPendingMessages()
                currentSessionId = null
                Log.d(TAG,"Session ended")
            }
        }
    }

    /**
     * Get the current session ID (for debugging).
     */
    fun getCurrentSessionId(): String? = currentSessionId
}
