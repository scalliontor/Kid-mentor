import asyncio
import websockets
import json

async def test_streaming():
    uri = "ws://171.226.10.121:8000/voice/ws"
    print(f"Connecting to {uri}...")
    try:
        async with websockets.connect(uri) as websocket:
            print("Connected! Sending init JSON...")
            await websocket.send(json.dumps({"device_id": "test_script", "firmware_version": "2.0.0"}))
            
            print("Sending START...")
            await websocket.send("START")
            
            response = await websocket.recv()
            print(f"Received: {response}")
            
            if response == "LISTENING":
                print("Server is listening. Sending dummy audio bytes (simulating Opus)...")
                # Send some dummy binary data
                for _ in range(5):
                    await websocket.send(b'\x00\x00\x00\x00')
                    await asyncio.sleep(0.1)
                
                print("Sending END...")
                await websocket.send("END")
                
                while True:
                    response = await websocket.recv()
                    if isinstance(response, str):
                        print(f"Received Text: {response}")
                        if response == "IDLE":
                            print("Session finished successfully!")
                            break
                    else:
                        print(f"Received Binary Data: {len(response)} bytes")

    except Exception as e:
        print(f"Connection failed: {e}")

if __name__ == "__main__":
    asyncio.run(test_streaming())
