package com.android.helper.control;

import com.android.helper.device.Point;
import com.android.helper.device.Position;
import com.android.helper.device.Size;
import com.android.helper.util.AffineMatrix;

/* JADX INFO: loaded from: classes.dex */
public final class PositionMapper {
    private final Size videoSize;
    private final AffineMatrix videoToDeviceMatrix;

    public PositionMapper(Size size, AffineMatrix affineMatrix) {
        this.videoSize = size;
        this.videoToDeviceMatrix = affineMatrix;
    }

    public static PositionMapper create(Size size, AffineMatrix affineMatrix, Size size2) {
        if (!size.equals(size2) || affineMatrix != null) {
            affineMatrix = AffineMatrix.ndcToPixels(size2).multiply(affineMatrix).multiply(AffineMatrix.ndcFromPixels(size));
        }
        return new PositionMapper(size, affineMatrix);
    }

    public Size getVideoSize() {
        return this.videoSize;
    }

    public Point map(Position position) {
        if (!this.videoSize.equals(position.getScreenSize())) {
            return null;
        }
        Point point = position.getPoint();
        AffineMatrix affineMatrix = this.videoToDeviceMatrix;
        return affineMatrix != null ? affineMatrix.apply(point) : point;
    }
}
