package com.tesla.screenflow

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException

/**
 * TeslaWebServer — 基于 NanoWSD 的单端口 HTTP + WebSocket 服务器。
 * 模仿 androidWebMirroring 的架构，HTTP 和信令在同一个端口（默认 8080）。
 */
class TeslaWebServer(
    port: Int,
    private val context: Context,
    private val signalCallback: SignalCallback
) : NanoWSD(port) {

    companion object {
        private const val TAG = "TeslaScreenFlow::Server"
    }

    private var webSocket: NanoWSD.WebSocket? = null

    interface SignalCallback {
        fun onAnswerReceived(sdp: String)
        fun onIceCandidateReceived(candidate: String, sdpMid: String, sdpMLineIndex: Int)
        fun onTouchEvent(action: String, x: Float, y: Float)
        fun onClientConnected()
        fun onClientDisconnected()
        fun onOfferReady(sdp: String)
    }

    // ═══════════════════════════════════════════
    // HTTP 部分
    // ═══════════════════════════════════════════
    override fun serveHttp(request: IHTTPSession): Response {
        val uri = request.uri ?: "/"
        val path = if (uri == "/") "index.html" else uri.removePrefix("/")

        return try {
            val inputStream = context.assets.open(path)
            val mimeType = when {
                path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "text/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".svg") -> "image/svg+xml"
                path.endsWith(".png") -> "image/png"
                else -> "application/octet-stream"
            }
            val response = newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, inputStream.available().toLong())
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response
        } catch (e: IOException) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

    // ═══════════════════════════════════════════
    // WebSocket 部分
    // ═══════════════════════════════════════════
    override fun openWebSocket(handshake: IHTTPSession): WebSocket? {
        webSocket = NanoWS(handshake)
        return webSocket
    }

    private inner class NanoWS(handshake: IHTTPSession) : NanoWSD.WebSocket(handshake) {

        private var pingTimer: java.util.Timer? = null

        override fun onOpen() {
            Log.i(TAG, "WebSocket connected")
            // 每 5 秒发送 ping 保持连接活跃
            pingTimer = java.util.Timer()
            pingTimer?.schedule(object : java.util.TimerTask() {
                override fun run() {
                    try { ping(ByteArray(0)) } catch (e: Exception) {}
                }
            }, 5000, 5000)
            signalCallback.onClientConnected()
        }

        override fun onClose(code: WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            Log.i(TAG, "WebSocket closed: $reason, code=$code, remote=$initiatedByRemote")
            pingTimer?.cancel()
            pingTimer = null
            // 客户端断开时通知 Service 重新生成 Offer
            signalCallback.onClientDisconnected()
        }

        override fun onMessage(message: WebSocketFrame) {
            val text = message.textPayload ?: return
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid JSON: $text")
                return
            }

            val type = json.optString("type", "")
            try {
                when (type) {
                    "answer" -> signalCallback.onAnswerReceived(json.getString("sdp"))
                    "ice" -> {
                        signalCallback.onIceCandidateReceived(
                            json.getString("candidate"),
                            json.optString("sdpMid", ""),
                            json.optInt("sdpMLineIndex", 0)
                        )
                    }
                    "touch" -> signalCallback.onTouchEvent(
                        json.getString("action"),
                        json.optDouble("x", 0.0).toFloat(),
                        json.optDouble("y", 0.0).toFloat()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing $type", e)
            }
        }

        override fun onPong(pong: WebSocketFrame) {}
        override fun onException(exception: IOException) {
            Log.e(TAG, "WebSocket exception", exception)
        }
    }

    // ═══════════════════════════════════════════
    // 发送 API
    // ═══════════════════════════════════════════
    fun sendOffer(sdp: String) {
        try {
            val json = JSONObject().apply {
                put("type", "offer")
                put("sdp", sdp)
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendOffer failed", e)
        }
    }

    fun sendIce(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        try {
            val json = JSONObject().apply {
                put("type", "ice")
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
                put("candidate", candidate)
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendIce failed", e)
        }
    }
}
