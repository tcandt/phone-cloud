package com.android.helper.control;

import com.android.helper.device.Point;

/* JADX INFO: loaded from: classes.dex */
public class Pointer {
    private final long id;
    private final int localId;
    private Point point;
    private float pressure;
    private boolean up;

    public Pointer(long j, int i) {
        this.id = j;
        this.localId = i;
    }

    public long getId() {
        return this.id;
    }

    public int getLocalId() {
        return this.localId;
    }

    public Point getPoint() {
        return this.point;
    }

    public void setPoint(Point point) {
        this.point = point;
    }

    public float getPressure() {
        return this.pressure;
    }

    public void setPressure(float f) {
        this.pressure = f;
    }

    public boolean isUp() {
        return this.up;
    }

    public void setUp(boolean z) {
        this.up = z;
    }
}
