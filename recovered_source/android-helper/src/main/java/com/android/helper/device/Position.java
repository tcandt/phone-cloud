package com.android.helper.device;

import java.util.Objects;

/* JADX INFO: loaded from: classes.dex */
public class Position {
    private final Point point;
    private final Size screenSize;

    public Position(Point point, Size size) {
        this.point = point;
        this.screenSize = size;
    }

    public Position(int i, int i2, int i3, int i4) {
        this(new Point(i, i2), new Size(i3, i4));
    }

    public Point getPoint() {
        return this.point;
    }

    public Size getScreenSize() {
        return this.screenSize;
    }

    public Position rotate(int i) {
        if (i == 1) {
            return new Position(new Point(this.screenSize.getHeight() - this.point.getY(), this.point.getX()), this.screenSize.rotate());
        }
        if (i != 2) {
            return i != 3 ? this : new Position(new Point(this.point.getY(), this.screenSize.getWidth() - this.point.getX()), this.screenSize.rotate());
        }
        return new Position(new Point(this.screenSize.getWidth() - this.point.getX(), this.screenSize.getHeight() - this.point.getY()), this.screenSize);
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj != null && getClass() == obj.getClass()) {
            Position position = (Position) obj;
            if (Objects.equals(this.point, position.point) && Objects.equals(this.screenSize, position.screenSize)) {
                return true;
            }
        }
        return false;
    }

    public int hashCode() {
        return Objects.hash(this.point, this.screenSize);
    }

    public String toString() {
        return "Position{point=" + this.point + ", screenSize=" + this.screenSize + '}';
    }
}
