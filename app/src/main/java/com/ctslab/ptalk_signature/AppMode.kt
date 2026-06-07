package com.ctslab.ptalk_signature

import androidx.annotation.StringRes

/**
 * Chế độ hoạt động của ứng dụng.
 * Mỗi chế độ có endpoint, UI theme và nội dung khác nhau.
 *
 * Văn bản hiển thị được tham chiếu qua resource id (@StringRes) thay vì literal,
 * để toàn bộ chữ đổi theo ngôn ngữ Việt/Anh người dùng chọn (xem [LocalePrefs]).
 */
enum class AppMode(
    val httpBasePath: String,
    val wsPath: String,
    @StringRes val brandTitleRes: Int,
    @StringRes val greetingTextRes: Int,
    @StringRes val subGreetingTextRes: Int,
    @StringRes val statusIdleTextRes: Int,
) {
    KID_MENTOR(
        httpBasePath = "v2",
        wsPath = "v2/ws",
        brandTitleRes = R.string.brand_kid_mentor,
        greetingTextRes = R.string.greeting_hello,
        subGreetingTextRes = R.string.kid_sub_greeting,
        statusIdleTextRes = R.string.status_idle,
    ),
    ELDER_CARE(
        httpBasePath = "eldercare",
        wsPath = "eldercare/ws",
        brandTitleRes = R.string.brand_elder_care,
        greetingTextRes = R.string.greeting_hello,
        subGreetingTextRes = R.string.elder_sub_greeting,
        statusIdleTextRes = R.string.status_idle,
    );

    /** Full HTTP base URL for Retrofit */
    fun httpBaseUrl(): String =
        "${ServerConfig.SERVER_HOST}/$httpBasePath/"

    /** Full WebSocket URL for OkHttp */
    fun wsUrl(): String =
        "ws://${ServerConfig.SERVER_HOST_RAW}/$wsPath"
}
