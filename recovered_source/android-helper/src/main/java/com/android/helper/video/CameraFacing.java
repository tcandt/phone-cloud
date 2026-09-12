package com.android.helper.video;

/* JADX INFO: loaded from: classes.dex */
public enum CameraFacing {
    FRONT("front", 0),
    BACK("back", 1),
    EXTERNAL("external", 2);

    private final String name;
    private final int value;

    CameraFacing(String str, int i) {
        this.name = str;
        this.value = i;
    }

    int value() {
        return this.value;
    }

    public static CameraFacing findByName(String str) {
        for (CameraFacing cameraFacing : values()) {
            if (str.equals(cameraFacing.name)) {
                return cameraFacing;
            }
        }
        return null;
    }
}
