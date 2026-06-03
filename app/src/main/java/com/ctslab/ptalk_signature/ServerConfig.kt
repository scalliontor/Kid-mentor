package com.ctslab.ptalk_signature

object ServerConfig {
    /** Raw host:port — used to construct full URLs */
    const val SERVER_HOST_RAW = "171.226.10.121:8000"
    const val SERVER_HOST = "http://$SERVER_HOST_RAW"

    // ── Active mode (set at runtime by ModeSelectActivity) ─────────────
    var activeMode: AppMode = AppMode.KID_MENTOR

    /** HTTP base URL for the active mode (used by Retrofit) */
    val HTTP_BASE_URL: String
        get() = activeMode.httpBaseUrl()

    /** WebSocket URL for the active mode */
    val WS_URL: String
        get() = activeMode.wsUrl()

    // Cho phép fallback xuống HTTP nếu WebSocket hoặc Opus Codec lỗi
    val TRANSPORT_MODE = TransportMode.AUTO
    val OPUS_ENGINE_MODE = OpusEngineMode.AUTO
}

enum class TransportMode {
    AUTO,
    STREAMING_ONLY,
    LEGACY_HTTP_ONLY
}
