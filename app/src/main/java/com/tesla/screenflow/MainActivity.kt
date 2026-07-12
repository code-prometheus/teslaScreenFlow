package com.tesla.screenflow

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 主 Activity — 简单的控制界面，启动/停止投屏服务。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TeslaScreenFlow::MainActivity"
        private const val REQUEST_CODE = 100
        private const val TESLA_RED = 0xFFE8212E.toInt()
    }

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var clientCountText: TextView
    private var isCapturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")

        // 动态构建 UI
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 80, 48, 48)
            setBackgroundColor(0xFF1A1A1A.toInt())
        }

        // 标题
        rootLayout.addView(TextView(this).apply {
            text = "teslaScreenFlow"
            textSize = 28f
            setTextColor(TESLA_RED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        // IP 显示
        ipText = TextView(this).apply {
            text = "服务器: 未启动"
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(ipText)

        // 状态显示
        statusText = TextView(this).apply {
            text = "未连接"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(statusText)

        // 客户端数
        clientCountText = TextView(this).apply {
            text = "已连接设备: 0"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        rootLayout.addView(clientCountText)

        // 开始按钮
        startButton = Button(this).apply {
            text = getString(R.string.start_capture)
            setBackgroundColor(TESLA_RED)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setPadding(48, 16, 48, 16)
            setOnClickListener { startCapture() }
        }
        rootLayout.addView(startButton)

        // 停止按钮
        stopButton = Button(this).apply {
            text = getString(R.string.stop_capture)
            setBackgroundColor(0xFF444444.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setPadding(48, 16, 48, 16)
            isEnabled = false
            setOnClickListener { stopCapture() }
        }
        rootLayout.addView(stopButton)

        setContentView(rootLayout)

        // 注册广播接收器（监听服务端连接状态）
        updateIPDisplay()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun startCapture() {
        Log.i(TAG, "请求录屏权限...")

        // 检查无障碍服务
        if (!TouchSimulator.isEnabled()) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CODE || resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "录屏授权被拒绝或取消")
            statusText.text = "录屏授权被拒绝"
            return
        }

        Log.i(TAG, "录屏授权成功，启动前台服务...")

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }

        isCapturing = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusText.text = "投屏已启动"
        updateIPDisplay()
    }

    private fun stopCapture() {
        Log.i(TAG, "停止投屏")

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        stopService(serviceIntent)

        isCapturing = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusText.text = "已停止"
        clientCountText.text = "已连接设备: 0"
        ipText.text = "服务器: 未启动"
    }

    private fun updateIPDisplay() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            val ip = if (ipInt != 0) {
                "${ipInt and 0xFF}.${(ipInt shr 8) and 0xFF}.${(ipInt shr 16) and 0xFF}.${(ipInt shr 24) and 0xFF}"
            } else {
                "未知"
            }
            ipText.text = "服务器: http://$ip:8080 | ws://$ip:8081"
        } catch (e: Exception) {
            ipText.text = "服务器: 请连接Wi-Fi"
        }
    }
}
