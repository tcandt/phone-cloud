package com.cloudphone.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cloudphone.app.audio.OpusAudioDecoder
import com.cloudphone.app.databinding.ActivityControllerBinding
import com.cloudphone.app.service.CloudPhoneHostService
import com.cloudphone.app.webrtc.WebRTCConnectionState
import com.cloudphone.app.webrtc.WebRTCManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okio.ByteString
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

class ControllerActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "ControllerActivity"
        private const val PREV_HEADER_LEN = 49
    }

    private lateinit var binding: ActivityControllerBinding
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    // Fallback TextureView & MediaCodec for WebSocket PREV
    private var fallbackDecoder: MediaCodec? = null
    private var fallbackSurface: Surface? = null
    private var isFallbackDecoderConfigured = false

    // Audio Engine for WebSocket AUDO fallback
    private var audioTrack: AudioTrack? = null
    private lateinit var opusAudioDecoder: OpusAudioDecoder

    // WebRTC Engine
    private var rootEglBase: EglBase? = null
    private var webRTCManager: WebRTCManager? = null

    private var targetDeviceId: String = ""
    private var serverUrl: String = ""
    private var token: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControllerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverUrl = intent.getStringExtra(CloudPhoneHostService.EXTRA_SERVER_URL) ?: "ws://192.168.1.100:8000"
        token = intent.getStringExtra(CloudPhoneHostService.EXTRA_TOKEN) ?: ""
        targetDeviceId = intent.getStringExtra(CloudPhoneHostService.EXTRA_DEVICE_ID) ?: ""

        setupToolbar()
        setupBottomNav()
        setupQuickActions()
        setupTouchControl()
        initAudioEngine()
        initWebRTC()

        binding.remoteVideoView.surfaceTextureListener = this
        connectWebSocket()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
        binding.tvTargetDevice.text = if (targetDeviceId.isNotEmpty()) "Target: $targetDeviceId" else "Live Controller"
    }

    private fun setupBottomNav() {
        binding.btnNavBack.setOnClickListener { sendKeycode(4) }
        binding.btnNavHome.setOnClickListener { sendKeycode(3) }
        binding.btnNavRecents.setOnClickListener { sendKeycode(187) }
    }

    private fun setupQuickActions() {
        binding.btnText.setOnClickListener { showTextInputDialog() }
        binding.btnClipboard.setOnClickListener { showClipboardSyncDialog() }
        binding.btnCamera.setOnClickListener { showCameraControlDialog() }
        binding.btnVolUp.setOnClickListener { sendKeycode(24) }
        binding.btnVolDown.setOnClickListener { sendKeycode(25) }
        binding.btnPower.setOnClickListener { sendKeycode(26) }
    }

    private fun initAudioEngine() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
                .setBufferSizeInBytes(Math.max(minBuf * 2, 4096))
                .build()
            audioTrack?.play()
            opusAudioDecoder = OpusAudioDecoder(48000, 2)
            Log.i(TAG, "AudioTrack and OpusAudioDecoder initialized")
        } catch (e: Throwable) {
            Log.w(TAG, "AudioTrack init deferred: ${e.message}")
        }
    }

    private fun initWebRTC() {
        try {
            rootEglBase = EglBase.create()
            binding.webrtcVideoView.init(rootEglBase?.eglBaseContext, null)
            binding.webrtcVideoView.setEnableHardwareScaler(true)
            binding.webrtcVideoView.setZOrderMediaOverlay(true)

            webRTCManager = WebRTCManager(this, rootEglBase!!, object : WebRTCManager.WebRTCListener {
                override fun onStateChanged(newState: WebRTCConnectionState) {
                    runOnUiThread { handleConnectionStateChanged(newState) }
                }

                override fun onSendSignaling(msg: JsonObject) {
                    webSocket?.send(msg.toString())
                }

                override fun onClipboardReceived(text: String) {
                    runOnUiThread {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Remote Clipboard", text))
                        Toast.makeText(this@ControllerActivity, "Remote clipboard synced", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onCameraResponse(response: JsonObject) {
                    runOnUiThread {
                        val action = response.get("action")?.asString ?: "camera"
                        val status = response.get("status")?.asString ?: "ok"
                        Toast.makeText(this@ControllerActivity, "Camera $action: $status", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onVideoTrackReady(track: VideoTrack) {
                    runOnUiThread {
                        binding.connectingOverlay.visibility = View.GONE
                    }
                }
            })
            webRTCManager?.attachRenderer(binding.webrtcVideoView)
            Log.i(TAG, "WebRTCManager and SurfaceViewRenderer initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize WebRTCManager: ${e.message}", e)
        }
    }

    private fun handleConnectionStateChanged(state: WebRTCConnectionState) {
        when (state) {
            WebRTCConnectionState.DISCONNECTED -> {
                binding.tvStreamStats.text = "Disconnected"
            }
            WebRTCConnectionState.SIGNALING -> {
                binding.tvStreamStats.text = "Signaling..."
                binding.tvConnectingMessage.text = "Establishing signaling connection..."
            }
            WebRTCConnectionState.NEGOTIATING -> {
                binding.tvStreamStats.text = "SDP Offer/Answer..."
                binding.tvConnectingMessage.text = "Negotiating WebRTC PeerConnection..."
            }
            WebRTCConnectionState.ICE_CONNECTING -> {
                binding.tvStreamStats.text = "ICE Trickle..."
                binding.tvConnectingMessage.text = "Connecting ICE candidates..."
            }
            WebRTCConnectionState.CONNECTED -> {
                binding.tvStreamStats.text = "WebRTC P2P (Direct)"
                binding.connectingOverlay.visibility = View.GONE
                binding.webrtcVideoView.visibility = View.VISIBLE
                binding.remoteVideoView.visibility = View.GONE
                Toast.makeText(this, "WebRTC P2P Connected", Toast.LENGTH_SHORT).show()
            }
            WebRTCConnectionState.FALLBACK_WS -> {
                binding.tvStreamStats.text = "WebSocket Fallback (TCP)"
                binding.webrtcVideoView.visibility = View.GONE
                binding.remoteVideoView.visibility = View.VISIBLE
                if (targetDeviceId.isNotEmpty()) {
                    requestWsPreviewStream(targetDeviceId)
                }
                Toast.makeText(this, "WebRTC failed, fell back to WebSocket PREV", Toast.LENGTH_SHORT).show()
            }
            WebRTCConnectionState.RECONNECTING -> {
                binding.tvStreamStats.text = "Reconnecting..."
            }
        }
    }

    private fun setupTouchControl() {
        binding.touchOverlayView.setOnTouchListener { v, event ->
            val w = v.width
            val h = v.height
            if (w <= 0 || h <= 0) return@setOnTouchListener false

            val actionMasked = event.actionMasked
            val pointerIndex = event.actionIndex

            when (actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val id = event.getPointerId(pointerIndex)
                    val x = event.getX(pointerIndex).toInt().coerceIn(0, w)
                    val y = event.getY(pointerIndex).toInt().coerceIn(0, h)
                    dispatchTouchEvent(0, x, y, w, h, id.toLong())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val id = event.getPointerId(pointerIndex)
                    val x = event.getX(pointerIndex).toInt().coerceIn(0, w)
                    val y = event.getY(pointerIndex).toInt().coerceIn(0, h)
                    dispatchTouchEvent(1, x, y, w, h, id.toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val pointerCount = event.pointerCount
                    for (p in 0 until pointerCount) {
                        val id = event.getPointerId(p)
                        val x = event.getX(p).toInt().coerceIn(0, w)
                        val y = event.getY(p).toInt().coerceIn(0, h)
                        dispatchTouchEvent(2, x, y, w, h, id.toLong())
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    val pointerCount = event.pointerCount
                    for (p in 0 until pointerCount) {
                        val id = event.getPointerId(p)
                        val x = event.getX(p).toInt().coerceIn(0, w)
                        val y = event.getY(p).toInt().coerceIn(0, h)
                        dispatchTouchEvent(1, x, y, w, h, id.toLong())
                    }
                }
                else -> return@setOnTouchListener false
            }
            true
        }
    }

    private fun dispatchTouchEvent(action: Int, x: Int, y: Int, w: Int, h: Int, pointerId: Long = 0) {
        val rtc = webRTCManager
        if (rtc != null && rtc.currentState == WebRTCConnectionState.CONNECTED) {
            val sent = rtc.sendTouch(action, x, y, w, h, pointerId)
            if (sent) return
        }

        // WebSocket Fallback Path
        sendWsTouchEvent(action, x, y, w, h, pointerId)
    }

    private fun sendWsTouchEvent(action: Int, x: Int, y: Int, w: Int, h: Int, pointerId: Long = 0) {
        if (targetDeviceId.isEmpty() || webSocket == null) return

        val touchEvent = JsonObject().apply {
            addProperty("type", "touch")
            addProperty("action", action)
            addProperty("x", x)
            addProperty("y", y)
            addProperty("w", w)
            addProperty("h", h)
            addProperty("id", pointerId)
            addProperty("client_ts_ms", System.currentTimeMillis())
        }

        val groupControlMsg = JsonObject().apply {
            addProperty("message_type", "group_control_event")
            val targetArray = com.google.gson.JsonArray()
            targetArray.add(targetDeviceId)
            add("target_device_ids", targetArray)
            add("event", touchEvent)
        }

        webSocket?.send(groupControlMsg.toString())
    }

    private fun sendKeycode(keycode: Int) {
        val rtc = webRTCManager
        if (rtc != null && rtc.currentState == WebRTCConnectionState.CONNECTED) {
            rtc.sendKeycode(keycode, 0)
            binding.root.postDelayed({ rtc.sendKeycode(keycode, 1) }, 50)
            return
        }

        // WebSocket Fallback Path
        if (targetDeviceId.isEmpty() || webSocket == null) return

        val keyEvent = JsonObject().apply {
            addProperty("type", "inject_keycode")
            addProperty("action", 0) // KeyDown
            addProperty("keycode", keycode)
            addProperty("repeat", 0)
            addProperty("meta", 0)
        }

        val groupControlMsg = JsonObject().apply {
            addProperty("message_type", "group_control_event")
            val targetArray = com.google.gson.JsonArray()
            targetArray.add(targetDeviceId)
            add("target_device_ids", targetArray)
            add("event", keyEvent)
        }

        webSocket?.send(groupControlMsg.toString())

        binding.root.postDelayed({
            keyEvent.addProperty("action", 1) // KeyUp
            webSocket?.send(groupControlMsg.toString())
        }, 50)
    }

    fun sendInjectText(text: String) {
        val rtc = webRTCManager
        if (rtc != null && rtc.currentState == WebRTCConnectionState.CONNECTED) {
            rtc.sendText(text)
            return
        }

        if (targetDeviceId.isEmpty() || webSocket == null || text.isEmpty()) return

        val textEvent = JsonObject().apply {
            addProperty("type", "inject_text")
            addProperty("text", text)
        }

        val groupControlMsg = JsonObject().apply {
            addProperty("message_type", "group_control_event")
            val targetArray = com.google.gson.JsonArray()
            targetArray.add(targetDeviceId)
            add("target_device_ids", targetArray)
            add("event", textEvent)
        }

        webSocket?.send(groupControlMsg.toString())
    }

    fun sendClipboard(text: String, paste: Boolean = true) {
        val rtc = webRTCManager
        if (rtc != null && rtc.currentState == WebRTCConnectionState.CONNECTED) {
            rtc.sendClipboard(text, paste)
            return
        }

        if (targetDeviceId.isEmpty() || webSocket == null) return

        val clipPayload = JsonObject().apply {
            addProperty("type", "set_clipboard")
            addProperty("text", text)
            addProperty("paste", paste)
            addProperty("source", "android_controller")
        }

        val injectMsg = JsonObject().apply {
            addProperty("message_type", "inject_data")
            addProperty("channel", "clipboard-channel")
            add("payload", clipPayload)
            val targetArray = com.google.gson.JsonArray()
            targetArray.add(targetDeviceId)
            add("target_device_ids", targetArray)
        }

        webSocket?.send(injectMsg.toString())
    }

    private fun showTextInputDialog() {
        val input = EditText(this).apply {
            hint = "Type text to send to remote device..."
        }
        AlertDialog.Builder(this)
            .setTitle("Remote Text Input")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    sendInjectText(text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClipboardSyncDialog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        val currentText = if (clipData != null && clipData.itemCount > 0) {
            clipData.getItemAt(0).text.toString()
        } else ""

        val input = EditText(this).apply {
            setText(currentText)
            hint = "Clipboard text..."
        }

        AlertDialog.Builder(this)
            .setTitle("Sync Clipboard to Remote")
            .setMessage("Send local clipboard to remote device:")
            .setView(input)
            .setPositiveButton("Sync & Paste") { _, _ ->
                val text = input.text.toString()
                sendClipboard(text, true)
                Toast.makeText(this, "Clipboard synced and pasted", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Sync Only") { _, _ ->
                val text = input.text.toString()
                sendClipboard(text, false)
                Toast.makeText(this, "Clipboard synced", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCameraControlDialog() {
        val options = arrayOf(
            "Switch Camera (Front / Back)",
            "Capture Snapshot",
            "Start Camera Stream",
            "Stop Camera Stream",
            "Camera Status"
        )
        AlertDialog.Builder(this)
            .setTitle("Camera Remote Control")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> webRTCManager?.sendCameraCommand("camera_switch")
                    1 -> webRTCManager?.sendCameraCommand("camera_snapshot")
                    2 -> webRTCManager?.sendCameraCommand("camera_start")
                    3 -> webRTCManager?.sendCameraCommand("camera_stop")
                    4 -> webRTCManager?.sendCameraCommand("camera_status")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        fallbackSurface = Surface(surfaceTexture)
        initFallbackMediaCodec(fallbackSurface!!)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        releaseFallbackMediaCodec()
        fallbackSurface?.release()
        fallbackSurface = null
        return true
    }
    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    private fun initFallbackMediaCodec(outputSurface: Surface) {
        try {
            val codec = MediaCodec.createDecoderByType("video/avc")
            val format = MediaFormat.createVideoFormat("video/avc", 1080, 1920)
            codec.configure(format, outputSurface, null, 0)
            codec.start()
            fallbackDecoder = codec
            isFallbackDecoderConfigured = true
            Log.i(TAG, "Fallback Hardware MediaCodec decoder initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to configure fallback MediaCodec: ${e.message}", e)
        }
    }

    private fun releaseFallbackMediaCodec() {
        try {
            fallbackDecoder?.stop()
            fallbackDecoder?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing fallback MediaCodec", e)
        }
        fallbackDecoder = null
        isFallbackDecoderConfigured = false
    }

    private fun connectWebSocket() {
        val baseWsUrl = if (serverUrl.startsWith("http://")) {
            serverUrl.replace("http://", "ws://")
        } else if (serverUrl.startsWith("https://")) {
            serverUrl.replace("https://", "wss://")
        } else {
            serverUrl
        }

        val fullUrl = "$baseWsUrl/connect_client?token=$token"
        val request = Request.Builder().url(fullUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Signaling WebSocket connected")
                runOnUiThread {
                    binding.tvConnectingMessage.text = "Signaling connected. Starting WebRTC..."
                }

                if (targetDeviceId.isNotEmpty()) {
                    runOnUiThread {
                        webRTCManager?.startConnection(targetDeviceId)
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleSignalingText(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size >= 4 && data[0] == 'A'.code.toByte() && data[1] == 'U'.code.toByte() &&
                    data[2] == 'D'.code.toByte() && data[3] == 'O'.code.toByte()) {
                    handleBinaryAudio(data)
                } else {
                    handleBinaryPreview(data)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Signaling WebSocket failure: ${t.message}")
                runOnUiThread {
                    binding.tvConnectingMessage.text = "Connection error: ${t.message}"
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Signaling WebSocket closed: $code / $reason")
            }
        })
    }

    private fun requestWsPreviewStream(deviceId: String) {
        val startPreviewMsg = JsonObject().apply {
            addProperty("message_type", "start_preview")
            addProperty("device_id", deviceId)
            addProperty("fps", 30)
            addProperty("max_size", 1080)
            addProperty("bitrate", 4000000)
            addProperty("stay_awake", true)
        }
        webSocket?.send(startPreviewMsg.toString())
        Log.i(TAG, "Requested WebSocket preview stream for $deviceId")
    }

    private fun handleSignalingText(text: String) {
        try {
            val json = Gson().fromJson(text, JsonObject::class.java)
            val msgType = json.get("message_type")?.asString ?: json.get("type")?.asString

            if ((msgType == "device_list" || msgType == "device_list_update") && targetDeviceId.isEmpty()) {
                val devices = json.getAsJsonArray("devices")
                if (devices != null && devices.size() > 0) {
                    val firstDev = devices.get(0).asJsonObject
                    targetDeviceId = firstDev.get("device_id")?.asString ?: firstDev.get("id")?.asString ?: ""
                    if (targetDeviceId.isNotEmpty()) {
                        runOnUiThread {
                            binding.tvTargetDevice.text = "Target: $targetDeviceId"
                            webRTCManager?.startConnection(targetDeviceId)
                        }
                    }
                }
                return
            }

            // WebRTC signaling messages forwarded from agent
            if (msgType == "device_msg" || msgType == "forward") {
                val payload = json.getAsJsonObject("payload")
                if (payload != null) {
                    val pType = payload.get("type")?.asString
                    if (pType == "offer") {
                        val sdp = payload.get("sdp")?.asString ?: ""
                        if (sdp.isNotEmpty()) {
                            runOnUiThread { webRTCManager?.handleRemoteOffer(sdp) }
                        }
                    } else if (pType == "candidate") {
                        val candObj = payload.getAsJsonObject("candidate")
                        if (candObj != null) {
                            runOnUiThread { webRTCManager?.handleRemoteCandidate(candObj) }
                        }
                    }
                }
            } else if (msgType == "offer") {
                val sdp = json.get("sdp")?.asString ?: ""
                if (sdp.isNotEmpty()) {
                    runOnUiThread { webRTCManager?.handleRemoteOffer(sdp) }
                }
            } else if (msgType == "candidate") {
                val candObj = json.getAsJsonObject("candidate")
                if (candObj != null) {
                    runOnUiThread { webRTCManager?.handleRemoteCandidate(candObj) }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error parsing signaling JSON: ${e.message}")
        }
    }

    private fun handleBinaryAudio(data: ByteArray) {
        if (data.size < 4) return
        val payload = ByteArray(data.size - 4)
        System.arraycopy(data, 4, payload, 0, payload.size)
        opusAudioDecoder.decode(payload) { pcm ->
            feedAudio(pcm)
        }
    }

    private fun feedAudio(pcmData: ByteArray) {
        val track = audioTrack ?: return
        try {
            track.write(pcmData, 0, pcmData.size)
        } catch (e: Throwable) {
            Log.w(TAG, "AudioTrack write failed: ${e.message}")
        }
    }

    private fun handleBinaryPreview(data: ByteArray) {
        if (data.size < PREV_HEADER_LEN) return

        // Validate Magic "PREV"
        if (data[0] != 0x50.toByte() || data[1] != 0x52.toByte() ||
            data[2] != 0x45.toByte() || data[3] != 0x56.toByte()
        ) return

        val isKey = data[36].toInt() == 1
        val payloadLen = ByteBuffer.wrap(data, 45, 4).int

        if (data.size < PREV_HEADER_LEN + payloadLen) return

        val nalu = ByteArray(payloadLen)
        System.arraycopy(data, PREV_HEADER_LEN, nalu, 0, payloadLen)

        feedFallbackDecoder(nalu, isKey)
    }

    private fun feedFallbackDecoder(nalu: ByteArray, isKey: Boolean) {
        val codec = fallbackDecoder ?: return
        try {
            val inputBufferIndex = codec.dequeueInputBuffer(10000L)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(nalu)
                val flags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(inputBufferIndex, 0, nalu.size, System.nanoTime() / 1000, flags)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            while (outputBufferIndex >= 0) {
                codec.releaseOutputBuffer(outputBufferIndex, true)
                runOnUiThread {
                    binding.connectingOverlay.visibility = View.GONE
                }
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Fallback Decoder feed error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webRTCManager?.close()
        webRTCManager = null

        try {
            binding.webrtcVideoView.release()
        } catch (e: Throwable) {}

        try {
            rootEglBase?.release()
        } catch (e: Throwable) {}
        rootEglBase = null

        if (targetDeviceId.isNotEmpty()) {
            val stopPreviewMsg = JsonObject().apply {
                addProperty("message_type", "stop_preview")
                addProperty("device_id", targetDeviceId)
            }
            webSocket?.send(stopPreviewMsg.toString())
        }
        webSocket?.close(1000, "Activity closed")

        releaseFallbackMediaCodec()

        try {
            opusAudioDecoder.release()
        } catch (e: Throwable) {}

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Throwable) {}
        audioTrack = null
    }
}
