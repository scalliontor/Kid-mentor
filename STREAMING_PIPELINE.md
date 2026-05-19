# PTalk Kids — Streaming Pipeline Architecture

## Overview

```
Android Client ──WS──▶ kids/main.py ──Redis──▶ STT Worker
                              │                     │
                              │                jobs:llm
                              │                     ▼
                              │               LLM Worker (streaming tokens)
                              │                     │
                              │              smart_chunker.should_flush()
                              │                     │
                              │                jobs:tts
                              │                     ▼
                              │               TTS Worker (OmniVoice)
                              │                     │
                              │               resp:{request_id}
                              │                     ▼
◀──WS binary──           stream_results() ◀─── Redis ───┘
```

---

## 1. WebSocket Entry — `kids/main.py`

**Endpoint:** `ws://host:8002/ws`

### Client Commands

| Command | Effect |
|---------|--------|
| `START` / `START_PCM` / `START_PCM_OUT` | Increment `current_gen`, cancel in-flight task, send `"LISTENING"`, begin accumulating audio |
| `END` | Downsample 48kHz→16kHz, send `"PROCESSING"`, spawn `handle_pipeline` |
| `CLEAR_SESSION` | New `session_id`, send `"SESSION_CLEARED"` |

**Binary frames:** Opus-encoded audio batches → decoded to PCM @ 48kHz → accumulated in `pcm_acc_48k`.

### State Machine

```
START → LISTENING → [audio accumulation] → END → PROCESSING → [pipeline]
                                                                ↓
                                              THINKING (after 8s if no TTS)
                                                                ↓
                                              SPEAKING (first TTS_CHUNK)
                                                                ↓
                                              STREAM_DONE → IDLE
```

### Text Events (in order)

```
"LISTENING"         ← after START
"PROCESSING"        ← after END
"THINKING"          ← conditional: 8s timer, only if no TTS arrived yet
<emotion_code>      ← "00"/"01"/"02"/"10" on first TTS_CHUNK
"SPEAKING"          ← right after emotion
<binary opus/pcm>   ← repeated per TTS chunk (10 frames per WS message)
"STREAM_DONE"       ← all chunks sent
"IDLE"              ← after drain delay (up to 25s)
```

### Wait Message (8s timer)

`_send_wait_if_slow` background task:
- Waits 8 seconds
- If no `TTS_CHUNK` arrived, sends `"THINKING"` text event
- Plays a random Vietnamese wait message via local TTSEngine
- When first real `TTS_CHUNK` arrives, waits for in-flight wait audio to finish before sending real audio

### Audio Constants

| Constant | Value | Description |
|----------|-------|-------------|
| `INTERNAL_SR` | 16000 | STT/TTS sample rate |
| `OPUS_SAMPLE_RATE` | 48000 | Client playback rate |
| `OPUS_BATCH_SIZE` | 10 | Frames per WS binary message (~200ms) |
| `OPUS_FRAME_DURATION` | 0.02 | 20ms per frame |
| `MIN_AUDIO_MS` | 300 | Minimum input duration |

### Disconnect Handling

On WebSocket close:
1. Cancel current `handle_pipeline` task
2. Call `mark_cancelled(r, request_id)` → sets `cancel:{id}` with 30s TTL
3. Delete `resp:{request_id}` stream

---

## 2. Pipeline Dispatcher — `kids/pipeline.py`

### `process_stream(audio_path, session_id, request_id)` — async generator

1. Push job to `jobs:stt` stream via `xadd_json`
2. Yield events from `stream_results(r, "resp:{request_id}")`
3. Stop on `TTS_END` or `NO_INPUT`
4. Clean up `resp:{request_id}` after completion

### Job Payload → `jobs:stt`

```json
{
    "request_id": "a1b2c3d4e5f6",
    "session_id": "device_123_...",
    "audio_uri": "/tmp/.../file.wav",
    "resp_stream": "resp:a1b2c3d4e5f6",
    "llm_config": {
        "model": "gpt-4o-mini",
        "system_prompt": "<ROLE_PROMPT with RAG context>",
        "safety_prompt": "<SAFETY_PROMPT>",
        "temperature": 0.7,
        "max_tokens": 5000,
        "top_p": 0.95,
        "frequency_penalty": 0.4,
        "presence_penalty": 0.0,
        "user_name": "Bạn nhỏ",
        "location_name": "Việt Nam"
    }
}
```

**Timeout:** `PIPELINE_TIMEOUT` = 60s (env-configurable). `stream_results()` resets the deadline after each yield to prevent timeout during long playback.

---

## 3. STT Worker — `workers/stt_worker.py`

- Reads from `jobs:stt`
- Calls STT engine (Whisper/similar) on `audio_uri`
- Pushes result to `jobs:llm` with the same `request_id` and `llm_config`

---

## 4. LLM Worker — `workers/llm_worker.py`

### Streaming Loop

1. Read from `jobs:llm` via `safe_read_group`
2. Check `is_cancelled(r, request_id)` before starting
3. Call `chat_completion_stream(messages, ...)` — yields tokens
4. Accumulate tokens in `buffer`
5. Every 10 tokens: check `is_cancelled()` again
6. On each token: call `should_flush(buffer, chunk_idx, ...)`
7. When flush triggers:
   - `_sanitize_for_tts(chunk)` — clean text for TTS
   - `_infer_emotion(sentence)` — detect emotion
   - `xadd_json(r, STREAM_TTS, tts_job)` → push to `jobs:tts`
