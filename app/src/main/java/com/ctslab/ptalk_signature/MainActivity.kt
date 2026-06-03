package com.ctslab.ptalk_signature

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
import com.buivan.ptalk_child.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private enum class ActiveVoiceTransport {
    NONE,
    STREAMING,
    LEGACY_HTTP
}

class MainActivity : AppCompatActivity() {

    private var appMode: AppMode = AppMode.KID_MENTOR

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

        // ── Nhận AppMode từ intent ─────────────────────────────────────
        val modeName = intent.getStringExtra(ModeSelectActivity.EXTRA_APP_MODE)
        appMode = modeName?.let { AppMode.valueOf(it) } ?: AppMode.KID_MENTOR
        ServerConfig.activeMode = appMode

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        applyModeUI()
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

    /**
     * Thay đổi giao diện (text, màu sắc, background) dựa theo AppMode.
     */
    private fun applyModeUI() {
        // Greeting texts
        binding.tvGreeting.text = appMode.greetingText
        binding.tvSubGreeting.text = appMode.subGreetingText

        // Brand title (center text in brand bar)
        binding.tvBrandTitle.text = appMode.brandTitle

        // Status idle text
        viewModel.statusText.value = appMode.statusIdleText

        // Background gradient
        if (appMode == AppMode.ELDER_CARE) {
            binding.main.setBackgroundResource(R.drawable.bg_gradient_eldercare)
            binding.tvGreeting.setTextColor(0xFFD35400.toInt())
            binding.tvSubGreeting.setTextColor(0xFFBF6516.toInt())
            binding.tvStatus.setTextColor(0xFFBF6516.toInt())
            binding.btnCancel.setBackgroundResource(R.drawable.bg_cancel_button_elder)
        }
    }

    override fun onResume() {
        super.onResume()
        streamingVoiceClient.preconnect()
    }

