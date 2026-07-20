package com.tesla.screenflow

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.lang.reflect.Method
import java.net.InetAddress

/**
 * 通过 Android 原生 API + 反射配置热点固定 IP 和 DNS。
 */
object HotspotConfig {

    private const val TAG = "TeslaScreenFlow::Hotspot"
    const val HOTSPOT_IP = "192.168.43.2"
    const val HOTSPOT_GATEWAY = "192.168.43.1"
    const val HOTSPOT_SUBNET = "255.255.255.0"

    fun configureHotspot(context: Context) {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                configureApi33(wm)
            } else {
                configureLegacy(wm)
            }
            Log.i(TAG, "Hotspot configured: $HOTSPOT_IP")
        } catch (e: Exception) {
            Log.e(TAG, "Hotspot config failed", e)
        }
    }

    private fun configureApi33(wm: WifiManager) {
        val clz = Class.forName("android.net.wifi.SoftApConfiguration")
        val bldClz = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val getCfg: Method = WifiManager::class.java.getMethod("getSoftApConfiguration")
        val cur = getCfg.invoke(wm)
        val bld = bldClz.getConstructor(clz).newInstance(cur)

        // IP configuration
        try {
            val ipCfgBldClz = Class.forName("android.net.wifi.SoftApConfiguration\$IpConfiguration\$Builder")
            val ipCfgClz = Class.forName("android.net.wifi.SoftApConfiguration\$IpConfiguration")
            val ipBld = ipCfgBldClz.getConstructor().newInstance()
            ipCfgBldClz.getMethod("setIpAddress", String::class.java).invoke(ipBld, HOTSPOT_IP)
            ipCfgBldClz.getMethod("setGateway", String::class.java).invoke(ipBld, HOTSPOT_GATEWAY)
            ipCfgBldClz.getMethod("setPrefixLength", Int::class.java).invoke(ipBld, 24)
            val ipCfg = ipCfgBldClz.getMethod("build").invoke(ipBld)
            bldClz.getMethod("setIpConfiguration", ipCfgClz).invoke(bld, ipCfg)
        } catch (e: Exception) {
            Log.w(TAG, "IpConfiguration not supported", e)
        }

        val newCfg = bldClz.getMethod("build").invoke(bld)
        WifiManager::class.java.getMethod("setSoftApConfiguration", clz).invoke(wm, newCfg)
    }

    @Suppress("DEPRECATION")
    private fun configureLegacy(wm: WifiManager) {
        val getCfg: Method = WifiManager::class.java.getMethod("getWifiApConfiguration")
        val cfg = getCfg.invoke(wm) ?: return
        try {
            cfg.javaClass.getMethod("setIpAddress", String::class.java).invoke(cfg, HOTSPOT_IP)
        } catch (_: Exception) {}
        val setCfg: Method = WifiManager::class.java.getMethod("setWifiApConfiguration",
            Class.forName("android.net.wifi.WifiConfiguration"))
        setCfg.invoke(wm, cfg)
    }

    fun setHotspotDns(context: Context, dnsIP: String) {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val clz = Class.forName("android.net.wifi.SoftApConfiguration")
                val bldClz = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
                val getCfg: Method = WifiManager::class.java.getMethod("getSoftApConfiguration")
                val cur = getCfg.invoke(wm)
                val bld = bldClz.getConstructor(clz).newInstance(cur)
                bldClz.getMethod("setDnsAddress", InetAddress::class.java).invoke(bld, InetAddress.getByName(dnsIP))
                val newCfg = bldClz.getMethod("build").invoke(bld)
                WifiManager::class.java.getMethod("setSoftApConfiguration", clz).invoke(wm, newCfg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS config failed", e)
        }
    }
}
