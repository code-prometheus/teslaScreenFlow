package com.tesla.screenflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log

/**
 * 前台服务 — 管理 MediaProjection 屏幕捕获、音频捕获、Web 服务器和 WebSocket 信令。
 *
 * ## 生命周期:
 * 1. Activity 调用 [startForegroundService] 启动本 Service
 * 2. 用户授权 MediaProjection 后，Activity 调用 [setMediaProjectionResult] 传回结果
 * 3. Service 初始化 WebRTC，启动 Web 服务器和信令服务
 * 4. 停止时清理所有资源
 */
class ScreenCaptureService : Service() {

    // ── 静态状态 ──
    companion object {
        private const val TAG = "TeslaScreenFlow::Capture"

        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "tesla_screenflow_capture"
        const val CHANNEL_NAME = "TeslaScreenFlow 投屏服务"

        // 前台服务类型（Android 14+ 需要显式声明）
        const val ACTION_START = "com.tesla.screenflow.START_CAPTURE"
        const val ACTION_STOP = "com.tesla.screenflow.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        // VirtualDisplay 参数
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val VIDEO_DPI = 280

        // 音频采样参数
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        @Volatile
        private var running = false

        /** 检查服务是否正在运行 */
        fun isRunning(): Boolean = running
    }

    // ── 核心组件 ──
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var audioRecord: AudioRecord? = null
    private var webServer: WebServer? = null
    private var signalingServer: SignalingServer? = null
    private var webRTCManager: WebRTCManager? = null
    private var pendingResultCode: Int = 0
    private var pendingData: Intent? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (resultCode != -1 && data != null) {
                    startCapture(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy")
        stopCapture()
        super.onDestroy()
    }

    // ── 公开 API ──

    /**
     * 由 MainActivity 在用户授权 MediaProjection 后调用。
     * 启动前台通知、初始化所有组件。
     */
    fun startCapture(resultCode: Int, data: Intent) {
        Log.i(TAG, "开始捕获: resultCode=$resultCode")
        running = true

        // 启动前台通知
        startForeground(NOTIFICATION_ID, buildNotification())

        // 获取 MediaProjection
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection 被系统停止")
                stopCapture()
            }
        }, null)

        // 创建虚拟显示器
        createVirtualDisplay()

        // 初始化音频捕获
        initAudioCapture()

        // 启动 Web 服务器
        startWebServer()

        // 启动信令服务器
        startSignalingServer()

        // 初始化并启动 WebRTC
        initWebRTC()

        Log.i(TAG, "所有组件已启动")
    }

    fun stopCapture() {
        Log.i(TAG, "停止捕获")
        running = false

        webRTCManager?.dispose()
        webRTCManager = null

        signalingServer?.stopSafely()
        signalingServer = null

        webServer?.stop()
        webServer = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "所有资源已释放")
    }

    // ── 内部实现 ──

    private fun createVirtualDisplay() {
        val metrics = DisplayMetrics()
        val flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "TeslaScreenFlow",
            VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_DPI,
            flags,
            null,  // surface: null = offscreen
            null,  // callback
            null   // handler
        )
        Log.i(TAG, "VirtualDisplay 创建: ${VIDEO_WIDTH}x${VIDEO_HEIGHT} @ ${VIDEO_DPI}dpi")
    }

    private fun initAudioCapture() {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AUDIO_CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .build()

            audioRecord?.startRecording()
            Log.i(TAG, "AudioRecord 启动: ${AUDIO_SAMPLE_RATE}Hz, buffer=$bufferSize")
        } else {
            Log.w(TAG, "AudioPlaybackCapture 需要 Android 10+")
        }
    }

    private fun startWebServer() {
        webServer = WebServer("0.0.0.0", 8080) { path ->
            try {
                assets.open(path).bufferedReader().readText()
            } catch (e: Exception) {
                null
            }
        }
        webServer?.start()
        Log.i(TAG, "WebServer 启动: http://0.0.0.0:8080")
    }

    private fun startSignalingServer() {
        signalingServer = SignalingServer(8081, object : SignalingServer.SignalCallback {
            override fun onAnswerReceived(clientId: String, sdp: String) {
                webRTCManager?.processAnswer(sdp)
            }

            override fun onIceCandidateReceived(clientId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                webRTCManager?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
            }

            override fun onTouchEvent(clientId: String, action: String, x: Float, y: Float) {
                TouchSimulator.performTouch(action, x, y)
            }

            override fun onClientConnected(clientId: String) {
                Log.i(TAG, "客户端连接: $clientId")
            }

            override fun onClientDisconnected(clientId: String) {
                Log.i(TAG, "客户端断开: $clientId")
            }

            override fun onError(error: String) {
                Log.e(TAG, "信令错误: $error")
            }
        })
        signalingServer?.start()
        Log.i(TAG, "SignalingServer 启动: ws://0.0.0.0:8081")
    }

    private fun initWebRTC() {
        webRTCManager = WebRTCManager()
        webRTCManager!!.init(applicationContext, object : WebRTCManager.StreamCallback {
            override fun onOfferReady(sdp: String) {
                Log.i(TAG, "Offer 就绪，广播到所有客户端")
                signalingServer?.broadcastOffer(sdp)
            }

            override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                signalingServer?.sendIceCandidate(
                    clientId = "broadcast",
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    candidate = candidate.sdp
                )
            }

            override fun onStreamReady() {
                Log.i(TAG, "WebRTC 流已连接")
            }

            override fun onError(error: String) {
                Log.e(TAG, "WebRTC 错误: $error")
            }
        })
        webRTCManager!!.startStreaming(audioRecord)
    }

    // ── 通知 ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "TeslaScreenFlow 屏幕投射服务通知"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}
