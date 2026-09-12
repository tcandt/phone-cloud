package com.android.helper.video;

/* JADX INFO: loaded from: classes.dex */
public final class CameraAspectRatio {
    private static final float SENSOR = -1.0f;
    private float ar;

    private CameraAspectRatio(float f) {
        this.ar = f;
    }

    public static CameraAspectRatio fromFloat(float f) {
        if (f < 0.0f) {
            throw new IllegalArgumentException("Invalid aspect ratio: " + f);
        }
        return new CameraAspectRatio(f);
    }

    public static CameraAspectRatio fromFraction(int i, int i2) {
        if (i <= 0 || i2 <= 0) {
            throw new IllegalArgumentException("Invalid aspect ratio: " + i + ":" + i2);
        }
        return new CameraAspectRatio(i / i2);
    }

    public static CameraAspectRatio sensorAspectRatio() {
        return new CameraAspectRatio(SENSOR);
    }

    public boolean isSensor() {
        return this.ar == SENSOR;
    }

    public float getAspectRatio() {
        return this.ar;
    }
}
