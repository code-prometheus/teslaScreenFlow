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
import javax.net.ssl.SSLContext

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "TeslaScreenFlow::Capture"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "tesla_screenflow_capture"
        const val CHANNEL_NAME = "TeslaScreenFlow"
        const val ACTION_START = "com.tesla.screenflow.START_CAPTURE"
        const val ACTION_STOP = "com.tesla.screenflow.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val HTTPS_PORT = 443
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var jpegCapture: ScreenJpegCapture? = null
    private var sslContext: SSLContext? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createNotificationChannel()
        try {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "teslaScreenFlow:capture")
            wakeLock?.acquire(60 * 60 * 1000L)
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_START -> {
                val rc = intent.getIntExtra(EXTRA_RESULT_CODE, pendingResultCode)
                var data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                if (data == null) data = pendingData
                pendingResultCode = 0; pendingData = null
                startCaptureInternals(rc, data)
            }
            ACTION_STOP -> { stopCapture(); stopSelf() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { stopCapture(); super.onDestroy() }

    private fun startCaptureInternals(resultCode: Int, data: Intent?) {
        running = true

        // 配置热点 IP + DNS
        try {
            HotspotConfig.configureHotspot(this)
            HotspotConfig.setHotspotDns(this, HotspotConfig.HOTSPOT_IP)
        } catch (e: Exception) {
            Log.e(TAG, "Hotspot config failed", e)
        }

        // 生成 SSL 证书
        sslContext = CertManager.getSslContext(this)

        startTeslaServer()

        if (resultCode != Activity.RESULT_OK || data == null) return

        try {
            val mp = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager)
                .getMediaProjection(resultCode, data)
            mediaProjection = mp

            jpegCapture = ScreenJpegCapture(applicationContext, mp) { jpeg -> teslaServer?.sendJpegFrame(jpeg) }
            jpegCapture?.start()

            initAudioCapture(mp)
            Log.i(TAG, "Capture started on port $HTTPS_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Capture init failed", e)
        }
    }

    fun stopCapture() {
        running = false
        jpegCapture?.stop(); jpegCapture = null
        teslaServer?.stop(); teslaServer = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        mediaProjection?.stop(); mediaProjection = null
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun initAudioCapture(mp: MediaProjection) {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val buf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                audioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT).setSampleRate(AUDIO_SAMPLE_RATE).setChannelMask(AUDIO_CHANNEL_CONFIG).build())
                    .setBufferSizeInBytes(if (buf > 0) buf * 2 else 4096)
                    .build()
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) audioRecord?.startRecording()
                else audioRecord = null
            }
        } catch (_: Exception) { audioRecord = null }
    }

    private fun startTeslaServer() {
        teslaServer = TeslaWebServer(HTTPS_PORT, applicationContext, object : TeslaWebServer.Callback {
            override fun onTouchEvent(action: String, x: Float, y: Float) {
                TouchSimulator.performTouch(action, x, y)
            }
        }, sslContext)
        teslaServer?.start()
        Log.i(TAG, "HTTPS server started on $HTTPS_PORT")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "TeslaScreenFlow"; setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("teslaScreenFlow").setContentText("投屏运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera).setContentIntent(pi).setOngoing(true).build()
        else @Suppress("DEPRECATION") Notification.Builder(this)
            .setContentTitle("teslaScreenFlow").setContentText("投屏运行中")
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentIntent(pi).setOngoing(true).build()
    }
}
