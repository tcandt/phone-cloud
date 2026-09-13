package com.cloudphone.app.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class WebRTCManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val listener: WebRTCListener
) {

    companion object {
        private const val TAG = "WebRTCManager"
        private const val CONNECTION_TIMEOUT_MS = 12000L
    }

    interface WebRTCListener {
        fun onStateChanged(newState: WebRTCConnectionState)
        fun onSendSignaling(msg: JsonObject)
        fun onClipboardReceived(text: String)
        fun onCameraResponse(response: JsonObject)
        fun onVideoTrackReady(track: VideoTrack)
        fun onAdbOutput(data: ByteArray) {}
        fun onFileMessage(message: String) {}
        fun onAiCommandResponse(response: JsonObject) {}
        fun onCameraStreamStarted() {}
        fun onCameraStreamStopped() {}
    }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var surfaceRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

    private val dataChannels = ConcurrentHashMap<String, DataChannel>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null

    var currentState: WebRTCConnectionState = WebRTCConnectionState.DISCONNECTED
        private set

    private var targetDeviceId: String = ""
    private var currentIceServers: List<PeerConnection.IceServer>? = null

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        try {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            Log.i(TAG, "PeerConnectionFactory initialized successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize PeerConnectionFactory: ${e.message}", e)
        }
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        this.surfaceRenderer = renderer
        remoteVideoTrack?.addSink(renderer)
    }

    fun setIceServers(servers: List<PeerConnection.IceServer>) {
        this.currentIceServers = servers
        Log.i(TAG, "Configured ${servers.size} ICE/TURN servers for WebRTC")
        peerConnection?.let { pc ->
            try {
                val rtcConfig = PeerConnection.RTCConfiguration(servers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                }
                pc.setConfiguration(rtcConfig)
                Log.i(TAG, "Successfully updated RTCConfiguration on active PeerConnection")
            } catch (e: Throwable) {
                Log.w(TAG, "Could not dynamically update RTCConfiguration: ${e.message}")
            }
        }
    }

    fun startConnection(deviceId: String, iceServersList: List<PeerConnection.IceServer>? = null) {
        this.targetDeviceId = deviceId
        setState(WebRTCConnectionState.SIGNALING)

        val servers = iceServersList ?: currentIceServers
        val iceServers = servers?.toMutableList() ?: mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, peerObserver)

        // Client-initiated DataChannels per Original specification (file, adb, ai)
        // Agent initiates input-channel, clipboard-channel, camera-channel received in onDataChannel
        createDataChannelInternal("file-channel", ordered = true)
        createDataChannelInternal("adb-channel", ordered = true)
        createDataChannelInternal("ai-command-channel", ordered = true)

        // Arm connection timeout -> fallback to WS
        startConnectionTimeout()

        // Send request-offer to Agent via signaling
        setState(WebRTCConnectionState.NEGOTIATING)
        val requestOfferPayload = JsonObject().apply {
            addProperty("type", "request-offer")
            addProperty("request", "request-offer")
        }
        val forwardMsg = JsonObject().apply {
            addProperty("message_type", "forward")
            addProperty("device_id", deviceId)
            add("payload", requestOfferPayload)
        }
        listener.onSendSignaling(forwardMsg)
        Log.i(TAG, "WebRTC connection initiated for device: $deviceId with ${iceServers.size} ICE servers")
    }

    private fun startConnectionTimeout() {
        cancelConnectionTimeout()
        connectionTimeoutRunnable = Runnable {
            if (currentState != WebRTCConnectionState.CONNECTED) {
                Log.w(TAG, "WebRTC connection timed out after ${CONNECTION_TIMEOUT_MS}ms. Triggering FALLBACK_WS.")
                setState(WebRTCConnectionState.FALLBACK_WS)
            }
        }
        mainHandler.postDelayed(connectionTimeoutRunnable!!, CONNECTION_TIMEOUT_MS)
    }

    private fun cancelConnectionTimeout() {
        connectionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectionTimeoutRunnable = null
    }

    private fun createDataChannelInternal(label: String, ordered: Boolean = true): DataChannel? {
        val init = DataChannel.Init().apply {
            this.ordered = ordered
        }
        val dc = peerConnection?.createDataChannel(label, init)
        if (dc != null) {
            setupDataChannel(label, dc)
        }
        return dc
    }

    private fun setupDataChannel(label: String, dc: DataChannel) {
        dataChannels[label] = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.i(TAG, "DataChannel [$label] state changed: ${dc.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                handleDataChannelMessage(label, buffer)
            }
        })
    }

    private fun handleDataChannelMessage(label: String, buffer: DataChannel.Buffer) {
        val bytes = ByteArray(buffer.data.remaining())
        buffer.data.get(bytes)

        when (label) {
            "clipboard-channel" -> {
                val str = String(bytes, StandardCharsets.UTF_8)
                try {
                    val json = Gson().fromJson(str, JsonObject::class.java)
                    val text = json.get("text")?.asString ?: ""
                    if (text.isNotEmpty()) {
                        mainHandler.post { listener.onClipboardReceived(text) }
                    }
                } catch (e: Throwable) {
                    mainHandler.post { listener.onClipboardReceived(str) }
                }
            }
            "camera-channel" -> {
                if (buffer.binary) {
                    Log.d(TAG, "Received binary camera data: ${bytes.size} bytes")
                } else {
                    val str = String(bytes, StandardCharsets.UTF_8)
                    try {
                        val json = Gson().fromJson(str, JsonObject::class.java)
                        val action = json.get("action")?.asString
                        if (action == "start") {
                            mainHandler.post { listener.onCameraStreamStarted() }
                        } else if (action == "stop") {
                            mainHandler.post { listener.onCameraStreamStopped() }
                        }
                        mainHandler.post { listener.onCameraResponse(json) }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to parse camera channel response: $str")
                    }
                }
            }
            "adb-channel" -> {
                mainHandler.post { listener.onAdbOutput(bytes) }
            }
            "file-channel" -> {
                val str = String(bytes, StandardCharsets.UTF_8)
                mainHandler.post { listener.onFileMessage(str) }
            }
            "ai-command-channel" -> {
                val str = String(bytes, StandardCharsets.UTF_8)
                try {
                    val json = Gson().fromJson(str, JsonObject::class.java)
                    mainHandler.post { listener.onAiCommandResponse(json) }
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to parse AI command response: $str")
                }
            }
        }
    }

    fun handleRemoteOffer(sdpString: String) {
        Log.i(TAG, "Handling remote SDP offer from agent")
        setState(WebRTCConnectionState.NEGOTIATING)

        val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.i(TAG, "Remote description set successfully. Creating answer...")
                createAnswer()
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
                setState(WebRTCConnectionState.FALLBACK_WS)
            }
        }, sdp)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                Log.i(TAG, "Answer created successfully. Setting local description...")
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.i(TAG, "Local description set. Sending answer to agent via signaling.")
                        sendAnswerToSignaling(desc.description)
                        setState(WebRTCConnectionState.ICE_CONNECTING)
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Failed to set local description: $error")
                        setState(WebRTCConnectionState.FALLBACK_WS)
                    }
                }, desc)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create answer: $error")
                setState(WebRTCConnectionState.FALLBACK_WS)
            }
        }, constraints)
    }

    private fun sendAnswerToSignaling(sdp: String) {
        val answerPayload = JsonObject().apply {
            addProperty("type", "answer")
            addProperty("sdp", sdp)
        }
        val forwardMsg = JsonObject().apply {
            addProperty("message_type", "forward")
            addProperty("device_id", targetDeviceId)
            add("payload", answerPayload)
        }
        listener.onSendSignaling(forwardMsg)
    }

    fun handleRemoteCandidate(candidateObj: JsonObject) {
        try {
            val sdp = candidateObj.get("candidate")?.asString ?: ""
            val sdpMid = candidateObj.get("sdpMid")?.asString ?: "0"
            val sdpMLineIndex = candidateObj.get("sdpMLineIndex")?.asInt ?: 0

            if (sdp.isNotEmpty()) {
                val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                peerConnection?.addIceCandidate(iceCandidate)
                Log.d(TAG, "Added remote ICE candidate: $sdpMid index $sdpMLineIndex")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error adding remote ICE candidate: ${e.message}")
        }
    }

    fun restartIce() {
        setState(WebRTCConnectionState.RECONNECTING)
        peerConnection?.restartIce()
        val requestOfferPayload = JsonObject().apply {
            addProperty("type", "request-offer")
            addProperty("request", "request-offer")
        }
        val forwardMsg = JsonObject().apply {
            addProperty("message_type", "forward")
            addProperty("device_id", targetDeviceId)
            add("payload", requestOfferPayload)
        }
        listener.onSendSignaling(forwardMsg)
    }

    // Input & Control via WebRTC DataChannel
    fun sendTouch(action: Int, x: Int, y: Int, w: Int, h: Int, pointerId: Long = 0): Boolean {
        val dc = dataChannels["input-channel"] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false

        val msg = JsonObject().apply {
            addProperty("type", "touch")
            addProperty("action", action)
            addProperty("x", x)
            addProperty("y", y)
            addProperty("w", w)
            addProperty("h", h)
            addProperty("id", pointerId)
        }
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(msg.toString().toByteArray(StandardCharsets.UTF_8)), false)
        return dc.send(buffer)
    }

    fun sendKeycode(keycode: Int, action: Int = 0): Boolean {
        val dc = dataChannels["input-channel"] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false

        val msg = JsonObject().apply {
            addProperty("type", "inject_keycode")
            addProperty("action", action)
            addProperty("keycode", keycode)
            addProperty("repeat", 0)
            addProperty("meta", 0)
        }
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(msg.toString().toByteArray(StandardCharsets.UTF_8)), false)
        return dc.send(buffer)
    }

    fun sendText(text: String): Boolean {
        val dc = dataChannels["input-channel"] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false

        val msg = JsonObject().apply {
            addProperty("type", "inject_text")
            addProperty("text", text)
        }
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(msg.toString().toByteArray(StandardCharsets.UTF_8)), false)
        return dc.send(buffer)
    }

    fun sendScroll(x: Int, y: Int, w: Int, h: Int, scrollH: Int, scrollV: Int): Boolean {
        val dc = dataChannels["input-channel"] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false

        val msg = JsonObject().apply {
            addProperty("type", "inject_scroll")
            addProperty("x", x)
            addProperty("y", y)
            addProperty("w", w)
            addProperty("h", h)
            addProperty("scroll_h", scrollH)
            addProperty("scroll_v", scrollV)
        }
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(msg.toString().toByteArray(StandardCharsets.UTF_8)), false)
        return dc.send(buffer)
    }

    fun sendClipboard(text: String, paste: Boolean = true): Boolean {
        val dc = dataChannels["clipboard-channel"] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false

        val msg = JsonObject().apply {
            addProperty("type", "set_clipboard")
            addProperty("text", text)
            addProperty("paste", paste)
        }
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(msg.toString().toByteArray(StandardCharsets.UTF_8)), false)
        return dc.send(buffer)
    }

    fun sendCameraCommand(action: String, requestId: String = "", params: JsonObject? = null): Boolean {
        val dc = dataChannels["camera-channel"] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false

        val msg = JsonObject().apply {
            addProperty("action", action)
            addProperty("request_id", if (requestId.isNotEmpty()) requestId else "cam_${System.currentTimeMillis()}")
            if (params != null) {
                add("params", params)
            }
        }
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(msg.toString().toByteArray(StandardCharsets.UTF_8)), false)
        return dc.send(buffer)
    }

    fun sendCameraFrame(jpegBytes: ByteArray): Boolean {
        val dc = dataChannels["camera-channel"] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(jpegBytes), true)
        return dc.send(buffer)
    }

    fun sendAdbData(bytes: ByteArray): Boolean {
        val dc = dataChannels["adb-channel"] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), true)
        return dc.send(buffer)
    }

    fun sendAdbCommand(command: String): Boolean {
        return sendAdbData((command + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    fun sendFileChunk(chunk: ByteArray): Boolean {
        val dc = dataChannels["file-channel"] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        if (dc.bufferedAmount() > 1024 * 1024) return false
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(chunk), true)
        return dc.send(buffer)
    }

    fun sendFileMessage(msg: JsonObject): Boolean {
        val dc = dataChannels["file-channel"] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(msg.toString().toByteArray(StandardCharsets.UTF_8)), false)
        return dc.send(buffer)
    }

    fun sendAiCommand(command: JsonObject): Boolean {
        val dc = dataChannels["ai-command-channel"] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(command.toString().toByteArray(StandardCharsets.UTF_8)), false)
        return dc.send(buffer)
    }

    private fun setState(state: WebRTCConnectionState) {
        if (currentState == state) return
        Log.i(TAG, "State transition: $currentState -> $state")
        currentState = state
        if (state == WebRTCConnectionState.CONNECTED) {
            cancelConnectionTimeout()
        }
        mainHandler.post { listener.onStateChanged(state) }
    }

    private val peerObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "PeerConnection SignalingState: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.i(TAG, "PeerConnection IceConnectionState: $state")
            when (state) {
                PeerConnection.IceConnectionState.CHECKING -> {
                    setState(WebRTCConnectionState.ICE_CONNECTING)
                }
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    setState(WebRTCConnectionState.CONNECTED)
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    Log.w(TAG, "ICE connection failed, triggering fallback to WebSocket")
                    setState(WebRTCConnectionState.FALLBACK_WS)
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    Log.w(TAG, "ICE connection disconnected, attempting restart")
                    restartIce()
                }
                PeerConnection.IceConnectionState.CLOSED -> {
                    setState(WebRTCConnectionState.DISCONNECTED)
                }
                else -> {}
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "PeerConnection IceGatheringState: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            // Trickle ICE candidate immediately dispatched
            val candObj = JsonObject().apply {
                addProperty("candidate", candidate.sdp)
                addProperty("sdpMid", candidate.sdpMid)
                addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            val payload = JsonObject().apply {
                addProperty("type", "ice-candidate")
                add("candidate", candObj)
            }
            val forwardMsg = JsonObject().apply {
                addProperty("message_type", "forward")
                addProperty("device_id", targetDeviceId)
                add("payload", payload)
            }
            listener.onSendSignaling(forwardMsg)
            Log.d(TAG, "Trickled local ICE candidate to signaling: ${candidate.sdpMid}")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}

        override fun onDataChannel(dataChannel: DataChannel?) {
            if (dataChannel == null) return
            val label = dataChannel.label()
            Log.i(TAG, "Remote DataChannel received: $label")
            setupDataChannel(label, dataChannel)
        }

        override fun onRenegotiationNeeded() {
            Log.d(TAG, "PeerConnection renegotiation needed")
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            val track = receiver?.track()
            if (track is VideoTrack) {
                Log.i(TAG, "Remote VideoTrack received: ${track.id()}")
                remoteVideoTrack = track
                surfaceRenderer?.let { track.addSink(it) }
                mainHandler.post { listener.onVideoTrackReady(track) }
            } else if (track is AudioTrack) {
                Log.i(TAG, "Remote AudioTrack received: ${track.id()}")
                remoteAudioTrack = track
                track.setEnabled(true)
            }
        }
    }

    fun close() {
        cancelConnectionTimeout()
        dataChannels.values.forEach {
            try { it.close() } catch (e: Throwable) {}
        }
        dataChannels.clear()

        remoteVideoTrack?.let {
            surfaceRenderer?.let { renderer -> it.removeSink(renderer) }
        }
        remoteVideoTrack = null
        remoteAudioTrack = null

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        setState(WebRTCConnectionState.DISCONNECTED)
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
