package com.buivan.ptalk_child

import android.Manifest
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

class StreamingVoiceClient(
    private val listener: Listener,
    private val wsUrl: String = ServerConfig.WS_URL
) {
    interface Listener {
        fun onProtocolEvent(event: StreamingEvent)
        fun onAudioChunkReceived()
        fun onTransportFailure(type: StreamingFailure, message: String)
        fun onReachabilityChanged(reachability: WsReachability, reason: String?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(40, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var opusEngine: OpusEngine? = null
    private var micStreamer: PcmMicStreamer? = null
    private var audioPlayer: StreamingAudioPlayer? = null
    private val isConnected = AtomicBoolean(false)
    private val isSessionActive = AtomicBoolean(false)
    private var hasNotifiedCodecFailure = false
    private var resolvedEngineMode: OpusEngineMode? = null
    private var isEnginePermanentlyUnavailable = false
    private var reachability: WsReachability = WsReachability.OFFLINE
    private var unavailableUntilMs = 0L
    private var isShuttingDown = false

    private val outgoingQueue = ConcurrentLinkedQueue<ByteArray>()
    private val isServerListening = AtomicBoolean(false)

    fun preconnect() {
        if (ServerConfig.TRANSPORT_MODE == TransportMode.LEGACY_HTTP_ONLY) return
        if (!resolveEngineMode()) {
            unavailableUntilMs = Long.MAX_VALUE
            return
        }
        Log.d("StreamingVoiceClient", "Preconnect WebSocket: $wsUrl")
        connectIfNeeded()
    }

    fun canStartStreaming(): Boolean {
        if (!resolveEngineMode()) return false
        if (webSocket == null && !isTemporarilyUnavailable()) {
            connectIfNeeded()
        }
        return ServerConfig.TRANSPORT_MODE != TransportMode.LEGACY_HTTP_ONLY &&
                !isTemporarilyUnavailable() &&
                resolvedEngineMode != null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startSession(): Boolean {
        if (!canStartStreaming()) return false

        outgoingQueue.clear()
        isServerListening.set(false)
        connectIfNeeded()

        return try {
            val activeEngine = createSessionEngine() ?: return false
            val activePlayer = StreamingAudioPlayer(activeEngine)
            opusEngine = activeEngine
            audioPlayer = activePlayer

            isSessionActive.set(true)
            
            if (isConnected.get() && webSocket != null) {
                val cmd = if (activeEngine.isPcmEncoding) "START_PCM" else "START"
                webSocket?.send(cmd)
            }

            micStreamer = PcmMicStreamer(
                opusEngine = activeEngine,
                onOpusPacket = { packet ->
                    val encoded = AudioFrameProtocol.packFrame(packet)
                    if (isServerListening.get() && isConnected.get() && webSocket != null) {
                        webSocket?.send(ByteString.of(*encoded)) ?: false
                    } else {
                        // Limit pre-buffering to ~2 seconds (100 packets)
                        if (outgoingQueue.size > 100) {
                            outgoingQueue.poll()
                        }
                        outgoingQueue.offer(encoded)
                        true
                    }
                },
                onError = { t ->
                    stopSession(sendEnd = false)
                    setReachability(WsReachability.DEGRADED, "Mic stream interrupted")
                    notifyTransportFailure(StreamingFailure.NetworkLost, "Luồng ghi âm bị gián đoạn: ${t.message ?: "unknown"}")
                }
            )

            val started = micStreamer?.start() == true
            if (!started) {
                stopSession(sendEnd = false)
                return false
            }
            true
        } catch (t: Throwable) {
            cleanupSession()
            notifyTransportFailure(StreamingFailure.CodecUnavailable, "Không khởi động được streaming: ${t.message ?: "unknown"}")
            false
        }
    }

    fun stopSession(sendEnd: Boolean = true) {
        val hadSession = isSessionActive.getAndSet(false)
        micStreamer?.stop()
        micStreamer = null
        if (sendEnd && hadSession) {
            webSocket?.send("END")
        }
    }

    fun cancelPlaybackAndReset(notifyIdle: Boolean = true) {
        stopSession(sendEnd = false)
        cleanupSession()
        if (notifyIdle) {
            mainHandler.post { listener.onProtocolEvent(StreamingEvent.Idle) }
        }
    }

    fun shutdown() {
        isShuttingDown = true
        stopSession(sendEnd = false)
        cleanupSession()
        webSocket?.close(1000, "Activity destroyed")
        webSocket = null
        isConnected.set(false)
        okHttpClient.dispatcher.executorService.shutdown()
    }

    private fun connectIfNeeded() {
        if (webSocket != null || isTemporarilyUnavailable()) return
        isShuttingDown = false
        setReachability(WsReachability.CONNECTING, "Đang kết nối WebSocket...")
        Log.d("StreamingVoiceClient", "Opening WebSocket: $wsUrl")
        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("StreamingVoiceClient", "WebSocket connected: ${response.code}")
                webSocket.send("""{"device_id":"android_app","firmware_version":"2.0.0"}""")
                isConnected.set(true)
                unavailableUntilMs = 0L
                setReachability(WsReachability.ONLINE, "WebSocket connected")
                
                if (isSessionActive.get()) {
                    val activeEngine = opusEngine
                    val cmd = if (activeEngine?.isPcmEncoding == true) "START_PCM" else "START"
                    webSocket.send(cmd)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = StreamingEventParser.parse(text)
                if (event == StreamingEvent.Listening) {
                    isServerListening.set(true)
                    while (true) {
                        val packet = outgoingQueue.poll() ?: break
                        webSocket.send(ByteString.of(*packet))
                    }
                }
                if (event == StreamingEvent.Speaking) {
                    audioPlayer?.start()
                }
                if (event == StreamingEvent.Idle) {
                    cleanupSession()
                }
                mainHandler.post { listener.onProtocolEvent(event) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    audioPlayer?.start()
                    audioPlayer?.playPacket(bytes.toByteArray())
                    mainHandler.post { listener.onAudioChunkReceived() }
                } catch (e: ProtocolException) {
                    stopSession(sendEnd = false)
                    setReachability(WsReachability.DEGRADED, "Invalid audio frame format from server")
                    notifyTransportFailure(StreamingFailure.ProtocolError, "Gói audio từ server không hợp lệ.")
                } catch (t: Throwable) {
                    stopSession(sendEnd = false)
                    setReachability(WsReachability.DEGRADED, "Streaming audio playback failed")
                    notifyTransportFailure(StreamingFailure.ServerError, "Không phát được audio streaming: ${t.message ?: "unknown"}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected.set(false)
                this@StreamingVoiceClient.webSocket = null
                setReachability(WsReachability.OFFLINE, "WebSocket closed ($code)")
                if (!isShuttingDown && isSessionActive.get()) {
                    notifyTransportFailure(StreamingFailure.NetworkLost, "Mất kết nối WebSocket khi đang truyền âm thanh.")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("StreamingVoiceClient", "WebSocket failure: ${t.message}")
                isConnected.set(false)
                this@StreamingVoiceClient.webSocket = null
                unavailableUntilMs = SystemClock.elapsedRealtime() + RETRY_COOLDOWN_MS
                cleanupSession()
                setReachability(WsReachability.OFFLINE, "WebSocket failure: ${t.message ?: "unknown"}")
                notifyTransportFailure(StreamingFailure.WebSocketUnavailable, "Lỗi kết nối WS: ${t.message ?: "Mất mạng"}")
            }
        })
    }



    private fun isTemporarilyUnavailable(): Boolean {
        return SystemClock.elapsedRealtime() < unavailableUntilMs
    }

    private fun cleanupSession() {
        micStreamer?.stop()
        micStreamer = null
        audioPlayer?.stop()
        audioPlayer = null
        opusEngine?.release()
        opusEngine = null
        isSessionActive.set(false)
        isServerListening.set(false)
        outgoingQueue.clear()
    }

    private fun resolveEngineMode(): Boolean {
        if (resolvedEngineMode != null) return true
        if (isEnginePermanentlyUnavailable) return false

        val candidateModes = when (ServerConfig.OPUS_ENGINE_MODE) {
            OpusEngineMode.AUTO -> listOf(OpusEngineMode.FORCE_MEDIACODEC, OpusEngineMode.FORCE_JNI)
            OpusEngineMode.FORCE_MEDIACODEC -> listOf(OpusEngineMode.FORCE_MEDIACODEC)
            OpusEngineMode.FORCE_JNI -> listOf(OpusEngineMode.FORCE_JNI)
        }

        for (candidateMode in candidateModes) {
            val engine = OpusEngineFactory.instantiate(candidateMode) ?: continue
            val probePassed = OpusEngineFactory.runLocalProbe(engine)
            if (probePassed) {
                resolvedEngineMode = candidateMode
                hasNotifiedCodecFailure = false
                Log.d("StreamingVoiceClient", "Using Opus engine mode: $resolvedEngineMode")
                return true
            }
        }

        if (!hasNotifiedCodecFailure) {
            hasNotifiedCodecFailure = true
            notifyTransportFailure(
                StreamingFailure.CodecUnavailable,
                "Opus engine chưa tương thích backend. App sẽ dùng HTTP dự phòng."
            )
        }
        isEnginePermanentlyUnavailable = true
        unavailableUntilMs = Long.MAX_VALUE
        return false
    }

    private fun createSessionEngine(): OpusEngine? {
        val mode = resolvedEngineMode ?: return null
        val engine = OpusEngineFactory.instantiate(mode) ?: return null
        engine.start()
        return engine
    }

    private fun setReachability(next: WsReachability, reason: String?) {
        if (reachability == next) return
        reachability = next
        mainHandler.post { listener.onReachabilityChanged(next, reason) }
    }

    private fun notifyTransportFailure(type: StreamingFailure, message: String) {
        mainHandler.post { listener.onTransportFailure(type, message) }
    }

    private companion object {
        const val RETRY_COOLDOWN_MS = 1_000L
    }
}
