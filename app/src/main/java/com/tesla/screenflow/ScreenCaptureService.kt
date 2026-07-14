package com.tesla.screenflow

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "TeslaScreenFlow::Capture"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "tesla_screenflow_capture"
        const val CHANNEL_NAME = "TeslaScreenFlow 投屏服务"
        const val ACTION_START = "com.tesla.screenflow.START_CAPTURE"
        const val ACTION_STOP = "com.tesla.screenflow.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        @Volatile private var running = false
        fun isRunning(): Boolean = running
        @Volatile var pendingResultCode: Int = 0
        @Volatile var pendingData: Intent? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var teslaServer: TeslaWebServer? = null
    private var cachedOffer: String? = null
    private var webRTCManager: WebRTCManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        createNotificationChannel()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "teslaScreenFlow:capture")
            wakeLock?.acquire(60 * 60 * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
        when (intent?.action) {
            ACTION_START -> {
                val rc = intent.getIntExtra(EXTRA_RESULT_CODE, pendingResultCode)
                var d: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                if (d == null) d = pendingData
                pendingResultCode = 0
                pendingData = null
                startCaptureInternals(rc, d)
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCaptureInternals(resultCode: Int, data: Intent?) {
        Log.i(TAG, "startInternals: rc=$resultCode data=${data != null}")
        running = true
        try {
            startTeslaServer()
            Log.i(TAG, "TeslaServer started on port 8080")
        } catch (e: Exception) {
            Log.e(TAG, "TeslaServer failed", e)
        }
        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                initWebRTC(data)
                initAudioCapture()
                Log.i(TAG, "Capture+WebRTC OK")
            } catch (e: Exception) {
                Log.e(TAG, "Capture init failed", e)
            }
        }
    }

    fun stopCapture() {
        running = false
        webRTCManager?.dispose()
        webRTCManager = null
        teslaServer?.stop()
        teslaServer = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        mediaProjection?.stop()
        mediaProjection = null
        try {
            wakeLock?.release()
        } catch (e: Exception) {}
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun initAudioCapture() {
        try {
            val mp = webRTCManager?.getMediaProjection()
            if (mp == null) return
            mediaProjection = mp
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val buf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT)
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
                    .setBufferSizeInBytes(if (buf > 0) buf * 2 else 4096)
                    .build()
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.startRecording()
                } else {
                    audioRecord = null
                }
            }
        } catch (e: Exception) {
            audioRecord = null
        }
    }

    private fun startTeslaServer() {
        teslaServer = TeslaWebServer(8080, applicationContext, object : TeslaWebServer.SignalCallback {
            override fun onAnswerReceived(sdp: String) {
                webRTCManager?.processAnswer(sdp)
            }
            override fun onIceCandidateReceived(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                webRTCManager?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
            }
            override fun onTouchEvent(action: String, x: Float, y: Float) {
                TouchSimulator.performTouch(action, x, y)
            }
            override fun onClientConnected() {
                // 客户端连接时重新 Offer（支持断线重连）
                webRTCManager?.restartStreaming()
            }
            override fun onClientDisconnected() {}
            override fun onOfferReady(sdp: String) {
                cachedOffer = sdp
                teslaServer?.sendOffer(sdp)
            }
        })
        teslaServer?.start()
    }

    private fun initWebRTC(mediaProjectionData: Intent) {
        webRTCManager = WebRTCManager()
        webRTCManager!!.init(applicationContext, object : WebRTCManager.StreamCallback {
            override fun onOfferReady(sdp: String) {
                cachedOffer = sdp
                teslaServer?.sendOffer(sdp)
            }
            override fun onIceCandidate(c: org.webrtc.IceCandidate) {
                teslaServer?.sendIce(c.sdpMid, c.sdpMLineIndex, c.sdp)
            }
            override fun onStreamReady() {
                Log.i(TAG, "StreamReady")
            }
            override fun onError(e: String) {
                Log.e(TAG, "RTC error: $e")
            }
        }, mediaProjectionData)
        webRTCManager!!.startStreaming(audioRecord)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            ch.description = "TeslaScreenFlow"
            ch.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("teslaScreenFlow")
                .setContentText("投屏运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
                .setContentTitle("teslaScreenFlow")
                .setContentText("投屏运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
    }
}
