package com.example.xmppaudiocall

import android.content.Context
import org.webrtc.*

class WebRTCClient(
    private val context: Context,
    private val listener: WebRTCListener
) {

    interface WebRTCListener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onConnectionStateChange(state: PeerConnection.PeerConnectionState)
        fun onAddRemoteStream(stream: MediaStream)
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var isVideoCall = false
    private val rootEglBase: EglBase = EglBase.create()
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    fun getConnectionState(): PeerConnection.PeerConnectionState? {
        return peerConnection?.connectionState()
    }

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    init {
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(null, false, false)
        val decoderFactory = DefaultVideoDecoderFactory(null)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(enableVideo: Boolean = false) {
        isVideoCall = enableVideo
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { listener.onIceCandidate(it) }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    newState?.let { listener.onConnectionStateChange(it) }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(stream: MediaStream?) {
                    stream?.let {
                        it.audioTracks?.firstOrNull()?.setEnabled(true)
                        it.videoTracks?.firstOrNull()?.setEnabled(true)
                        listener.onAddRemoteStream(it)  // Add this
                    }
                }




                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate?>?) {
                    TODO("Not yet implemented")
                }
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dataChannel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )

        createAudioTrack()
        if (enableVideo) {
            createVideoTrack()
        }
    }

    private fun createVideoTrack() {
        videoCapturer = createCameraCapturer()

        surfaceTextureHelper = SurfaceTextureHelper.create(
            "CaptureThread",
            rootEglBase.eglBaseContext
        )

        videoSource = peerConnectionFactory?.createVideoSource(videoCapturer?.isScreencast == true)
        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer?.startCapture(640, 480, 30)

        videoTrack = peerConnectionFactory?.createVideoTrack("video_track", videoSource)

        val streamId = "local_stream"
        peerConnection?.addTrack(videoTrack, listOf(streamId))
    }

    // Add this new method
    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)

        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }

        for (deviceName in enumerator.deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }

        return null
    }

    // Add this new method
    fun initLocalVideoView(view: SurfaceViewRenderer) {
        view.init(rootEglBase.eglBaseContext, null)
        view.setMirror(true)
        view.setEnableHardwareScaler(true)
        videoTrack?.addSink(view)
    }

    // Add this new method
    fun initRemoteVideoView(view: SurfaceViewRenderer) {
        view.init(rootEglBase.eglBaseContext, null)
        view.setEnableHardwareScaler(true)
    }



    private fun createAudioTrack() {
        audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
        audioTrack = peerConnectionFactory?.createAudioTrack("audio_track", audioSource)

        val streamId = "local_stream"
        peerConnection?.addTrack(audioTrack, listOf(streamId))
    }

    fun createOffer(onSuccess: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        onSuccess(sdp?.description ?: "")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetFailure(error: String?) {
                        println("Set local description error: $error")
                    }
                    override fun onCreateFailure(error: String?) {}
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                println("Create offer error: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun createAnswer(onSuccess: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        onSuccess(sdp?.description ?: "")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetFailure(error: String?) {
                        println("Set local description error: $error")
                    }
                    override fun onCreateFailure(error: String?) {}
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                println("Create answer error: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(sdp: String, type: SessionDescription.Type) {
        val sessionDescription = SessionDescription(type, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                println("Remote description set successfully")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetFailure(error: String?) {
                println("Set remote description error: $error")
            }
            override fun onCreateFailure(error: String?) {}
        }, sessionDescription)
    }

    fun addIceCandidate(candidateString: String) {
        try {
            val parts = candidateString.split("|")
            if (parts.size >= 3) {
                val sdpMid = parts[0]
                val sdpMLineIndex = parts[1].toInt()
                val sdp = parts[2]

                val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                peerConnection?.addIceCandidate(candidate)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            videoTrack?.let {
                if (it.state() != MediaStreamTrack.State.ENDED) {
                    it.setEnabled(false)
                    it.dispose()
                }
            }
            videoTrack = null

            videoSource?.dispose()
            videoSource = null

            surfaceTextureHelper?.dispose()  // Add this line
            surfaceTextureHelper = null

            audioTrack?.let {
                if (it.state() != MediaStreamTrack.State.ENDED) {
                    it.setEnabled(false)
                    it.dispose()
                }
            }
            audioTrack = null

            audioSource?.dispose()
            audioSource = null

            peerConnection?.close()
            peerConnection = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // UPDATE dispose() to release EglBase:
    fun dispose() {
        close()
        try {
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            rootEglBase.release()  // Add this line
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}