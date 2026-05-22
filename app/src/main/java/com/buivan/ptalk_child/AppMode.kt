package com.buivan.ptalk_child

/**
 * Chế độ hoạt động của ứng dụng.
 * Mỗi chế độ có endpoint, UI theme và nội dung khác nhau.
 */
enum class AppMode(
    val httpBasePath: String,
    val wsPath: String,
    val brandTitle: String,
    val greetingText: String,
    val subGreetingText: String,
    val statusIdleText: String,
    val loginHeadline: String,
    val loginSubheadline: String,
) {
    KID_MENTOR(
        httpBasePath = "v2",
        wsPath = "v2/ws",
        brandTitle = "KID MENTOR",
        greetingText = "Trợ lý học tập cá nhân",
        subGreetingText = "",
        statusIdleText = "Giữ nút để nói chuyện",
        loginHeadline = "CHÀO MỪNG\nBẠN TRỞ LẠI",
        loginSubheadline = "Đăng nhập để tiếp tục học cùng PTalk",
    ),
    ELDER_CARE(
        httpBasePath = "eldercare",
        wsPath = "eldercare/ws",
        brandTitle = "ELDER CARE",
        greetingText = "Trợ lý sức khoẻ cá nhân",
        subGreetingText = "",
        statusIdleText = "Giữ nút để nói chuyện",
        loginHeadline = "CHÀO MỪNG\nBÁC TRỞ LẠI",
        loginSubheadline = "Đăng nhập để tiếp tục trò chuyện cùng AI",
    );

    /** Full HTTP base URL for Retrofit */
    fun httpBaseUrl(): String =
        "${ServerConfig.SERVER_HOST}/$httpBasePath/"

    /** Full WebSocket URL for OkHttp */
    fun wsUrl(): String =
        "ws://${ServerConfig.SERVER_HOST_RAW}/$wsPath"
}
