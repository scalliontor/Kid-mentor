# Tài Liệu Kỹ Thuật: PTalk Kids Android (Phiên bản V2 - Streaming & Auto Fallback)

> **Cập nhật lần cuối:** 13/05/2026
> **Mô tả:** Tài liệu kiến trúc toàn diện cho ứng dụng PTalk Kids (Android), bao gồm luồng xử lý mạng đa phương thức (WebSocket/HTTP), kiến trúc âm thanh đa luồng, và giao thức giao tiếp với CloudPTalk Server.

---

## 1. TỔNG QUAN KIẾN TRÚC (SYSTEM ARCHITECTURE)

Ứng dụng **PTalk Kids** là một client AI Voice-First. Học sinh sẽ nhấn nút giữ để thu âm giọng nói, gửi lên Server (STT -> LLM -> TTS), và nhận lại giọng nói phát trực tiếp theo thời gian thực (Streaming).

Để giải quyết vấn đề độ trễ mạng và sự phân mảnh phần cứng trên Android (đặc biệt là lỗi thiếu bộ giải mã Opus trên một số dòng Android 16), ứng dụng sử dụng mô hình **Hybrid Transport Architecture (Kiến trúc lai)**:

- **WebSocket V2 (Đường Cao Tốc):** Truyền tải âm thanh nén `Opus` hai chiều, độ trễ cực thấp (Latency < 200ms).
- **HTTP Fallback (Đường Dự Phòng):** Truyền tải âm thanh `M4A/WAV` theo cơ chế Request-Response cổ điển. Tự động kích hoạt khi phần cứng điện thoại không hỗ trợ Opus.

---

## 2. CẤU HÌNH MÁY CHỦ (SERVER CONFIGURATION)

Mọi cấu hình định tuyến mạng được quản lý tập trung tại `ServerConfig.kt`.

```kotlin
object ServerConfig {
    // Trỏ thẳng vào Nginx API Gateway
    const val HTTP_BASE_URL = "http://171.226.10.121:8000/voice/"
    const val WS_URL = "ws://171.226.10.121:8000/voice/ws"
    
    // Auto: Ưu tiên WebSocket, tự động lùi về HTTP nếu lỗi phần cứng
    val TRANSPORT_MODE = TransportMode.AUTO 
}
```

**Cơ chế Health Check:**
Khi App khởi động, nó sẽ gọi `GET /voice/health`. Nếu Server trả về chữ `"ok"` và Khởi tạo phần cứng Opus thành công, App sẽ mở kết nối `WebSocket`. Nếu một trong hai thất bại, App tự động đóng băng luồng WebSocket và chuyển sang dùng luồng `Legacy_HTTP_Only`.

---

## 3. KIẾN TRÚC ÂM THANH ĐA LUỒNG (AUDIO PIPELINE)

Đây là **TRÁI TIM** của hệ thống, giúp ứng dụng không bao giờ bị đứng hình (ANR) hay ngắt kết nối đột ngột (Lỗi 1006) khi nhận các bài thơ dài tới 3-5 phút.

### 3.1. Chiều Thu (Mic ➡️ Server): `PcmMicStreamer.kt`
- Sử dụng `AudioRecord` thu âm thô `PCM 16-bit, 48kHz, Mono`.
- **Đa luồng:** Chạy trên một `Thread` hoàn toàn tách biệt với Main UI.
- Dữ liệu PCM được đưa qua `OpusCodec` (JNI/MediaCodec) nén thành các frame cực nhỏ.
- **Length-Prefixed Framing:** Trước mỗi frame Opus, App sẽ chèn thêm 2 bytes (Little-Endian) ghi kích thước frame, sau đó bắn lên WebSocket.

### 3.2. Chiều Phát (Server ➡️ Loa): `StreamingAudioPlayer.kt`
**Tuyệt đối không gọi lệnh phát loa (`AudioTrack.write`) trong callback của mạng (OkHttp).** Thay vào đó, App sử dụng mô hình Sản xuất - Tiêu dùng (Producer - Consumer) thông qua hàng đợi `ConcurrentLinkedQueue`.

```mermaid
graph LR
    A[OkHttp WebSocket] -->|1. Decode Opus ra PCM| B{Hàng Đợi Queue}
    B -->|2. Lấy PCM ra theo thứ tự| C[Luồng Phát Loa Độc Lập]
    C -->|3. Gọi lệnh write()| D((AudioTrack Loa))
```

