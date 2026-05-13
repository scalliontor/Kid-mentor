import asyncio
import websockets

async def test():
    try:
        async with websockets.connect("ws://171.226.10.121:8000/voice/ws") as ws:
            print("Connected!")
            await ws.send('{"device_id": "android_app"}')
            print("Sent handshake")
            await ws.send("START")
            print("Sent START")
            res = await ws.recv()
            print("Received:", res)
    except Exception as e:
        print("Error:", e)

asyncio.run(test())