8. After stream ends: flush remaining buffer iteratively
9. Save conversation history to Redis `chat:{session_id}:turns`

### Chunk Payload → `jobs:tts`

```json
{
    "request_id": "a1b2c3d4e5f6",
    "session_id": "device_123_...",
    "text": "sanitized text for TTS",
    "emotion": "00",
    "chunk_index": 0,
    "is_last": false,
    "resp_stream": "resp:a1b2c3d4e5f6",
    "input_text": "original user query (chunk 0 only)"
}
```

### Emotion Codes

| Code | Meaning | Trigger words |
|------|---------|---------------|
| `"00"` | Neutral | Default |
| `"01"` | Happy | vui, haha, yêu, cười, tuyệt vời, hihi |
| `"10"` | Sad | buồn, xin lỗi, huhu, khóc, chán, mệt, đau |
| `"02"` | Angry | giận, ghét, cáu, tức, bực |

### Error Handling

- **Context overflow:** Trims history to last 1 turn (retry 1), then strips RAG context (retry 2)
- **Connection errors:** Sends fallback message "Tớ đang bị mất kết nối..."
- **General errors:** Sends fallback message "Xin lỗi cậu, dữ liệu tải về quá lớn..."

---

## 5. Smart Chunker — `workers/smart_chunker.py`

### `should_flush(buffer, chunk_index, llm_done, now_ms, last_flush_ms)`

Decides when to send accumulated LLM text to TTS. Balances **time-to-first-audio** vs **TTS call overhead**.

| Condition | chunk_index == 0 | chunk_index > 0 |
|-----------|-------------------|-----------------|
| `llm_done` | Flush all | Flush all |
| Terminal `.!?\n` + len >= | **15** chars | **40** chars |
| Soft `,;:—` + len >= | **40** chars | **80** chars |
| Force flush (no punct) + len >= | **80** chars | **160** chars |

Force flush cuts at last space to avoid splitting words.

**Design rationale:** Chunk 0 has lower thresholds to minimize time-to-first-audio (~1-2s faster). Subsequent chunks use higher thresholds because each TTS call has ~1-2s overhead that would exceed playback duration for very short text.

---

## 6. TTS Worker — `workers/tts_worker.py`

1. Read from `jobs:tts` via `safe_read_group`
2. Check `is_cancelled()` before starting
3. Call `TTSEngine.synthesize(text, output_path)` — OmniVoice via UNIX socket
4. Push result to `resp:{request_id}` stream:

```json
{
    "type": "TTS_CHUNK",
    "audio_output_path": "/tmp/audio_cache/output_<ts>_<uid>.wav",
    "chunk_index": 0,
    "is_last": false
}
```

5. Push `TTS_END` event when last chunk is done

### OmniVoice Protocol

```
Connect: UNIX socket /tmp/omnivoice_tts.sock
Send:    {"text": "...", "output_path": "/tmp/audio_cache/output_<ts>_<uid>.wav"}\n
Recv:    {"output_path": "..."}\n  (or {"error": "..."})
Timeout: 30s
```

---

## 7. Redis Transport — `shared/redis_bus.py`

### Streams

| Key | Type | Purpose |
|-----|------|---------|
| `jobs:stt` | Stream | STT worker input queue |
| `jobs:llm` | Stream | LLM worker input queue |
| `jobs:tts` | Stream | TTS worker input queue |
| `resp:{request_id}` | Stream | Per-request results → main.py |
| `cancel:{request_id}` | String (TTL 30s) | Cancellation flag |

### Key Functions

| Function | Description |
|----------|-------------|
| `xadd_json(r, stream, data)` | Add JSON message to stream (maxlen=20000) |
| `safe_read_group(r, stream, group, consumer)` | Read one message from consumer group |
| `stream_results(r, resp_stream, timeout)` | Async generator yielding events from resp stream |
| `mark_cancelled(r, request_id)` | Set cancel flag with 30s TTL |
| `is_cancelled(r, request_id)` | Check cancel flag |

### Event Types in `resp:{request_id}`

| Event | Fields | Description |
|-------|--------|-------------|
| `TTS_CHUNK` | `audio_output_path`, `chunk_index`, `is_last` | Audio chunk ready |
| `TTS_END` | — | All chunks sent |
| `NO_INPUT` | — | STT detected no speech |

---

## 8. Timing Breakdown

```
User speaks (1-3s) → STT (2-3s) → LLM first chunk (2-4s) → TTS (1.2-1.6s)
                                                         Total: ~5-9s to first audio

With 8s timer:
- If no TTS by 8s → "Đang suy nghĩ..." + wait message
- First TTS chunk → "Đang trả lời..." + real audio
```

### Latency Optimizations

1. **Smart chunker low thresholds for chunk 0** — flushes at 15 chars (terminal) vs 40
2. **Streaming LLM** — tokens sent to TTS as generated, not after full response
3. **Opus batching** — 10 frames per WS message (~200ms) reduces overhead
4. **Drain-aware playback** — slightly-faster-than-realtime throttling