    private fun runHttpHealthDiagnostic() {
        lifecycleScope.launch {
            val healthy = apiService.isServerHealthy()
            Log.d(TAG, "HTTP health diagnostic: healthy=$healthy, wsReachability=$wsReachability")

            if (!healthy && (viewModel.state.value == AppState.IDLE || viewModel.state.value == AppState.ERROR)) {
                if (wsReachability != WsReachability.ONLINE) {
                    viewModel.statusText.value = "WebSocket V2 là đường chính, health HTTP đang lỗi"
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
                    binding.btnCancel.visibility = View.GONE
                    isMicGestureActive = false
                    isMicTapFallbackActive = false
                    characterAnimator.playIdle()
                    waveformView.setStateIdle()
                }

                AppState.RECORDING -> {
                    binding.btnHoldToTalk.isEnabled = true
                    binding.btnHoldToTalk.alpha = 0.75f
                    binding.btnCancel.visibility = View.GONE
                    characterAnimator.playRecording()
                    waveformView.setStateRecording()
                }

                AppState.UPLOADING -> {
                    binding.btnHoldToTalk.isEnabled = false
                    binding.btnHoldToTalk.alpha = 0.5f
                    binding.btnCancel.visibility = View.GONE
                    isMicGestureActive = false
                    isMicTapFallbackActive = false
                    characterAnimator.playUploading()
                    waveformView.setStateUploading()
                }

                AppState.PLAYING -> {
                    binding.btnHoldToTalk.isEnabled = false
                    binding.btnHoldToTalk.alpha = 0.5f
                    binding.btnCancel.visibility = View.VISIBLE
                    isMicGestureActive = false
                    isMicTapFallbackActive = false
                    characterAnimator.playPlaying()
                    waveformView.setStatePlaying()
                }

                AppState.ERROR -> {
                    binding.btnHoldToTalk.isEnabled = true
                    binding.btnHoldToTalk.alpha = 1f
                    binding.btnCancel.visibility = View.GONE
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
        binding.btnHoldToTalk.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    val started = startMicCapture(fromTouch = true)
                    if (started) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (isMicGestureActive) {
                        finishMicCaptureAndSend(wasCancelled = event.action == MotionEvent.ACTION_CANCEL)
                        suppressFallbackClickUntilMs = SystemClock.elapsedRealtime() + 350L
                    }
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
                            viewModel.statusText.value = "Đang nghe... (nhấn lại để gửi)"
                        }
                    }

                    else -> Unit
                }
            }
        }

        binding.btnCancel.setOnClickListener {
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
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun startMicCapture(fromTouch: Boolean): Boolean {
        val state = viewModel.state.value ?: AppState.IDLE
        if (state == AppState.UPLOADING || state == AppState.PLAYING) return false

        if (!hasMicPermission()) {
            requestMicPermission()
            viewModel.statusText.value = "Cần cấp quyền micro để ghi âm"
            Toast.makeText(this, "Vui lòng cấp quyền micro để tiếp tục.", Toast.LENGTH_SHORT).show()
            return false
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
                viewModel.onError("Không thể mở WebSocket streaming, thử lại sau.")
                return false
            }
        }

        if (ServerConfig.TRANSPORT_MODE == TransportMode.STREAMING_ONLY) {
            if (wsReachability == WsReachability.CONNECTING) {
                viewModel.statusText.value = "Đang kết nối tới máy chủ, vui lòng chờ 1-2 giây rồi bấm lại..."
            } else {
                val currentStatus = viewModel.statusText.value ?: "Unknown"
                viewModel.onError("Lỗi máy chủ ($wsReachability): $currentStatus")
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
            viewModel.onError("Không thể bắt đầu ghi âm, thử lại!")
            false
        }
    }

    private fun finishMicCaptureAndSend(wasCancelled: Boolean) {
        val state = viewModel.state.value
        if (state != AppState.RECORDING) {
            isMicGestureActive = false
            return
        }

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

        if (wasCancelled) {
            viewModel.onError("Ghi âm bị gián đoạn, giữ nút và thử lại.")
        } else {
            viewModel.onError("Ghi âm thất bại, thử lại!")
        }
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
                    viewModel.statusText.value = "Giữ nút để nói chuyện"
                }
            }

            WsReachability.CONNECTING -> {
                viewModel.statusText.value = "Đang kết nối WebSocket..."
            }

            WsReachability.DEGRADED -> {
                viewModel.statusText.value = "WebSocket không ổn định, app sẽ thử đường dự phòng"
            }

            WsReachability.OFFLINE -> {
                viewModel.statusText.value =
                    if (ServerConfig.TRANSPORT_MODE == TransportMode.STREAMING_ONLY) {
                        "WebSocket V2 chưa sẵn sàng"
                    } else {
                        "WebSocket V2 chưa sẵn sàng, dùng HTTP dự phòng"
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
                    viewModel.statusText.value = "Đang xử lý..."
                }
                armFirstAudioTimeout()
                armIdleFinalTimeout()
            }

            StreamingEvent.Thinking -> {
                // Server is still processing (8s+ elapsed) — reset the first-audio timer
                viewModel.statusText.value = "Đang suy nghĩ..."
                armFirstAudioTimeout()
            }

            StreamingEvent.Speaking -> {
                clearStartAckTimeout()
                clearProcessingTimeout()
                wsSessionPhase = WsSessionPhase.WAIT_AUDIO
                waitingForFirstStreamingChunk = true
                viewModel.onStartPlaying()  // Disable button immediately
                viewModel.statusText.value = "Đang nói..."
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
                StreamingFailure.CodecUnavailable -> "Streaming V2 chưa tương thích codec, dùng HTTP dự phòng"
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
                viewModel.onError("Không kết nối được server. Kiểm tra server demo hoặc mạng.")
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
                    message = "Server chưa xác nhận nghe (LISTENING), bé thử lại nhé"
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
                    message = "Server chưa phản hồi PROCESSING, bé thử lại nhé"
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
                    message = "Server chưa trả audio, bé thử lại nhé"
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
                    message = "Luồng trả lời bị gián đoạn, bé thử lại nhé"
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
                    message = "Server chưa kết thúc phiên trả lời (IDLE), bé thử lại nhé"
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
                viewModel.statusText.value = "Cần cấp quyền micro để ghi âm"
                Toast.makeText(this, "App cần quyền micro để hoạt động!", Toast.LENGTH_LONG).show()
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
