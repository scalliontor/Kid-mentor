package com.buivan.ptalk_child

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class StreamingAudioPlayer(
    private val opusEngine: OpusEngine,
    private val pcmMode: Boolean = false
) {
    private var audioTrack: AudioTrack? = null
    private val lock = Any()

    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var isPlaying = false
    @Volatile private var isDraining = false
    private var playbackThread: Thread? = null

    fun start() {
        synchronized(lock) {
            if (audioTrack != null) return

            val minBufferSize = AudioTrack.getMinBufferSize(
                OpusAudioFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // 4-second buffer
            val trackBufferSize = minBufferSize.coerceAtLeast(OpusAudioFormat.SAMPLE_RATE * 4 * 2)

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

            track.play()
            audioTrack = track
            Log.w("StreamingAudioPlayer", "AudioTrack started, pcmMode=$pcmMode")

            isPlaying = true
            isDraining = false
            playbackThread = thread(start = true, name = "AudioPlaybackThread") {
                while (isPlaying || isDraining) {
                    val pcm = pcmQueue.poll()
                    if (pcm != null) {
                        try {
                            // Check isPlaying before write to avoid writing after stop()
                            if (!isPlaying && !isDraining) break
                            track.write(pcm, 0, pcm.size)
                        } catch (e: Exception) {
                            Log.e("StreamingAudioPlayer", "AudioTrack write failed", e)
                            break
                        }
                    } else if (isDraining) {
                        // Queue empty during drain — all audio written to AudioTrack
                        break
                    } else {
                        try {
                            Thread.sleep(1)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }
                Log.d("StreamingAudioPlayer", "Playback thread exited")
            }
        }
    }

    fun playPacket(packet: ByteArray) {
        val frames = AudioFrameProtocol.unpackFrames(packet)
        synchronized(lock) {
            if (!isPlaying) return
            frames.forEach { frame ->
                if (pcmMode) {
                    pcmQueue.add(frame)
                } else {
                    val pcm = opusEngine.decodeFrame(frame)
                    if (pcm != null && pcm.isNotEmpty()) {
                        pcmQueue.add(pcm)
                    } else {
                        Log.w("StreamingAudioPlayer", "Dropped undecodable Opus frame")
                    }
                }
            }
        }
    }

    fun stop() {
        isPlaying = false
        isDraining = false
        // Wait for playback thread to exit before releasing AudioTrack
        val t = playbackThread
        playbackThread = null
        if (t != null && t.isAlive) {
            t.interrupt()
            try { t.join(3000) } catch (_: InterruptedException) {}
        }

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

    /** Drain the PCM queue, then stop. Call from a background thread. */
    fun drainAndStop() {
        // Signal: no more packets will arrive, drain remaining queue
        isDraining = true

        // Wait for playback thread to finish writing all queued audio
        val deadline = System.currentTimeMillis() + 60_000
        val t = playbackThread
        while (t != null && t.isAlive && System.currentTimeMillis() < deadline) {
            try { t.join(500) } catch (_: InterruptedException) { break }
        }

        // Playback thread has exited — safe to stop and release AudioTrack
        isPlaying = false
        isDraining = false
        playbackThread = null

        synchronized(lock) {
            pcmQueue.clear()
            audioTrack?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            audioTrack = null
        }
        Log.d("StreamingAudioPlayer", "drainAndStop complete")
    }
}
