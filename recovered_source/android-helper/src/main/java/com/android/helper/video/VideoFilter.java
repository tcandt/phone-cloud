package com.android.helper.video;

import android.graphics.Rect;
import com.android.helper.device.Orientation;
import com.android.helper.device.Size;
import com.android.helper.util.AffineMatrix;

/* JADX INFO: loaded from: classes.dex */
public class VideoFilter {
    private Size size;
    private AffineMatrix transform;

    public VideoFilter(Size size) {
        this.size = size;
    }

    public Size getOutputSize() {
        return this.size;
    }

    public AffineMatrix getTransform() {
        return this.transform;
    }

    public AffineMatrix getInverseTransform() {
        AffineMatrix affineMatrix = this.transform;
        if (affineMatrix == null) {
            return null;
        }
        return affineMatrix.invert();
    }

    private static Rect transposeRect(Rect rect) {
        return new Rect(rect.top, rect.left, rect.bottom, rect.right);
    }

    public void addCrop(Rect rect, boolean z) {
        Rect rectTransposeRect = z ? transposeRect(rect) : rect;
        double width = this.size.getWidth();
        double height = this.size.getHeight();
        if (rectTransposeRect.left < 0 || rectTransposeRect.top < 0 || rectTransposeRect.right > width || rectTransposeRect.bottom > height) {
            throw new IllegalArgumentException("Crop " + rectTransposeRect + " exceeds the input area (" + this.size + ")");
        }
        this.transform = AffineMatrix.reframe(((double) rectTransposeRect.left) / width, 1.0d - (((double) rectTransposeRect.bottom) / height), ((double) rectTransposeRect.width()) / width, ((double) rectTransposeRect.height()) / height).multiply(this.transform);
        this.size = new Size(rectTransposeRect.width(), rectTransposeRect.height());
    }

    public void addRotation(int i) {
        if (i == 0) {
            return;
        }
        this.transform = AffineMatrix.rotateOrtho(i).multiply(this.transform);
        if (i % 2 != 0) {
            this.size = this.size.rotate();
        }
    }

    public void addOrientation(Orientation orientation) {
        if (orientation.isFlipped()) {
            this.transform = AffineMatrix.hflip().multiply(this.transform);
        }
        addRotation((4 - orientation.getRotation()) % 4);
    }

    public void addOrientation(int i, boolean z, Orientation orientation) {
        if (z) {
            addRotation((4 - i) % 4);
        }
        addOrientation(orientation);
    }

    public void addAngle(double d) {
        if (d == 0.0d) {
            return;
        }
        this.transform = AffineMatrix.rotate(-d).withAspectRatio(this.size).fromCenter().multiply(this.transform);
    }

    public void addResize(Size size) {
        if (this.size.equals(size)) {
            return;
        }
        if (this.transform == null) {
            this.transform = AffineMatrix.IDENTITY;
        }
        this.size = size;
    }
}
