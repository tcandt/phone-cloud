package com.android.helper;

import android.content.AttributionSource;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.AudioRecord;
import android.view.Surface;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/* JADX INFO: compiled from: D8$$SyntheticClass */
/* JADX INFO: loaded from: classes.dex */
public final /* synthetic */ class FakeContext$$ExternalSyntheticApiModelOutline0 {
    public static /* synthetic */ AttributionSource.Builder m(int i) {
        return new AttributionSource.Builder(i);
    }

    public static /* bridge */ /* synthetic */ CameraConstrainedHighSpeedCaptureSession m(Object obj) {
        return (CameraConstrainedHighSpeedCaptureSession) obj;
    }

    public static /* synthetic */ OutputConfiguration m(Surface surface) {
        return new OutputConfiguration(surface);
    }

    public static /* synthetic */ SessionConfiguration m(int i, List list, Executor executor, CameraCaptureSession.StateCallback stateCallback) {
        return new SessionConfiguration(i, list, executor, stateCallback);
    }

    /* JADX INFO: renamed from: m, reason: collision with other method in class */
    public static /* synthetic */ AudioRecord.Builder m2m() {
        return new AudioRecord.Builder();
    }

    /* JADX INFO: renamed from: m, reason: collision with other method in class */
    public static /* bridge */ /* synthetic */ Class m3m() {
        return AttributionSource.class;
    }

    /* JADX INFO: renamed from: m, reason: collision with other method in class */
    public static /* synthetic */ CompletableFuture m4m() {
        return new CompletableFuture();
    }

    /* JADX INFO: renamed from: m, reason: collision with other method in class */
    public static /* synthetic */ void m5m() {
    }
}
