package com.tesla.screenflow

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TeslaScreenFlow::Main"
        private const val REQUEST_CODE = 100
        private const val VPN_REQUEST = 200
        private const val RED = 0xFFE8212E.toInt()
    }

    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private var isCapturing = false

    private var vpnGranted = false
    private var minimizeReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestBatteryOptimizationExemption()

        // 注册最小化广播接收器
        minimizeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                moveTaskToBack(true)
            }
        }
        registerReceiver(minimizeReceiver, IntentFilter("com.tesla.screenflow.MINIMIZE"), Context.RECEIVER_EXPORTED)

        // 先请求 VPN（必须先拿到 VPN 权限才能后续启动）
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST)
        } else {
            vpnGranted = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(48, 80, 48, 48); setBackgroundColor(0xFF1A1A1A.toInt())
        }

        root.addView(TextView(this).apply {
            text = "teslaScreenFlow"; textSize = 28f; setTextColor(RED); gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })

        ipText = TextView(this).apply {
            text = "正在启动..."; textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        root.addView(ipText)

        statusText = TextView(this).apply {
            text = "请求录屏权限中..."; textSize = 14f; setTextColor(0xFFAAAAAA.toInt()); gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        root.addView(statusText)

        stopButton = Button(this).apply {
            text = "退出投屏"; setBackgroundColor(0xFF444444.toInt()); setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f; setPadding(48, 16, 48, 16); isEnabled = false
            setOnClickListener { stopCapture() }
        }
        root.addView(stopButton)

        setContentView(root)
        updateIPDisplay()

        if (vpnGranted) {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            vpnGranted = resultCode == Activity.RESULT_OK
            if (vpnGranted) {
                requestScreenCapture()
            } else {
                statusText.text = "VPN 被拒绝，部分功能不可用"
                // 继续允许录屏（即使 VPN 不工作）
                requestScreenCapture()
            }
            return
        }
        if (requestCode != REQUEST_CODE || resultCode != Activity.RESULT_OK || data == null) {
            statusText.text = "录屏被拒绝，请重新打开App"; return
        }

        ScreenCaptureService.pendingResultCode = resultCode
        ScreenCaptureService.pendingData = data

        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ContextCompat.startForegroundService(this, intent)
        else startService(intent)

        isCapturing = true
        stopButton.isEnabled = true; stopButton.setBackgroundColor(RED)
        statusText.text = "投屏已启动"; updateIPDisplay()
    }

    private fun stopCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java).apply { action = ScreenCaptureService.ACTION_STOP })
        isCapturing = false; stopButton.isEnabled = false
        stopButton.setBackgroundColor(0xFF444444.toInt())
        statusText.text = "已停止"; ipText.text = "服务器: 未启动"
    }

    private fun updateIPDisplay() {
        val addr = findLocalIP()
        ipText.text = "车机浏览器打开:\nhttps://code-prometheus.ai:8080"
    }

    private fun findLocalIP(): String {
        val candidates = mutableListOf<String>()
        val ifaces = NetworkInterface.getNetworkInterfaces()
        while (ifaces.hasMoreElements()) {
            val iface = ifaces.nextElement()
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name ?: ""
            if (name.startsWith("docker") || name.startsWith("veth") || name.startsWith("tun") ||
                name.startsWith("br-") || name.startsWith("virbr")) continue
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("192.168.43.")) return ip
                    candidates.add(ip)
                }
            }
        }
        for (ip in candidates) {
            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip
        }
        return if (candidates.isNotEmpty()) candidates.first() else "未知"
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {}
            }
        }
    }
}
