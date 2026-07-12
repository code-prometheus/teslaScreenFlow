package com.tesla.screenflow

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import org.webrtc.*

/**
 * WebRTC 管理器 — PeerConnection 生命周期管理。
 * 手机端作为 Offer 方，创建 VideoTrack (H.264) + AudioTrack (OPUS)。
 */
class WebRTCManager {

    companion object {
        private const val TAG = "TeslaScreenFlow::WebRTC"
        private const val STUN_URL = "stun:stun.l.google.com:19302"
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val VIDEO_FPS = 60
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

    interface StreamCallback {
        fun onOfferReady(sdp: String)
        fun onIceCandidate(candidate: IceCandidate)
        fun onStreamReady()
        fun onError(error: String)
    }

    fun init(context: Context, callback: StreamCallback) {
        this.callback = callback
        Log.i(TAG, "初始化 WebRTC...")

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

        Log.i(TAG, "PeerConnectionFactory 已初始化")
    }

    fun startStreaming(audioRecord: AudioRecord?) {
        try {
            createPeerConnection()
            createVideoTrack()
            if (audioRecord != null) {
                createAudioTrack(audioRecord)
            }
            createOffer()
            Log.i(TAG, "WebRTC 流已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动流失败", e)
            callback?.onError("启动流失败: ${e.message}")
        }
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder(STUN_URL).createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig,
            object : PeerConnectionObserverAdapter() {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "ICE candidate: ${candidate.sdp}")
                    callback?.onIceCandidate(candidate)
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE state: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> callback?.onStreamReady()
                        PeerConnection.IceConnectionState.FAILED -> callback?.onError("ICE 连接失败")
                        else -> {}
                    }
                }
            })

        Log.i(TAG, "PeerConnection 已创建")
    }

    private fun createVideoTrack() {
        val pc = peerConnection ?: return
        val factory = peerConnectionFactory ?: return

        videoSource = factory.createVideoSource(false)
        val surfaceTextureHelper = SurfaceTextureHelper.create(
            "CaptureThread", eglBase!!.eglBaseContext
        )
        videoSource!!.setSurfaceTextureHelper(surfaceTextureHelper)

        videoTrack = factory.createVideoTrack("videoTrack", videoSource)

        val transceiver = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
        transceiver.sender.track = videoTrack

        val params = transceiver.sender.parameters
        if (params != null) {
            for (enc in params.encodings) {
                enc.maxBitrateBps = VIDEO_START_BITRATE_KBPS * 1000
                enc.maxFramerate = VIDEO_FPS
                enc.scaleResolutionDownBy = 1.0
            }
            transceiver.sender.parameters = params
        }

        Log.i(TAG, "VideoTrack 创建: ${VIDEO_WIDTH}x${VIDEO_HEIGHT}@${VIDEO_FPS}fps ${VIDEO_START_BITRATE_KBPS}kbps")
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

        Log.i(TAG, "AudioTrack 创建 (OPUS)")
    }

    private fun createOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                Log.i(TAG, "Offer 创建成功")
                callback?.onOfferReady(sdp.description)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Offer 创建失败: $error")
                callback?.onError("Offer 创建失败: $error")
            }
        }, constraints)
    }

    fun processAnswer(sdp: String) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), answer)
        Log.i(TAG, "Answer 已设置")
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        Log.d(TAG, "ICE candidate 已添加")
    }

    fun dispose() {
        Log.i(TAG, "释放 WebRTC 资源...")
        videoTrack?.dispose()
        audioTrack?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        peerConnectionFactory?.dispose()
        eglBase?.release()
        videoTrack = null; audioTrack = null
        videoSource = null; audioSource = null
        peerConnection = null; peerConnectionFactory = null; eglBase = null
        Log.i(TAG, "WebRTC 资源已释放")
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
