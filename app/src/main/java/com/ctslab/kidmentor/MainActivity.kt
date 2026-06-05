package com.ctslab.kidmentor

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ctslab.kidmentor.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.ctslab.kidmentor.DashboardChatApi
import java.io.File

private enum class ActiveVoiceTransport {
    NONE,
    STREAMING,
    LEGACY_HTTP
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val audioRecorder by lazy { AudioRecorder(this) }
    private val audioPlayer by lazy { AudioPlayer() }
    private val apiService by lazy { ApiService(this) }
    private val streamingVoiceClient by lazy {
        StreamingVoiceClient(
            listener = object : StreamingVoiceClient.Listener {
                override fun onProtocolEvent(event: StreamingEvent) {
                    handleStreamingEvent(event)
                }

                override fun onAudioChunkReceived() {
                    handleStreamingAudioChunkReceived()
                }

                override fun onTransportFailure(type: StreamingFailure, message: String) {
                    handleStreamingFailure(type, message)
                }

                override fun onReachabilityChanged(reachability: WsReachability, reason: String?) {
                    handleStreamingReachabilityChanged(reachability, reason)
                }
            }
        )
    }

    private val characterAnimator by lazy { CharacterAnimator(binding.ivCharacter) }
    private val waveformView by lazy { binding.waveformView }
    private val isTabletDevice: Boolean
        get() = resources.configuration.smallestScreenWidthDp >= 600

    private var isMicGestureActive = false
    private var isMicTapFallbackActive = false
    private var suppressFallbackClickUntilMs = 0L
    private var activeTransport = ActiveVoiceTransport.NONE

    private var wsReachability = WsReachability.OFFLINE
    private var wsSessionPhase = WsSessionPhase.IDLE
    private var waitingForFirstStreamingChunk = false

    private var startAckTimeoutJob: Job? = null
    private var processingTimeoutJob: Job? = null
    private var firstAudioTimeoutJob: Job? = null
    private var playbackGapTimeoutJob: Job? = null
    private var idleFinalTimeoutJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        TokenManager.init(this)
        ActiveChild.init(this)
        DashboardChatApi.startNewSession()
        maybePromptAddChild()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // StarFieldView luôn hiện (kể cả Light hay Dark mode) theo yêu cầu
        binding.starFieldView.visibility = View.VISIBLE

        requestMicPermission()
        observeState()
        setupButtons()
        if (isTabletDevice) {
            binding.btnHoldToTalk.bringToFront()
        }
        characterAnimator.playIdle()

