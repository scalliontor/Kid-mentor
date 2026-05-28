package com.buivan.ptalk_child

object ServerConfig {
    const val HTTP_BASE_URL = "https://auth.ctslab.net/v2/"
    const val WS_URL = "wss://auth.ctslab.net/v2/ws"
    
    // Cho phép fallback xuống HTTP nếu WebSocket hoặc Opus Codec lỗi
    val TRANSPORT_MODE = TransportMode.AUTO 
    val OPUS_ENGINE_MODE = OpusEngineMode.AUTO
}

enum class TransportMode {
    AUTO,
    STREAMING_ONLY,
    LEGACY_HTTP_ONLY
}
