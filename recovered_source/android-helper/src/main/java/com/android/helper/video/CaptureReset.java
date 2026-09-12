package com.android.helper.video;

import android.media.MediaCodec;
import java.util.concurrent.atomic.AtomicBoolean;

/* JADX INFO: loaded from: classes.dex */
public class CaptureReset implements SurfaceCapture.CaptureListener {
    private final AtomicBoolean reset = new AtomicBoolean();
    private MediaCodec runningMediaCodec;

    public boolean consumeReset() {
        return this.reset.getAndSet(false);
    }

    public synchronized void reset() {
        this.reset.set(true);
        MediaCodec mediaCodec = this.runningMediaCodec;
        if (mediaCodec != null) {
            try {
                mediaCodec.signalEndOfInputStream();
            } catch (IllegalStateException unused) {
            }
        }
    }

    public synchronized void setRunningMediaCodec(MediaCodec mediaCodec) {
        this.runningMediaCodec = mediaCodec;
    }

    @Override // com.android.helper.video.SurfaceCapture.CaptureListener
    public void onInvalidated() {
        reset();
    }
}
