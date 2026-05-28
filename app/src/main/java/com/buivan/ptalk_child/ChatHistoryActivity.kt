package com.buivan.ptalk_child

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ChatHistoryActivity : AppCompatActivity() {

    private lateinit var rvSessions: RecyclerView
    private lateinit var rvMessages: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var tvTitle: TextView

    private val sessionsAdapter = SessionsAdapter { session ->
        loadMessages(session.id, session.title ?: "Phiên chat")
    }

    private val messagesAdapter = MessagesAdapter()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_history)

        TokenManager.init(this)

        rvSessions = findViewById(R.id.rvSessions)
        rvMessages = findViewById(R.id.rvMessages)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvTitle = findViewById(R.id.tvTitle)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            if (rvMessages.visibility == View.VISIBLE) {
                showSessions()
            } else {
                finish()
            }
        }

        rvSessions.layoutManager = LinearLayoutManager(this)
        rvSessions.adapter = sessionsAdapter

        rvMessages.layoutManager = LinearLayoutManager(this)
        rvMessages.adapter = messagesAdapter

        loadSessions()
    }

    private fun loadSessions() {
        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        rvSessions.visibility = View.GONE
        rvMessages.visibility = View.GONE
        tvTitle.text = "Lịch sử chat"

        lifecycleScope.launch {
            try {
                val sessions = withContext(Dispatchers.IO) { fetchSessions() }
                progressBar.visibility = View.GONE
                if (sessions.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    rvSessions.visibility = View.VISIBLE
                    sessionsAdapter.submitList(sessions)
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "Lỗi: ${e.message}"
            }
        }
    }

    private fun loadMessages(sessionId: String, sessionTitle: String) {
        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        rvSessions.visibility = View.GONE
        rvMessages.visibility = View.GONE
        tvTitle.text = sessionTitle

        lifecycleScope.launch {
            try {
                val messages = withContext(Dispatchers.IO) { fetchMessages(sessionId) }
                progressBar.visibility = View.GONE
                if (messages.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "Phiên chat trống"
                } else {
                    rvMessages.visibility = View.VISIBLE
                    messagesAdapter.submitList(messages)
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "Lỗi: ${e.message}"
            }
        }
    }

    private fun showSessions() {
        rvMessages.visibility = View.GONE
        tvEmpty.visibility = View.GONE
        loadSessions()
    }

    @Suppress("UNCHECKED_CAST")
    private fun fetchSessions(): List<ChatSession> {
        val token = TokenManager.getAccessToken() ?: return emptyList()
        val url = "${DashboardChatApi.BASE_URL}/api/v1/chat/sessions?product_source=kid_mentor&limit=50"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()

        val body = response.body?.string() ?: return emptyList()
        val json = Gson().fromJson(body, SessionsResponse::class.java)
        return json.sessions ?: emptyList()
    }

    private fun fetchMessages(sessionId: String): List<ChatMessage> {
        val token = TokenManager.getAccessToken() ?: return emptyList()
        val url = "${DashboardChatApi.BASE_URL}/api/v1/chat/messages?session_id=$sessionId&limit=100"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()

        val body = response.body?.string() ?: return emptyList()
        val json = Gson().fromJson(body, MessagesResponse::class.java)
        return json.messages ?: emptyList()
    }

    // ── Data classes ─────────────────────────────────────────────────

    data class SessionsResponse(
        @SerializedName("sessions") val sessions: List<ChatSession>?
    )

    data class MessagesResponse(
        @SerializedName("messages") val messages: List<ChatMessage>?
    )

    data class ChatSession(
        @SerializedName("id") val id: String,
        @SerializedName("title") val title: String?,
        @SerializedName("messageCount") val messageCount: Int,
        @SerializedName("startedAt") val startedAt: String?,
        @SerializedName("lastMessageAt") val lastMessageAt: String?
    )

    data class ChatMessage(
        @SerializedName("id") val id: String,
        @SerializedName("sender") val sender: String,
        @SerializedName("content") val content: String,
        @SerializedName("createdAt") val createdAt: String?
    )

    // ── Adapters ─────────────────────────────────────────────────────

    class SessionsAdapter(
        private val onClick: (ChatSession) -> Unit
    ) : RecyclerView.Adapter<SessionsAdapter.VH>() {

        private var items = listOf<ChatSession>()

        fun submitList(list: List<ChatSession>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_session, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title ?: "Voice Chat"
            holder.tvInfo.text = "${item.messageCount} tin nhắn"
            holder.tvDate.text = item.lastMessageAt?.take(10) ?: ""
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvSessionTitle)
            val tvInfo: TextView = view.findViewById(R.id.tvSessionInfo)
            val tvDate: TextView = view.findViewById(R.id.tvSessionDate)
        }
    }

    class MessagesAdapter : RecyclerView.Adapter<MessagesAdapter.VH>() {

        private var items = listOf<ChatMessage>()

        fun submitList(list: List<ChatMessage>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_message, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvContent.text = item.content
            holder.tvTime.text = item.createdAt?.take(16)?.replace("T", " ") ?: ""

            val card = holder.card
            val params = card.layoutParams as ViewGroup.MarginLayoutParams
            if (item.sender == "user") {
                params.marginStart = 80
                params.marginEnd = 0
                card.setCardBackgroundColor(0xFFFFEBEE.toInt()) // light red
            } else {
                params.marginStart = 0
                params.marginEnd = 80
                card.setCardBackgroundColor(0xFFFFFFFF.toInt()) // white
            }
            card.layoutParams = params
        }

        override fun getItemCount() = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.cardMessage)
            val tvContent: TextView = view.findViewById(R.id.tvMessageContent)
            val tvTime: TextView = view.findViewById(R.id.tvMessageTime)
        }
    }
}
