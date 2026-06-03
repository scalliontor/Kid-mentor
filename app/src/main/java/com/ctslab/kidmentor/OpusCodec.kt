package com.buivan.ptalk_child

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

object OpusAudioFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNEL_COUNT = 1
    const val FRAME_DURATION_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE / 1000 * FRAME_DURATION_MS
    const val PCM_BYTES_PER_SAMPLE = 2
    const val PCM_FRAME_BYTES = SAMPLES_PER_FRAME * PCM_BYTES_PER_SAMPLE
}

enum class OpusEngineMode {
    AUTO,
    FORCE_MEDIACODEC,
    FORCE_JNI
}

interface OpusEngine {
    val name: String
    val isPcmEncoding: Boolean
    fun isSupported(): Boolean
    fun start()
    fun encodeFrame(pcmFrame: ByteArray): ByteArray?
    fun decodeFrame(opusFrame: ByteArray): ByteArray?
    fun reset()
    fun release()
}

data class OpusEngineSelection(
    val engine: OpusEngine?,
    val mode: OpusEngineMode,
    val reason: String? = null
)

object OpusEngineFactory {
    private const val TAG = "OpusEngineFactory"

    fun instantiate(mode: OpusEngineMode): OpusEngine? {
        val candidate = when (mode) {
            OpusEngineMode.FORCE_MEDIACODEC -> MediaCodecOpusEngine()
            OpusEngineMode.FORCE_JNI -> JniOpusEngine()
            OpusEngineMode.AUTO -> error("AUTO cannot be instantiated directly")
        }
        val supported = candidate.isSupported()
        Log.w(TAG, "instantiate($mode): supported=$supported, engine=${candidate.name}")
        return candidate.takeIf { supported }
    }

    fun resolveCandidate(mode: OpusEngineMode): OpusEngineSelection {
        if (mode == OpusEngineMode.FORCE_MEDIACODEC || mode == OpusEngineMode.FORCE_JNI) {
            val engine = instantiate(mode)
            return if (engine != null) {
                OpusEngineSelection(engine, mode)
            } else {
                OpusEngineSelection(null, mode, "$mode is not supported on this device")
            }
        }

        val mediaCodecEngine = instantiate(OpusEngineMode.FORCE_MEDIACODEC)
        if (mediaCodecEngine != null) {
            return OpusEngineSelection(mediaCodecEngine, OpusEngineMode.FORCE_MEDIACODEC)
        }

        val jniEngine = instantiate(OpusEngineMode.FORCE_JNI)
        if (jniEngine != null) {
            return OpusEngineSelection(jniEngine, OpusEngineMode.FORCE_JNI)
        }

        return OpusEngineSelection(
            engine = null,
            mode = OpusEngineMode.AUTO,
            reason = "No compatible Opus engine found (MediaCodec/JNI unavailable)"
        )
    }

    fun runLocalProbe(engine: OpusEngine, frames: Int = 100): Boolean {
        return try {
            engine.start()
            val pcm = ByteArray(OpusAudioFormat.PCM_FRAME_BYTES)
            var encodedCount = 0
            var consecutiveEncodeMisses = 0

            repeat(frames) {
                val encoded = engine.encodeFrame(pcm)
                if (encoded == null || encoded.isEmpty()) {
                    consecutiveEncodeMisses++
                    if (consecutiveEncodeMisses > 5) {
                        return false
                    }
                    return@repeat
                }
                consecutiveEncodeMisses = 0
                encodedCount++
            }

            val passed = encodedCount >= (frames * 0.90).toInt()
            Log.w(
                TAG,
                "Probe ${engine.name}: encoded=$encodedCount/$frames isPcmEncoding=${engine.isPcmEncoding} pass=$passed"
            )
            passed
        } catch (t: Throwable) {
            Log.e(TAG, "Probe failed for ${engine.name}: ${t.message}", t)
            false
        } finally {
            engine.release()
        }
    }
}

