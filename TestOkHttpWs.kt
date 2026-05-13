import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun main() {
    val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    val request = Request.Builder().url("ws://171.226.10.121:8000/voice/ws").build()
    
    client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            println("OPEN: ${response.code}")
            webSocket.send("{\"device_id\":\"android_test\"}")
            webSocket.send("START")
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            println("MSG: $text")
            if (text == "LISTENING") {
                println("SUCCESS!")
                exitProcess(0)
            }
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            println("FAIL: ${t.message}")
            t.printStackTrace()
            exitProcess(1)
        }
    })
    
    Thread.sleep(10000)
}
