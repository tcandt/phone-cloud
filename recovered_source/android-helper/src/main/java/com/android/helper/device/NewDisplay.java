package com.android.helper.device;

/* JADX INFO: loaded from: classes.dex */
public final class NewDisplay {
    private int dpi;
    private Size size;

    public NewDisplay() {
    }

    public NewDisplay(Size size, int i) {
        this.size = size;
        this.dpi = i;
    }

    public Size getSize() {
        return this.size;
    }

    public int getDpi() {
        return this.dpi;
    }

    public boolean hasExplicitSize() {
        return this.size != null;
    }

    public boolean hasExplicitDpi() {
        return this.dpi != 0;
    }
}
