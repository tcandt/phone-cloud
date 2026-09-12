package com.android.helper.video;

import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.IDisplayWindowListener;
import com.android.helper.device.DisplayInfo;
import com.android.helper.device.Size;
import com.android.helper.util.Ln;
import com.android.helper.wrappers.DisplayManager;
import com.android.helper.wrappers.DisplayWindowListener;
import com.android.helper.wrappers.ServiceManager;

/* JADX INFO: loaded from: classes.dex */
public class DisplaySizeMonitor {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    private static final boolean USE_DEFAULT_METHOD;
    private int displayId = -1;
    private DisplayManager.DisplayListenerHandle displayListenerHandle;
    private IDisplayWindowListener displayWindowListener;
    private HandlerThread handlerThread;
    private Listener listener;
    private Size sessionDisplaySize;

    public interface Listener {
        void onDisplaySizeChanged();
    }

    static {
        USE_DEFAULT_METHOD = Build.VERSION.SDK_INT < 34;
    }

    public void start(final int i, Listener listener) {
        this.listener = listener;
        this.displayId = i;
        if (USE_DEFAULT_METHOD) {
            HandlerThread handlerThread = new HandlerThread("DisplayListener");
            this.handlerThread = handlerThread;
            handlerThread.start();
            this.displayListenerHandle = ServiceManager.getDisplayManager().registerDisplayListener(i2 -> m19lambda$start$0$comandroidhelpervideoDisplaySizeMonitor(i, i2), new Handler(this.handlerThread.getLooper()));
            return;
        }
        this.displayWindowListener = new DisplayWindowListener() { // from class: com.android.helper.video.DisplaySizeMonitor.1
            @Override // com.android.helper.wrappers.DisplayWindowListener, android.view.IDisplayWindowListener
            public void onDisplayConfigurationChanged(int i2, Configuration configuration) {
                if (Ln.isEnabled(Ln.Level.VERBOSE)) {
                    Ln.v("DisplaySizeMonitor: onDisplayConfigurationChanged(" + i2 + ")");
                }
                if (i2 == i) {
                    DisplaySizeMonitor.this.checkDisplaySizeChanged();
                }
            }
        };
        ServiceManager.getWindowManager().registerDisplayWindowListener(this.displayWindowListener);
    }

    /* JADX INFO: renamed from: lambda$start$0$com-android-helper-video-DisplaySizeMonitor, reason: not valid java name */
    /* synthetic */ void m19lambda$start$0$comandroidhelpervideoDisplaySizeMonitor(int i, int i2) {
        if (Ln.isEnabled(Ln.Level.VERBOSE)) {
            Ln.v("DisplaySizeMonitor: onDisplayChanged(" + i2 + ")");
        }
        if (i2 == i) {
            checkDisplaySizeChanged();
        }
    }

    public void stopAndRelease() {
        if (USE_DEFAULT_METHOD) {
            if (this.displayListenerHandle != null) {
                ServiceManager.getDisplayManager().unregisterDisplayListener(this.displayListenerHandle);
                this.displayListenerHandle = null;
            }
            HandlerThread handlerThread = this.handlerThread;
            if (handlerThread != null) {
                handlerThread.quitSafely();
                return;
            }
            return;
        }
        if (this.displayWindowListener != null) {
            ServiceManager.getWindowManager().unregisterDisplayWindowListener(this.displayWindowListener);
        }
    }

    private synchronized Size getSessionDisplaySize() {
        return this.sessionDisplaySize;
    }

    public synchronized void setSessionDisplaySize(Size size) {
        this.sessionDisplaySize = size;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkDisplaySizeChanged() {
        DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(this.displayId);
        if (displayInfo == null) {
            Ln.w("DisplayInfo for " + this.displayId + " cannot be retrieved");
            if (Ln.isEnabled(Ln.Level.VERBOSE)) {
                Ln.v("DisplaySizeMonitor: requestReset(): " + getSessionDisplaySize() + " -> (unknown)");
            }
            setSessionDisplaySize(null);
            this.listener.onDisplaySizeChanged();
            return;
        }
        Size size = displayInfo.getSize();
        Size sessionDisplaySize = getSessionDisplaySize();
        if (!size.equals(sessionDisplaySize)) {
            if (Ln.isEnabled(Ln.Level.VERBOSE)) {
                Ln.v("DisplaySizeMonitor: requestReset(): " + sessionDisplaySize + " -> " + size);
            }
            setSessionDisplaySize(size);
            this.listener.onDisplaySizeChanged();
            return;
        }
        if (Ln.isEnabled(Ln.Level.VERBOSE)) {
            Ln.v("DisplaySizeMonitor: Size not changed (" + size + "): do not requestReset()");
        }
    }
}
