package com.tesla.screenflow

import okhttp3.Dns
import java.net.InetAddress

/**
 * OkHttp Dns 实现：将 code-prometheus.ai 映射到 192.168.43.2。
 * 其他域名走系统 DNS。
 */
class LocalDns : Dns {
    companion object {
        private const val DOMAIN = "code-prometheus.ai"
        private const val TARGET_IP = "192.168.43.2"
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.equals(DOMAIN, ignoreCase = true)) {
            return listOf(InetAddress.getByName(TARGET_IP))
        }
        return Dns.SYSTEM.lookup(hostname)
    }
}
