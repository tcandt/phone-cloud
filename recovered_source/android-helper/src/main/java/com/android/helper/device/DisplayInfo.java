package com.android.helper.device;

/* JADX INFO: loaded from: classes.dex */
public final class DisplayInfo {
    public static final int FLAG_SUPPORTS_PROTECTED_BUFFERS = 1;
    private final int displayId;
    private final int dpi;
    private final int flags;
    private final int layerStack;
    private final int rotation;
    private final Size size;
    private final String uniqueId;

    public DisplayInfo(int i, Size size, int i2, int i3, int i4, int i5, String str) {
        this.displayId = i;
        this.size = size;
        this.rotation = i2;
        this.layerStack = i3;
        this.flags = i4;
        this.dpi = i5;
        this.uniqueId = str;
    }

    public int getDisplayId() {
        return this.displayId;
    }

    public Size getSize() {
        return this.size;
    }

    public int getRotation() {
        return this.rotation;
    }

    public int getLayerStack() {
        return this.layerStack;
    }

    public int getFlags() {
        return this.flags;
    }

    public int getDpi() {
        return this.dpi;
    }

    public String getUniqueId() {
        return this.uniqueId;
    }
}
