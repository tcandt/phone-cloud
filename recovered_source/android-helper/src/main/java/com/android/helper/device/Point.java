package com.android.helper.device;

import java.util.Objects;

/* JADX INFO: loaded from: classes.dex */
public class Point {
    private final int x;
    private final int y;

    public Point(int i, int i2) {
        this.x = i;
        this.y = i2;
    }

    public int getX() {
        return this.x;
    }

    public int getY() {
        return this.y;
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj != null && getClass() == obj.getClass()) {
            Point point = (Point) obj;
            if (this.x == point.x && this.y == point.y) {
                return true;
            }
        }
        return false;
    }

    public int hashCode() {
        return Objects.hash(Integer.valueOf(this.x), Integer.valueOf(this.y));
    }

    public String toString() {
        return "Point{x=" + this.x + ", y=" + this.y + '}';
    }
}
