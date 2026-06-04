# ELDER_CARE — tích hợp API Holobox trực tiếp + Quét thuốc

Port các chức năng từ app Unity `elder_care` sang native Kotlin, **chỉ áp dụng cho mode ELDER_CARE**.
KID_MENTOR giữ nguyên hoàn toàn (vẫn dùng WebSocket streaming tới server `171.226.10.121:8000`).

## Khác biệt cốt lõi

| | KID_MENTOR | ELDER_CARE (mới) |
|---|---|---|
| Backend voice | WebSocket `/v2/ws` (server tự STT+LLM+TTS) | Gọi **trực tiếp** API PTIT/Holobox + Dify |
| Transport | Streaming (Opus) + HTTP fallback | Ghi âm m4a → REST tuần tự |
| Quét thuốc | ❌ | ✅ CameraX → `/holobox/medician` (Gemini) |
| Gọi khẩn cấp | ❌ | ✅ quay số 113 (ACTION_DIAL) |

## Luồng voice ELDER_CARE

```
Giữ nút (MainActivity) → AudioRecorder ghi .m4a (AAC 16k mono)
  → HoloboxApi.processVoiceTurn():
       POST /holobox/transcribe   (m4a)            → text
       POST aichat.../v1/chat-messages (Dify)      → answer (+ conversation_id, giữ cho phiên)
       chunkText(≤250) → POST /holobox/synthesize  → WAV mỗi chunk → ghép thành 1 WAV
  → AudioPlayer.play(WAV)  (tái dùng đúng state machine IDLE/RECORDING/UPLOADING/PLAYING)
```

Quét thuốc: `MedicineScannerActivity` (CameraX) chụp JPG → `POST /holobox/medician`
(image + image_file + prompt) → parse Gemini `candidates[0].content.parts[0].text`
→ hiện kết quả + đọc to bằng `/holobox/synthesize`.

## 4 endpoint dùng (tất cả hardcode trong `HoloboxApi.kt` — nội bộ)

- STT  `POST https://aitools.ptit.edu.vn/holobox/transcribe`  (multipart `audio_file`) → `{text, duration_seconds, sample_rate}`
- TTS  `POST https://aitools.ptit.edu.vn/holobox/synthesize`  (json `{text}`) → WAV PCM16 mono 24kHz
- Vision `POST https://aitools.ptit.edu.vn/holobox/medician`  (multipart `image`+`image_file`+`prompt`) → JSON Gemini
- Chat `POST https://aichat.ptit.edu.vn/v1/chat-messages`  (Bearer, blocking) → `{answer, conversation_id}`

## File thay đổi

**Mới:** `HoloboxApi.kt`, `MedicineScannerActivity.kt`,
`res/layout/activity_medicine_scanner.xml`, `res/drawable/ic_scan_medicine.xml`, `res/drawable/ic_emergency.xml`.

**Sửa:** `MainActivity.kt` (nhánh ELDER_CARE + 2 nút), `res/layout/activity_main.xml` & `res/layout-sw600dp/activity_main.xml`
(thêm `btnScanMedicine` + `btnEmergency`, ẩn mặc định), `AndroidManifest.xml` (quyền CAMERA + activity),
`app/build.gradle.kts` (CameraX 1.4.1), `res/values/strings.xml`.

## Build (Android Studio trên Mac)

1. **Gradle Sync** (bắt buộc — kéo CameraX 1.4.1 về). Cần JDK 17 + AGP 8.12.
2. Build & cài lên thiết bị **thật** (CameraX + micro không chạy trên emulator không có camera).
3. Lần đầu chạy: cấp quyền **Micro** và **Camera** khi được hỏi.

## Test nhanh

1. Mở app → màn chọn mode → chọn **Elder Care**.
2. **Chat thoại:** giữ nút mic, nói 1 câu, thả → nghe Ami trả lời. (Trạng thái: Đang nghe → Đang xử lý → Đang trả lời.)
3. **Quét thuốc:** bấm icon quét thuốc (góc trái) → hướng camera vào vỉ thuốc → "Chụp & phân tích" → đọc kết quả.
4. **Khẩn cấp:** bấm icon điện thoại → mở trình quay số với 113 (chưa tự gọi, an toàn).

## Lưu ý / quyết định thiết kế

- **Ghép WAV** giả định header chuẩn 44 byte (đã verify format Holobox). Câu trả lời ngắn 1 chunk thì bỏ qua ghép.
- **Bug đã sửa từ bản Unity:** `conversation_id` bắt đầu phiên mới rồi lưu lại (không dùng chung toàn cục).
- **Quota:** luồng eldercare-trực-tiếp **không qua** `QuotaManager` (vì bỏ qua server). Nếu cần áp quota cho eldercare → nối thêm sau.
- **Liều thuốc** từ Gemini không có grounding web → nên coi là tham khảo, không thay tư vấn bác sĩ.
- Token Dify hardcode: chấp nhận vì dùng nội bộ.
