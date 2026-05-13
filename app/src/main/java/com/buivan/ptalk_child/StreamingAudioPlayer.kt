package com.buivan.ptalk_child

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class StreamingAudioPlayer(
    private val opusEngine: OpusEngine,
    private val isReceivingPcm: Boolean = false
) {
    private var audioTrack: AudioTrack? = null
    private val lock = Any()
    
    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var isPlaying = false
    private var playbackThread: Thread? = null

    fun start() {
        synchronized(lock) {
            if (audioTrack != null) return

            val minBufferSize = AudioTrack.getMinBufferSize(
                OpusAudioFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            // Set buffer size to at least 2 seconds to hold a massive jitter buffer
            val trackBufferSize = minBufferSize.coerceAtLeast(OpusAudioFormat.SAMPLE_RATE * 2 * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(OpusAudioFormat.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(trackBufferSize)
                .build()

            // Removed 100ms silence pre-fill to eliminate initial latency/clipping

            track.play()
            audioTrack = track
            
            isPlaying = true
            playbackThread = thread(start = true, name = "AudioPlaybackThread") {
                while (isPlaying) {
                    val pcm = pcmQueue.poll()
                    if (pcm != null) {
                        try {
                            track.write(pcm, 0, pcm.size)
                        } catch (e: Exception) {
                            Log.e("StreamingAudioPlayer", "AudioTrack write failed", e)
                        }
                    } else {
                        try {
                            Thread.sleep(1)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }
            }
        }
    }

    fun playPacket(packet: ByteArray) {
        val frames = AudioFrameProtocol.unpackFrames(packet)
        synchronized(lock) {
            if (!isPlaying) return
            frames.forEach { frame ->
                if (isReceivingPcm) {
                    pcmQueue.add(frame)
                    return@forEach
                }
                try {
                    val pcm = opusEngine.decodeFrame(frame)
                    if (pcm != null && pcm.isNotEmpty()) {
                        pcmQueue.add(pcm)
                    } else {
                        Log.w("StreamingAudioPlayer", "Dropped undecodable Opus frame")
                    }
                } catch (e: IllegalStateException) {
                    Log.w("StreamingAudioPlayer", "Codec already released, ignoring frame")
                }
            }
        }
    }

    fun stop() {
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread = null
        
        synchronized(lock) {
            pcmQueue.clear()
            audioTrack?.let {
                try {
                    it.pause()
                    it.flush()
                } catch (_: Exception) {
                }
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
            audioTrack = null
        }
    }
}