class MediaCodecOpusEngine : OpusEngine {
    override val name: String = "MediaCodecOpusEngine"
    override var isPcmEncoding: Boolean = false

    private var encoder: MediaCodec? = null
    @Volatile private var decoder: MediaCodec? = null
    private var decoderInitialized = false
    private val encoderInfo = MediaCodec.BufferInfo()
    private val decoderInfo = MediaCodec.BufferInfo()

    override fun isSupported(): Boolean {
        val sdkOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val decoderName = findCodec(isEncoder = false)
        val encoderName = findCodec(isEncoder = true)
        Log.w(TAG, "isSupported: SDK=${Build.VERSION.SDK_INT} (need>=${Build.VERSION_CODES.Q}), sdkOk=$sdkOk, decoder=$decoderName, encoder=$encoderName")
        return sdkOk && decoderName != null
    }

    override fun start() {
        if (!isSupported()) {
            throw IllegalStateException("MediaCodec Opus is not available on this device")
        }

        val encoderName = findCodec(isEncoder = true)
        if (encoderName != null) {
            Log.w(TAG, "Starting with Opus encoder: $encoderName")
            encoder = MediaCodec.createByCodecName(encoderName).apply {
                val format = MediaFormat.createAudioFormat(MIME_TYPE, OpusAudioFormat.SAMPLE_RATE, OpusAudioFormat.CHANNEL_COUNT).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, OpusAudioFormat.PCM_FRAME_BYTES)
                }
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } else {
            Log.w(TAG, "No Opus encoder found, falling back to raw PCM encoding! isPcmEncoding=true")
            isPcmEncoding = true
        }
        // Decoder is created lazily on first decodeFrame() call to avoid
        // Android MediaCodec bug where creating encoder + decoder simultaneously
        // causes the decoder to auto-release on some devices.
    }

    override fun encodeFrame(pcmFrame: ByteArray): ByteArray? {
        if (isPcmEncoding) {
            return pcmFrame
        }
        val codec = encoder ?: return null
        
        val inputIndex = codec.dequeueInputBuffer(50_000L)
        if (inputIndex >= 0) {
            codec.getInputBuffer(inputIndex)?.let { input ->
                input.clear()
                input.put(pcmFrame, 0, minOf(pcmFrame.size, input.capacity()))
            }
            codec.queueInputBuffer(inputIndex, 0, pcmFrame.size, 0, 0)
        } else {
            Log.e(TAG, "Encoder input buffer full, dropping PCM frame!")
        }

        var encodedData = ByteArray(0)
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(encoderInfo, DEQUEUE_TIMEOUT_US)
            when {
                outputIndex >= 0 -> {
                    val output = codec.getOutputBuffer(outputIndex)
                    val data = output?.readBytes(encoderInfo) ?: ByteArray(0)
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || data.isEmpty()) {
                        continue
                    }
                    if (encodedData.isEmpty()) {
                        encodedData = data
                    } else {
                        val combined = ByteArray(encodedData.size + data.size)
                        System.arraycopy(encodedData, 0, combined, 0, encodedData.size)
                        System.arraycopy(data, 0, combined, encodedData.size, data.size)
                        encodedData = combined
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Encoder format changed: ${codec.outputFormat}")
                }
                else -> {
                    return if (encodedData.isNotEmpty()) encodedData else null
                }
            }
        }
    }

    @Synchronized
    private fun ensureDecoder(): MediaCodec? {
        if (decoder != null) return decoder
        if (decoderInitialized) return null // already tried and failed
        decoderInitialized = true
        return try {
            val decoderName = findCodec(isEncoder = false)
            if (decoderName == null) {
                Log.e(TAG, "No Opus decoder found for lazy init")
                null
            } else {
                Log.w(TAG, "Lazy-creating Opus decoder: $decoderName")
                MediaCodec.createByCodecName(decoderName).apply {
                    val format = MediaFormat.createAudioFormat(MIME_TYPE, OpusAudioFormat.SAMPLE_RATE, OpusAudioFormat.CHANNEL_COUNT).apply {
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
                    }
                    configure(format, null, null, 0)
                    start()
                }.also { decoder = it }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to lazy-create decoder: ${t.message}", t)
            null
        }
    }

    override fun decodeFrame(opusFrame: ByteArray): ByteArray? {
        val codec = decoder ?: ensureDecoder() ?: return null

        try {
            val inputIndex = codec.dequeueInputBuffer(50_000L)
            if (inputIndex >= 0) {
                codec.getInputBuffer(inputIndex)?.let { input ->
                    input.clear()
                    input.put(opusFrame)
                }
                codec.queueInputBuffer(inputIndex, 0, opusFrame.size, 0, 0)
            } else {
                Log.e(TAG, "Decoder input buffer full, dropping Opus frame!")
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Decoder input rejected: ${e.message}")
            return null
        }

        var decodedData = ByteArray(0)
        while (true) {
            try {
                val outputIndex = codec.dequeueOutputBuffer(decoderInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        val output = codec.getOutputBuffer(outputIndex)
                        val data = output?.readBytes(decoderInfo) ?: ByteArray(0)
                        codec.releaseOutputBuffer(outputIndex, false)

                        if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || data.isEmpty()) {
                            continue
                        }
                        if (decodedData.isEmpty()) {
                            decodedData = data
                        } else {
                            val combined = ByteArray(decodedData.size + data.size)
                            System.arraycopy(decodedData, 0, combined, 0, decodedData.size)
                            System.arraycopy(data, 0, combined, decodedData.size, data.size)
                            decodedData = combined
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Decoder format changed: ${codec.outputFormat}")
                    }
                    else -> {
                        return if (decodedData.isNotEmpty()) decodedData else null
                    }
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Decoder output error: ${e.message}")
                return if (decodedData.isNotEmpty()) decodedData else null
            }
        }
    }

    override fun reset() {
        try {
            encoder?.flush()
        } catch (_: Exception) {
        }
        try {
            decoder?.flush()
        } catch (_: Exception) {
        }
    }

    override fun release() {
        encoder?.safeRelease()
        decoder?.safeRelease()
        encoder = null
        decoder = null
        decoderInitialized = false
    }

    private fun ByteBuffer.readBytes(info: MediaCodec.BufferInfo): ByteArray {
        val data = ByteArray(info.size)
        position(info.offset)
        limit(info.offset + info.size)
        get(data)
        return data
    }

    private fun MediaCodec.safeRelease() {
        try {
            stop()
        } catch (_: Exception) {
        }
        try {
            release()
        } catch (_: Exception) {
        }
    }

    private fun findCodec(isEncoder: Boolean): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return codecList.codecInfos.firstOrNull { codecInfo ->
            codecInfo.isEncoder == isEncoder &&
                    codecInfo.supportedTypes.any { it.equals(MIME_TYPE, ignoreCase = true) }
        }?.name
    }

    private companion object {
        private const val MIME_TYPE = "audio/opus"
        private const val BIT_RATE = 24_000
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val TAG = "MediaCodecOpusEngine"
    }
}

class JniOpusEngine : OpusEngine {
    override val name: String = "JniOpusEngine"
    override val isPcmEncoding: Boolean = false

    override fun isSupported(): Boolean {
        return false
    }

    override fun start() {
        throw UnsupportedOperationException("JNI Opus engine chưa được bundle trong bản build hiện tại")
    }

    override fun encodeFrame(pcmFrame: ByteArray): ByteArray? {
        return null
    }

    override fun decodeFrame(opusFrame: ByteArray): ByteArray? {
        return null
    }

    override fun reset() {
        // No-op until JNI implementation is integrated.
    }

    override fun release() {
        // No-op until JNI implementation is integrated.
    }
}
