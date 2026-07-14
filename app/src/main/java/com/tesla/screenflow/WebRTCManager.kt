package com.tesla.screenflow

import android.content.Context
import android.content.Intent
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*

/**
 * WebRTC 管理器 — PeerConnection 生命周期管理。
 * 使用 ScreenCapturerAndroid 管理屏幕捕获 + MediaProjection + VirtualDisplay。
 * 手机端作为 Offer 方，创建 VideoTrack (H.264) + AudioTrack (OPUS)。
 */
class WebRTCManager {

    companion object {
        private const val TAG = "TeslaScreenFlow::WebRTC"
        private const val STUN_URL = "stun:stun.l.google.com:19302"
        private const val VIDEO_FPS = 30
        private const val VIDEO_START_BITRATE_KBPS = 4000
    }

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var callback: StreamCallback? = null
    private var videoCapturer: VideoCapturer? = null
    private var mediaProjection: MediaProjection? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    interface StreamCallback {
        fun onOfferReady(sdp: String)
        fun onIceCandidate(candidate: IceCandidate)
        fun onStreamReady()
        fun onError(error: String)
    }

    fun init(context: Context, callback: StreamCallback, mediaProjectionIntent: Intent) {
        this.callback = callback
        Log.i(TAG, "WebRTC 初始化...")

        try {
            eglBase = EglBase.create()

            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            val encoderFactory = DefaultVideoEncoderFactory(
                eglBase!!.eglBaseContext, true, true
            )
            val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()

            // 创建 ScreenCapturerAndroid
            videoCapturer = ScreenCapturerAndroid(mediaProjectionIntent,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.e(TAG, "录屏权限被撤销")
                        callback.onError("录屏权限被撤销")
                    }
                })
            mediaProjection = (videoCapturer as ScreenCapturerAndroid).mediaProjection

            // 创建 SurfaceTextureHelper
            surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread", eglBase!!.eglBaseContext
            )

            // 创建 VideoSource
            videoSource = peerConnectionFactory!!.createVideoSource(videoCapturer!!.isScreencast)

            // 初始化 capturer
            videoCapturer!!.initialize(surfaceTextureHelper, context,
                videoSource!!.capturerObserver)

            // 创建 VideoTrack
            videoTrack = peerConnectionFactory!!.createVideoTrack("videoTrackAA", videoSource!!)

            // 以实际屏幕分辨率开始捕获
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val display = wm.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            display?.getRealMetrics(metrics)

            videoCapturer!!.startCapture(metrics.widthPixels, metrics.heightPixels, VIDEO_FPS)
            mediaProjection = (videoCapturer as ScreenCapturerAndroid).mediaProjection

            Log.i(TAG, "屏幕捕获已启动: ${metrics.widthPixels}x${metrics.heightPixels} @${VIDEO_FPS}fps")
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC 初始化失败", e)
            callback.onError("WebRTC init: ${e.message}")
        }
    }

    fun getMediaProjection(): MediaProjection? = mediaProjection

    fun startStreaming(audioRecord: AudioRecord?) {
        try {
            createPeerConnection()
            addVideoTrack()
            if (audioRecord != null) {
                createAudioTrack(audioRecord)
            }
            createOffer()
            Log.i(TAG, "流已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动流失败", e)
            callback?.onError("启动流: ${e.message}")
        }
    }

    fun restartStreaming() {
        try {
            peerConnection?.close()
            peerConnection = null
            createPeerConnection()
            addVideoTrack()
            if (audioTrack != null) {
                peerConnection?.addTrack(audioTrack)
            }
            createOffer()
            Log.i(TAG, "流已重启")
        } catch (e: Exception) {
            Log.e(TAG, "重启流失败", e)
        }
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder(STUN_URL).createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.PLAN_B
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            keyType = PeerConnection.KeyType.ECDSA
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig,
            object : PeerConnectionObserverAdapter() {
                override fun onIceCandidate(candidate: IceCandidate) {
                    callback?.onIceCandidate(candidate)
                }
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> callback?.onStreamReady()
                        PeerConnection.IceConnectionState.FAILED -> callback?.onError("ICE 连接失败")
                        else -> {}
                    }
                }
            })
    }

    private fun addVideoTrack() {
        val pc = peerConnection ?: return
        val vt = videoTrack ?: return
        val stream = peerConnectionFactory!!.createLocalMediaStream("streamAA")
        stream.addTrack(vt)
        pc.addStream(stream)
    }

    private fun createAudioTrack(audioRecord: AudioRecord) {
        val pc = peerConnection ?: return
        val factory = peerConnectionFactory ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
        }
        audioSource = factory.createAudioSource(constraints)
        audioTrack = factory.createAudioTrack("audioTrack", audioSource)
        pc.addTrack(audioTrack)
    }

    private fun createOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                callback?.onOfferReady(sdp.description)
            }
            override fun onCreateFailure(error: String) {
                callback?.onError("Offer: $error")
            }
        }, constraints)
    }

    fun processAnswer(sdp: String) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), answer)
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun dispose() {
        try { videoCapturer?.stopCapture() } catch (e: Exception) {}
        try { videoCapturer?.dispose() } catch (e: Exception) {}
        videoTrack?.dispose()
        audioTrack?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnection?.close()
        peerConnectionFactory?.dispose()
        eglBase?.release()
        videoCapturer = null; videoTrack = null; audioTrack = null
        videoSource = null; audioSource = null; surfaceTextureHelper = null
        mediaProjection = null
        peerConnection = null; peerConnectionFactory = null; eglBase = null
    }
}

// ── 适配器 ──
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}

open class PeerConnectionObserverAdapter : PeerConnection.Observer {
    override fun onIceCandidate(c: IceCandidate) {}
    override fun onIceCandidatesRemoved(cs: Array<out IceCandidate>) {}
    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(r: Boolean) {}
    override fun onAddStream(s: MediaStream) {}
    override fun onRemoveStream(s: MediaStream) {}
    override fun onDataChannel(dc: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onSignalingChange(s: PeerConnection.SignalingState) {}
    override fun onAddTrack(r: RtpReceiver, ss: Array<out MediaStream>) {}
    override fun onTrack(tr: RtpTransceiver) {}
}
