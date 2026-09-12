package com.android.helper.control;

import android.view.MotionEvent;
import com.android.helper.device.Point;
import java.util.ArrayList;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
public class PointersState {
    public static final int MAX_POINTERS = 10;
    private final List<Pointer> pointers = new ArrayList();

    private int indexOf(long j) {
        for (int i = 0; i < this.pointers.size(); i++) {
            if (this.pointers.get(i).getId() == j) {
                return i;
            }
        }
        return -1;
    }

    private boolean isLocalIdAvailable(int i) {
        for (int i2 = 0; i2 < this.pointers.size(); i2++) {
            if (this.pointers.get(i2).getLocalId() == i) {
                return false;
            }
        }
        return true;
    }

    private int nextUnusedLocalId() {
        for (int i = 0; i < 10; i++) {
            if (isLocalIdAvailable(i)) {
                return i;
            }
        }
        return -1;
    }

    public Pointer get(int i) {
        return this.pointers.get(i);
    }

    public int getPointerIndex(long j) {
        int iIndexOf = indexOf(j);
        if (iIndexOf != -1) {
            return iIndexOf;
        }
        if (this.pointers.size() >= 10) {
            return -1;
        }
        int iNextUnusedLocalId = nextUnusedLocalId();
        if (iNextUnusedLocalId == -1) {
            throw new AssertionError("pointers.size() < maxFingers implies that a local id is available");
        }
        this.pointers.add(new Pointer(j, iNextUnusedLocalId));
        return this.pointers.size() - 1;
    }

    public int update(MotionEvent.PointerProperties[] pointerPropertiesArr, MotionEvent.PointerCoords[] pointerCoordsArr) {
        int size = this.pointers.size();
        for (int i = 0; i < size; i++) {
            Pointer pointer = this.pointers.get(i);
            pointerPropertiesArr[i].id = pointer.getLocalId();
            Point point = pointer.getPoint();
            pointerCoordsArr[i].x = point.getX();
            pointerCoordsArr[i].y = point.getY();
            pointerCoordsArr[i].pressure = pointer.getPressure();
        }
        cleanUp();
        return size;
    }

    private void cleanUp() {
        for (int size = this.pointers.size() - 1; size >= 0; size--) {
            if (this.pointers.get(size).isUp()) {
                this.pointers.remove(size);
            }
        }
    }
}
