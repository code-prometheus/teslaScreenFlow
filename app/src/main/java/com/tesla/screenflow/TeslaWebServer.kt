package com.tesla.screenflow

import android.content.Context
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException

class TeslaWebServer(
    port: Int,
    private val context: Context,
    private val callback: Callback
) : NanoWSD("0.0.0.0", port) {

    private var webSocket: NanoWSD.WebSocket? = null

    interface Callback {
        fun onTouchEvent(action: String, x: Float, y: Float)
    }

    override fun serveHttp(request: IHTTPSession): Response {
        val uri = request.uri ?: "/"
        val path = if (uri == "/") "index.html" else uri.removePrefix("/")
        return try {
            val bytes = context.assets.open(path).readBytes()
            newFixedLengthResponse(Response.Status.OK, mime(path), ByteArrayInputStream(bytes), bytes.size.toLong())
                .apply { addHeader("Cache-Control", "no-cache") }
        } catch (e: IOException) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500")
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket? {
        webSocket = NanoWS(handshake)
        return webSocket
    }

    private inner class NanoWS(handshake: IHTTPSession) : NanoWSD.WebSocket(handshake) {
        private var pingTimer: java.util.Timer? = null

        override fun onOpen() {
            pingTimer = java.util.Timer()
            pingTimer?.schedule(object : java.util.TimerTask() {
                override fun run() {
                    try { ping(ByteArray(0)) } catch (_: Exception) {}
                }
            }, 5000, 5000)
        }

        override fun onClose(code: WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            pingTimer?.cancel(); pingTimer = null
        }

        override fun onMessage(message: WebSocketFrame) {
            val text = message.textPayload ?: return
            val json = try { JSONObject(text) } catch (_: Exception) { return }
            if (json.optString("type") == "touch") {
                callback.onTouchEvent(json.getString("action"), json.optDouble("x", 0.0).toFloat(), json.optDouble("y", 0.0).toFloat())
            }
        }

        override fun onPong(pong: WebSocketFrame) {}
        override fun onException(exception: IOException) {}
    }

    fun sendJpegFrame(data: ByteArray) {
        try { webSocket?.send(data) } catch (_: Exception) {}
    }

    private fun mime(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "text/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }
}
