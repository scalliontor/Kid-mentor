package com.ctslab.ptalk_signature

import android.media.MediaPlayer
import android.util.Log
import java.io.File

class AudioPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private val TAG = "AudioPlayer"

    // Nhận trực tiếp File WAV từ ApiService, không cần lưu lại
    fun play(
        audioFile: File,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop() // Dừng bất kỳ audio nào đang phát trước đó

        try {
            // WAV header diagnostic
            try {
                audioFile.inputStream().use { fis ->
                    val header = ByteArray(44)
                    val read = fis.read(header)
                    if (read >= 44) {
                        val fileSize = audioFile.length()
                        val dataSize = header[40].toInt() and 0xFF or
                                ((header[41].toInt() and 0xFF) shl 8) or
                                ((header[42].toInt() and 0xFF) shl 16) or
                                ((header[43].toInt() and 0xFF) shl 24)
                        val sampleRate = header[24].toInt() and 0xFF or
                                ((header[25].toInt() and 0xFF) shl 8) or
                                ((header[26].toInt() and 0xFF) shl 16) or
                                ((header[27].toInt() and 0xFF) shl 24)
                        val channels = header[22].toInt() and 0xFF or
                                ((header[23].toInt() and 0xFF) shl 8)
                        val bitsPerSample = header[34].toInt() and 0xFF or
                                ((header[35].toInt() and 0xFF) shl 8)
                        val expectedDurationMs = if (sampleRate > 0 && channels > 0 && bitsPerSample > 0) {
                            dataSize.toLong() * 1000 / (sampleRate * channels * bitsPerSample / 8)
                        } else -1
                        Log.w(TAG, "WAV header: fileSize=$fileSize, dataSize=$dataSize, sampleRate=$sampleRate, channels=$channels, bitsPerSample=$bitsPerSample, expectedDuration=${expectedDurationMs}ms")
                    } else {
                        Log.e(TAG, "WAV file too small for header: $read bytes read")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WAV header read error: ${e.message}")
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                start()
                Log.w(TAG, "Phát WAV: ${audioFile.name}, fileSize=${audioFile.length()}, mediaPlayer duration: ${duration}ms")

                setOnCompletionListener {
                    Log.d(TAG, "Phát xong")
                    release()
                    mediaPlayer = null
                    onComplete()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer lỗi: what=$what, extra=$extra")
                    onError("Lỗi phát audio")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không khởi tạo được MediaPlayer: ${e.message}")
            onError("Không phát được audio: ${e.message}")
        }
    }

    fun stop() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
    }

    fun isPlaying() = mediaPlayer?.isPlaying == true
}

//package com.buivan.ptalk_child
//
//import android.content.Context
//import android.media.MediaPlayer
//import android.util.Log
//import java.io.File
//import java.io.FileOutputStream
//
//class AudioPlayer(private val context: Context) {
//
//    private var mediaPlayer: MediaPlayer? = null
//    private val TAG = "AudioPlayer"
//
//    // onComplete gọi khi phát xong, onError gọi khi có lỗi
//    fun play(
//        audioBytes: ByteArray,
//        onComplete: () -> Unit,
//        onError: (String) -> Unit
//    ) {
//        // Lưu bytes ra file tạm rồi mới phát
//        val tempFile = File(context.cacheDir, "response_${System.currentTimeMillis()}.mp3")
//        try {
//            FileOutputStream(tempFile).use { it.write(audioBytes) }
//        } catch (e: Exception) {
//            onError("Không ghi được file audio: ${e.message}")
//            return
//        }
//
//        stop() // Dừng bất kỳ audio nào đang phát trước đó
//
//        try {
//            mediaPlayer = MediaPlayer().apply {
//                setDataSource(tempFile.absolutePath)
//                prepare()
//                start()
//                Log.d(TAG, "Bắt đầu phát audio, duration: ${duration}ms")
//
//                setOnCompletionListener {
//                    Log.d(TAG, "Phát xong")
//                    release()
//                    mediaPlayer = null
//                    onComplete()
//                }
//
//                setOnErrorListener { _, what, extra ->
//                    Log.e(TAG, "MediaPlayer lỗi: what=$what, extra=$extra")
//                    onError("Lỗi phát audio")
//                    true
//                }
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Không khởi tạo được MediaPlayer: ${e.message}")
//            onError("Không phát được audio: ${e.message}")
//        }
//    }
//
//    fun stop() {
//        mediaPlayer?.let {
//            if (it.isPlaying) it.stop()
//            it.release()
//        }
//        mediaPlayer = null
//    }
//
//    fun isPlaying() = mediaPlayer?.isPlaying == true
//}
