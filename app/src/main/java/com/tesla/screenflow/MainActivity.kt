package com.tesla.screenflow

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
        private const val RED = 0xFFE8212E.toInt()
    }

    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private var isCapturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate - auto-starting capture")

        // 保持屏幕常亮
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 请求忽略电池优化
        requestBatteryOptimizationExemption()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 80, 48, 48)
            setBackgroundColor(0xFF1A1A1A.toInt())
        }

        root.addView(TextView(this).apply {
            text = "teslaScreenFlow"
            textSize = 28f; setTextColor(RED); gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })

        ipText = TextView(this).apply {
            text = "正在启动..."
            textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        root.addView(ipText)

        statusText = TextView(this).apply {
            text = "请求录屏权限中..."
            textSize = 14f; setTextColor(0xFFAAAAAA.toInt()); gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        root.addView(statusText)

        stopButton = Button(this).apply {
            text = "退出投屏"
            setBackgroundColor(0xFF444444.toInt()); setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f; setPadding(48, 16, 48, 16); isEnabled = false
            setOnClickListener { stopCapture() }
        }
        root.addView(stopButton)

        setContentView(root)
        updateIPDisplay()

        // 一键投屏：打开App立刻请求录屏权限
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        Log.i(TAG, "请求录屏权限...")
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "onActivityResult: req=$requestCode res=$resultCode data=${data != null}")

        if (requestCode != REQUEST_CODE || resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "录屏被拒绝")
            statusText.text = "录屏被拒绝，请重新打开App"
            return
        }

        Log.i(TAG, "录屏授权成功，启动Service...")

        // 静态变量传递
        ScreenCaptureService.pendingResultCode = resultCode
        ScreenCaptureService.pendingData = data

        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }

        isCapturing = true
        stopButton.isEnabled = true
        stopButton.setBackgroundColor(RED)
        statusText.text = "投屏已启动"
        updateIPDisplay()
    }

    private fun stopCapture() {
        Log.i(TAG, "停止投屏")
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        stopService(intent)
        isCapturing = false
        stopButton.isEnabled = false
        stopButton.setBackgroundColor(0xFF444444.toInt())
        statusText.text = "已停止"
        ipText.text = "服务器: 未启动"
    }

    private fun updateIPDisplay() {
        try {
            val addr = findLocalIP()
            ipText.text = "车机浏览器打开:\nhttp://$addr:8080"
        } catch (e: Exception) {
            ipText.text = "请连接Wi-Fi或开启热点"
        }
    }

    /**
     * 查找本机局域网 IP。
     * 优先热点地址 (192.168.43.x)，其次 WiFi (192.168.x.x / 10.x.x.x / 172.16-31.x.x)。
     * 排除回环、虚拟网卡。
     */
    private fun findLocalIP(): String {
        val candidates = mutableListOf<String>()
        val ifaces = NetworkInterface.getNetworkInterfaces()
        while (ifaces.hasMoreElements()) {
            val iface = ifaces.nextElement()
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name ?: ""
            if (name.startsWith("docker") || name.startsWith("veth") ||
                name.startsWith("tun") || name.startsWith("br-") ||
                name.startsWith("virbr")) continue
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
            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                return ip
            }
        }
        return if (candidates.isNotEmpty()) candidates.first() else "未知"
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Battery optimization request failed", e)
                }
            }
        }
    }
}
