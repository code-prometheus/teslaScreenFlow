package com.tesla.screenflow

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import org.xbill.DNS.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class VpnDnsInterceptor : VpnService() {

    companion object {
        private const val TAG = "TeslaScreenFlow::VPN"
        private const val VPN_MTU = 20000
        @Volatile var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var localIP: String = "192.168.43.2"
    private var targetDomain: Name? = null
    private var dnsDone = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopVpn(); return START_NOT_STICKY }
        localIP = intent?.getStringExtra("local_ip") ?: localIP
        val d = intent?.getStringExtra("domain") ?: "code-prometheus.ai"
        targetDomain = Name.fromString("$d.")
        Log.i(TAG, "VPN: $d → $localIP")
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        try {
            vpnInterface = Builder()
                .setSession("teslaScreenFlow VPN")
                .addAddress(localIP, 24)
                .addDnsServer(localIP)
                .addRoute("0.0.0.0", 0)
                .setMtu(VPN_MTU)
                .setBlocking(true)
                .establish()
            isRunning = true; running.set(true)
            Thread(this::vpnLoop, "VpnLoop").start()
        } catch (e: Exception) {
            Log.e(TAG, "VPN fail", e); isRunning = false
        }
    }

    private fun stopVpn() {
        running.set(false); isRunning = false
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        stopSelf()
    }

    private fun vpnLoop() {
        try {
            val input = FileInputStream(vpnInterface!!.fileDescriptor)
            val output = FileOutputStream(vpnInterface!!.fileDescriptor)
            val pkt = ByteArray(VPN_MTU)
            while (running.get() && !dnsDone) {
                val len = input.read(pkt)
                if (len <= 0) continue
                val ipVer = (pkt[0].toInt() shr 4) and 0x0F
                if (ipVer != 4) continue
                val protocol = pkt[9].toInt() and 0xFF
                val ipHdrLen = (pkt[0].toInt() and 0x0F) * 4
                val srcIP = readIp(pkt, 12)
                val dstIP = readIp(pkt, 16)
                when (protocol) {
                    17 -> dnsUdp(pkt, len, ipHdrLen, srcIP, dstIP, output)
                    6  -> dnsTcp(pkt, len, ipHdrLen, srcIP, dstIP, output)
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "Loop err", e)
        }
    }

    private fun dnsUdp(pkt: ByteArray, len: Int, ipHdrLen: Int, srcIP: Int, dstIP: Int, output: FileOutputStream) {
        val u = ipHdrLen
        val dstPort = (((pkt[u+2].toInt() and 0xFF) shl 8) or (pkt[u+3].toInt() and 0xFF))
        if (dstPort != 53) return
        val srcPort = (((pkt[u].toInt() and 0xFF) shl 8) or (pkt[u+1].toInt() and 0xFF))
        val resp = process(pkt.copyOfRange(u + 8, len)) ?: return
        output.write(udpReply(pkt, ipHdrLen, u, dstIP, srcIP, srcPort, 53, resp))
        onDone()
    }

    private fun dnsTcp(pkt: ByteArray, len: Int, ipHdrLen: Int, srcIP: Int, dstIP: Int, output: FileOutputStream) {
        val t = ipHdrLen
        val dstPort = (((pkt[t+2].toInt() and 0xFF) shl 8) or (pkt[t+3].toInt() and 0xFF))
        if (dstPort != 53) return
        val srcPort = (((pkt[t].toInt() and 0xFF) shl 8) or (pkt[t+1].toInt() and 0xFF))
        val flags = pkt[t+13].toInt() and 0xFF
        val tcpHdrLen = ((pkt[t+12].toInt() shr 4) and 0x0F) * 4
        val seq = (((pkt[t+4].toLong() and 0xFF) shl 24) or ((pkt[t+5].toLong() and 0xFF) shl 16) or
                   ((pkt[t+6].toLong() and 0xFF) shl 8) or (pkt[t+7].toLong() and 0xFF))
        if (flags and 0x02 != 0) { // SYN
            output.write(tcpPkt(dstIP, srcIP, dstPort, srcPort, seq + 1, seq + 1, 0x12, ByteArray(0)))
        } else if (flags and 0x08 != 0) { // PUSH
            val payload = pkt.copyOfRange(t + tcpHdrLen, len)
            if (payload.size > 2) {
                val resp = process(payload.copyOfRange(2, payload.size)) ?: return
                val tcpResp = ByteArray(2 + resp.size).also { it[0]=((resp.size shr 8) and 0xFF).toByte(); it[1]=(resp.size and 0xFF).toByte(); for (i in resp.indices) it[2+i]=resp[i] }
                output.write(tcpPkt(dstIP, srcIP, dstPort, srcPort, seq + payload.size, seq + 1, 0x19, tcpResp))
            }
            onDone()
        }
    }

    private fun process(dnsRaw: ByteArray): ByteArray? {
        try {
            val q = Message(dnsRaw)
            val rq = q.question
            if (rq.type != Type.A || rq.name != targetDomain || q.header.getFlag(Flags.QR.toInt())) return null
            val r = Message(q.header.id)
            r.header.setFlag(Flags.QR.toInt()); r.header.setFlag(Flags.AA.toInt()); r.header.setFlag(Flags.RA.toInt())
            r.addRecord(rq, Section.QUESTION)
            r.addRecord(ARecord(rq.name, DClass.IN, 60L, InetAddress.getByName(localIP)), Section.ANSWER)
            return r.toWire()
        } catch (_: Exception) { return null }
    }

    @Synchronized private fun onDone() {
        if (dnsDone) return
        dnsDone = true
        Log.i(TAG, "DNS done, stopping VPN in 1s")
        Thread { Thread.sleep(1000); stopVpn() }.start()
    }

    private fun readIp(p: ByteArray, o: Int) = ((p[o].toInt() and 0xFF) shl 24) or ((p[o+1].toInt() and 0xFF) shl 16) or ((p[o+2].toInt() and 0xFF) shl 8) or (p[o+3].toInt() and 0xFF)
    private fun writeIp(p: ByteArray, o: Int, v: Int) { p[o]=((v shr 24) and 0xFF).toByte(); p[o+1]=((v shr 16) and 0xFF).toByte(); p[o+2]=((v shr 8) and 0xFF).toByte(); p[o+3]=(v and 0xFF).toByte() }

    private fun udpReply(req: ByteArray, ih: Int, u: Int, srcIP: Int, dstIP: Int, sp: Int, dp: Int, dns: ByteArray): ByteArray {
        val total = ih + 8 + dns.size; val r = ByteArray(total)
        r[0]=0x45; r[1]=0; r[2]=((total shr 8) and 0xFF).toByte(); r[3]=(total and 0xFF).toByte()
        r[4]=0; r[5]=0; r[6]=0x40; r[7]=0; r[8]=64; r[9]=17
        writeIp(r, 12, srcIP); writeIp(r, 16, dstIP)
        val ic = ipCksum(r, ih); r[10]=((ic shr 8) and 0xFF).toByte(); r[11]=(ic and 0xFF).toByte()
        val x = ih; r[x]=((sp shr 8) and 0xFF).toByte(); r[x+1]=(sp and 0xFF).toByte()
        r[x+2]=((dp shr 8) and 0xFF).toByte(); r[x+3]=(dp and 0xFF).toByte()
        val ul = 8 + dns.size; r[x+4]=((ul shr 8) and 0xFF).toByte(); r[x+5]=(ul and 0xFF).toByte()
        r[x+6]=0; r[x+7]=0
        for (i in dns.indices) r[x+8+i]=dns[i]
        return r
    }

    private fun tcpPkt(srcIP: Int, dstIP: Int, sp: Int, dp: Int, seq: Long, ack: Long, flags: Int, data: ByteArray): ByteArray {
        val total = 40 + data.size; val r = ByteArray(total)
        r[0]=0x45; r[1]=0; r[2]=((total shr 8) and 0xFF).toByte(); r[3]=(total and 0xFF).toByte()
        r[4]=0; r[5]=0; r[6]=0x40; r[7]=0; r[8]=64; r[9]=6
        writeIp(r, 12, srcIP); writeIp(r, 16, dstIP)
        val ic = ipCksum(r, 20); r[10]=((ic shr 8) and 0xFF).toByte(); r[11]=(ic and 0xFF).toByte()
        r[20]=((sp shr 8) and 0xFF).toByte(); r[21]=(sp and 0xFF).toByte()
        r[22]=((dp shr 8) and 0xFF).toByte(); r[23]=(dp and 0xFF).toByte()
        r[24]=((seq shr 24) and 0xFF).toByte(); r[25]=((seq shr 16) and 0xFF).toByte(); r[26]=((seq shr 8) and 0xFF).toByte(); r[27]=(seq and 0xFF).toByte()
        r[28]=((ack shr 24) and 0xFF).toByte(); r[29]=((ack shr 16) and 0xFF).toByte(); r[30]=((ack shr 8) and 0xFF).toByte(); r[31]=(ack and 0xFF).toByte()
        r[32]=0x50; r[33]=flags.toByte(); r[34]=0xFF.toByte(); r[35]=0xFF.toByte(); r[36]=0; r[37]=0; r[38]=0; r[39]=0
        for (i in data.indices) r[40+i]=data[i]
        return r
    }

    private fun ipCksum(d: ByteArray, len: Int): Int {
        var s=0; var i=0
        while (i<len) { s += ((d[i].toInt() and 0xFF) shl 8) or (if (i+1<len) (d[i+1].toInt() and 0xFF) else 0); i+=2 }
        while (s ushr 16 > 0) s = (s and 0xFFFF) + (s ushr 16)
        return s.inv() and 0xFFFF
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
}
