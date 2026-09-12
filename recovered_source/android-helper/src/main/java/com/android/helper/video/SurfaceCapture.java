package com.android.helper.video;

import android.view.Surface;
import com.android.helper.device.ConfigurationException;
import com.android.helper.device.Size;
import java.io.IOException;

/* JADX INFO: loaded from: classes.dex */
public abstract class SurfaceCapture {
    private CaptureListener listener;

    public interface CaptureListener {
        void onInvalidated();
    }

    public abstract Size getSize();

    protected abstract void init() throws ConfigurationException, IOException;

    public boolean isClosed() {
        return false;
    }

    public void prepare() throws ConfigurationException, IOException {
    }

    public abstract void release();

    public abstract void requestInvalidate();

    public abstract boolean setMaxSize(int i);

    public abstract void start(Surface surface) throws IOException;

    public void stop() {
    }

    protected void invalidate() {
        this.listener.onInvalidated();
    }

    public final void init(CaptureListener captureListener) throws ConfigurationException, IOException {
        this.listener = captureListener;
        init();
    }
}