        streamingVoiceClient.preconnect()
        runHttpHealthDiagnostic()
    }

    override fun onResume() {
        super.onResume()
        streamingVoiceClient.preconnect()
        refreshActiveChildUi()
    }

    /** Show the active child's name in the sub-greeting; tap to switch child. */
    private fun refreshActiveChildUi() {
        if (!TokenManager.isLoggedIn()) return
        val name = ActiveChild.getName()
        binding.tvSubGreeting.text =
            if (!name.isNullOrBlank()) getString(R.string.main_active_child_fmt, name)
            else getString(R.string.main_sub_greeting)
        binding.tvSubGreeting.setOnClickListener { showChildPicker() }
    }

    /** Pick which child is currently using the app (drives RAG + chat history). */
    private fun showChildPicker() {
        lifecycleScope.launch {
            val children = ChildrenApiService.list()
            if (children == null) return@launch
            if (children.isEmpty()) {
                startActivity(android.content.Intent(this@MainActivity, ChildInfoActivity::class.java))
                return@launch
            }
            val names = children.map { it.fullName ?: getString(R.string.children_unnamed) }.toTypedArray()
            val checked = children.indexOfFirst { it.id != null && it.id == ActiveChild.getId() }
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.main_pick_child_title)
                .setSingleChoiceItems(names, checked) { dialog, which ->
                    val c = children[which]
                    if (c.id != null && c.username != null) {
                        ActiveChild.set(c.id, c.username, c.fullName)
                        refreshActiveChildUi()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.profile_close, null)
                .show()
        }
    }

    /**
     * After first login, prompt the parent once to add a child if they have none.
     * A transient network failure leaves the flag unset so it's retried next launch.
     */
    private fun maybePromptAddChild() {
        if (!TokenManager.isLoggedIn()) return
        val prefs = getSharedPreferences("ptalk_student_info", MODE_PRIVATE)
        if (prefs.getBoolean("prompted", false)) return
        lifecycleScope.launch {
            val children = ChildrenApiService.list() ?: return@launch
            prefs.edit().putBoolean("prompted", true).apply()
            // Auto-select the only child so the AI is personalized immediately.
            if (children.size == 1) {
                val c = children[0]
                if (c.id != null && c.username != null && !ActiveChild.isSet()) {
                    ActiveChild.set(c.id, c.username, c.fullName)
                    refreshActiveChildUi()
                }
            }
            if (children.isEmpty()) {
                startActivity(
                    android.content.Intent(this@MainActivity, ChildInfoActivity::class.java)
                        .putExtra(ChildInfoActivity.EXTRA_ONBOARDING, true)
                )
            }
        }
    }

    private fun runHttpHealthDiagnostic() {
        lifecycleScope.launch {
            val healthy = apiService.isServerHealthy()
            Log.d(TAG, "HTTP health diagnostic: healthy=$healthy, wsReachability=$wsReachability")

            if (!healthy && (viewModel.state.value == AppState.IDLE || viewModel.state.value == AppState.ERROR)) {
                if (wsReachability != WsReachability.ONLINE) {
                    viewModel.statusText.value = getString(R.string.status_ws_unstable)
                }
            }
        }
    }

    private fun observeState() {
        viewModel.statusText.observe(this) { text ->
            binding.tvStatus.text = text
        }

        viewModel.state.observe(this) { state ->
            when (state) {
                AppState.IDLE -> {
                    binding.btnHoldToTalk.isEnabled = true
                    binding.btnHoldToTalk.alpha = 1f
                    binding.btnHoldToTalk.setImageResource(R.drawable.ic_mic_custom)
                    binding.btnHoldToTalk.setBackgroundResource(
                        if (isTabletDevice) R.drawable.bg_hold_button_tablet else R.drawable.bg_hold_button
                    )
                    isMicGestureActive = false
                    isMicTapFallbackActive = false
                    characterAnimator.playIdle()
                    waveformView.setStateIdle()

                    // Khôi phục trạng thái text dựa trên reachability
                    if (wsReachability != WsReachability.ONLINE) {
                        updateWsReachability(wsReachability, null)
                    } else {
                        viewModel.statusText.value = getString(if (isTabletDevice) R.string.status_idle_tablet else R.string.status_idle)
                    }
                }

                AppState.RECORDING -> {
                    binding.btnHoldToTalk.isEnabled = true
                    binding.btnHoldToTalk.alpha = 0.75f
                    binding.btnHoldToTalk.setImageResource(R.drawable.ic_mic_custom)
                    binding.btnHoldToTalk.setBackgroundResource(
                        if (isTabletDevice) R.drawable.bg_hold_button_tablet else R.drawable.bg_hold_button
                    )
                    characterAnimator.playRecording()
                    waveformView.setStateRecording()
                    viewModel.statusText.value = getString(
                        if (isMicTapFallbackActive) R.string.status_listening_press_again else R.string.status_listening
                    )
                }

                AppState.UPLOADING -> {
                    binding.btnHoldToTalk.isEnabled = false
                    binding.btnHoldToTalk.alpha = 0.5f
                    binding.btnHoldToTalk.setImageResource(R.drawable.ic_mic_custom)
                    binding.btnHoldToTalk.setBackgroundResource(
                        if (isTabletDevice) R.drawable.bg_hold_button_tablet else R.drawable.bg_hold_button
                    )
                    isMicGestureActive = false
                    isMicTapFallbackActive = false
                    characterAnimator.playUploading()
                    waveformView.setStateUploading()
                    viewModel.statusText.value = getString(R.string.status_processing)
                }

                AppState.PLAYING -> {
                    binding.btnHoldToTalk.isEnabled = true
                    binding.btnHoldToTalk.alpha = 1f
                    binding.btnHoldToTalk.setImageResource(R.drawable.ic_close)
                    binding.btnHoldToTalk.setBackgroundResource(
                        if (isTabletDevice) R.drawable.bg_hold_button_tablet_cancel else R.drawable.bg_hold_button_cancel
                    )
                    isMicGestureActive = false
                    isMicTapFallbackActive = false
                    characterAnimator.playPlaying()
                    waveformView.setStatePlaying()
                    viewModel.statusText.value = getString(R.string.status_cancel)
                }

                AppState.ERROR -> {
                    binding.btnHoldToTalk.isEnabled = true
                    binding.btnHoldToTalk.alpha = 1f
                    binding.btnHoldToTalk.setImageResource(R.drawable.ic_mic_custom)
                    binding.btnHoldToTalk.setBackgroundResource(
                        if (isTabletDevice) R.drawable.bg_hold_button_tablet else R.drawable.bg_hold_button
                    )
                    isMicGestureActive = false
                    isMicTapFallbackActive = false
                    characterAnimator.playError()
                    waveformView.setStateError()
                    Toast.makeText(
                        this,
                        viewModel.statusText.value ?: "Có lỗi xảy ra",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupButtons() {
        // Hamburger menu - open profile/settings
        findViewById<android.widget.ImageView>(R.id.btnHamburger)?.setOnClickListener {
            val intent = android.content.Intent(this, HomeProfileActivity::class.java)
            startActivity(intent)
        }

        binding.btnHoldToTalk.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // If currently playing, tap the X button to cancel
                    if (viewModel.state.value == AppState.PLAYING) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        cancelCurrentPlayback()
                        return@setOnTouchListener true
                    }
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    val started = startMicCapture(fromTouch = true)
                    if (started) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isMicGestureActive) {
                        val centerX = view.width / 2f
                        val centerY = view.height / 2f
                        val distance = Math.hypot((event.x - centerX).toDouble(), (event.y - centerY).toDouble()).toFloat()

                        val threshold = 100 * resources.displayMetrics.density
                        val cancelZoneShadow = findViewById<android.view.View>(R.id.cancelZoneShadow)
                        
                        if (distance > threshold) {
                            // Vuốt ra ngoài ngưỡng -> Huỷ
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            finishMicCaptureAndSend(wasCancelled = true)
                            suppressFallbackClickUntilMs = SystemClock.elapsedRealtime() + 350L
                            
                            cancelZoneShadow?.animate()?.alpha(0f)?.setDuration(200)?.start()
                        } else {
                            // Cập nhật độ đậm của vùng bóng mờ khi kéo ra xa
                            if (cancelZoneShadow?.visibility != android.view.View.VISIBLE) {
                                cancelZoneShadow?.visibility = android.view.View.VISIBLE
                            }
                            cancelZoneShadow?.alpha = (distance / threshold).coerceIn(0f, 1f) * 0.8f
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (isMicGestureActive) {
                        finishMicCaptureAndSend(wasCancelled = event.action == MotionEvent.ACTION_CANCEL)
                        suppressFallbackClickUntilMs = SystemClock.elapsedRealtime() + 350L
                    }
                    findViewById<android.view.View>(R.id.cancelZoneShadow)?.animate()?.alpha(0f)?.setDuration(200)?.start()
                    true
                }

                else -> false
            }
        }

        if (isTabletDevice) {
            binding.btnHoldToTalk.setOnClickListener { view ->
                val now = SystemClock.elapsedRealtime()
                if (now < suppressFallbackClickUntilMs) {
                    return@setOnClickListener
                }

                when (viewModel.state.value) {
                    AppState.PLAYING -> {
                        cancelCurrentPlayback()
                    }

                    AppState.RECORDING -> {
                        if (isMicTapFallbackActive) {
                            finishMicCaptureAndSend(wasCancelled = false)
                            isMicTapFallbackActive = false
                        }
                    }

                    AppState.IDLE, AppState.ERROR -> {
                        val started = startMicCapture(fromTouch = false)
                        if (started) {
                            isMicTapFallbackActive = true
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            viewModel.statusText.value = getString(R.string.status_listening_press_again)
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun cancelCurrentPlayback() {
        clearAllStreamingTimeouts()
        waitingForFirstStreamingChunk = false
        if (activeTransport == ActiveVoiceTransport.STREAMING) {
            streamingVoiceClient.cancelPlaybackAndReset(notifyIdle = false)
        } else {
            audioPlayer.stop()
        }
        wsSessionPhase = WsSessionPhase.IDLE
        viewModel.onCancelPlayback()
        isMicGestureActive = false
        isMicTapFallbackActive = false
        activeTransport = ActiveVoiceTransport.NONE
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private var guestRequestCount = 0
    private val GUEST_MAX_REQUESTS = 20
    private var quotaCheckPassed = false
    private var cachedRemainingQuota = -1 // -1 = unknown

    private fun countQuotaUsage() {
        val isGuest = intent.getBooleanExtra("is_guest", false)
        if (isGuest) {
            val prefs = getSharedPreferences("ptalk_guest", MODE_PRIVATE)
            val used = prefs.getInt("guest_request_count", 0)
            prefs.edit().putInt("guest_request_count", used + 1).apply()
        } else {
            // Logged-in users: track quota server-side
            val username = TokenManager.getUsername() ?: return
            lifecycleScope.launch {
                val result = AuthApiService.useQuota(username)
                if (result == null) {
                    // Quota exhausted or error
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Đã hết quota hôm nay", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun startMicCapture(fromTouch: Boolean): Boolean {
        val state = viewModel.state.value ?: AppState.IDLE
        if (state == AppState.UPLOADING || state == AppState.PLAYING) return false

        if (!hasMicPermission()) {
            requestMicPermission()
            viewModel.statusText.value = getString(R.string.err_mic_permission)
            Toast.makeText(this, getString(R.string.err_mic_permission_toast), Toast.LENGTH_SHORT).show()
            return false
        }

        // Guest mode — check quota (don't count yet, count on send)
        val isGuest = intent.getBooleanExtra("is_guest", false)
        if (isGuest) {
            val prefs = getSharedPreferences("ptalk_guest", MODE_PRIVATE)
            val used = prefs.getInt("guest_request_count", 0)
            if (used >= GUEST_MAX_REQUESTS) {
                Toast.makeText(this, getString(R.string.profile_quota_exhausted), Toast.LENGTH_LONG).show()
                return false
            }
        } else {
            // Logged-in users — check cached quota
            if (cachedRemainingQuota == 0) {
                Toast.makeText(this, "Đã hết quota hôm nay", Toast.LENGTH_LONG).show()
                return false
            }
            // Refresh quota in background if unknown
            if (cachedRemainingQuota < 0) {
                val username = TokenManager.getUsername()
                if (username != null) {
                    lifecycleScope.launch {
                        val quota = AuthApiService.getQuota(username)
                        cachedRemainingQuota = quota?.remaining ?: -1
                    }
                }
            }
        }

        if (ServerConfig.TRANSPORT_MODE != TransportMode.LEGACY_HTTP_ONLY &&
            streamingVoiceClient.canStartStreaming()
        ) {
            activeTransport = ActiveVoiceTransport.STREAMING
            val startedStreaming = streamingVoiceClient.startSession()
            if (startedStreaming) {
                clearAllStreamingTimeouts()
                wsSessionPhase = WsSessionPhase.CAPTURING
                waitingForFirstStreamingChunk = false
                isMicGestureActive = fromTouch
                viewModel.onStartRecording()
                armStartAckTimeout()
                return true
            }
            activeTransport = ActiveVoiceTransport.NONE
            if (ServerConfig.TRANSPORT_MODE == TransportMode.STREAMING_ONLY) {
                viewModel.onError(getString(R.string.err_recording_start))
                return false
            }
        }

        if (ServerConfig.TRANSPORT_MODE == TransportMode.STREAMING_ONLY) {
            if (wsReachability == WsReachability.CONNECTING) {
                viewModel.statusText.value = getString(R.string.status_ws_connecting_wait)
            } else {
                val currentStatus = viewModel.statusText.value ?: "Unknown"
                viewModel.onError(getString(R.string.status_ws_error, wsReachability.name, currentStatus))
                streamingVoiceClient.preconnect() // Force retry
            }
            return false
        }

        return startLegacyMicCapture(fromTouch)
    }

    private fun startLegacyMicCapture(fromTouch: Boolean): Boolean {
        return try {
            audioRecorder.start()
            isMicGestureActive = fromTouch
            activeTransport = ActiveVoiceTransport.LEGACY_HTTP
            wsSessionPhase = WsSessionPhase.IDLE
            viewModel.onStartRecording()
            true
        } catch (_: Exception) {
            isMicGestureActive = false
            isMicTapFallbackActive = false
            activeTransport = ActiveVoiceTransport.NONE
            viewModel.onError(getString(R.string.err_recording_start))
            false
        }
    }

    private fun finishMicCaptureAndSend(wasCancelled: Boolean) {
        val state = viewModel.state.value
        if (state != AppState.RECORDING) {
            isMicGestureActive = false
            return
        }

        if (wasCancelled) {
            clearStartAckTimeout()
            if (activeTransport == ActiveVoiceTransport.STREAMING) {
                streamingVoiceClient.cancelPlaybackAndReset(notifyIdle = false)
            } else {
                audioRecorder.stop()
            }
            wsSessionPhase = WsSessionPhase.IDLE
            isMicGestureActive = false
            activeTransport = ActiveVoiceTransport.NONE
            viewModel.onError(getString(R.string.err_recording_cancel))
            return
        }

        // Ghi nhận quota khi thực sự gửi (không bị huỷ)
        countQuotaUsage()

        // Log voice interaction to Dashboard
        DashboardChatApi.logUserMessage("[Voice message]")

        if (activeTransport == ActiveVoiceTransport.STREAMING) {
            clearStartAckTimeout()
            streamingVoiceClient.stopSession(sendEnd = true)
            isMicGestureActive = false
            waitingForFirstStreamingChunk = true
            wsSessionPhase = WsSessionPhase.WAIT_PROCESSING
            viewModel.onStopRecording()
            armProcessingTimeout()
            return
        }

        val audioFile = audioRecorder.stop()
        isMicGestureActive = false
        activeTransport = ActiveVoiceTransport.NONE

        if (audioFile != null && audioFile.length() > 0) {
            viewModel.onStopRecording()
            sendAudioToServer(audioFile)
            return
        }

        viewModel.onError(getString(R.string.err_recording_fail))
    }

    private fun handleStreamingReachabilityChanged(reachability: WsReachability, reason: String?) {
        updateWsReachability(reachability, reason)
    }

    private fun updateWsReachability(reachability: WsReachability, reason: String?) {
        val previous = wsReachability
        wsReachability = reachability

        if (previous == reachability) {
            return
        }

        Log.d(TAG, "WS reachability: $previous -> $reachability, reason=$reason")

        val shouldRenderStatus = activeTransport == ActiveVoiceTransport.NONE &&
                (viewModel.state.value == AppState.IDLE || viewModel.state.value == AppState.ERROR)

        if (!shouldRenderStatus) return

        when (reachability) {
            WsReachability.ONLINE -> {
                if (viewModel.state.value == AppState.IDLE) {
                    viewModel.statusText.value = getString(if (isTabletDevice) R.string.status_idle_tablet else R.string.status_idle)
                }
            }

            WsReachability.CONNECTING -> {
                viewModel.statusText.value = getString(R.string.status_ws_connecting)
            }

            WsReachability.DEGRADED -> {
                viewModel.statusText.value = getString(R.string.status_ws_unstable)
            }

            WsReachability.OFFLINE -> {
                viewModel.statusText.value =
                    if (ServerConfig.TRANSPORT_MODE == TransportMode.STREAMING_ONLY) {
                        getString(R.string.status_ws_not_ready)
                    } else {
                        getString(R.string.status_ws_not_ready_backup)
                    }
            }
        }
    }

    private fun handleStreamingEvent(event: StreamingEvent) {
        updateWsReachability(WsReachability.ONLINE, "Protocol event: $event")

        when (event) {
            StreamingEvent.Listening -> {
                clearStartAckTimeout()
                wsSessionPhase = WsSessionPhase.CAPTURING
                if (viewModel.state.value != AppState.RECORDING) {
                    viewModel.onStartRecording()
                }
            }

            StreamingEvent.Processing -> {
                clearStartAckTimeout()
                clearProcessingTimeout()
                wsSessionPhase = WsSessionPhase.WAIT_AUDIO
                waitingForFirstStreamingChunk = true
                if (viewModel.state.value == AppState.RECORDING) {
                    viewModel.onStopRecording()
                } else {
                    viewModel.statusText.value = getString(R.string.status_processing)
                }
                armFirstAudioTimeout()
                armIdleFinalTimeout()
            }

            StreamingEvent.Thinking -> {
                // Server is still processing (8s+ elapsed) — reset the first-audio timer
                viewModel.statusText.value = getString(R.string.status_thinking)
                armFirstAudioTimeout()
            }

            StreamingEvent.Speaking -> {
                clearStartAckTimeout()
                clearProcessingTimeout()
                wsSessionPhase = WsSessionPhase.WAIT_AUDIO
                waitingForFirstStreamingChunk = true
                viewModel.onStartPlaying()  // Switch to X button with "Huỷ" label
                // Log robot response to Dashboard
                DashboardChatApi.logRobotResponse("[Voice response]")
                armFirstAudioTimeout()
                armIdleFinalTimeout()
            }

            StreamingEvent.StreamDone -> {
                // Server finished sending audio, clear playback gap timeout
                // but keep idle final timeout (waiting for IDLE)
                clearPlaybackGapTimeout()
            }

            StreamingEvent.Idle -> {
                clearAllStreamingTimeouts()
                waitingForFirstStreamingChunk = false
                wsSessionPhase = WsSessionPhase.IDLE
                activeTransport = ActiveVoiceTransport.NONE
                isMicGestureActive = false
                isMicTapFallbackActive = false
                viewModel.onFinishPlaying()
            }

            is StreamingEvent.Emotion -> {
                // Emotion code reserved for future character variants.
            }

            is StreamingEvent.UnknownText -> {
                viewModel.statusText.value = event.text
            }
        }
    }

    private fun handleStreamingFailure(type: StreamingFailure, message: String) {
        clearAllStreamingTimeouts()
        waitingForFirstStreamingChunk = false

        when (type) {
            StreamingFailure.WebSocketUnavailable,
            StreamingFailure.NetworkLost -> updateWsReachability(WsReachability.OFFLINE, message)

            StreamingFailure.ProtocolError,
            StreamingFailure.ServerError,
            StreamingFailure.CodecUnavailable -> updateWsReachability(WsReachability.DEGRADED, message)
        }

        if (activeTransport == ActiveVoiceTransport.STREAMING) {
            activeTransport = ActiveVoiceTransport.NONE
            wsSessionPhase = WsSessionPhase.IDLE
            isMicGestureActive = false
            isMicTapFallbackActive = false
            waitingForFirstStreamingChunk = false
            viewModel.onError(message)
            return
        }

        if (viewModel.state.value == AppState.IDLE || viewModel.state.value == AppState.ERROR) {
            viewModel.statusText.value = when (type) {
                StreamingFailure.CodecUnavailable -> getString(R.string.status_ws_not_ready_backup)
                StreamingFailure.WebSocketUnavailable -> message // Hiển thị nguyên văn lỗi (Timeout, Refused...)
                StreamingFailure.ProtocolError,
                StreamingFailure.NetworkLost,
                StreamingFailure.ServerError -> message
            }
        }
    }

    private fun handleStreamingAudioChunkReceived() {
        updateWsReachability(WsReachability.ONLINE, "Received streaming audio chunk")

        if (activeTransport != ActiveVoiceTransport.STREAMING) return

        if (waitingForFirstStreamingChunk) {
            waitingForFirstStreamingChunk = false
            wsSessionPhase = WsSessionPhase.PLAYING
            clearFirstAudioTimeout()
            viewModel.onStartPlaying()
        }

        // Reset idle final timeout — count from last chunk, not session start
        armIdleFinalTimeout()

        armStreamingPlaybackGapTimeout()
        armIdleFinalTimeout()
    }

    private fun sendAudioToServer(audioFile: File) {
        lifecycleScope.launch {
            val completed = withTimeoutOrNull(HTTP_SOFT_TIMEOUT_MS) {
                apiService.sendAudio(audioFile, object : ApiService.AudioResponseCallback {
                    override fun onSuccess(audioFile: File) {
                        runOnUiThread {
                            viewModel.onStartPlaying()
                            audioPlayer.play(
                                audioFile = audioFile,
                                onComplete = {
                                    runOnUiThread {
                                        activeTransport = ActiveVoiceTransport.NONE
                                        viewModel.onFinishPlaying()
                                    }
                                },
                                onError = { errorMsg ->
                                    runOnUiThread {
                                        activeTransport = ActiveVoiceTransport.NONE
                                        viewModel.onError(errorMsg)
                                    }
                                }
                            )
                        }
                    }

                    override fun onError(errorMessage: String) {
                        runOnUiThread {
                            activeTransport = ActiveVoiceTransport.NONE
                            viewModel.onError(errorMessage)
                        }
                    }
                })
                true
            }

            if (completed != true) {
                activeTransport = ActiveVoiceTransport.NONE
                viewModel.onError(getString(R.string.err_ws_connection))
            }
        }
    }

    private fun armStartAckTimeout() {
        clearStartAckTimeout()
        startAckTimeoutJob = lifecycleScope.launch {
            delay(START_ACK_TIMEOUT_MS)
            val stillWaitingAck = activeTransport == ActiveVoiceTransport.STREAMING &&
                    wsSessionPhase == WsSessionPhase.CAPTURING &&
                    viewModel.state.value == AppState.RECORDING
            if (stillWaitingAck) {
                onStreamingTimeout(
                    reachability = WsReachability.DEGRADED,
                    message = getString(R.string.err_ws_not_listening)
                )
            }
        }
    }

    private fun armProcessingTimeout() {
        clearProcessingTimeout()
        processingTimeoutJob = lifecycleScope.launch {
            delay(PROCESSING_TIMEOUT_MS)
            val stillWaitingProcessing = activeTransport == ActiveVoiceTransport.STREAMING &&
                    wsSessionPhase == WsSessionPhase.WAIT_PROCESSING &&
                    viewModel.state.value == AppState.UPLOADING
            if (stillWaitingProcessing) {
                onStreamingTimeout(
                    reachability = WsReachability.DEGRADED,
                    message = getString(R.string.err_ws_not_processing)
                )
            }
        }
    }

    private fun armFirstAudioTimeout() {
        clearFirstAudioTimeout()
        firstAudioTimeoutJob = lifecycleScope.launch {
            delay(FIRST_AUDIO_TIMEOUT_MS)
            val stillWaitingAudio = activeTransport == ActiveVoiceTransport.STREAMING &&
                    wsSessionPhase == WsSessionPhase.WAIT_AUDIO &&
                    waitingForFirstStreamingChunk
            if (stillWaitingAudio) {
                onStreamingTimeout(
                    reachability = WsReachability.DEGRADED,
                    message = getString(R.string.err_ws_not_audio)
                )
            }
        }
    }

    private fun armStreamingPlaybackGapTimeout() {
        clearPlaybackGapTimeout()
        playbackGapTimeoutJob = lifecycleScope.launch {
            delay(PLAYBACK_GAP_TIMEOUT_MS)
            val stalledPlayback = activeTransport == ActiveVoiceTransport.STREAMING &&
                    wsSessionPhase == WsSessionPhase.PLAYING &&
                    viewModel.state.value == AppState.PLAYING
            if (stalledPlayback) {
                onStreamingTimeout(
                    reachability = WsReachability.DEGRADED,
                    message = getString(R.string.err_ws_interrupted)
                )
            }
        }
    }

    private fun armIdleFinalTimeout() {
        clearIdleFinalTimeout()
        idleFinalTimeoutJob = lifecycleScope.launch {
            delay(IDLE_FINAL_TIMEOUT_MS)
            val missingIdleFinal = activeTransport == ActiveVoiceTransport.STREAMING &&
                    wsSessionPhase != WsSessionPhase.IDLE &&
                    (viewModel.state.value == AppState.UPLOADING || viewModel.state.value == AppState.PLAYING)
            if (missingIdleFinal) {
                onStreamingTimeout(
                    reachability = WsReachability.DEGRADED,
                    message = getString(R.string.err_ws_not_idle)
                )
            }
        }
    }

    private fun onStreamingTimeout(reachability: WsReachability, message: String) {
        clearAllStreamingTimeouts()

        if (activeTransport != ActiveVoiceTransport.STREAMING) {
            return
        }

        updateWsReachability(reachability, message)
        waitingForFirstStreamingChunk = false
        wsSessionPhase = WsSessionPhase.IDLE
        activeTransport = ActiveVoiceTransport.NONE
        isMicGestureActive = false
        isMicTapFallbackActive = false

        // Drain remaining audio before resetting — don't abruptly stop playback
        Thread {
            streamingVoiceClient.drainPlaybackAndReset(notifyIdle = false)
            runOnUiThread {
                viewModel.onFinishPlaying()
            }
        }.start()
    }

    private fun clearStartAckTimeout() {
        startAckTimeoutJob?.cancel()
        startAckTimeoutJob = null
    }

    private fun clearProcessingTimeout() {
        processingTimeoutJob?.cancel()
        processingTimeoutJob = null
    }

    private fun clearFirstAudioTimeout() {
        firstAudioTimeoutJob?.cancel()
        firstAudioTimeoutJob = null
    }

    private fun clearPlaybackGapTimeout() {
        playbackGapTimeoutJob?.cancel()
        playbackGapTimeoutJob = null
    }

    private fun clearIdleFinalTimeout() {
        idleFinalTimeoutJob?.cancel()
        idleFinalTimeoutJob = null
    }

    private fun clearAllStreamingTimeouts() {
        clearStartAckTimeout()
        clearProcessingTimeout()
        clearFirstAudioTimeout()
        clearPlaybackGapTimeout()
        clearIdleFinalTimeout()
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                viewModel.statusText.value = getString(R.string.err_mic_permission)
                Toast.makeText(this, getString(R.string.err_mic_permission_activity), Toast.LENGTH_LONG).show()
            }
        }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // End Dashboard chat session
        DashboardChatApi.endSession()
        clearAllStreamingTimeouts()
        streamingVoiceClient.shutdown()
        audioPlayer.stop()
        audioRecorder.stop()
        characterAnimator.stopCurrent()
    }

    private companion object {
        private const val TAG = "MainActivity"

        private const val START_ACK_TIMEOUT_MS = 5_000L
        private const val PROCESSING_TIMEOUT_MS = 10_000L
        private const val FIRST_AUDIO_TIMEOUT_MS = 30_000L
        private const val PLAYBACK_GAP_TIMEOUT_MS = 30_000L
        private const val IDLE_FINAL_TIMEOUT_MS = 120_000L

        private const val HTTP_SOFT_TIMEOUT_MS = 90_000L
    }
}
