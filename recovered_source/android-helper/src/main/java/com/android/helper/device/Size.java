package com.android.helper.device;

import android.graphics.Rect;
import java.util.Objects;

/* JADX INFO: loaded from: classes.dex */
public final class Size {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    private final int height;
    private final int width;

    public Size(int i, int i2) {
        this.width = i;
        this.height = i2;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getMax() {
        return Math.max(this.width, this.height);
    }

    public Size rotate() {
        return new Size(this.height, this.width);
    }

    public Size limit(int i) {
        if (i != 0) {
            int i2 = this.height;
            int i3 = this.width;
            boolean z = i2 > i3;
            int i4 = z ? i2 : i3;
            if (i4 > i) {
                if (z) {
                    i2 = i3;
                }
                int i5 = (i2 * i) / i4;
                int i6 = z ? i5 : i;
                if (!z) {
                    i = i5;
                }
                return new Size(i6, i);
            }
        }
        return this;
    }

    public Size round8() {
        if (isMultipleOf8()) {
            return this;
        }
        int i = this.height;
        int i2 = this.width;
        boolean z = i > i2;
        int i3 = z ? i : i2;
        if (z) {
            i = i2;
        }
        int i4 = i3 & (-8);
        int i5 = (i + 4) & (-8);
        if (i5 > i4) {
            i5 = i4;
        }
        int i6 = z ? i5 : i4;
        if (!z) {
            i4 = i5;
        }
        return new Size(i6, i4);
    }

    public boolean isMultipleOf8() {
        return (this.width & 7) == 0 && (this.height & 7) == 0;
    }

    public Rect toRect() {
        return new Rect(0, 0, this.width, this.height);
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj != null && getClass() == obj.getClass()) {
            Size size = (Size) obj;
            if (this.width == size.width && this.height == size.height) {
                return true;
            }
        }
        return false;
    }

    public int hashCode() {
        return Objects.hash(Integer.valueOf(this.width), Integer.valueOf(this.height));
    }

    public String toString() {
        return this.width + "x" + this.height;
    }
}
