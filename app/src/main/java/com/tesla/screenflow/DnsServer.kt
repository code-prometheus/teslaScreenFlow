package com.tesla.screenflow

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * 简易 DNS 服务器 —— 拦截 prometheus.ai（及子域名）解析到手机 IP。
 * 监听 UDP 端口 53，仅响应 A 记录查询。
 * 车机连接手机热点后，手机会自动成为 DHCP DNS 服务器。
 * 其他域名透明转发（不做上游查询，仅返回空响应避免阻塞）。
 */
class DnsServer(
    private val localIP: String,
    private val domain: String = "prometheus.ai"
) : Thread("DnsServer") {

    companion object {
        private const val TAG = "TeslaScreenFlow::DNS"
        private const val DNS_PORT = 53
    }

    @Volatile
    var running = true

    override fun run() {
        try {
            val socket = DatagramSocket(DNS_PORT, InetAddress.getByName("0.0.0.0"))
            socket.reuseAddress = true
            Log.i(TAG, "DNS 服务器启动在 $DNS_PORT，解析 $domain → $localIP")

            val buf = ByteArray(512)
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val response = buildResponse(packet)
                    if (response != null) {
                        val respPacket = DatagramPacket(
                            response, response.size,
                            packet.address, packet.port
                        )
                        socket.send(respPacket)
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "DNS 处理失败", e)
                }
            }
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "DNS 启动失败（可能需要热点模式）", e)
        }
    }

    fun shutdown() {
        running = false
        interrupt()
    }

    private fun buildResponse(query: DatagramPacket): ByteArray? {
        val data = query.data
        val len = query.length
        if (len < 12) return null

        // 解析 DNS header
        val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)

        // 只处理标准查询 (QR=0)
        if ((flags ushr 15) != 0) return null
        if (qdCount == 0) return null

        // 解析域名
        var pos = 12
        val sb = StringBuilder()
        while (pos < len && data[pos].toInt() != 0) {
            val labelLen = data[pos].toInt() and 0xFF
            if (labelLen == 0) break
            if (labelLen > 63) break  // 压缩指针，跳过
            pos++
            for (i in 0 until labelLen) {
                if (pos < len) sb.append(data[pos++].toChar())
            }
            sb.append('.')
        }
        if (sb.isNotEmpty() && sb.last() == '.') sb.deleteCharAt(sb.length - 1)
        val qname = sb.toString().lowercase()
        pos++ // skip null terminator

        if (pos + 4 > len) return null
        val qtype = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        // val qclass = ((data[pos+2].toInt() and 0xFF) shl 8) or (data[pos+3].toInt() and 0xFF)

        // 只响应 prometheus.ai 及其子域名的 A 记录查询
        val matches = qname == domain || qname.endsWith(".$domain")
        if (!matches || qtype != 1) return null  // 1 = A record

        // 构建响应
        val localBytes = parseIPv4(localIP) ?: return null

        val response = ByteArray(512)
        // Header
        response[0] = data[0]; response[1] = data[1]  // ID
        response[2] = 0x81.toByte(); response[3] = 0x80.toByte()  // QR=1, RA=1
        response[4] = 0; response[5] = 1  // QDCOUNT=1
        response[6] = 0; response[7] = 1  // ANCOUNT=1
        response[8] = 0; response[9] = 0   // NSCOUNT=0
        response[10] = 0; response[11] = 0  // ARCOUNT=0

        // Copy question section
        var rPos = 12
        for (i in 12 until pos + 4) {
            response[rPos++] = data[i]
        }

        // Answer: name pointer (0xC00C), type A, class IN, TTL 60, length 4
        response[rPos++] = 0xC0.toByte()
        response[rPos++] = 0x0C.toByte()
        response[rPos++] = 0; response[rPos++] = 1   // TYPE A
        response[rPos++] = 0; response[rPos++] = 1   // CLASS IN
        response[rPos++] = 0; response[rPos++] = 0   // TTL
        response[rPos++] = 0; response[rPos++] = 60
        response[rPos++] = 0; response[rPos++] = 4   // RDLENGTH
        response[rPos++] = localBytes[0]
        response[rPos++] = localBytes[1]
        response[rPos++] = localBytes[2]
        response[rPos++] = localBytes[3]

        return response.copyOf(rPos)
    }

    private fun parseIPv4(ip: String): ByteArray? {
        try {
            val parts = ip.split(".")
            if (parts.size != 4) return null
            return ByteArray(4) { i -> parts[i].toInt().toByte() }
        } catch (e: Exception) {
            return null
        }
    }
}
