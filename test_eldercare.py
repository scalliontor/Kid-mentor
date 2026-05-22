import asyncio
import websockets
import json

async def test():
    uri = "ws://171.226.10.121:8000/eldercare/ws"
    try:
        async with websockets.connect(uri) as websocket:
            print("Connected to eldercare/ws")
            await websocket.send(json.dumps({"device_id":"test","firmware_version":"2.0.0"}))
            print("Sent device_id")
            response = await websocket.recv()
            print(f"Received: {response}")
    except Exception as e:
        print(f"Error: {e}")

asyncio.run(test())
