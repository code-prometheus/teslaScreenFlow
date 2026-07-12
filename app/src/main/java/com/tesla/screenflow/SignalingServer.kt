package com.tesla.screenflow

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONException
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * WebSocket 信令服务器 — WebRTC SDP/ICE 交换 + 触控指令回传。
 * 手机端监听 8081 端口，车机浏览器连接此端口。
 */
class SignalingServer(
    port: Int,
    private val callback: SignalCallback
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "TeslaScreenFlow::Signaling"
    }

    private val clients = mutableMapOf<String, WebSocket>()

    // ── 发送 API ──

    fun sendOffer(clientId: String, sdp: String) {
        val conn = clients[clientId] ?: return
        try {
            conn.send(JSONObject().apply {
                put("type", "offer")
                put("sdp", sdp)
            }.toString())
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to build offer", e)
        }
    }

    fun sendIceCandidate(clientId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val conn = clients[clientId] ?: return
        try {
            conn.send(JSONObject().apply {
                put("type", "ice")
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
                put("candidate", candidate)
            }.toString())
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to build ICE message", e)
        }
    }

    fun broadcastOffer(sdp: String) {
        for ((id, conn) in clients) {
            try {
                conn.send(JSONObject().apply {
                    put("type", "offer")
                    put("sdp", sdp)
                }.toString())
            } catch (e: JSONException) {
                Log.e(TAG, "Broadcast failed for $id", e)
            }
        }
    }

    fun stopSafely() {
        try {
            stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    // ── WebSocket 回调 ──

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val clientId = clientId(conn)
        clients[clientId] = conn
        Log.i(TAG, "Client connected: $clientId")
        callback.onClientConnected(clientId)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val clientId = clientId(conn)
        clients.remove(clientId)
        Log.i(TAG, "Client disconnected: $clientId (code=$code)")
        callback.onClientDisconnected(clientId)
    }

    override fun onMessage(conn: WebSocket, raw: String) {
        val clientId = clientId(conn)

        val json: JSONObject = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            Log.w(TAG, "Invalid JSON from $clientId", e)
            return
        }

        val type = json.optString("type", "")
        Log.d(TAG, "Message: type=$type from $clientId")

        try {
            when (type) {
                "answer" -> {
                    callback.onAnswerReceived(clientId, json.getString("sdp"))
                }
                "ice" -> {
                    callback.onIceCandidateReceived(
                        clientId,
                        json.getString("candidate"),
                        json.optString("sdpMid", ""),
                        json.optInt("sdpMLineIndex", 0)
                    )
                }
                "touch" -> {
                    callback.onTouchEvent(
                        clientId,
                        json.getString("action"),
                        json.optDouble("x", 0.0).toFloat(),
                        json.optDouble("y", 0.0).toFloat()
                    )
                }
                else -> Log.w(TAG, "Unknown message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing $type from $clientId", e)
            callback.onError("Failed to process $type: ${e.message}")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        val id = conn?.let { clientId(it) } ?: "unknown"
        Log.e(TAG, "WebSocket error for $id", ex)
        callback.onError("WebSocket error for $id: ${ex.message}")
    }

    override fun onStart() {
        Log.i(TAG, "SignalingServer started on port $port")
    }

    // ── 工具 ──

    private fun clientId(conn: WebSocket): String {
        val addr = conn.remoteSocketAddress
        return if (addr != null) "${addr.address?.hostAddress ?: "unknown"}:${addr.port}"
        else conn.hashCode().toString()
    }

    // ── 回调接口 ──

    interface SignalCallback {
        fun onAnswerReceived(clientId: String, sdp: String)
        fun onIceCandidateReceived(clientId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int)
        fun onTouchEvent(clientId: String, action: String, x: Float, y: Float)
        fun onClientConnected(clientId: String)
        fun onClientDisconnected(clientId: String)
        fun onError(error: String)
    }
}
