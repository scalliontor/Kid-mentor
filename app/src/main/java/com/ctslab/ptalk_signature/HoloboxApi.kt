package com.ctslab.ptalk_signature

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client gọi TRỰC TIẾP các API của hệ "Holobox/PTIT" mà bản Unity elder_care dùng:
 *  - STT  : /holobox/transcribe   (multipart audio_file)         -> {"text": ...}
 *  - Chat : aichat.ptit.edu.vn/v1/chat-messages (Dify, blocking) -> {"answer", "conversation_id"}
 *  - TTS  : /holobox/synthesize   (json {"text"})                -> WAV bytes (PCM16 mono 24kHz)
 *  - Vision: /holobox/medician    (multipart image + prompt)     -> JSON dạng Gemini
 *
 * Dùng cho ELDER_CARE mode. KID_MENTOR vẫn đi qua server WebSocket như cũ.
 * Tất cả hàm public là suspend, chạy trên Dispatchers.IO.
 */
class HoloboxApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)   // STT + Dify (~6s) + TTS có thể lâu
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Giữ conversation_id để Dify nhớ ngữ cảnh trong 1 phiên (bắt đầu rỗng = hội thoại mới).
    private var conversationId: String? = null

    // ── Voice turn: ghi âm (m4a) -> STT -> Chat -> TTS -> file WAV để AudioPlayer phát ──
    suspend fun processVoiceTurn(audioFile: File, cacheDir: File): File = withContext(Dispatchers.IO) {
        val transcript = transcribe(audioFile)
        if (transcript.isBlank()) throw IOException("Chưa nghe rõ, bác thử nói lại nhé")
        Log.d(TAG, "STT: $transcript")

        val answer = chat(transcript)
        if (answer.isBlank()) throw IOException("AI chưa trả lời được, thử lại nhé")
        Log.d(TAG, "Chat answer (${answer.length} chars)")

        val wavBytes = synthesizeChunked(answer)
        if (wavBytes.isEmpty()) throw IOException("Không tạo được giọng nói, thử lại nhé")

        val out = File(cacheDir, "holobox_reply_${System.currentTimeMillis()}.wav")
        out.writeBytes(wavBytes)
        out
    }

    // ── Quét thuốc: ảnh JPG -> /medician -> text mô tả thuốc (tiếng Việt) ──
    suspend fun scanMedicine(imageFile: File): String = withContext(Dispatchers.IO) {
        val jpeg = "image/jpeg".toMediaTypeOrNull()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            // Gửi cả 2 field như bản Unity để chắc chắn server nhận đúng tên field.
            .addFormDataPart("image", "capture.jpg", imageFile.asRequestBody(jpeg))
            .addFormDataPart("image_file", "capture.jpg", imageFile.asRequestBody(jpeg))
            .addFormDataPart("prompt", MEDICINE_PROMPT)
            .build()
        val req = Request.Builder().url(MEDICIAN_URL).post(body).build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Quét thuốc lỗi (HTTP ${resp.code})")
            parseGeminiText(raw)
        }
    }

    // ── Tạo WAV từ text (dùng cho việc đọc to kết quả quét thuốc) ──
    suspend fun synthesizeToFile(text: String, cacheDir: File): File = withContext(Dispatchers.IO) {
        val wavBytes = synthesizeChunked(text)
        if (wavBytes.isEmpty()) throw IOException("Không tạo được giọng nói")
        val out = File(cacheDir, "holobox_tts_${System.currentTimeMillis()}.wav")
        out.writeBytes(wavBytes)
        out
    }

    fun resetConversation() {
        conversationId = null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal calls
    // ──────────────────────────────────────────────────────────────────────

    private fun transcribe(file: File): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio_file", file.name, file.asRequestBody("audio/mp4".toMediaTypeOrNull()))
            .build()
        val req = Request.Builder().url(TRANSCRIBE_URL).post(body).build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("STT lỗi (HTTP ${resp.code})")
            return JSONObject(raw).optString("text", "")
        }
    }

    private fun chat(query: String): String {
        val payload = JSONObject().apply {
            put("query", query)
            put("response_mode", "blocking")
            put("user", CHAT_USER)
            put("inputs", JSONObject())
            if (!conversationId.isNullOrEmpty()) put("conversation_id", conversationId)
        }
        val req = Request.Builder()
            .url(CHAT_URL)
            .addHeader("Authorization", "Bearer $CHAT_TOKEN")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Chat lỗi (HTTP ${resp.code})")
            val json = JSONObject(raw)
            if (conversationId.isNullOrEmpty()) {
                val newId = json.optString("conversation_id", "")
                if (newId.isNotEmpty()) conversationId = newId
            }
            return json.optString("answer", "")
        }
    }

    /** Chia text thành chunk ≤250 ký tự, gọi TTS từng chunk rồi ghép thành 1 WAV liền mạch. */
    private fun synthesizeChunked(text: String): ByteArray {
        val chunks = chunkText(text)
        if (chunks.isEmpty()) return ByteArray(0)
        val wavs = ArrayList<ByteArray>(chunks.size)
        for (chunk in chunks) {
            val wav = synthesizeOne(chunk)
            if (wav.isNotEmpty()) wavs.add(wav)
        }
        if (wavs.isEmpty()) throw IOException("TTS lỗi")
        return mergeWavs(wavs)
    }

    private fun synthesizeOne(text: String): ByteArray {
        val payload = JSONObject().put("text", text)
        val req = Request.Builder().url(SYNTHESIZE_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "TTS chunk lỗi: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Lấy candidates[0].content.parts[0].text từ JSON dạng Gemini. */
    private fun parseGeminiText(json: String): String {
        if (json.isBlank()) return ""
        return try {
            val candidates = JSONObject(json).optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")?.optJSONArray("parts") ?: return ""
            if (parts.length() == 0) return ""
            parts.getJSONObject(0).optString("text", "")
        } catch (e: Exception) {
            Log.e(TAG, "Parse Gemini JSON lỗi: ${e.message}")
            ""
        }
    }

    /**
     * Ghép nhiều file WAV PCM16 cùng định dạng thành 1 WAV (giữ header chunk đầu,
     * nối phần data, vá lại ChunkSize & Subchunk2Size). Giả định header chuẩn 44 byte.
     */
    private fun mergeWavs(wavs: List<ByteArray>): ByteArray {
        if (wavs.size == 1) return wavs[0]
        val header = wavs[0].copyOf(WAV_HEADER)
        val dataParts = wavs.map { w ->
            if (w.size > WAV_HEADER) w.copyOfRange(WAV_HEADER, w.size) else ByteArray(0)
        }
        val totalData = dataParts.sumOf { it.size }
        writeIntLE(header, 4, 36 + totalData)   // RIFF ChunkSize
        writeIntLE(header, 40, totalData)        // data Subchunk2Size
        val out = ByteArrayOutputStream(WAV_HEADER + totalData)
        out.write(header)
        for (part in dataParts) out.write(part)
        return out.toByteArray()
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /**
     * Làm sạch markdown + chuẩn hoá viết tắt cho TTS đọc tiếng Việt, rồi chia chunk ≤250 ký tự.
     * Port rút gọn từ TTSService.ChunkText của bản Unity.
     */
    private fun chunkText(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        var text = input

        // Bỏ markdown
        text = text.replace(Regex("\\*+"), "")
        text = text.replace(Regex("#+\\s"), "")
        text = text.replace(Regex("(?m)^\\s*[-•]\\s*"), "")
        text = text.replace(Regex("`+"), "")

        // URL -> "đường link"
        text = text.replace(
            Regex("(?:https?://|www\\.)\\S+|(?<!\\w)[a-zA-Z0-9-]+\\.(?:com|vn|edu|org|net|io|gov)(?:/\\S*)?"),
            "đường link"
        )

        // Viết tắt phổ biến (dài -> ngắn)
        val abbr = listOf(
            "GS.TS" to "Giáo sư Tiến sỹ", "PGS.TS" to "Phó giáo sư Tiến sỹ",
            "PGS" to "Phó giáo sư", "PTIT" to "pi ti ai ti", "CNTT" to "Công nghệ thông tin",
            "API" to "ây pi ai", "AI" to "ây ai", "app" to "ứng dụng", "App" to "ứng dụng",
            "%" to " phần trăm", "mg" to " mi li gam", "ml" to " mi li lít",
            "kg" to " ki lô gam", "cm" to " xen ti mét", "mm" to " mi li mét"
        )
        for ((k, v) in abbr) {
            text = if (k == "%") {
                text.replace(k, v) // dấu %: thay thẳng
            } else {
                // chỉ thay khi đứng tách từ -> "app"/"AI"/"mg"... không dính vào "apple", "EMAIL"...
                text.replace(Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(k) + "(?![\\p{L}\\p{N}])"), v)
            }
        }

        // Bỏ emoji / ký tự lạ, dọn khoảng trắng
        text = text.replace(Regex("[^\\p{L}\\p{N}\\p{P}\\s]"), " ")
        text = text.replace(Regex("\\s{2,}"), " ").trim()

        // Tách câu rồi gom thành chunk ≤ MAX
        val sentences = mutableListOf<String>()
        for (line in text.split('\r', '\n')) {
            val t = line.trim()
            if (t.isEmpty()) continue
            for (s in t.split(Regex("(?<=[.!?])\\s+"))) {
                val st = s.trim()
                if (st.isNotEmpty()) sentences.add(st)
            }
        }

        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        fun flush() { if (current.isNotEmpty()) { chunks.add(current.toString().trim()); current = StringBuilder() } }

        for (seg in sentences) {
            if (seg.length > MAX_CHUNK) {
                flush()
                for (p in seg.split(Regex("(?<=,)\\s*"))) {
                    val pt = p.trim()
                    if (pt.isEmpty()) continue
                    if (current.isEmpty()) current.append(pt)
                    else if (current.length + 1 + pt.length <= MAX_CHUNK) current.append(' ').append(pt)
                    else { flush(); current.append(pt) }
                }
                continue
            }
            if (current.isEmpty()) current.append(seg)
            else if (current.length + 1 + seg.length <= MAX_CHUNK) current.append(' ').append(seg)
            else { flush(); current.append(seg) }
        }
        flush()
        return chunks
    }

    private companion object {
        private const val TAG = "HoloboxApi"

        private const val TRANSCRIBE_URL = "https://aitools.ptit.edu.vn/holobox/transcribe"
        private const val SYNTHESIZE_URL = "https://aitools.ptit.edu.vn/holobox/synthesize"
        private const val MEDICIAN_URL = "https://aitools.ptit.edu.vn/holobox/medician"
        private const val CHAT_URL = "https://aichat.ptit.edu.vn/v1/chat-messages"

        // Token nội bộ (theo yêu cầu: dùng trực tiếp API của bản elder_care).
        private const val CHAT_TOKEN = "app-mUjz5BmSFgQMCtyShNo5mpID"
        private const val CHAT_USER = "aiptit"

        private const val MAX_CHUNK = 250
        private const val WAV_HEADER = 44

        private const val MEDICINE_PROMPT =
            "Hãy phân tích hình ảnh này và nhận diện chính xác viên/vỉ thuốc trong ảnh. " +
                "1) Loại trừ người dùng, chỉ tập trung vào thuốc. " +
                "2) Cho biết: Tên thuốc, Công dụng chính, Liều dùng tham khảo, và Lưu ý quan trọng. " +
                "Trả lời bằng tiếng Việt. TUYỆT ĐỐI KHÔNG dùng bảng hay Markdown phức tạp. " +
                "Viết dưới dạng các đoạn văn ngắn hoặc liệt kê dòng đơn giản."

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
