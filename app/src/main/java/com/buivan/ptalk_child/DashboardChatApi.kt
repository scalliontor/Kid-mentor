package com.buivan.ptalk_child

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- Data classes ---

data class CreateSessionRequest(
    val product_source: String = "kidmentor",
    val channel: String = "app",
    val title: String? = null
)

data class CreateSessionResponse(
    val success: Boolean,
    val sessionId: String?,
    val startedAt: String?
)

data class CreateMessageRequest(
    val session_id: String,
    val sender: String,
    val content: String,
    val message_type: String = "voice",
    val audio_url: String? = null,
    val sentiment: String? = "neutral",
    val emotion_code: String? = null
)

data class CreateMessageResponse(
    val success: Boolean,
    val messageId: String?,
    val createdAt: String?
)

data class ChatSession(
    val id: String,
    val userId: String,
    val deviceId: String?,
    val productSource: String,
    val channel: String,
    val title: String?,
    val messageCount: Int,
    val avgSentiment: String?,
    val startedAt: String,
    val lastMessageAt: String?
)

data class SessionsResponse(
    val sessions: List<ChatSession>,
    val pagination: PaginationInfo
)

data class PaginationInfo(
    val page: Int,
    val limit: Int,
    val total: Int,
    val totalPages: Int
)

// --- Retrofit interface ---

interface DashboardChatService {
    @POST("api/v1/chat/sessions")
    suspend fun createSession(@Body request: CreateSessionRequest): Response<CreateSessionResponse>

    @POST("api/v1/chat/messages")
    suspend fun createMessage(@Body request: CreateMessageRequest): Response<CreateMessageResponse>

    @GET("api/v1/chat/sessions")
    suspend fun getSessions(
        @Query("product_source") productSource: String?,
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): Response<SessionsResponse>
}

/**
 * Manages chat session persistence to the Dashboard backend.
 * Kid Mentor voice interactions are logged as chat messages so they
 * appear in the Dashboard's chat history view.
 *
 * Usage:
 *   DashboardChatApi.logUserMessage("Xin chào")
 *   DashboardChatApi.logRobotResponse("Chào bé! Bạn khỏe không?")
 */
object DashboardChatApi {

    private const val TAG = "DashboardChatApi"
    private const val DASHBOARD_BASE_URL = "http://171.226.10.121:3000/"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val token = TokenManager.getAccessToken()
            val request = if (!token.isNullOrEmpty()) {
                original.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                original
            }
            chain.proceed(request)
        }
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(DASHBOARD_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service = retrofit.create(DashboardChatService::class.java)

    // Current active session ID (created on first message)
    @Volatile
    private var currentSessionId: String? = null

    // Pending messages queued before session is created
    private val pendingMessages = mutableListOf<PendingMessage>()

    private data class PendingMessage(
        val sender: String,
        val content: String,
        val sentiment: String?
    )

    /**
     * Create a new chat session. Called once per voice conversation.
     * Returns the session ID, or null if creation failed.
     */
    private suspend fun ensureSession(title: String? = null): String? {
        // Fast path: already have a session
        currentSessionId?.let { return it }

        return sessionMutex.withLock {
            // Double-check after acquiring lock
            currentSessionId?.let { return it }

            try {
                val response = service.createSession(
                    CreateSessionRequest(
                        product_source = "kidmentor",
                        channel = "app",
                        title = title ?: "Kid Mentor - ${TokenManager.getUsername() ?: "Guest"}"
                    )
                )

                if (response.isSuccessful && response.body()?.success == true) {
                    val sessionId = response.body()?.sessionId
                    currentSessionId = sessionId
                    Log.d(TAG, "Session created: $sessionId")

                    // Flush any pending messages
                    flushPendingMessages(sessionId)

                    sessionId
                } else {
                    Log.w(TAG, "Session creation failed: ${response.code()}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Session creation error: ${e.message}")
                null
            }
        }
    }

    private suspend fun flushPendingMessages(sessionId: String) {
        val messages: List<PendingMessage>
        synchronized(pendingMessages) {
            messages = pendingMessages.toList()
            pendingMessages.clear()
        }

        for (msg in messages) {
            try {
                service.createMessage(
                    CreateMessageRequest(
                        session_id = sessionId,
                        sender = msg.sender,
                        content = msg.content,
                        message_type = "voice",
                        sentiment = msg.sentiment ?: "neutral"
                    )
                )
                Log.d(TAG, "Flushed pending message: ${msg.sender}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush pending message: ${e.message}")
            }
        }
    }

    /**
     * Log a user voice message (what the user said).
     */
    fun logUserMessage(content: String, sentiment: String? = "neutral") {
        logMessage("user", content, sentiment)
    }

    /**
     * Log a robot response (what the AI said back).
     */
    fun logRobotResponse(content: String, sentiment: String? = "neutral") {
        logMessage("robot", content, sentiment)
    }

    private fun logMessage(sender: String, content: String, sentiment: String?) {
        scope.launch {
            try {
                val sessionId = ensureSession()

                if (sessionId == null) {
                    // Queue message for later flush
                    synchronized(pendingMessages) {
                        pendingMessages.add(PendingMessage(sender, content, sentiment))
                    }
                    Log.d(TAG, "Queued message (no session yet): $sender")
                    return@launch
                }

                val response = service.createMessage(
                    CreateMessageRequest(
                        session_id = sessionId,
                        sender = sender,
                        content = content,
                        message_type = "voice",
                        sentiment = sentiment ?: "neutral"
                    )
                )

                if (response.isSuccessful) {
                    Log.d(TAG, "Message logged: $sender - ${content.take(50)}...")
                } else {
                    Log.w(TAG, "Message log failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message log error: ${e.message}")
            }
        }
    }

    /**
     * End the current session (call when user leaves the chat screen).
     */
    fun endSession() {
        val sessionId = currentSessionId
        Log.d(TAG, "Session ended: $sessionId")
        currentSessionId = null
        synchronized(pendingMessages) {
            pendingMessages.clear()
        }
    }
}
