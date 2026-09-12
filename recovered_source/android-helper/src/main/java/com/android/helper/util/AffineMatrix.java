package com.android.helper.util;

import com.android.helper.device.Point;
import com.android.helper.device.Size;

/* JADX INFO: loaded from: classes.dex */
public class AffineMatrix {
    public static final AffineMatrix IDENTITY = new AffineMatrix(1.0d, 0.0d, 0.0d, 1.0d, 0.0d, 0.0d);
    private final double a;
    private final double b;
    private final double c;
    private final double d;
    private final double e;
    private final double f;

    public AffineMatrix(double d, double d2, double d3, double d4, double d5, double d6) {
        this.a = d;
        this.b = d2;
        this.c = d3;
        this.d = d4;
        this.e = d5;
        this.f = d6;
    }

    public String toString() {
        return "[" + this.a + ", " + this.c + ", " + this.e + "; " + this.b + ", " + this.d + ", " + this.f + "]";
    }

    public static AffineMatrix ndcFromPixels(Size size) {
        return new AffineMatrix(1.0d / ((double) size.getWidth()), 0.0d, 0.0d, (-1.0d) / ((double) size.getHeight()), 0.0d, 1.0d);
    }

    public static AffineMatrix ndcToPixels(Size size) {
        double width = size.getWidth();
        double height = size.getHeight();
        return new AffineMatrix(width, 0.0d, 0.0d, -height, 0.0d, height);
    }

    public Point apply(Point point) {
        double x = point.getX();
        double y = point.getY();
        return new Point((int) ((this.a * x) + (this.c * y) + this.e), (int) ((this.b * x) + (this.d * y) + this.f));
    }

    public AffineMatrix multiply(AffineMatrix affineMatrix) {
        if (affineMatrix == null) {
            return this;
        }
        double d = this.a;
        double d2 = affineMatrix.a;
        double d3 = this.c;
        double d4 = affineMatrix.b;
        double d5 = (d * d2) + (d3 * d4);
        double d6 = this.b;
        double d7 = this.d;
        double d8 = (d2 * d6) + (d4 * d7);
        double d9 = affineMatrix.c;
        double d10 = d * d9;
        double d11 = affineMatrix.d;
        double d12 = d10 + (d3 * d11);
        double d13 = (d9 * d6) + (d11 * d7);
        double d14 = affineMatrix.e;
        double d15 = affineMatrix.f;
        return new AffineMatrix(d5, d8, d12, d13, (d * d14) + (d3 * d15) + this.e, (d6 * d14) + (d7 * d15) + this.f);
    }

    public static AffineMatrix multiplyAll(AffineMatrix... affineMatrixArr) {
        AffineMatrix affineMatrixMultiply = null;
        for (AffineMatrix affineMatrix : affineMatrixArr) {
            affineMatrixMultiply = affineMatrixMultiply == null ? affineMatrix : affineMatrixMultiply.multiply(affineMatrix);
        }
        return affineMatrixMultiply;
    }

    public AffineMatrix invert() {
        double d = this.a;
        double d2 = this.d;
        double d3 = this.c;
        double d4 = this.b;
        double d5 = (d * d2) - (d3 * d4);
        if (d5 == 0.0d) {
            return null;
        }
        double d6 = (-d4) / d5;
        double d7 = (-d3) / d5;
        double d8 = d / d5;
        double d9 = this.f;
        double d10 = d3 * d9;
        double d11 = this.e;
        return new AffineMatrix(d2 / d5, d6, d7, d8, (d10 - (d2 * d11)) / d5, ((d11 * d4) - (d * d9)) / d5);
    }

    public AffineMatrix fromCenter() {
        return translate(0.5d, 0.5d).multiply(this).multiply(translate(-0.5d, -0.5d));
    }

    public AffineMatrix withAspectRatio(double d) {
        return scale(1.0d / d, 1.0d).multiply(this).multiply(scale(d, 1.0d));
    }

    public AffineMatrix withAspectRatio(Size size) {
        return withAspectRatio(((double) size.getWidth()) / ((double) size.getHeight()));
    }

    public static AffineMatrix translate(double d, double d2) {
        return new AffineMatrix(1.0d, 0.0d, 0.0d, 1.0d, d, d2);
    }

    public static AffineMatrix scale(double d, double d2) {
        return new AffineMatrix(d, 0.0d, 0.0d, d2, 0.0d, 0.0d);
    }

    public static AffineMatrix scale(Size size, Size size2) {
        return scale(((double) size2.getWidth()) / ((double) size.getWidth()), ((double) size2.getHeight()) / ((double) size.getHeight()));
    }

    public static AffineMatrix reframe(double d, double d2, double d3, double d4) {
        if (d3 == 0.0d || d4 == 0.0d) {
            throw new IllegalArgumentException("Cannot reframe to an empty area: " + d3 + "x" + d4);
        }
        return scale(1.0d / d3, 1.0d / d4).multiply(translate(-d, -d2));
    }

    public static AffineMatrix rotateOrtho(int i) {
        if (i == 0) {
            return IDENTITY;
        }
        if (i == 1) {
            return new AffineMatrix(0.0d, 1.0d, -1.0d, 0.0d, 1.0d, 0.0d);
        }
        if (i == 2) {
            return new AffineMatrix(-1.0d, 0.0d, 0.0d, -1.0d, 1.0d, 1.0d);
        }
        if (i == 3) {
            return new AffineMatrix(0.0d, -1.0d, 1.0d, 0.0d, 0.0d, 1.0d);
        }
        throw new IllegalArgumentException("Invalid rotation: " + i);
    }

    public static AffineMatrix hflip() {
        return new AffineMatrix(-1.0d, 0.0d, 0.0d, 1.0d, 1.0d, 0.0d);
    }

    public static AffineMatrix vflip() {
        return new AffineMatrix(1.0d, 0.0d, 0.0d, -1.0d, 0.0d, 1.0d);
    }

    public static AffineMatrix rotate(double d) {
        double radians = Math.toRadians(d);
        double dCos = Math.cos(radians);
        double dSin = Math.sin(radians);
        return new AffineMatrix(dCos, dSin, -dSin, dCos, 0.0d, 0.0d);
    }

    public void to4x4(float[] fArr) {
        fArr[0] = (float) this.a;
        fArr[1] = (float) this.b;
        fArr[2] = 0.0f;
        fArr[3] = 0.0f;
        fArr[4] = (float) this.c;
        fArr[5] = (float) this.d;
        fArr[6] = 0.0f;
        fArr[7] = 0.0f;
        fArr[8] = 0.0f;
        fArr[9] = 0.0f;
        fArr[10] = 1.0f;
        fArr[11] = 0.0f;
        fArr[12] = (float) this.e;
        fArr[13] = (float) this.f;
        fArr[14] = 0.0f;
        fArr[15] = 1.0f;
    }

    public float[] to4x4() {
        float[] fArr = new float[16];
        to4x4(fArr);
        return fArr;
    }
}
