package com.cloudphone.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
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
        private const val REQUEST_CAMERA_PERMISSION = 101
    }

    private val mainHandler = Handler(Looper.getMainLooper())
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

    // WebRTC Engine & ICE readiness state machine
    private var rootEglBase: EglBase? = null
    private var webRTCManager: WebRTCManager? = null
    private var cachedIceServers: List<org.webrtc.PeerConnection.IceServer>? = null
    private var isSignalingReady = false
    private var isIceConfigReady = false
    private var isWebRTCStarted = false
    private var iceConfigFallbackRunnable: Runnable? = null

    data class CameraLensInfo(
        val id: String,
        val facing: Int,
        val facingName: String,
        val supportedResolutions: List<Size>
    )

    // Camera2 Surveillance pipeline state
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var cameraImageReader: ImageReader? = null
    private var isCameraCapturing = false
    private var cameraHandlerThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var currentSelectedCameraId: String? = null
    private var currentSelectedLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private var currentCaptureWidth = 1280
    private var currentCaptureHeight = 720

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
        fetchTurnConfig()

        binding.remoteVideoView.surfaceTextureListener = this
        connectWebSocket()
    }

    private fun fetchTurnConfig() {
        val httpUrl = if (serverUrl.startsWith("ws://")) {
            serverUrl.replace("ws://", "http://")
        } else if (serverUrl.startsWith("wss://")) {
            serverUrl.replace("wss://", "https://")
        } else {
            serverUrl
        }

        val turnUrl = "$httpUrl/api/turn"
        val request = Request.Builder()
            .url(turnUrl)
            .apply {
                if (token.isNotEmpty()) {
                    addHeader("Authorization", "Bearer $token")
                }
            }
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.w(TAG, "Failed to fetch /api/turn: ${e.message}. Trying /api/ice_servers...")
                fetchIceServersFallback(httpUrl)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        parseAndApplyIceServers(body)
                    } else {
                        fetchIceServersFallback(httpUrl)
                    }
                }
            }
        })
    }

    private fun fetchIceServersFallback(httpUrl: String) {
        val iceUrl = "$httpUrl/api/ice_servers"
        val req = Request.Builder().url(iceUrl).apply {
            if (token.isNotEmpty()) addHeader("Authorization", "Bearer $token")
        }.build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.w(TAG, "Failed to fetch ICE servers: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        parseAndApplyIceServers(body)
                    }
                }
            }
        })
    }

    private fun parseAndApplyIceServers(jsonStr: String) {
        try {
            val jsonArray = Gson().fromJson(jsonStr, com.google.gson.JsonArray::class.java) ?: return
            val servers = mutableListOf<org.webrtc.PeerConnection.IceServer>()
            for (elem in jsonArray) {
                if (!elem.isJsonObject) continue
                val obj = elem.asJsonObject
                val username = obj.get("username")?.asString
                val credential = obj.get("credential")?.asString

                val urlsElem = obj.get("urls")
                val urlList = mutableListOf<String>()
                if (urlsElem != null && urlsElem.isJsonArray) {
                    for (u in urlsElem.asJsonArray) {
                        urlList.add(u.asString)
                    }
                } else if (urlsElem != null && urlsElem.isJsonPrimitive) {
                    urlList.add(urlsElem.asString)
                }

                for (u in urlList) {
                    val b = org.webrtc.PeerConnection.IceServer.builder(u)
                    if (!username.isNullOrEmpty()) b.setUsername(username)
                    if (!credential.isNullOrEmpty()) b.setPassword(credential)
                    servers.add(b.createIceServer())
                }
            }
            if (servers.isNotEmpty()) {
                cachedIceServers = servers
                webRTCManager?.setIceServers(servers)
                isIceConfigReady = true
                maybeStartWebRTC()
                Log.i(TAG, "Successfully loaded and configured ${servers.size} server ICE/TURN servers")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error parsing ICE servers JSON: ${e.message}")
            if (!isIceConfigReady) {
                isIceConfigReady = true
                maybeStartWebRTC()
            }
        }
    }

    private fun maybeStartWebRTC() {
        runOnUiThread {
            if (isSignalingReady && isIceConfigReady && targetDeviceId.isNotEmpty() && !isWebRTCStarted) {
                isWebRTCStarted = true
                iceConfigFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                binding.tvConnectingMessage.text = "Signaling & ICE ready. Connecting WebRTC..."
                webRTCManager?.startConnection(targetDeviceId, cachedIceServers)
                Log.i(TAG, "WebRTC connection initiated with ${cachedIceServers?.size ?: 0} ICE servers")
            }
        }
    }

    private fun queryAvailableCameraLenses(): List<CameraLensInfo> {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        val result = mutableListOf<CameraLensInfo>()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                val facingName = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                    else -> "Lens $id"
                }
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
                result.add(CameraLensInfo(id, facing, facingName, sizes))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error querying camera lenses: ${e.message}")
        }
        return result
    }

    private fun selectBestResolution(sizes: List<Size>, targetWidth: Int, targetHeight: Int): Size {
        if (sizes.isEmpty()) return Size(targetWidth, targetHeight)
        return sizes.find { it.width == targetWidth && it.height == targetHeight }
            ?: sizes.minByOrNull { Math.abs(it.width * it.height - targetWidth * targetHeight) }
            ?: sizes[0]
    }

    private fun startCameraCapture() {
        if (isCameraCapturing) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted, requesting permission from user")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            return
        }
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            val lenses = queryAvailableCameraLenses()
            if (lenses.isEmpty()) {
                Log.w(TAG, "No camera devices found on device")
                return
            }

            // Select camera matching currentSelectedLensFacing or currentSelectedCameraId
            val selectedLens = (if (currentSelectedCameraId != null) {
                lenses.find { it.id == currentSelectedCameraId }
            } else null) ?: lenses.find { it.facing == currentSelectedLensFacing } ?: lenses[0]

            currentSelectedCameraId = selectedLens.id
            currentSelectedLensFacing = selectedLens.facing

            val chosenSize = selectBestResolution(selectedLens.supportedResolutions, currentCaptureWidth, currentCaptureHeight)
            Log.i(TAG, "Starting camera capture on lens ${selectedLens.id} (${selectedLens.facingName}) at ${chosenSize.width}x${chosenSize.height}")

            val thread = HandlerThread("CameraCaptureThread").apply { start() }
            cameraHandlerThread = thread
            val handler = Handler(thread.looper)
            cameraHandler = handler

            val reader = ImageReader.newInstance(chosenSize.width, chosenSize.height, ImageFormat.JPEG, 2)
            cameraImageReader = reader
            reader.setOnImageAvailableListener({ imgReader ->
                val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    webRTCManager?.sendCameraFrame(bytes)
                } catch (e: Throwable) {
                    Log.w(TAG, "Camera frame capture error: ${e.message}")
                } finally {
                    image.close()
                }
            }, handler)

            cameraManager.openCamera(selectedLens.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    isCameraCapturing = true
                    try {
                        val surface = reader.surface
                        val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        }
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (cameraDevice == null) return
                                cameraCaptureSession = session
                                try {
                                    session.setRepeatingRequest(captureRequestBuilder.build(), null, handler)
                                    Log.i(TAG, "Camera capture repeating request active at ~30 FPS on lens ${selectedLens.id}")
                                } catch (e: Throwable) {
                                    Log.e(TAG, "Failed to start camera repeating request: ${e.message}")
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Camera capture session configuration failed")
                            }
                        }, handler)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to setup camera capture session: ${e.message}")
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    stopCameraCapture()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera device error: $error")
                    stopCameraCapture()
                }
            }, handler)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize camera capture: ${e.message}", e)
        }
    }

    private fun stopCameraCapture() {
        isCameraCapturing = false
        try { cameraCaptureSession?.stopRepeating() } catch (e: Throwable) {}
        try { cameraCaptureSession?.close() } catch (e: Throwable) {}
        cameraCaptureSession = null
        try { cameraDevice?.close() } catch (e: Throwable) {}
        cameraDevice = null
        try { cameraImageReader?.close() } catch (e: Throwable) {}
        cameraImageReader = null
        try { cameraHandlerThread?.quitSafely() } catch (e: Throwable) {}
        cameraHandlerThread = null
        cameraHandler = null
        Log.i(TAG, "Camera capture stopped and released")
    }

    private fun switchCameraFacing(targetFacing: Int? = null, targetCameraId: String? = null) {
        val lenses = queryAvailableCameraLenses()
        if (lenses.size <= 1 && targetCameraId == null && targetFacing == null) {
            Toast.makeText(this, "Only one camera lens available", Toast.LENGTH_SHORT).show()
            return
        }

        if (targetCameraId != null) {
            currentSelectedCameraId = targetCameraId
            val found = lenses.find { it.id == targetCameraId }
            if (found != null) currentSelectedLensFacing = found.facing
        } else if (targetFacing != null) {
            currentSelectedLensFacing = targetFacing
            currentSelectedCameraId = lenses.find { it.facing == targetFacing }?.id
        } else {
            // Toggle between FRONT and BACK
            val nextFacing = if (currentSelectedLensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            val match = lenses.find { it.facing == nextFacing } ?: lenses.find { it.id != currentSelectedCameraId }
            if (match != null) {
                currentSelectedLensFacing = match.facing
                currentSelectedCameraId = match.id
            }
        }

        val lensName = lenses.find { it.id == currentSelectedCameraId }?.facingName ?: "Selected"
        Toast.makeText(this, "Switched to $lensName camera", Toast.LENGTH_SHORT).show()

        if (isCameraCapturing) {
            stopCameraCapture()
            startCameraCapture()
        }
    }

    private fun configureCameraCapture(params: JsonObject) {
        val resolution = params.get("resolution")?.asString?.lowercase()
        if (resolution != null) {
            when (resolution) {
                "4k" -> { currentCaptureWidth = 3840; currentCaptureHeight = 2160 }
                "1080p" -> { currentCaptureWidth = 1920; currentCaptureHeight = 1080 }
                "720p" -> { currentCaptureWidth = 1280; currentCaptureHeight = 720 }
                "480p" -> { currentCaptureWidth = 640; currentCaptureHeight = 480 }
            }
        }
        if (params.has("width") && params.has("height")) {
            currentCaptureWidth = params.get("width").asInt
            currentCaptureHeight = params.get("height").asInt
        }
        if (params.has("facing")) {
            val facingStr = params.get("facing").asString.lowercase()
            val targetFacing = when (facingStr) {
                "front" -> CameraCharacteristics.LENS_FACING_FRONT
                "back" -> CameraCharacteristics.LENS_FACING_BACK
                "external" -> CameraCharacteristics.LENS_FACING_EXTERNAL
                else -> null
            }
            if (targetFacing != null) {
                currentSelectedLensFacing = targetFacing
                currentSelectedCameraId = null
            }
        }
        Toast.makeText(this, "Camera configured: ${currentCaptureWidth}x${currentCaptureHeight}", Toast.LENGTH_SHORT).show()
        if (isCameraCapturing) {
            stopCameraCapture()
            startCameraCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Camera permission granted by user")
                startCameraCapture()
            } else {
                Toast.makeText(this, "Camera permission required for surveillance camera mode", Toast.LENGTH_LONG).show()
            }
        }
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
                        val hasSnapshot = response.has("image_base64")
                        val msg = if (hasSnapshot) "Camera snapshot captured!" else "Camera $action: $status"
                        Toast.makeText(this@ControllerActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onVideoTrackReady(track: VideoTrack) {
                    runOnUiThread {
                        binding.connectingOverlay.visibility = View.GONE
                    }
                }

                override fun onAdbOutput(data: ByteArray) {
                    Log.d(TAG, "ADB channel output: ${data.size} bytes")
                }

                override fun onFileMessage(message: String) {
                    Log.i(TAG, "File channel message: $message")
                }

                override fun onAiCommandResponse(response: JsonObject) {
                    runOnUiThread {
                        Toast.makeText(this@ControllerActivity, "AI Command: ${response.get("status")?.asString}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onCameraStreamStarted() {
                    runOnUiThread {
                        Toast.makeText(this@ControllerActivity, "Remote camera streaming started", Toast.LENGTH_SHORT).show()
                        startCameraCapture()
                    }
                }

                override fun onCameraStreamStopped() {
                    runOnUiThread {
                        Toast.makeText(this@ControllerActivity, "Remote camera streaming stopped", Toast.LENGTH_SHORT).show()
                        stopCameraCapture()
                    }
                }

                override fun onCameraSwitchRequested(facing: String) {
                    runOnUiThread {
                        val targetFacing = when (facing.lowercase()) {
                            "front" -> CameraCharacteristics.LENS_FACING_FRONT
                            "back" -> CameraCharacteristics.LENS_FACING_BACK
                            "external" -> CameraCharacteristics.LENS_FACING_EXTERNAL
                            else -> null
                        }
                        switchCameraFacing(targetFacing = targetFacing)
                    }
                }

                override fun onCameraConfigureRequested(params: JsonObject) {
                    runOnUiThread {
                        configureCameraCapture(params)
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
        val lenses = queryAvailableCameraLenses()
        val currentLensName = lenses.find { it.id == currentSelectedCameraId }?.facingName ?: "Back"
        val options = arrayOf(
            "Switch Lens (Current: $currentLensName)",
            "Change Resolution (Current: ${currentCaptureWidth}x${currentCaptureHeight})",
            "Take Surveillance Snapshot",
            if (isCameraCapturing) "Stop Camera Streaming" else "Start Camera Streaming",
            "Camera Surveillance Status"
        )
        AlertDialog.Builder(this)
            .setTitle("Surveillance Camera Control")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showLensSelectionDialog(lenses)
                    1 -> showResolutionSelectionDialog()
                    2 -> {
                        webRTCManager?.sendCameraCommand("camera_snapshot")
                        Toast.makeText(this, "Requested snapshot from camera...", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        if (isCameraCapturing) {
                            stopCameraCapture()
                            webRTCManager?.sendCameraCommand("camera_stop")
                        } else {
                            startCameraCapture()
                            webRTCManager?.sendCameraCommand("camera_start")
                        }
                    }
                    4 -> showCameraStatusDialog(currentLensName)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showLensSelectionDialog(lenses: List<CameraLensInfo>) {
        if (lenses.isEmpty()) {
            Toast.makeText(this, "No cameras available", Toast.LENGTH_SHORT).show()
            return
        }
        val items = lenses.map { "${it.facingName} Camera (ID: ${it.id})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Camera Lens")
            .setItems(items) { _, which ->
                val selected = lenses[which]
                switchCameraFacing(targetFacing = selected.facing, targetCameraId = selected.id)
                val params = JsonObject().apply {
                    addProperty("facing", selected.facingName.lowercase())
                    addProperty("camera_id", selected.id)
                }
                webRTCManager?.sendCameraCommand("camera_switch", params = params)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResolutionSelectionDialog() {
        val resOptions = arrayOf("4K (3840x2160)", "1080p (1920x1080)", "720p (1280x720)", "480p (640x480)")
        AlertDialog.Builder(this)
            .setTitle("Select Camera Resolution")
            .setItems(resOptions) { _, which ->
                val resKey = when (which) {
                    0 -> "4k"
                    1 -> "1080p"
                    2 -> "720p"
                    else -> "480p"
                }
                val params = JsonObject().apply {
                    addProperty("resolution", resKey)
                }
                configureCameraCapture(params)
                webRTCManager?.sendCameraCommand("camera_configure", params = params)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCameraStatusDialog(currentLensName: String) {
        val status = """
            Status: ${if (isCameraCapturing) "Streaming Active (~30 FPS)" else "Idle / Standby"}
            Current Lens: $currentLensName (ID: ${currentSelectedCameraId ?: "auto"})
            Resolution: ${currentCaptureWidth}x${currentCaptureHeight}
            Format: JPEG Over WebRTC DataChannel
            Direct PTS Sync: Enabled
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("Surveillance Camera Diagnostics")
            .setMessage(status)
            .setPositiveButton("OK", null)
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
                isSignalingReady = true
                runOnUiThread {
                    binding.tvConnectingMessage.text = "Signaling connected. Awaiting ICE configuration..."
                }

                iceConfigFallbackRunnable = Runnable {
                    if (!isIceConfigReady) {
                        Log.i(TAG, "ICE/TURN config wait timeout (1500ms), falling back to default STUN")
                        isIceConfigReady = true
                        maybeStartWebRTC()
                    }
                }
                mainHandler.postDelayed(iceConfigFallbackRunnable!!, 1500L)
                maybeStartWebRTC()
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

            // Parse server configuration containing ICE/TURN servers
            if (msgType == "config" && json.has("ice_servers")) {
                parseAndApplyIceServers(json.get("ice_servers").toString())
                return
            }

            if ((msgType == "device_list" || msgType == "device_list_update") && targetDeviceId.isEmpty()) {
                val devices = json.getAsJsonArray("devices")
                if (devices != null && devices.size() > 0) {
                    val firstDev = devices.get(0).asJsonObject
                    targetDeviceId = firstDev.get("device_id")?.asString ?: firstDev.get("id")?.asString ?: ""
                    if (targetDeviceId.isNotEmpty()) {
                        runOnUiThread {
                            binding.tvTargetDevice.text = "Target: $targetDeviceId"
                        }
                        maybeStartWebRTC()
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
                    } else if (pType == "candidate" || pType == "ice-candidate") {
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
            } else if (msgType == "candidate" || msgType == "ice-candidate") {
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
        stopCameraCapture()
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
