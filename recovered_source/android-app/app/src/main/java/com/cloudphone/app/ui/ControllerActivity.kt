package com.cloudphone.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream
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
import com.google.gson.JsonParser
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
    private var currentCaptureRequestBuilder: CaptureRequest.Builder? = null

    // Local Camera Injection State (Controller Camera2 -> Remote Agent)
    private var currentZoomRatio: Float = 1.0f
    private var currentCameraRotation: Int = 0
    private var cameraWakeLock: PowerManager.WakeLock? = null
    private var isRecording: Boolean = false
    private var recordingOutputFile: File? = null
    private var recordingOutputStream: FileOutputStream? = null
    private var recordingStartTimeMs: Long = 0L
    private var recordedFrameCount: Long = 0L

    // Remote Surveillance Camera State (Remote Phone Hardware Camera -> WebRTC VideoTrack)
    private var isRemoteSurveillanceMode: Boolean = false
    private var remoteCameraFacing: String = "back"
    private var remoteCameraId: String = "0"
    private var remoteCameraSize: String = "1920x1080"
    private var remoteCameraFps: Int = 30
    private var remoteCameraZoomRatio: Float = 1.0f
    private var remoteCameraOrientation: String = "auto"
    private var remoteStayAwake: Boolean = true
    private var remoteScreenOff: Boolean = false

    // Viewport PTZ Parity (Pan X/Y, Zoom, Rotate, Horizontal Mirror)
    private var viewportZoom: Float = 1.0f
    private var viewportPanX: Float = 0f
    private var viewportPanY: Float = 0f
    private var viewportRotation: Float = 0f
    private var viewportMirror: Boolean = false
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var isPanning: Boolean = false

    // Remote Surveillance Stream MP4 Recording & Snapshot
    private var isRemoteRecording = false
    private var remoteMp4Recorder: RemoteStreamMp4Recorder? = null
    private var remoteRecordingOutputFile: File? = null
    private var remoteRecordingHandler: Handler? = null
    private var remoteRecordingRunnable: Runnable? = null
    private var remoteRecordingFrameCount = 0L

    // Dynamic Remote Device Camera Inventory
    data class RemoteCameraItem(
        val id: String,
        val facing: String,
        val title: String,
        val zoomRatio: Float = 1.0f
    )
    private val remoteCameraInventory = mutableListOf<RemoteCameraItem>()

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
        fetchRemoteDeviceInfo(httpUrl)
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

    private fun fetchRemoteDeviceInfo(httpUrl: String) {
        val devUrl = "$httpUrl/devices"
        val req = Request.Builder().url(devUrl).apply {
            if (token.isNotEmpty()) addHeader("Authorization", "Bearer $token")
        }.build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.w(TAG, "Failed to fetch /devices for camera inventory: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        parseRemoteDeviceCameras(body)
                    }
                }
            }
        })
    }

    private fun parseRemoteDeviceCameras(jsonStr: String) {
        try {
            val jsonArray = JsonParser.parseString(jsonStr).asJsonArray
            for (elem in jsonArray) {
                if (!elem.isJsonObject) continue
                val obj = elem.asJsonObject
                val dId = obj.get("device_id")?.asString ?: obj.get("id")?.asString ?: ""
                if (dId == targetDeviceId) {
                    val infoObj = obj.getAsJsonObject("device_info") ?: obj.getAsJsonObject("info")
                    val camsArray = infoObj?.getAsJsonArray("cameras")
                    if (camsArray != null && camsArray.size() > 0) {
                        synchronized(remoteCameraInventory) {
                            remoteCameraInventory.clear()
                            for (cElem in camsArray) {
                                if (!cElem.isJsonObject) continue
                                val cObj = cElem.asJsonObject
                                val id = cObj.get("id")?.asString ?: "0"
                                val facing = cObj.get("facing")?.asString?.lowercase() ?: "back"
                                val title = when (facing) {
                                    "front" -> "Front Selfie Lens (ID: $id)"
                                    "external" -> "External Camera Lens (ID: $id)"
                                    else -> "Back Camera Lens (ID: $id)"
                                }
                                remoteCameraInventory.add(RemoteCameraItem(id, facing, title))
                            }
                        }
                        Log.i(TAG, "Loaded ${remoteCameraInventory.size} remote hardware cameras from device metadata")
                    }
                    break
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error parsing remote camera inventory: ${e.message}")
        }
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

            // Acquire Partial WakeLock to maintain surveillance camera operation with screen off
            if (cameraWakeLock == null) {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    cameraWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CloudPhone:SurveillanceWakeLock")
                    cameraWakeLock?.acquire(4 * 3600 * 1000L) // Keep surveillance active for up to 4 hours
                    Log.i(TAG, "Surveillance partial WakeLock acquired (screen-off background streaming enabled)")
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to acquire surveillance WakeLock: ${e.message}")
                }
            }

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

                    // Write to instant recording file if active
                    if (isRecording && recordingOutputStream != null) {
                        try {
                            recordingOutputStream?.write(bytes)
                            recordedFrameCount++
                        } catch (e: Throwable) {
                            Log.w(TAG, "Error writing instant recording frame: ${e.message}")
                        }
                    }
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
                            set(CaptureRequest.JPEG_ORIENTATION, currentCameraRotation)
                            applyZoomToBuilder(this, currentZoomRatio, selectedLens.id)
                        }
                        currentCaptureRequestBuilder = captureRequestBuilder
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
        if (isRecording) {
            stopRecording()
        }
        isCameraCapturing = false
        currentCaptureRequestBuilder = null
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

        // Release WakeLock
        try {
            if (cameraWakeLock?.isHeld == true) {
                cameraWakeLock?.release()
                Log.i(TAG, "Surveillance WakeLock released")
            }
        } catch (e: Throwable) {}
        cameraWakeLock = null

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
        if (params.has("zoom")) {
            setCameraZoom(params.get("zoom").asFloat)
        }
        if (params.has("rotation")) {
            setCameraRotation(params.get("rotation").asInt)
        } else if (params.has("degrees")) {
            setCameraRotation(params.get("degrees").asInt)
        }
        Toast.makeText(this, "Camera configured: ${currentCaptureWidth}x${currentCaptureHeight}", Toast.LENGTH_SHORT).show()
        if (isCameraCapturing && (params.has("resolution") || params.has("width") || params.has("height") || params.has("facing"))) {
            stopCameraCapture()
            startCameraCapture()
        }
    }

    private fun applyZoomToBuilder(builder: CaptureRequest.Builder, zoomRatio: Float, cameraId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager != null) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(cameraId)
                    val rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    if (rect != null) {
                        val cropW = (rect.width() / zoomRatio).toInt()
                        val cropH = (rect.height() / zoomRatio).toInt()
                        val cropX = (rect.width() - cropW) / 2
                        val cropY = (rect.height() - cropH) / 2
                        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed applying SCALER_CROP_REGION: ${e.message}")
                }
            }
        }
    }

    private fun setCameraZoom(zoomRatio: Float) {
        currentZoomRatio = zoomRatio.coerceIn(1.0f, 10.0f)
        val builder = currentCaptureRequestBuilder
        val session = cameraCaptureSession
        val handler = cameraHandler
        val camId = currentSelectedCameraId ?: ""
        if (builder != null && session != null && isCameraCapturing) {
            try {
                applyZoomToBuilder(builder, currentZoomRatio, camId)
                session.setRepeatingRequest(builder.build(), null, handler)
                Log.i(TAG, "Camera digital zoom set to ${currentZoomRatio}x")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to apply camera zoom: ${e.message}")
            }
        }
    }

    private fun setCameraRotation(degrees: Int) {
        currentCameraRotation = when (degrees % 360) {
            90 -> 90
            180 -> 180
            270 -> 270
            else -> 0
        }
        val builder = currentCaptureRequestBuilder
        val session = cameraCaptureSession
        val handler = cameraHandler
        if (builder != null && session != null && isCameraCapturing) {
            try {
                builder.set(CaptureRequest.JPEG_ORIENTATION, currentCameraRotation)
                session.setRepeatingRequest(builder.build(), null, handler)
                Log.i(TAG, "Camera JPEG orientation set to ${currentCameraRotation}°")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to apply camera rotation: ${e.message}")
            }
        }
    }

    private fun startRecording() {
        if (!isCameraCapturing) {
            Toast.makeText(this, "Start camera streaming before recording", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val recDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val recFile = File(recDir, "surveillance_${System.currentTimeMillis()}.mjpeg")
            recordingOutputFile = recFile
            recordingOutputStream = FileOutputStream(recFile)
            recordingStartTimeMs = System.currentTimeMillis()
            recordedFrameCount = 0L
            isRecording = true
            Toast.makeText(this, "Instant recording started: ${recFile.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            recordingOutputStream?.flush()
            recordingOutputStream?.close()
        } catch (e: Throwable) {}
        recordingOutputStream = null
        val durationSec = (System.currentTimeMillis() - recordingStartTimeMs) / 1000
        val sizeKb = (recordingOutputFile?.length() ?: 0) / 1024
        Toast.makeText(this, "Recording saved: ${recordedFrameCount} frames (${sizeKb} KB, ${durationSec}s)", Toast.LENGTH_LONG).show()
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
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

                override fun onCameraZoomRequested(zoom: Float) {
                    runOnUiThread {
                        setCameraZoom(zoom)
                        Toast.makeText(this@ControllerActivity, "Digital Zoom: ${zoom}x", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onCameraRotateRequested(degrees: Int) {
                    runOnUiThread {
                        setCameraRotation(degrees)
                        Toast.makeText(this@ControllerActivity, "Camera Orientation: ${degrees}°", Toast.LENGTH_SHORT).show()
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

            // When in Remote Surveillance Camera Mode and zoomed in, touch drags pan the viewport
            if (isRemoteSurveillanceMode && viewportZoom > 1.0f) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        isPanning = true
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isPanning) {
                            val dx = event.x - lastTouchX
                            val dy = event.y - lastTouchY
                            viewportPanX += dx
                            viewportPanY += dy
                            lastTouchX = event.x
                            lastTouchY = event.y
                            applyViewportTransform()
                            return@setOnTouchListener true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isPanning = false
                        return@setOnTouchListener true
                    }
                }
            }

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

    fun applyViewportTransform() {
        binding.webrtcVideoView.apply {
            pivotX = width / 2f
            pivotY = height / 2f
            scaleX = viewportZoom * (if (viewportMirror) -1f else 1f)
            scaleY = viewportZoom
            translationX = viewportPanX
            translationY = viewportPanY
            rotation = viewportRotation
        }
    }

    fun resetViewportPTZ() {
        viewportZoom = 1.0f
        viewportPanX = 0f
        viewportPanY = 0f
        viewportRotation = 0f
        viewportMirror = false
        applyViewportTransform()
        Toast.makeText(this, "Viewport PTZ reset to default", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRemoteSurveillanceMode() {
        isRemoteSurveillanceMode = !isRemoteSurveillanceMode
        if (isRemoteSurveillanceMode) {
            Toast.makeText(this, "Switching to Remote Phone Hardware Camera...", Toast.LENGTH_SHORT).show()
            val options = WebRTCManager.buildRemoteCameraOptions(
                facing = remoteCameraFacing,
                cameraId = remoteCameraId,
                size = remoteCameraSize,
                fps = remoteCameraFps,
                zoomRatio = remoteCameraZoomRatio,
                orientation = remoteCameraOrientation,
                stayAwake = remoteStayAwake,
                powerOff = remoteScreenOff
            )
            webRTCManager?.reconnectWithOptions(options)
        } else {
            Toast.makeText(this, "Switching back to Remote Phone Display...", Toast.LENGTH_SHORT).show()
            resetViewportPTZ()
            val options = JsonObject().apply {
                addProperty("video_source", "display")
            }
            webRTCManager?.reconnectWithOptions(options)
        }
    }

    private fun showRemoteLensSelectionDialog() {
        val lenses: List<Pair<String, Pair<String, String>>> = synchronized(remoteCameraInventory) {
            if (remoteCameraInventory.isNotEmpty()) {
                remoteCameraInventory.map { item ->
                    item.title to (item.facing to item.id)
                }
            } else {
                listOf(
                    "Back Main Lens (1.0x)" to ("back" to "0"),
                    "Back Ultra-Wide Lens (0.5x)" to ("back" to "0"),
                    "Back Telephoto Lens (2.0x)" to ("back" to "0"),
                    "Front Selfie Camera (1.0x)" to ("front" to "1"),
                    "External Camera Lens" to ("external" to "")
                )
            }
        }
        val titles = lenses.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Remote Camera Lens")
            .setItems(titles) { _, which ->
                val chosen = lenses[which]
                remoteCameraFacing = chosen.second.first
                remoteCameraId = chosen.second.second
                if (chosen.first.contains("0.5x")) {
                    remoteCameraZoomRatio = 0.5f
                } else if (chosen.first.contains("2.0x")) {
                    remoteCameraZoomRatio = 2.0f
                } else if (remoteCameraFacing == "front") {
                    remoteCameraZoomRatio = 1.0f
                    viewportMirror = true // Front selfie defaults to mirror flip
                } else {
                    remoteCameraZoomRatio = 1.0f
                }
                applyViewportTransform()
                Toast.makeText(this, "Selected ${chosen.first}", Toast.LENGTH_SHORT).show()
                if (isRemoteSurveillanceMode) {
                    val options = WebRTCManager.buildRemoteCameraOptions(
                        facing = remoteCameraFacing,
                        cameraId = remoteCameraId,
                        size = remoteCameraSize,
                        fps = remoteCameraFps,
                        zoomRatio = remoteCameraZoomRatio,
                        orientation = remoteCameraOrientation,
                        stayAwake = remoteStayAwake,
                        powerOff = remoteScreenOff
                    )
                    webRTCManager?.reconnectWithOptions(options)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRemoteResolutionSelectionDialog() {
        val resOptions = arrayOf("1080P (1920x1080)", "720P (1280x720)", "480P (640x480)", "4K (3840x2160)")
        val resValues = arrayOf("1920x1080", "1280x720", "640x480", "3840x2160")
        AlertDialog.Builder(this)
            .setTitle("Select Remote Camera Resolution")
            .setItems(resOptions) { _, which ->
                remoteCameraSize = resValues[which]
                Toast.makeText(this, "Remote camera resolution: $remoteCameraSize", Toast.LENGTH_SHORT).show()
                if (isRemoteSurveillanceMode) {
                    val options = WebRTCManager.buildRemoteCameraOptions(
                        facing = remoteCameraFacing,
                        cameraId = remoteCameraId,
                        size = remoteCameraSize,
                        fps = remoteCameraFps,
                        zoomRatio = remoteCameraZoomRatio,
                        orientation = remoteCameraOrientation,
                        stayAwake = remoteStayAwake,
                        powerOff = remoteScreenOff
                    )
                    webRTCManager?.reconnectWithOptions(options)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleRemoteScreenPower() {
        remoteScreenOff = !remoteScreenOff
        webRTCManager?.sendSetDisplayPower(!remoteScreenOff)
        Toast.makeText(
            this,
            if (remoteScreenOff) "Remote screen turned OFF (Camera streaming continues)" else "Remote screen turned ON",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showViewportZoomDialog() {
        val zoomLevels = arrayOf("1.0x (Normal)", "1.5x", "2.0x", "3.0x", "5.0x")
        val zoomValues = arrayOf(1.0f, 1.5f, 2.0f, 3.0f, 5.0f)
        AlertDialog.Builder(this)
            .setTitle("Viewport PTZ Zoom")
            .setItems(zoomLevels) { _, which ->
                viewportZoom = zoomValues[which]
                if (viewportZoom <= 1.0f) {
                    viewportPanX = 0f
                    viewportPanY = 0f
                }
                applyViewportTransform()
                Toast.makeText(this, "Viewport zoom: ${viewportZoom}x (Drag with finger to pan)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun takeRemoteSurveillanceSnapshot() {
        val view = binding.webrtcVideoView
        if (view.width <= 0 || view.height <= 0) {
            Toast.makeText(this, "Remote video stream is not active yet", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.view.PixelCopy.request(view, bitmap, { copyResult ->
                    if (copyResult == android.view.PixelCopy.SUCCESS) {
                        saveSnapshotBitmap(bitmap)
                    } else {
                        mainHandler.post {
                            Toast.makeText(this, "PixelCopy snapshot completed with code $copyResult", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, Handler(Looper.getMainLooper()))
            } else {
                saveSnapshotBitmap(bitmap)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error capturing remote surveillance snapshot: ${e.message}", e)
            Toast.makeText(this, "Snapshot error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSnapshotBitmap(bitmap: android.graphics.Bitmap) {
        val timestamp = System.currentTimeMillis()
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        val file = File(dir, "surveillance_snapshot_$timestamp.jpg")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            mainHandler.post {
                Toast.makeText(this, "Remote Snapshot saved: ${file.name}", Toast.LENGTH_LONG).show()
                Log.i(TAG, "Remote surveillance snapshot saved to ${file.absolutePath} (${file.length()} bytes)")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save snapshot file: ${e.message}", e)
        }
    }

    class RemoteStreamMp4Recorder(
        private val outputFile: File,
        private val width: Int,
        private val height: Int,
        private val fps: Int = 30,
        private val bitrate: Int = 4_000_000
    ) {
        private var mediaCodec: MediaCodec? = null
        private var mediaMuxer: MediaMuxer? = null
        private var inputSurface: Surface? = null
        private var trackIndex = -1
        private var isMuxerStarted = false
        private val bufferInfo = MediaCodec.BufferInfo()
        private var isRunning = false
        private var drainThread: Thread? = null

        fun start() {
            val w = if (width % 2 != 0) width - 1 else width
            val h = if (height % 2 != 0) height - 1 else height
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            isRunning = true
            drainThread = Thread({ drainEncoder() }, "RemoteStreamMp4RecorderDrain")
            drainThread?.start()
        }

        fun getInputSurface(): Surface? = inputSurface

        private fun drainEncoder() {
            while (isRunning) {
                val codec = mediaCodec ?: break
                val muxer = mediaMuxer ?: break
                val outIndex = try {
                    codec.dequeueOutputBuffer(bufferInfo, 10_000)
                } catch (_: Throwable) {
                    -1
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!isMuxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        isMuxerStarted = true
                    }
                } else if (outIndex >= 0) {
                    val encodedData = codec.getOutputBuffer(outIndex)
                    if (encodedData != null && bufferInfo.size > 0 && isMuxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }

        fun stop() {
            isRunning = false
            try {
                mediaCodec?.signalEndOfInputStream()
                drainThread?.join(1000)
            } catch (_: Throwable) {}
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
            } catch (_: Throwable) {}
            mediaCodec = null
            try {
                if (isMuxerStarted) {
                    mediaMuxer?.stop()
                }
                mediaMuxer?.release()
            } catch (_: Throwable) {}
            mediaMuxer = null
            inputSurface?.release()
            inputSurface = null
        }
    }

    private fun toggleRemoteRecording() {
        if (isRemoteRecording) {
            isRemoteRecording = false
            remoteRecordingRunnable?.let { remoteRecordingHandler?.removeCallbacks(it) }
            remoteRecordingRunnable = null
            remoteMp4Recorder?.stop()
            remoteMp4Recorder = null
            val file = remoteRecordingOutputFile
            val msg = if (file != null && file.exists()) {
                "Remote MP4 video saved: ${file.name} (${file.length()} bytes)"
            } else {
                "Remote recording stopped. Saved $remoteRecordingFrameCount frames"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } else {
            val view = binding.webrtcVideoView
            if (view.width <= 0 || view.height <= 0) {
                Toast.makeText(this, "Remote video view is not active", Toast.LENGTH_SHORT).show()
                return
            }
            val timestamp = System.currentTimeMillis()
            val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val mp4File = File(moviesDir, "surveillance_rec_$timestamp.mp4")
            remoteRecordingOutputFile = mp4File
            remoteRecordingFrameCount = 0L

            try {
                val recorder = RemoteStreamMp4Recorder(mp4File, view.width, view.height, 30, 4_000_000)
                recorder.start()
                remoteMp4Recorder = recorder
                isRemoteRecording = true

                if (remoteRecordingHandler == null) {
                    val thread = HandlerThread("RemoteRecordingThread")
                    thread.start()
                    remoteRecordingHandler = Handler(thread.looper)
                }

                val encSurface = recorder.getInputSurface()
                remoteRecordingRunnable = object : Runnable {
                    override fun run() {
                        if (!isRemoteRecording) return
                        if (encSurface != null && encSurface.isValid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                            android.view.PixelCopy.request(view, bmp, { res ->
                                if (res == android.view.PixelCopy.SUCCESS) {
                                    try {
                                        val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            encSurface.lockHardwareCanvas()
                                        } else {
                                            encSurface.lockCanvas(null)
                                        }
                                        canvas.drawBitmap(bmp, 0f, 0f, null)
                                        encSurface.unlockCanvasAndPost(canvas)
                                        remoteRecordingFrameCount++
                                    } catch (_: Throwable) {}
                                }
                            }, Handler(Looper.getMainLooper()))
                        }
                        remoteRecordingHandler?.postDelayed(this, 33) // ~30 fps hardware video recording
                    }
                }
                remoteRecordingHandler?.post(remoteRecordingRunnable!!)
                Toast.makeText(this, "Remote MP4 recording started (${mp4File.name})", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start MP4 recorder: ${e.message}", e)
                Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showCameraControlDialog() {
        val options = arrayOf(
            // --- Remote Surveillance Camera (Hardware Camera of Remote Phone) ---
            if (isRemoteSurveillanceMode) "📹 Surveillance: Switch to Remote Display Mode" else "📷 Surveillance: Switch to Remote Camera Stream",
            "🔍 Remote Lens (${remoteCameraFacing.uppercase()}, ID: $remoteCameraId, ${remoteCameraZoomRatio}x)",
            "📐 Remote Resolution (${remoteCameraSize} @ ${remoteCameraFps}fps)",
            if (remoteScreenOff) "💡 Remote Screen: Turn ON" else "🌙 Remote Screen: Turn OFF (Keep Streaming)",
            "🖼️ Viewport PTZ: Zoom (Current: ${viewportZoom}x, Pan: ${viewportPanX.toInt()},${viewportPanY.toInt()})",
            "🔄 Viewport Rotate (${viewportRotation.toInt()}°)",
            "🪞 Viewport Mirror (${if (viewportMirror) "ON (Flipped)" else "OFF"})",
            "↩️ Reset Viewport PTZ (1.0x, Pan 0,0)",
            "📸 Take Remote Stream Snapshot (VideoTrack)",
            if (isRemoteRecording) "⏹️ Stop Remote Stream Recording" else "🔴 Start Remote Stream Recording",
            // --- Local Camera Injection (Controller -> Remote Device) ---
            "--- Local Camera Injection ---",
            if (isCameraCapturing) "🛑 Stop Local Camera Injection" else "▶️ Start Local Camera Injection (Controller Camera2)",
            "⚙️ Local Camera Injection Lens & Settings"
        )
        AlertDialog.Builder(this)
            .setTitle("Surveillance & Camera Controls")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleRemoteSurveillanceMode()
                    1 -> showRemoteLensSelectionDialog()
                    2 -> showRemoteResolutionSelectionDialog()
                    3 -> toggleRemoteScreenPower()
                    4 -> showViewportZoomDialog()
                    5 -> {
                        viewportRotation = (viewportRotation + 90f) % 360f
                        applyViewportTransform()
                        Toast.makeText(this, "Viewport rotated to ${viewportRotation.toInt()}°", Toast.LENGTH_SHORT).show()
                    }
                    6 -> {
                        viewportMirror = !viewportMirror
                        applyViewportTransform()
                        Toast.makeText(this, "Viewport mirror: ${if (viewportMirror) "Enabled (Flipped)" else "Disabled"}", Toast.LENGTH_SHORT).show()
                    }
                    7 -> resetViewportPTZ()
                    8 -> takeRemoteSurveillanceSnapshot()
                    9 -> toggleRemoteRecording()
                    10 -> {} // Separator
                    11 -> {
                        if (isCameraCapturing) {
                            stopCameraCapture()
                            webRTCManager?.sendCameraCommand("camera_stop")
                        } else {
                            startCameraCapture()
                            webRTCManager?.sendCameraCommand("camera_start")
                        }
                    }
                    12 -> showLocalCameraInjectionSettings()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showLocalCameraInjectionSettings() {
        val lenses = queryAvailableCameraLenses()
        val currentLensName = lenses.find { it.id == currentSelectedCameraId }?.facingName ?: "Back"
        val options = arrayOf(
            "Switch Local Lens (Current: $currentLensName)",
            "Change Local Resolution (Current: ${currentCaptureWidth}x${currentCaptureHeight})",
            "Local Camera2 Digital Zoom (Current: ${currentZoomRatio}x)",
            "Rotate Local Camera (${currentCameraRotation}°)",
            if (isRecording) "Stop Local MJPEG Recording ($recordedFrameCount frames)" else "Start Local MJPEG Recording",
            "Local Camera2 Diagnostics"
        )
        AlertDialog.Builder(this)
            .setTitle("Local Camera Injection Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showLensSelectionDialog(lenses)
                    1 -> showResolutionSelectionDialog()
                    2 -> showZoomSelectionDialog()
                    3 -> showRotationSelectionDialog()
                    4 -> toggleRecording()
                    5 -> showCameraStatusDialog(currentLensName)
                }
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showZoomSelectionDialog() {
        val zoomLevels = arrayOf(
            "1.0x (Standard Wide)",
            "1.5x",
            "2.0x (2x Tele)",
            "3.0x (3x Tele)",
            "5.0x (5x Extreme Zoom)",
            "10.0x (Max Digital Zoom)"
        )
        val zoomValues = arrayOf(1.0f, 1.5f, 2.0f, 3.0f, 5.0f, 10.0f)
        AlertDialog.Builder(this)
            .setTitle("PTZ Digital Zoom")
            .setItems(zoomLevels) { _, which ->
                val zoom = zoomValues[which]
                setCameraZoom(zoom)
                webRTCManager?.sendCameraZoom(zoom)
                Toast.makeText(this, "Zoom set to ${zoom}x", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRotationSelectionDialog() {
        val rotOptions = arrayOf("0° (Normal)", "90° (Clockwise)", "180° (Inverted)", "270° (Counter-Clockwise)")
        val rotValues = arrayOf(0, 90, 180, 270)
        AlertDialog.Builder(this)
            .setTitle("Rotate Camera Orientation")
            .setItems(rotOptions) { _, which ->
                val deg = rotValues[which]
                setCameraRotation(deg)
                webRTCManager?.sendCameraRotate(deg)
                Toast.makeText(this, "Rotation set to ${deg}°", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
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
            PTZ Digital Zoom: ${currentZoomRatio}x
            Orientation: ${currentCameraRotation}°
            Screen-Off Mode: ${if (cameraWakeLock?.isHeld == true) "Active (WakeLock held)" else "Inactive"}
            Instant Recording: ${if (isRecording) "RECORDING (${recordedFrameCount} frames)" else "Standby"}
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
