package com.ctslab.kidmentor

object ServerConfig {
    const val HTTP_BASE_URL = "http://171.226.10.121:8000/v2/"
    const val WS_URL = "ws://171.226.10.121:8000/v2/ws"

    // Cho phép fallback xuống HTTP nếu WebSocket hoặc Opus Codec lỗi
    val TRANSPORT_MODE = TransportMode.AUTO
    val OPUS_ENGINE_MODE = OpusEngineMode.AUTO
}

enum class TransportMode {
    AUTO,
    STREAMING_ONLY,
    LEGACY_HTTP_ONLY
}
