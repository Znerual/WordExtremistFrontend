package com.laurenz.wordextremist.network

import android.util.Log
import okhttp3.* // OkHttp, Request, WebSocket, WebSocketListener
import okio.ByteString
import org.json.JSONObject // For sending/receiving JSON
import com.laurenz.wordextremist.util.TokenManager

class GameWebSocketClient(
    private val gameId: String,
    private val contextForToken: android.content.Context, // To get token
    // private val googleIdToken: String, // Needed for connection URL
    private val listener: GameWebSocketListenerCallback
) {
    interface GameWebSocketListenerCallback {
        fun onOpen()
        fun onMessageReceived(message: JSONObject) // Or parsed JSONObject/data class
        fun onClosing(code: Int, reason: String)
        fun onFailure(t: Throwable, response: Response?)
        fun onClosed(code: Int, reason: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS) // No timeout for long-lived connections
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS) // Add ping interval for keep-alive
        .build()

    private var webSocket: WebSocket? = null

    fun connect() {
        if (webSocket != null) {
            Log.w("GameWebSocketClient", "Already connected or connecting.")
            return
        }

        val jwtToken = TokenManager.getToken(contextForToken)
        if (jwtToken == null) {
            Log.e("GameWebSocketClient", "Cannot connect: JWT Token is missing.")
            listener.onFailure(IllegalStateException("Missing auth token for WebSocket"), null)
            return
        }

        // Adjust WebSocket URL - REMOVED TOKEN PARAMETER
        val wsUrl = ApiService.BASE_URL.replaceFirst("http", "ws") + "ws/game/$gameId?token=$jwtToken" // "${ApiService.BASE_URL.replace("http", "ws")}ws/game/$gameId?token=$googleIdToken"
        val request = Request.Builder()
            .url(wsUrl)
            .build()


        Log.d("GameWebSocketClient", "Connecting to: ${request.url}")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                Log.i("GameWebSocketClient", "WebSocket Opened: ${response.message}")
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                Log.d("GameWebSocketClient", "Message received (text): $text")
                try {
                    val jsonMessage = JSONObject(text)
                    listener.onMessageReceived(jsonMessage) // Pass parsed JSON
                } catch (e: Exception) {
                    Log.e("GameWebSocketClient", "Failed to parse JSON message: $text", e)
                    // Optionally, notify listener of the raw text or error
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                super.onMessage(webSocket, bytes)
                Log.d("GameWebSocketClient", "Message received (bytes): ${bytes.hex()}")
                // Handle binary messages if your backend sends them
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
                Log.i("GameWebSocketClient", "WebSocket Closing: $code / $reason")
                listener.onClosing(code, reason)
                // webSocket.close(1000, null) // Acknowledge close if needed
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                Log.i("GameWebSocketClient", "WebSocket Closed: $code / $reason")
                this@GameWebSocketClient.webSocket = null
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                Log.e("GameWebSocketClient", "WebSocket Failure: ${t.message}", t)
                this@GameWebSocketClient.webSocket = null
                listener.onFailure(t, response)
            }
        })
    }

    fun sendMessage(message: JSONObject) { // Send JSON directly
        if (webSocket == null) {
            Log.w("GameWebSocketClient", "WebSocket not connected, cannot send message.")
            return
        }
        val textMessage = message.toString()
        Log.d("GameWebSocketClient", "Sending message: $textMessage")
        val sent = webSocket?.send(textMessage)
        if (sent != true) {
            Log.w("GameWebSocketClient", "Failed to queue message for sending (socket closing or buffer full?).")
            // Consider notifying the listener or retrying later
        }
    }

    fun sendPlayerAction(actionType: String, payload: Map<String, Any?>?) {
        val action = JSONObject().apply {
            put("action_type", actionType)
            if (payload != null) {
                put("payload", JSONObject(payload))
            }

        }
        sendMessage(action)
    }


    fun close() {
        Log.d("GameWebSocketClient", "Attempting to close WebSocket.")
        webSocket?.close(1000, "Client initiated close")
        webSocket = null
    }

    // Check connection status
    fun isConnected(): Boolean {
        return webSocket != null
    }
}