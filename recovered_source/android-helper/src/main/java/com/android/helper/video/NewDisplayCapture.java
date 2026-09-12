package com.android.helper.video;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.view.Surface;
import com.android.helper.Options;
import com.android.helper.control.PositionMapper;
import com.android.helper.device.DisplayInfo;
import com.android.helper.device.NewDisplay;
import com.android.helper.device.Orientation;
import com.android.helper.device.Size;
import com.android.helper.opengl.AffineOpenGLFilter;
import com.android.helper.opengl.OpenGLRunner;
import com.android.helper.util.AffineMatrix;
import com.android.helper.util.Ln;
import com.android.helper.wrappers.ServiceManager;
import java.io.IOException;

/* JADX INFO: loaded from: classes.dex */
public class NewDisplayCapture extends SurfaceCapture {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 4096;
    private static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 256;
    private static final int VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 32768;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 8;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 2048;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 16384;
    private static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 2;
    private static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1;
    private static final int VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 128;
    private static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 512;
    private static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 64;
    private static final int VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 8192;
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1024;
    private final float angle;
    private final Orientation captureOrientation;
    private final boolean captureOrientationLocked;
    private final Rect crop;
    private int displayImePolicy;
    private Size displaySize;
    private final DisplaySizeMonitor displaySizeMonitor = new DisplaySizeMonitor();
    private AffineMatrix displayTransform;
    private int dpi;
    private AffineMatrix eventTransform;
    private OpenGLRunner glRunner;
    private int mainDisplayDpi;
    private Size mainDisplaySize;
    private int maxSize;
    private final NewDisplay newDisplay;
    private Size physicalSize;
    private final boolean vdDestroyContent;
    private final VirtualDisplayListener vdListener;
    private final boolean vdSystemDecorations;
    private Size videoSize;
    private VirtualDisplay virtualDisplay;

    public NewDisplayCapture(VirtualDisplayListener virtualDisplayListener, Options options) {
        this.vdListener = virtualDisplayListener;
        this.newDisplay = options.getNewDisplay();
        this.maxSize = options.getMaxSize();
        this.displayImePolicy = options.getDisplayImePolicy();
        this.crop = options.getCrop();
        this.captureOrientationLocked = options.getCaptureOrientationLock() != Orientation.Lock.Unlocked;
        this.captureOrientation = options.getCaptureOrientation();
        this.angle = options.getAngle();
        this.vdDestroyContent = options.getVDDestroyContent();
        this.vdSystemDecorations = options.getVDSystemDecorations();
    }

    @Override // com.android.helper.video.SurfaceCapture
    protected void init() {
        this.displaySize = this.newDisplay.getSize();
        int dpi = this.newDisplay.getDpi();
        this.dpi = dpi;
        if (this.displaySize == null || dpi == 0) {
            DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(0);
            if (displayInfo != null) {
                this.mainDisplaySize = displayInfo.getSize();
                if (displayInfo.getRotation() % 2 != 0) {
                    this.mainDisplaySize = this.mainDisplaySize.rotate();
                }
                this.mainDisplayDpi = displayInfo.getDpi();
                return;
            }
            Ln.w("Main display not found, fallback to 1920x1080 240dpi");
            this.mainDisplaySize = new Size(1920, 1080);
            this.mainDisplayDpi = 240;
        }
    }

