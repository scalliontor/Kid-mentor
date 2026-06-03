package com.ctslab.kidmentor

enum class WsReachability {
    CONNECTING,
    ONLINE,
    DEGRADED,
    OFFLINE
}

enum class WsSessionPhase {
    IDLE,
    CAPTURING,
    WAIT_PROCESSING,
    WAIT_AUDIO,
    PLAYING
}
