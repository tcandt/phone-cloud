package com.android.helper.video;

import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.view.Surface;
import com.android.helper.FakeContext$$ExternalSyntheticApiModelOutline0;
import com.android.helper.Options;
import com.android.helper.device.ConfigurationException;
import com.android.helper.device.Orientation;
import com.android.helper.device.Size;
import com.android.helper.opengl.AffineOpenGLFilter;
import com.android.helper.opengl.OpenGLRunner;
import com.android.helper.util.AffineMatrix;
import com.android.helper.util.HandlerExecutor;
import com.android.helper.util.Ln;
import com.android.helper.util.LogUtils;
import com.android.helper.wrappers.ServiceManager;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;

/* JADX INFO: loaded from: classes.dex */
public class CameraCapture extends SurfaceCapture {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    public static final float[] VFLIP_MATRIX = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f};
    private final float angle;
    private final CameraAspectRatio aspectRatio;
    private CameraDevice cameraDevice;
    private Executor cameraExecutor;
    private final CameraFacing cameraFacing;
    private Handler cameraHandler;
    private String cameraId;
    private HandlerThread cameraThread;
    private final float cameraZoom;
    private final Orientation captureOrientation;
    private Size captureSize;
    private final Rect crop;
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final String explicitCameraId;
    private final Size explicitSize;
    private final int fps;
    private OpenGLRunner glRunner;
    private final boolean highSpeed;
    private int maxSize;
    private AffineMatrix transform;
    private Size videoSize;

    @Override // com.android.helper.video.SurfaceCapture
    public void requestInvalidate() {
    }

    public CameraCapture(Options options) {
        this.explicitCameraId = options.getCameraId();
        this.cameraFacing = options.getCameraFacing();
        this.explicitSize = options.getCameraSize();
        this.maxSize = options.getMaxSize();
        this.aspectRatio = options.getCameraAspectRatio();
        this.fps = options.getCameraFps();
        this.highSpeed = options.getCameraHighSpeed();
        this.crop = options.getCrop();
        this.captureOrientation = options.getCaptureOrientation();
        this.angle = options.getAngle();
        this.cameraZoom = options.getCameraZoom();
    }

    @Override // com.android.helper.video.SurfaceCapture
    protected void init() throws ConfigurationException, IOException {
        HandlerThread handlerThread = new HandlerThread("camera");
        this.cameraThread = handlerThread;
        handlerThread.start();
        this.cameraHandler = new Handler(this.cameraThread.getLooper());
        this.cameraExecutor = new HandlerExecutor(this.cameraHandler);
        try {
            String strSelectCamera = selectCamera(this.explicitCameraId, this.cameraFacing);
            this.cameraId = strSelectCamera;
            if (strSelectCamera == null) {
                throw new ConfigurationException("No matching camera found");
            }
            Ln.i("Using camera '" + this.cameraId + "'");
            this.cameraDevice = openCamera(this.cameraId);
        } catch (CameraAccessException | InterruptedException e) {
            throw new IOException(e);
        }
    }

    @Override // com.android.helper.video.SurfaceCapture
    public void prepare() throws IOException {
        try {
            Size sizeSelectSize = selectSize(this.cameraId, this.explicitSize, this.maxSize, this.aspectRatio, this.highSpeed);
            this.captureSize = sizeSelectSize;
            if (sizeSelectSize == null) {
                throw new IOException("Could not select camera size");
            }
            VideoFilter videoFilter = new VideoFilter(this.captureSize);
            Rect rect = this.crop;
            if (rect != null) {
                videoFilter.addCrop(rect, false);
            }
            if (this.captureOrientation != Orientation.Orient0) {
                videoFilter.addOrientation(this.captureOrientation);
            } else {
                try {
                    CameraCharacteristics cameraCharacteristics = ServiceManager.getCameraManager().getCameraCharacteristics(this.cameraId);
                    Integer num = (Integer) cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (num != null) {
                        if (num.intValue() == 90) {
                            videoFilter.addOrientation(Orientation.Orient90);
                        } else if (num.intValue() == 270) {
                            videoFilter.addOrientation(Orientation.Orient270);
                        } else if (num.intValue() == 180) {
                            videoFilter.addOrientation(Orientation.Orient180);
                        }
                    }
                } catch (Exception e) {
                    Ln.w("Could not apply auto sensor orientation: " + e.getMessage());
                }
            }
            videoFilter.addAngle(this.angle);
            this.transform = videoFilter.getInverseTransform();
            this.videoSize = videoFilter.getOutputSize().limit(this.maxSize).round8();
        } catch (CameraAccessException e2) {
            throw new IOException(e2);
        }
    }

    private static String selectCamera(String str, CameraFacing cameraFacing) throws ConfigurationException, CameraAccessException {
        CameraManager cameraManager = ServiceManager.getCameraManager();
        String[] cameraIdList = cameraManager.getCameraIdList();
        if (str == null) {
            if (cameraFacing == null) {
                if (cameraIdList.length > 0) {
                    return cameraIdList[0];
                }
                return null;
            }
            for (String str2 : cameraIdList) {
                if (cameraFacing.value() == ((Integer) cameraManager.getCameraCharacteristics(str2).get(CameraCharacteristics.LENS_FACING)).intValue()) {
                    return str2;
                }
            }
            return null;
        }
        if (Arrays.asList(cameraIdList).contains(str)) {
            return str;
        }
        for (String str3 : cameraIdList) {
            try {
                Set physicalCameraIds = cameraManager.getCameraCharacteristics(str3).getPhysicalCameraIds();
                if (physicalCameraIds != null && physicalCameraIds.contains(str)) {
                    Ln.i("Explicit camera ID " + str + " is a physical sub-camera of logical " + str3 + ", using parent logical camera");
                    return str3;
                }
            } catch (Exception unused) {
            }
        }
        Ln.e("Camera with id " + str + " not found\n" + LogUtils.buildCameraListMessage(false));
        throw new ConfigurationException("Camera id not found");
    }

    private static Size selectSize(String str, Size size, final int i, CameraAspectRatio cameraAspectRatio, boolean z) throws CameraAccessException {
        if (size != null) {
            return size;
        }
        CameraCharacteristics cameraCharacteristics = ServiceManager.getCameraManager().getCameraCharacteristics(str);
        StreamConfigurationMap streamConfigurationMap = (StreamConfigurationMap) cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size[] highSpeedVideoSizes = z ? streamConfigurationMap.getHighSpeedVideoSizes() : streamConfigurationMap.getOutputSizes(MediaCodec.class);
        if (highSpeedVideoSizes == null) {
            return null;
        }
        Stream stream = Arrays.stream(highSpeedVideoSizes);
        if (i > 0) {
            stream = stream.filter(new Predicate() { // from class: com.android.helper.video.CameraCapture$$ExternalSyntheticLambda16
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return CameraCapture.lambda$selectSize$0(i, (android.util.Size) obj);
                }
            });
        }
        final Float fResolveAspectRatio = resolveAspectRatio(cameraAspectRatio, cameraCharacteristics);
        if (fResolveAspectRatio != null) {
            stream = stream.filter(new Predicate() { // from class: com.android.helper.video.CameraCapture$$ExternalSyntheticLambda17
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return CameraCapture.lambda$selectSize$1(fResolveAspectRatio, (android.util.Size) obj);
                }
            });
        }
        Optional optionalMax = stream.max(new Comparator() { // from class: com.android.helper.video.CameraCapture$$ExternalSyntheticLambda18
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                return CameraCapture.lambda$selectSize$2(fResolveAspectRatio, (android.util.Size) obj, (android.util.Size) obj2);
            }
        });
        if (!optionalMax.isPresent()) {
            return null;
        }
        android.util.Size size2 = (android.util.Size) optionalMax.get();
        return new Size(size2.getWidth(), size2.getHeight());
    }

    static /* synthetic */ boolean lambda$selectSize$0(int i, android.util.Size size) {
        return size.getWidth() <= i && size.getHeight() <= i;
    }

    static /* synthetic */ boolean lambda$selectSize$1(Float f, android.util.Size size) {
        float width = (size.getWidth() / size.getHeight()) / f.floatValue();
        return width >= 0.9f && width <= 1.1f;
    }

    static /* synthetic */ int lambda$selectSize$2(Float f, android.util.Size size, android.util.Size size2) {
        int iCompare = Integer.compare(size.getWidth(), size2.getWidth());
        if (iCompare != 0) {
            return iCompare;
        }
        if (f != null) {
            int iCompare2 = Float.compare(Math.abs(1.0f - ((size2.getWidth() / size2.getHeight()) / f.floatValue())), Math.abs(1.0f - ((size.getWidth() / size.getHeight()) / f.floatValue())));
            if (iCompare2 != 0) {
                return iCompare2;
            }
        }
        return Integer.compare(size.getHeight(), size2.getHeight());
    }

    private static Float resolveAspectRatio(CameraAspectRatio cameraAspectRatio, CameraCharacteristics cameraCharacteristics) {
        if (cameraAspectRatio == null) {
            return null;
        }
        if (cameraAspectRatio.isSensor()) {
            Rect rect = (Rect) cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            return Float.valueOf(rect.width() / rect.height());
        }
        return Float.valueOf(cameraAspectRatio.getAspectRatio());
    }

    @Override // com.android.helper.video.SurfaceCapture
    public void start(Surface surface) throws IOException {
        if (this.transform != null) {
            OpenGLRunner openGLRunner = new OpenGLRunner(new AffineOpenGLFilter(this.transform), VFLIP_MATRIX);
            this.glRunner = openGLRunner;
            surface = openGLRunner.start(this.captureSize, this.videoSize, surface);
        }
        try {
            setRepeatingRequest(createCaptureSession(this.cameraDevice, surface), createCaptureRequest(surface));
        } catch (CameraAccessException | InterruptedException e) {
            stop();
            throw new IOException(e);
        }
    }

    @Override // com.android.helper.video.SurfaceCapture
    public void stop() {
        OpenGLRunner openGLRunner = this.glRunner;
        if (openGLRunner != null) {
            openGLRunner.stopAndRelease();
            this.glRunner = null;
        }
    }

    @Override // com.android.helper.video.SurfaceCapture
    public void release() {
        CameraDevice cameraDevice = this.cameraDevice;
        if (cameraDevice != null) {
            cameraDevice.close();
        }
        HandlerThread handlerThread = this.cameraThread;
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
    }

    @Override // com.android.helper.video.SurfaceCapture
    public Size getSize() {
        return this.videoSize;
    }

    @Override // com.android.helper.video.SurfaceCapture
    public boolean setMaxSize(int i) {
        if (this.explicitSize != null) {
            return false;
        }
        this.maxSize = i;
        return true;
    }

    private CameraDevice openCamera(String str) throws InterruptedException, CameraAccessException {
        final CompletableFuture completableFutureM4m = FakeContext$$ExternalSyntheticApiModelOutline0.m4m();
        ServiceManager.getCameraManager().openCamera(str, new CameraDevice.StateCallback() { // from class: com.android.helper.video.CameraCapture.1
            @Override // android.hardware.camera2.CameraDevice.StateCallback
            public void onOpened(CameraDevice cameraDevice) {
                Ln.d("Camera opened successfully");
                completableFutureM4m.complete(cameraDevice);
            }

            @Override // android.hardware.camera2.CameraDevice.StateCallback
            public void onDisconnected(CameraDevice cameraDevice) {
                Ln.w("Camera disconnected");
                CameraCapture.this.disconnected.set(true);
                CameraCapture.this.invalidate();
            }

            @Override // android.hardware.camera2.CameraDevice.StateCallback
            public void onError(CameraDevice cameraDevice, int i) {
                int i2 = 1;
                if (i == 1) {
                    i2 = 4;
                } else if (i == 2) {
                    i2 = 5;
                } else if (i != 3) {
                    i2 = 3;
                }
                completableFutureM4m.completeExceptionally(new CameraAccessException(i2));
            }
        }, this.cameraHandler);
        try {
            return (CameraDevice) completableFutureM4m.get();
        } catch (ExecutionException e) {
            throw ((CameraAccessException) e.getCause());
        }
    }

    private CameraCaptureSession createCaptureSession(CameraDevice cameraDevice, Surface surface) throws InterruptedException, CameraAccessException {
        final CompletableFuture completableFutureM4m = FakeContext$$ExternalSyntheticApiModelOutline0.m4m();
        List listAsList = Arrays.asList(FakeContext$$ExternalSyntheticApiModelOutline0.m(surface));
        boolean z = this.highSpeed;
        FakeContext$$ExternalSyntheticApiModelOutline0.m5m();
        cameraDevice.createCaptureSession(FakeContext$$ExternalSyntheticApiModelOutline0.m(z ? 1 : 0, listAsList, this.cameraExecutor, new CameraCaptureSession.StateCallback() { // from class: com.android.helper.video.CameraCapture.2
            @Override // android.hardware.camera2.CameraCaptureSession.StateCallback
            public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                completableFutureM4m.complete(cameraCaptureSession);
            }

            @Override // android.hardware.camera2.CameraCaptureSession.StateCallback
            public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                completableFutureM4m.completeExceptionally(new CameraAccessException(3));
            }
        }));
        try {
            return (CameraCaptureSession) completableFutureM4m.get();
        } catch (ExecutionException e) {
            throw ((CameraAccessException) e.getCause());
        }
    }

    private CaptureRequest createCaptureRequest(Surface surface) throws CameraAccessException {
        CaptureRequest.Builder builderCreateCaptureRequest = this.cameraDevice.createCaptureRequest(3);
        builderCreateCaptureRequest.addTarget(surface);
        if (this.fps > 0) {
            builderCreateCaptureRequest.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range(Integer.valueOf(this.fps), Integer.valueOf(this.fps)));
        }
        if (Build.VERSION.SDK_INT >= 30 && this.cameraZoom > 0.0f) {
            try {
                builderCreateCaptureRequest.set(CaptureRequest.CONTROL_ZOOM_RATIO, Float.valueOf(this.cameraZoom));
                Ln.i("Camera hardware zoom ratio applied: " + this.cameraZoom + "x");
            } catch (Exception e) {
                Ln.w("Failed to set CONTROL_ZOOM_RATIO: " + e.getMessage());
            }
        }
        return builderCreateCaptureRequest.build();
    }

    private void setRepeatingRequest(CameraCaptureSession cameraCaptureSession, CaptureRequest captureRequest) throws InterruptedException, CameraAccessException {
        CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() { // from class: com.android.helper.video.CameraCapture.3
            @Override // android.hardware.camera2.CameraCaptureSession.CaptureCallback
            public void onCaptureStarted(CameraCaptureSession cameraCaptureSession2, CaptureRequest captureRequest2, long j, long j2) {
            }

            @Override // android.hardware.camera2.CameraCaptureSession.CaptureCallback
            public void onCaptureFailed(CameraCaptureSession cameraCaptureSession2, CaptureRequest captureRequest2, CaptureFailure captureFailure) {
                Ln.w("Camera capture failed: frame " + captureFailure.getFrameNumber());
            }
        };
        if (this.highSpeed) {
            CameraConstrainedHighSpeedCaptureSession cameraConstrainedHighSpeedCaptureSessionM = FakeContext$$ExternalSyntheticApiModelOutline0.m(cameraCaptureSession);
            cameraConstrainedHighSpeedCaptureSessionM.setRepeatingBurst(cameraConstrainedHighSpeedCaptureSessionM.createHighSpeedRequestList(captureRequest), captureCallback, this.cameraHandler);
        } else {
            cameraCaptureSession.setRepeatingRequest(captureRequest, captureCallback, this.cameraHandler);
        }
    }

    @Override // com.android.helper.video.SurfaceCapture
    public boolean isClosed() {
        return this.disconnected.get();
    }
}
