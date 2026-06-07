package com.ctslab.ptalk_signature

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ctslab.ptalk_signature.databinding.ActivityMainBinding
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
    private val holoboxApi by lazy { HoloboxApi() }   // ELDER_CARE: gọi API PTIT trực tiếp
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

        // Shared settings screen (carries the active mode through for theming + mode section).
        binding.btnAccount?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                putExtra(ModeSelectActivity.EXTRA_APP_MODE, appMode.name)
            })
        }

        // Quay lại màn chọn chế độ (nút trên màn hình + phím back hệ thống).
        binding.btnBackHome?.setOnClickListener { goToModeSelect() }
        onBackPressedDispatcher.addCallback(this) { goToModeSelect() }
        if (isTabletDevice) {
            binding.btnHoldToTalk.bringToFront()
        }
        characterAnimator.playIdle()

        if (appMode == AppMode.ELDER_CARE) {
            // ELDER_CARE dùng API trực tiếp của bản elder_care (Holobox + Dify),
            // không đi qua WebSocket server -> bỏ preconnect/health, bật các nút phụ.
            setupEldercareButtons()
        } else {
            streamingVoiceClient.preconnect()
            runHttpHealthDiagnostic()
        }
    }

    /**
     * Thay đổi giao diện (text, màu sắc, background) dựa theo AppMode.
     */
    private fun applyModeUI() {
        // Greeting texts
        binding.tvGreeting.text = getString(appMode.greetingTextRes)
        binding.tvSubGreeting.text = getString(appMode.subGreetingTextRes)

        // Brand title (center text in brand bar)
        binding.tvBrandTitle.text = getString(appMode.brandTitleRes)

        // Status idle text
        viewModel.statusText.value = getString(appMode.statusIdleTextRes)

        // Background gradient
        if (appMode == AppMode.ELDER_CARE) {
            binding.main.setBackgroundResource(R.drawable.bg_gradient_eldercare)
            binding.tvGreeting.setTextColor(ContextCompat.getColor(this, R.color.greeting_elder))
            binding.tvSubGreeting.setTextColor(ContextCompat.getColor(this, R.color.subgreeting_elder))
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.subgreeting_elder))
        }
    }

    override fun onResume() {
        super.onResume()
        if (appMode != AppMode.ELDER_CARE) streamingVoiceClient.preconnect()
    }

    /** Quay về màn chọn chế độ Kid/Elder (giữ nguyên phiên đăng nhập). */
    private fun goToModeSelect() {
        val isGuest = intent.getBooleanExtra(LoginActivity.EXTRA_IS_GUEST, false)
        val target = Intent(this, ModeSelectActivity::class.java).apply {
            putExtra(LoginActivity.EXTRA_IS_GUEST, isGuest)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(target)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun runHttpHealthDiagnostic() {
        lifecycleScope.launch {
            val healthy = apiService.isServerHealthy()
            Log.d(TAG, "HTTP health diagnostic: healthy=$healthy, wsReachability=$wsReachability")

            if (!healthy && (viewModel.state.value == AppState.IDLE || viewModel.state.value == AppState.ERROR)) {
                if (wsReachability != WsReachability.ONLINE) {
                    viewModel.statusText.value = getString(R.string.status_http_health_fail)
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
                    viewModel.statusText.value = getString(R.string.status_tap_to_stop)
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
                        viewModel.statusText.value ?: getString(R.string.error_generic_short),
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
                    // Đang phát tiếng -> chạm nút X để huỷ phát
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
                            // Vuốt ra ngoài ngưỡng -> Huỷ ghi âm
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            finishMicCaptureAndSend(wasCancelled = true)
                            suppressFallbackClickUntilMs = SystemClock.elapsedRealtime() + 350L
                            cancelZoneShadow?.animate()?.alpha(0f)?.setDuration(200)?.start()
                        } else {
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
                            viewModel.statusText.value = getString(R.string.status_listening_tap_again)
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    /** Huỷ phát tiếng đang chạy (chạm nút X khi đang phát). Mirror KidMentor. */
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

    private fun startMicCapture(fromTouch: Boolean): Boolean {
        val state = viewModel.state.value ?: AppState.IDLE
        if (state == AppState.UPLOADING || state == AppState.PLAYING) return false

        if (!hasMicPermission()) {
            requestMicPermission()
            viewModel.statusText.value = getString(R.string.mic_permission_needed)
            Toast.makeText(this, getString(R.string.mic_permission_prompt), Toast.LENGTH_SHORT).show()
            return false
        }

        if (appMode == AppMode.ELDER_CARE) {
            // ELDER_CARE: ghi âm m4a rồi gọi Holobox trực tiếp (bỏ qua WebSocket streaming).
            return startLegacyMicCapture(fromTouch)
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
                viewModel.onError(getString(R.string.error_ws_open))
                return false
            }
        }

        if (ServerConfig.TRANSPORT_MODE == TransportMode.STREAMING_ONLY) {
            if (wsReachability == WsReachability.CONNECTING) {
                viewModel.statusText.value = getString(R.string.status_connecting_retry)
            } else {
                val currentStatus = viewModel.statusText.value ?: "Unknown"
                viewModel.onError(getString(R.string.error_server_fmt, wsReachability.toString(), currentStatus))
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
            viewModel.onError(getString(R.string.error_record_start))
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
            if (appMode == AppMode.ELDER_CARE) {
                sendAudioToHolobox(audioFile)
            } else {
                sendAudioToServer(audioFile)
            }
            return
        }

        if (wasCancelled) {
            viewModel.onError(getString(R.string.error_record_interrupted))
        } else {
            viewModel.onError(getString(R.string.error_record_failed))
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
                    viewModel.statusText.value = getString(R.string.status_idle)
                }
            }

            WsReachability.CONNECTING -> {
                viewModel.statusText.value = getString(R.string.status_ws_connecting)
            }

            WsReachability.DEGRADED -> {
                viewModel.statusText.value = getString(R.string.status_ws_degraded)
            }

            WsReachability.OFFLINE -> {
                viewModel.statusText.value =
                    if (ServerConfig.TRANSPORT_MODE == TransportMode.STREAMING_ONLY) {
                        getString(R.string.status_ws_offline)
                    } else {
                        getString(R.string.status_ws_offline_fallback)
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
                viewModel.onStartPlaying()  // Disable button immediately
                viewModel.statusText.value = getString(R.string.status_speaking)
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
                StreamingFailure.CodecUnavailable -> getString(R.string.error_codec_fallback)
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
                                onError = { _ ->
                                    runOnUiThread {
                                        activeTransport = ActiveVoiceTransport.NONE
                                        viewModel.onError(getString(R.string.error_audio_playback))
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
                viewModel.onError(getString(R.string.error_no_server))
            }
        }
    }

    /**
     * ELDER_CARE: gửi audio m4a tới Holobox/Dify trực tiếp (STT -> Chat -> TTS),
     * nhận về 1 file WAV rồi phát bằng AudioPlayer như nhánh HTTP cũ.
     */
    private fun sendAudioToHolobox(audioFile: File) {
        lifecycleScope.launch {
            try {
                val replyWav = holoboxApi.processVoiceTurn(audioFile, cacheDir)
                viewModel.onStartPlaying()
                audioPlayer.play(
                    audioFile = replyWav,
                    onComplete = {
                        replyWav.delete()
                        runOnUiThread {
                            activeTransport = ActiveVoiceTransport.NONE
                            viewModel.onFinishPlaying()
                        }
                    },
                    onError = { _ ->
                        replyWav.delete()
                        runOnUiThread {
                            activeTransport = ActiveVoiceTransport.NONE
                            viewModel.onError(getString(R.string.error_audio_playback))
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Holobox voice turn lỗi", e)
                activeTransport = ActiveVoiceTransport.NONE
                viewModel.onError(friendlyNetworkMessage(e))
            }
        }
    }

    /** Đổi exception kỹ thuật thành thông báo dễ hiểu cho người lớn tuổi. */
    private fun friendlyNetworkMessage(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> getString(R.string.net_no_internet)
        is java.net.SocketTimeoutException -> getString(R.string.net_slow)
        is java.io.IOException -> e.message ?: getString(R.string.net_io)
        else -> e.message ?: getString(R.string.net_unknown)
    }

    /** Bật & nối các nút riêng của ELDER_CARE: quét thuốc + gọi khẩn cấp. */
    private fun setupEldercareButtons() {
        binding.btnScanMedicine?.visibility = View.VISIBLE
        binding.btnEmergency?.visibility = View.VISIBLE

        binding.btnScanMedicine?.setOnClickListener {
            startActivity(Intent(this, MedicineScannerActivity::class.java).apply {
                putExtra(ModeSelectActivity.EXTRA_APP_MODE, appMode.name)
            })
        }
        binding.btnEmergency?.setOnClickListener {
            val number = AppSettings.getEmergencyNumber(this)
            startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number")))
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
                    message = getString(R.string.error_timeout_listening)
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
                    message = getString(R.string.error_timeout_processing)
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
                    message = getString(R.string.error_timeout_audio)
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
                    message = getString(R.string.error_timeout_stream)
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
                    message = getString(R.string.error_timeout_idle)
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
                viewModel.statusText.value = getString(R.string.mic_permission_needed)
                Toast.makeText(this, getString(R.string.mic_permission_toast), Toast.LENGTH_LONG).show()
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
        // ELDER_CARE không dùng streaming -> tránh ép khởi tạo lazy chỉ để shutdown.
        if (appMode != AppMode.ELDER_CARE) streamingVoiceClient.shutdown()
        audioPlayer.stop()
        audioRecorder.stop()
        characterAnimator.stopCurrent()
    }

    private companion object {
        private const val TAG = "MainActivity"

        private const val EMERGENCY_NUMBER = "113"

        private const val START_ACK_TIMEOUT_MS = 5_000L
        private const val PROCESSING_TIMEOUT_MS = 10_000L
        private const val FIRST_AUDIO_TIMEOUT_MS = 30_000L
        private const val PLAYBACK_GAP_TIMEOUT_MS = 30_000L
        private const val IDLE_FINAL_TIMEOUT_MS = 120_000L

        private const val HTTP_SOFT_TIMEOUT_MS = 90_000L
    }
}