    @Override // com.android.helper.video.SurfaceCapture
    public void prepare() {
        int rotation;
        if (this.virtualDisplay == null) {
            if (!this.newDisplay.hasExplicitSize()) {
                this.displaySize = this.mainDisplaySize;
            }
            if (!this.newDisplay.hasExplicitDpi()) {
                this.dpi = scaleDpi(this.mainDisplaySize, this.mainDisplayDpi, this.displaySize);
            }
            Size size = this.displaySize;
            this.videoSize = size;
            this.displaySizeMonitor.setSessionDisplaySize(size);
            rotation = 0;
        } else {
            DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(this.virtualDisplay.getDisplay().getDisplayId());
            this.displaySize = displayInfo.getSize();
            this.dpi = displayInfo.getDpi();
            rotation = displayInfo.getRotation();
        }
        VideoFilter videoFilter = new VideoFilter(this.displaySize);
        Rect rect = this.crop;
        if (rect != null) {
            videoFilter.addCrop(rect, rotation % 2 != 0);
        }
        videoFilter.addOrientation(rotation, this.captureOrientationLocked, this.captureOrientation);
        videoFilter.addAngle(this.angle);
        Size outputSize = videoFilter.getOutputSize();
        if (!outputSize.isMultipleOf8() || (this.maxSize != 0 && outputSize.getMax() > this.maxSize)) {
            int i = this.maxSize;
            if (i != 0) {
                outputSize = outputSize.limit(i);
            }
            videoFilter.addResize(outputSize.round8());
        }
        this.eventTransform = videoFilter.getInverseTransform();
        this.videoSize = videoFilter.getOutputSize();
        if (rotation % 2 == 0) {
            this.physicalSize = this.displaySize;
        } else {
            this.physicalSize = this.displaySize.rotate();
        }
        VideoFilter videoFilter2 = new VideoFilter(this.physicalSize);
        videoFilter2.addRotation(rotation);
        this.displayTransform = AffineMatrix.multiplyAll(videoFilter2.getInverseTransform(), this.eventTransform);
    }

    public void startNew(Surface surface) {
        int i;
        try {
            int i2 = this.vdDestroyContent ? 459 : 203;
            if (this.vdSystemDecorations) {
                i2 |= VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
            }
            if (Build.VERSION.SDK_INT >= 33) {
                int i3 = i2 | 15360;
                if (Build.VERSION.SDK_INT >= 34) {
                    i2 |= 64512;
                    i = i2;
                } else {
                    i = i3;
                }
            } else {
                i = i2;
            }
            VirtualDisplay virtualDisplayCreateNewVirtualDisplay = ServiceManager.getDisplayManager().createNewVirtualDisplay("scrcpy", this.displaySize.getWidth(), this.displaySize.getHeight(), this.dpi, surface, i);
            this.virtualDisplay = virtualDisplayCreateNewVirtualDisplay;
            int displayId = virtualDisplayCreateNewVirtualDisplay.getDisplay().getDisplayId();
            Ln.i("New display: " + this.displaySize.getWidth() + "x" + this.displaySize.getHeight() + "/" + this.dpi + " (id=" + displayId + ")");
            if (this.displayImePolicy != -1) {
                ServiceManager.getWindowManager().setDisplayImePolicy(displayId, this.displayImePolicy);
            }
            this.displaySizeMonitor.start(displayId, this::invalidate);
        } catch (Exception e) {
            Ln.e("Could not create display", e);
            throw new AssertionError("Could not create display");
        }
    }

    @Override // com.android.helper.video.SurfaceCapture
    public void start(Surface surface) throws IOException {
        if (this.displayTransform != null) {
            OpenGLRunner openGLRunner = new OpenGLRunner(new AffineOpenGLFilter(this.displayTransform));
            this.glRunner = openGLRunner;
            surface = openGLRunner.start(this.physicalSize, this.videoSize, surface);
        }
        VirtualDisplay virtualDisplay = this.virtualDisplay;
        if (virtualDisplay == null) {
            startNew(surface);
        } else {
            virtualDisplay.setSurface(surface);
        }
        if (this.vdListener != null) {
            this.vdListener.onNewVirtualDisplay(this.virtualDisplay.getDisplay().getDisplayId(), PositionMapper.create(this.videoSize, this.eventTransform, this.displaySize));
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
        VirtualDisplay virtualDisplay = this.virtualDisplay;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            this.virtualDisplay = null;
        }
    }

    @Override // com.android.helper.video.SurfaceCapture
    public synchronized Size getSize() {
        return this.videoSize;
    }

    @Override // com.android.helper.video.SurfaceCapture
    public synchronized boolean setMaxSize(int i) {
        this.maxSize = i;
        return true;
    }

    private static int scaleDpi(Size size, int i, Size size2) {
        return (i * size2.getMax()) / size.getMax();
    }

    @Override // com.android.helper.video.SurfaceCapture
    public void requestInvalidate() {
        invalidate();
    }
}
