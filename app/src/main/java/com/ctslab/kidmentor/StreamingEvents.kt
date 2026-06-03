package com.ctslab.kidmentor

sealed class StreamingEvent {
    data object Listening : StreamingEvent()
    data object Processing : StreamingEvent()
    data object Thinking : StreamingEvent()
    data object Speaking : StreamingEvent()
    data object StreamDone : StreamingEvent()
    data object Idle : StreamingEvent()
    data class Emotion(val code: String) : StreamingEvent()
    data class UnknownText(val text: String) : StreamingEvent()
}

enum class StreamingFailure {
    CodecUnavailable,
    WebSocketUnavailable,
    ProtocolError,
    NetworkLost,
    ServerError
}

object StreamingEventParser {
    fun parse(text: String): StreamingEvent {
        return when (text) {
            "LISTENING" -> StreamingEvent.Listening
            "PROCESSING" -> StreamingEvent.Processing
            "THINKING" -> StreamingEvent.Thinking
            "SPEAKING" -> StreamingEvent.Speaking
            "STREAM_DONE" -> StreamingEvent.StreamDone
            "IDLE" -> StreamingEvent.Idle
            else -> {
                if (text.length == 2 && text.all { it.isDigit() }) {
                    StreamingEvent.Emotion(text)
                } else {
                    StreamingEvent.UnknownText(text)
                }
            }
        }
    }
}
