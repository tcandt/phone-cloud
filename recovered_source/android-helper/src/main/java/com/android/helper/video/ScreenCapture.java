package com.android.helper.video;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;
import com.android.helper.Options;
import com.android.helper.control.PositionMapper;
import com.android.helper.device.ConfigurationException;
import com.android.helper.device.DisplayInfo;
import com.android.helper.device.Orientation;
import com.android.helper.device.Size;
import com.android.helper.opengl.AffineOpenGLFilter;
import com.android.helper.opengl.OpenGLRunner;
import com.android.helper.util.AffineMatrix;
import com.android.helper.util.Ln;
import com.android.helper.util.LogUtils;
import com.android.helper.wrappers.ServiceManager;
import com.android.helper.wrappers.SurfaceControl;
import java.io.IOException;

/* JADX INFO: loaded from: classes.dex */
public class ScreenCapture extends SurfaceCapture {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    private final float angle;
    private Orientation captureOrientation;
    private Orientation.Lock captureOrientationLock;
    private final Rect crop;
    private IBinder display;
    private final int displayId;
    private DisplayInfo displayInfo;
    private final DisplaySizeMonitor displaySizeMonitor = new DisplaySizeMonitor();
    private OpenGLRunner glRunner;
    private int maxSize;
    private AffineMatrix transform;
    private final VirtualDisplayListener vdListener;
    private Size videoSize;
    private VirtualDisplay virtualDisplay;

    public ScreenCapture(VirtualDisplayListener virtualDisplayListener, Options options) {
        this.vdListener = virtualDisplayListener;
        this.displayId = options.getDisplayId();
        this.maxSize = options.getMaxSize();
        this.crop = options.getCrop();
        this.captureOrientationLock = options.getCaptureOrientationLock();
        this.captureOrientation = options.getCaptureOrientation();
        this.angle = options.getAngle();
    }

    @Override // com.android.helper.video.SurfaceCapture
    public void init() {
        this.displaySizeMonitor.start(this.displayId, this::invalidate);
    }

    @Override // com.android.helper.video.SurfaceCapture
    public void prepare() throws ConfigurationException {
        DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(this.displayId);
        this.displayInfo = displayInfo;
        if (displayInfo == null) {
            Ln.e("Display " + this.displayId + " not found\n" + LogUtils.buildDisplayListMessage());
            throw new ConfigurationException("Unknown display id: " + this.displayId);
        }
        if ((displayInfo.getFlags() & 1) == 0) {
            Ln.w("Display doesn't have FLAG_SUPPORTS_PROTECTED_BUFFERS flag, mirroring can be restricted");
        }
        Size size = this.displayInfo.getSize();
        this.displaySizeMonitor.setSessionDisplaySize(size);
        if (this.captureOrientationLock == Orientation.Lock.LockedInitial) {
            this.captureOrientationLock = Orientation.Lock.LockedValue;
            this.captureOrientation = Orientation.fromRotation(this.displayInfo.getRotation());
        }
        VideoFilter videoFilter = new VideoFilter(size);
        if (this.crop != null) {
            videoFilter.addCrop(this.crop, this.displayInfo.getRotation() % 2 != 0);
        }
        videoFilter.addOrientation(this.displayInfo.getRotation(), this.captureOrientationLock != Orientation.Lock.Unlocked, this.captureOrientation);
        videoFilter.addAngle(this.angle);
        this.transform = videoFilter.getInverseTransform();
        this.videoSize = videoFilter.getOutputSize().limit(this.maxSize).round8();
    }

    @Override // com.android.helper.video.SurfaceCapture
    public void start(Surface surface) throws IOException {
        Size size;
        PositionMapper positionMapperCreate;
        int displayId;
        IBinder iBinder = this.display;
        if (iBinder != null) {
            SurfaceControl.destroyDisplay(iBinder);
            this.display = null;
        }
        VirtualDisplay virtualDisplay = this.virtualDisplay;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            this.virtualDisplay = null;
        }
        if (this.transform != null) {
            size = this.displayInfo.getSize();
            OpenGLRunner openGLRunner = new OpenGLRunner(new AffineOpenGLFilter(this.transform));
            this.glRunner = openGLRunner;
            surface = openGLRunner.start(size, this.videoSize, surface);
        } else {
            size = this.videoSize;
        }
        Surface surface2 = surface;
        Size size2 = size;
        try {
            this.virtualDisplay = ServiceManager.getDisplayManager().createVirtualDisplay("scrcpy", size2.getWidth(), size2.getHeight(), this.displayId, surface2);
            Ln.d("Display: using DisplayManager API");
        } catch (Exception e) {
            try {
                this.display = createDisplay();
                Size size3 = this.displayInfo.getSize();
                setDisplaySurface(this.display, surface2, size3.toRect(), size2.toRect(), this.displayInfo.getLayerStack());
                Ln.d("Display: using SurfaceControl API");
            } catch (Exception e2) {
                Ln.e("Could not create display using DisplayManager", e);
                Ln.e("Could not create display using SurfaceControl", e2);
                throw new AssertionError("Could not create display");
            }
        }
        if (this.vdListener != null) {
            if (this.virtualDisplay == null || this.displayId == 0) {
                positionMapperCreate = PositionMapper.create(this.videoSize, this.transform, this.displayInfo.getSize());
                displayId = this.displayId;
            } else {
                positionMapperCreate = PositionMapper.create(this.videoSize, this.transform, size2);
                displayId = this.virtualDisplay.getDisplay().getDisplayId();
            }
            this.vdListener.onNewVirtualDisplay(displayId, positionMapperCreate);
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
        this.displaySizeMonitor.stopAndRelease();
        IBinder iBinder = this.display;
        if (iBinder != null) {
            SurfaceControl.destroyDisplay(iBinder);
            this.display = null;
        }
        VirtualDisplay virtualDisplay = this.virtualDisplay;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            this.virtualDisplay = null;
        }
    }

    @Override // com.android.helper.video.SurfaceCapture
    public Size getSize() {
        return this.videoSize;
    }

    @Override // com.android.helper.video.SurfaceCapture
    public boolean setMaxSize(int i) {
        this.maxSize = i;
        return true;
    }

    private static IBinder createDisplay() throws Exception {
        return SurfaceControl.createDisplay("monitor", Build.VERSION.SDK_INT < 30 || (Build.VERSION.SDK_INT == 30 && !"S".equals(Build.VERSION.CODENAME)));
    }

    private static void setDisplaySurface(IBinder iBinder, Surface surface, Rect rect, Rect rect2, int i) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(iBinder, surface);
            SurfaceControl.setDisplayProjection(iBinder, 0, rect, rect2);
            SurfaceControl.setDisplayLayerStack(iBinder, i);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    @Override // com.android.helper.video.SurfaceCapture
    public void requestInvalidate() {
        invalidate();
    }
}
