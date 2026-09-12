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
import androidx.lifecycle.lifecycleScope
import com.cloudphone.app.databinding.ActivityControllerBinding
import com.cloudphone.app.service.CloudPhoneHostService
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ControllerActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "ControllerActivity"
        private const val PREV_HEADER_LEN = 49
    }

    private lateinit var binding: ActivityControllerBinding
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var decoder: MediaCodec? = null
    private var surface: Surface? = null
    private var isDecoderConfigured = false
    private var audioTrack: AudioTrack? = null
    private var audioDecoder: MediaCodec? = null

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

        binding.remoteVideoView.surfaceTextureListener = this
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
            initAudioDecoder()
        } catch (e: Throwable) {
            Log.w(TAG, "AudioTrack init deferred: ${e.message}")
        }
    }

    private fun initAudioDecoder() {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2)
            // Opus identification header (19 bytes RFC 7845)
            val csd0 = ByteBuffer.allocate(19).order(ByteOrder.nativeOrder())
            csd0.put("OpusHead".toByteArray(Charsets.US_ASCII))
            csd0.put(1.toByte())
            csd0.put(2.toByte())
            csd0.putShort(0.toShort())
            csd0.putInt(48000)
            csd0.putShort(0.toShort())
            csd0.put(0.toByte())
            csd0.flip()
            format.setByteBuffer("csd-0", csd0)

            val csd1 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0L)
            csd1.flip()
            format.setByteBuffer("csd-1", csd1)

            val csd2 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80000000L)
            csd2.flip()
            format.setByteBuffer("csd-2", csd2)

            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(format, null, null, 0)
            codec.start()
            audioDecoder = codec
            Log.i(TAG, "Audio MediaCodec (Opus) initialized successfully")
        } catch (e: Throwable) {
            Log.w(TAG, "Opus decoder initialization fallback: ${e.message}")
        }
    }

    private fun setupTouchControl() {
        binding.remoteVideoView.setOnTouchListener { v, event ->
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
                    sendTouchEvent(0, x, y, w, h, id.toLong())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val id = event.getPointerId(pointerIndex)
                    val x = event.getX(pointerIndex).toInt().coerceIn(0, w)
                    val y = event.getY(pointerIndex).toInt().coerceIn(0, h)
                    sendTouchEvent(1, x, y, w, h, id.toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val pointerCount = event.pointerCount
                    for (p in 0 until pointerCount) {
                        val id = event.getPointerId(p)
                        val x = event.getX(p).toInt().coerceIn(0, w)
                        val y = event.getY(p).toInt().coerceIn(0, h)
                        sendTouchEvent(2, x, y, w, h, id.toLong())
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    val pointerCount = event.pointerCount
                    for (p in 0 until pointerCount) {
                        val id = event.getPointerId(p)
                        val x = event.getX(p).toInt().coerceIn(0, w)
                        val y = event.getY(p).toInt().coerceIn(0, h)
                        sendTouchEvent(1, x, y, w, h, id.toLong())
                    }
                }
                else -> return@setOnTouchListener false
            }
            true
        }
    }

    private fun sendTouchEvent(action: Int, x: Int, y: Int, w: Int, h: Int, pointerId: Long = 0) {
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

        // KeyUp after 50ms
        binding.root.postDelayed({
            keyEvent.addProperty("action", 1) // KeyUp
            webSocket?.send(groupControlMsg.toString())
        }, 50)
    }

    fun sendInjectText(text: String) {
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

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(surfaceTexture)
        initMediaCodec(surface!!)
        connectWebSocket()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        releaseMediaCodec()
        surface?.release()
        surface = null
        return true
    }
    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    private fun initMediaCodec(outputSurface: Surface) {
        try {
            val codec = MediaCodec.createDecoderByType("video/avc")
            val format = MediaFormat.createVideoFormat("video/avc", 1080, 1920)
            codec.configure(format, outputSurface, null, 0)
            codec.start()
            decoder = codec
            isDecoderConfigured = true
            Log.i(TAG, "Hardware MediaCodec decoder initialized successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to configure hardware MediaCodec: ${e.message}", e)
        }
    }

    private fun releaseMediaCodec() {
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing MediaCodec", e)
        }
        decoder = null
        isDecoderConfigured = false
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
                    binding.tvConnectingMessage.text = "Signaling connected. Subscribing to stream..."
                }

                if (targetDeviceId.isNotEmpty()) {
                    requestStream(targetDeviceId)
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

    private fun requestStream(deviceId: String) {
        val startPreviewMsg = JsonObject().apply {
            addProperty("message_type", "start_preview")
            addProperty("device_id", deviceId)
            addProperty("fps", 30)
            addProperty("max_size", 1080)
            addProperty("bitrate", 4000000)
            addProperty("stay_awake", true)
        }
        webSocket?.send(startPreviewMsg.toString())
        Log.i(TAG, "Requested preview stream for $deviceId")
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
                        }
                        requestStream(targetDeviceId)
                    }
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
        feedOpusDecoder(payload)
    }

    private fun feedOpusDecoder(opusPacket: ByteArray) {
        val codec = audioDecoder
        if (codec == null) {
            // Direct PCM fallback if Opus decoder is unavailable
            feedAudio(opusPacket)
            return
        }

        try {
            val inIndex = codec.dequeueInputBuffer(5000L)
            if (inIndex >= 0) {
                val inBuf = codec.getInputBuffer(inIndex)
                if (inBuf != null) {
                    inBuf.clear()
                    inBuf.put(opusPacket)
                    codec.queueInputBuffer(inIndex, 0, opusPacket.size, System.nanoTime() / 1000, 0)
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            while (outIndex >= 0) {
                val outBuf = codec.getOutputBuffer(outIndex)
                if (outBuf != null && bufferInfo.size > 0) {
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    val pcmBytes = ByteArray(bufferInfo.size)
                    outBuf.get(pcmBytes)
                    feedAudio(pcmBytes)
                }
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Opus decoding error, fallback direct: ${e.message}")
            feedAudio(opusPacket)
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

        feedDecoder(nalu, isKey)
    }

    private fun feedDecoder(nalu: ByteArray, isKey: Boolean) {
        val codec = decoder ?: return
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
            Log.e(TAG, "Decoder feed error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (targetDeviceId.isNotEmpty()) {
            val stopPreviewMsg = JsonObject().apply {
                addProperty("message_type", "stop_preview")
                addProperty("device_id", targetDeviceId)
            }
            webSocket?.send(stopPreviewMsg.toString())
        }
        webSocket?.close(1000, "Activity closed")
        releaseMediaCodec()

        try {
            audioDecoder?.stop()
            audioDecoder?.release()
        } catch (e: Throwable) {}
        audioDecoder = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Throwable) {}
        audioTrack = null
    }
}