- **Mạng (Producer):** Cứ có gói âm thanh từ Server, chỉ mất 1ms để giải nén Opus và vứt tọt vào `Queue`. Nhờ vậy luồng mạng liên tục thông suốt, luôn đáp ứng PING/PONG của Server.
- **Loa (Consumer):** Một `Thread` thứ 2 chạy vòng lặp vô hạn. Nếu `Queue` có dữ liệu, nó lấy ra và đút vào `AudioTrack`. Việc cái loa phát chậm hay bị đầy bộ đệm sẽ chỉ làm `Thread` này kẹt, không ảnh hưởng đến bất cứ ai khác.

---

## 4. GIAO THỨC WEBSOCKET (STATE MACHINE)

Ứng dụng duy trì **1 kết nối WebSocket duy nhất** (Keep-Alive) xuyên suốt vòng đời. Quá trình giao tiếp diễn ra qua 5 trạng thái (States):

| Trạng thái | Hành động của App | Hành động của Server |
|---|---|---|
| **CONNECTED** | Kết nối thành công tới `ws://...` | Sẵn sàng nhận lệnh. |
| **LISTENING** | Gửi Text `"START"` | Trả về `"LISTENING"`, bắt đầu mở luồng thu âm. |
| **(Streaming)** | Gửi Binary (Các gói Opus liên tục) | Đưa vào bộ đệm nhận (Buffer) -> STT. |
| **PROCESSING**| Người dùng nhả nút, gửi Text `"END"` | Trả về `"PROCESSING"`, bắt đầu tư duy LLM. |
| **SPEAKING** | Lắng nghe, không gửi gì cả | Trả Mã cảm xúc (VD: `"10"`), trả `"SPEAKING"`. Bắt đầu xả Binary Opus frames về liên tục. |
| **IDLE** | Đóng bộ giải mã Opus | Trả `"IDLE"`. Lượt hội thoại kết thúc. |

**Tính năng Ngắt Lời (Interrupt):**
Nếu bé đang nghe Lisa đọc dở một bài thơ quá dài, bé có thể bấm nút thu âm lần nữa (Gửi `"START"`). Lập tức Server sẽ dừng bài thơ, vứt bỏ Audio cũ, và App dọn dẹp hàng đợi loa (`Queue.clear()`) để bắt đầu nghe câu hỏi mới của bé.

---

## 5. CÁC LỚP CHÍNH TRONG MÃ NGUỒN (CODE MAP)

- `MainActivity.kt`: Giao diện UI chính, quản lý nút bấm PTT (Push To Talk), hiển thị các ảnh động (Avatar) dựa theo Mã cảm xúc trả về từ Server.
- `StreamingVoiceClient.kt`: Người Nhạc Trưởng. Quản lý vòng đời OkHttp WebSocket, duy trì State Machine, phân luồng sự kiện tới UI và Audio Player.
- `PcmMicStreamer.kt`: Phụ trách mở Mic, thu âm, nén Opus và bắn vào luồng mạng.
- `StreamingAudioPlayer.kt`: Phụ trách hàng đợi an toàn, giải nén Opus và nhét vào AudioTrack để phát âm thanh.
- `ApiService.kt`: Định nghĩa các hàm kết nối API thuần túy (HTTP Fallback) thông qua thư viện Retrofit.

---

## 6. LƯU Ý KHI BUILD & DEPLOY

1. **Quyền HĐH (Permissions):** App yêu cầu quyền `RECORD_AUDIO` và `INTERNET`. Phải cấp quyền thủ công hoặc thiết lập trong máy ảo trước khi test.
2. **Phiên bản Java:** Gradle yêu cầu **Java 17** để build.
3. **Logcat:** Khi test, mở Logcat với bộ lọc (Filter) chữ `VoiceClient` hoặc `StreamingVoiceClient` để biết chính xác App đang dùng đường WebSocket tốc độ cao hay đang bị hạ cấp xuống đường HTTP dự phòng.
4. **Môi trường Server:** Nginx trên Server bắt buộc phải cấu hình `proxy_read_timeout 600s;` trở lên, tránh việc Nginx tự động cắt ngang kết nối khi AI đang xử lý những bài thơ dài.
