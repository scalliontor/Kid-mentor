"""
Fake ESP32 Device (V2 Opus)
Mô phỏng PTalk V2 giao tiếp với CloudPTalk.
- Encode Opus @ 48kHz
- Giao thức: START, END, SPEAKING, PROCESSING, IDLE
"""
import asyncio
import os
import time
import sys
import subprocess
import numpy as np
import soundfile as sf
import websockets

from shared.opus_codec import (
    OpusDecoder, OpusEncoder,
    decode_ws_message, encode_pcm_to_opus_frames,
    OPUS_SAMPLE_RATE, OPUS_FRAME_SIZE
)

SERVER = "171.226.10.121"
PORT = 8000
WS_PATH = "/voice/ws"
INPUT_FILE = os.environ.get(
    "INPUT_FILE",
    "/mnt/DA0054DE0054C365/STEAM_LAB/cloud_ptalk/CloudPTalk/test_audio.wav",
)
PLAY_AUDIO = os.environ.get("PLAY_AUDIO") == "1"

async def test():
    print(f"👴 Mô phỏng ESP32 (V2 Opus) kết nối tới {SERVER}:{PORT}")
    
    # 1. Load and resample audio to 48kHz for Opus
    try:
        wav, sr = sf.read(INPUT_FILE, dtype="float32", always_2d=True)
        wav = wav.mean(axis=1) if wav.shape[1] > 1 else wav[:, 0]
        if sr != OPUS_SAMPLE_RATE:
            new_len = int(len(wav) * OPUS_SAMPLE_RATE / sr)
            wav = np.interp(np.linspace(0, 1, new_len), np.linspace(0, 1, len(wav)), wav).astype("float32")
        wav = np.clip(wav, -1.0, 1.0)
        pcm_48k = (wav * 32767.0).astype(np.int16)
    except Exception as e:
        print(f"❌ Cannot read {INPUT_FILE}: {e}")
        return

    encoder = OpusEncoder()
    decoder = OpusDecoder()
    
    opus_frames = encode_pcm_to_opus_frames(encoder, pcm_48k)
    print(f"📂 Audio: {len(pcm_48k)} samples -> {len(opus_frames)} Opus frames")
    
    aplay_proc = None
    if PLAY_AUDIO:
        # Tham số: S16_LE (16-bit little endian), c 1 (mono), r 48000 (tần số 48kHz)
        print("🔊 Đang khởi tạo bộ phát loa trực tiếp (aplay)...")
        aplay_proc = subprocess.Popen(
            ['aplay', '-q', '-f', 'S16_LE', '-c', '1', '-r', str(OPUS_SAMPLE_RATE)],
            stdin=subprocess.PIPE
        )

    start_time = time.time()
    received_pcm = []
    
    try:
        async with websockets.connect(f"ws://{SERVER}:{PORT}{WS_PATH}") as ws:
            print("🔌 Đã kết nối!")
            
            # Handshake
            await ws.send('{"device_id": "esp32_opus_test", "firmware_version": "2.0.0"}')
            
            # START
            await ws.send("START")
            print("🎤 Đã gửi START")

            while True:
                msg = await asyncio.wait_for(ws.recv(), timeout=10)
                if isinstance(msg, bytes):
                    print(f"⚠️ Nhận binary trước LISTENING: {len(msg)} bytes")
                    continue
                print(f"📨 Server: {msg}")
                if msg == "LISTENING":
                    break
                if msg == "IDLE":
                    raise RuntimeError("Server returned IDLE before accepting audio")
            
            # Send audio in chunks
            for i in range(0, len(opus_frames), 5):
                batch = opus_frames[i:i+5]
                await ws.send(b"".join(batch))
                await asyncio.sleep(len(batch) * 0.02)
                
            print(f"📤 Đã gửi {len(opus_frames)} frames")
            
            # END
            await ws.send("END")
            request_time = time.time()
            print("🛑 Đã gửi END, đang chờ AI phản hồi...")
            
            ttfb = None
            # Listen
            while True:
                msg = await ws.recv()
                
                if ttfb is None and (isinstance(msg, bytes) or msg == "SPEAKING"):
                    ttfb = time.time() - request_time
                    print(f"⏱️ [TTFB] Time to First Byte: {ttfb:.2f}s")
                    
                if isinstance(msg, bytes):
                    pcm = decode_ws_message(decoder, msg)
                    if len(pcm) > 0:
                        received_pcm.append(pcm)
                        # Push audio directly to speaker!
                        if aplay_proc and aplay_proc.stdin:
                            try:
                                loop = asyncio.get_running_loop()
                                await loop.run_in_executor(None, aplay_proc.stdin.write, pcm.tobytes())
                                await loop.run_in_executor(None, aplay_proc.stdin.flush)
                            except BrokenPipeError:
                                print("❌ Loa (aplay) đã bị ngắt đột ngột!")
                else:
                    print(f"📨 Server: {msg}")
                    if msg == "IDLE":
                        break
                        
    except Exception as e:
        print(f"❌ Lỗi kết nối: {e}")
        return

    finally:
        # Tắt loa
        if aplay_proc and aplay_proc.stdin:
            aplay_proc.stdin.close()
            aplay_proc.terminate()
        
        elapsed = time.time() - start_time
        if received_pcm:
            out_pcm = np.concatenate(received_pcm)
            out_path = "output_opus.wav"
            sf.write(out_path, out_pcm, OPUS_SAMPLE_RATE)
            print(f"\n✅ THÀNH CÔNG! Đã nhận {len(out_pcm)} mẫu PCM phản hồi (Mất tổng cộng {elapsed:.2f}s)")
            print(f"💾 File lưu tại: {out_path}")
        else:
            print(f"\n⚠️ Không nhận được audio phản hồi nào sau {elapsed:.2f}s")

if __name__ == "__main__":
    asyncio.run(test())
