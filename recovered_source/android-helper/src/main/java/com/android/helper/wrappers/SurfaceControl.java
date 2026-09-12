package com.android.helper.wrappers;

import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;
import com.android.helper.util.Ln;
import java.lang.reflect.Method;

/* JADX INFO: loaded from: classes.dex */
public final class SurfaceControl {
    private static final Class<?> CLASS;
    public static final int POWER_MODE_NORMAL = 2;
    public static final int POWER_MODE_OFF = 0;
    private static Method getBuiltInDisplayMethod;
    private static Method getPhysicalDisplayIdsMethod;
    private static Method getPhysicalDisplayTokenMethod;
    private static Method setDisplayPowerModeMethod;

    static {
        try {
            CLASS = Class.forName("android.view.SurfaceControl");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private SurfaceControl() {
    }

    public static void openTransaction() {
        try {
            CLASS.getMethod("openTransaction", null).invoke(null, null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void closeTransaction() {
        try {
            CLASS.getMethod("closeTransaction", null).invoke(null, null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void setDisplayProjection(IBinder iBinder, int i, Rect rect, Rect rect2) {
        try {
            CLASS.getMethod("setDisplayProjection", IBinder.class, Integer.TYPE, Rect.class, Rect.class).invoke(null, iBinder, Integer.valueOf(i), rect, rect2);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void setDisplayLayerStack(IBinder iBinder, int i) {
        try {
            CLASS.getMethod("setDisplayLayerStack", IBinder.class, Integer.TYPE).invoke(null, iBinder, Integer.valueOf(i));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void setDisplaySurface(IBinder iBinder, Surface surface) {
        try {
            CLASS.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(null, iBinder, surface);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static IBinder createDisplay(String str, boolean z) throws Exception {
        return (IBinder) CLASS.getMethod("createDisplay", String.class, Boolean.TYPE).invoke(null, str, Boolean.valueOf(z));
    }

    private static Method getGetBuiltInDisplayMethod() throws NoSuchMethodException {
        if (getBuiltInDisplayMethod == null) {
            if (Build.VERSION.SDK_INT < 29) {
                getBuiltInDisplayMethod = CLASS.getMethod("getBuiltInDisplay", Integer.TYPE);
            } else {
                getBuiltInDisplayMethod = CLASS.getMethod("getInternalDisplayToken", null);
            }
        }
        return getBuiltInDisplayMethod;
    }

    public static boolean hasGetBuildInDisplayMethod() {
        try {
            getGetBuiltInDisplayMethod();
            return true;
        } catch (NoSuchMethodException unused) {
            return false;
        }
    }

    public static IBinder getBuiltInDisplay() {
        try {
            Method getBuiltInDisplayMethod2 = getGetBuiltInDisplayMethod();
            return Build.VERSION.SDK_INT < 29 ? (IBinder) getBuiltInDisplayMethod2.invoke(null, 0) : (IBinder) getBuiltInDisplayMethod2.invoke(null, null);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return null;
        }
    }

    private static Method getGetPhysicalDisplayTokenMethod() throws NoSuchMethodException {
        if (getPhysicalDisplayTokenMethod == null) {
            getPhysicalDisplayTokenMethod = CLASS.getMethod("getPhysicalDisplayToken", Long.TYPE);
        }
        return getPhysicalDisplayTokenMethod;
    }

    public static IBinder getPhysicalDisplayToken(long j) {
        try {
            return (IBinder) getGetPhysicalDisplayTokenMethod().invoke(null, Long.valueOf(j));
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return null;
        }
    }

    private static Method getGetPhysicalDisplayIdsMethod() throws NoSuchMethodException {
        if (getPhysicalDisplayIdsMethod == null) {
            getPhysicalDisplayIdsMethod = CLASS.getMethod("getPhysicalDisplayIds", null);
        }
        return getPhysicalDisplayIdsMethod;
    }

    public static boolean hasGetPhysicalDisplayIdsMethod() {
        try {
            getGetPhysicalDisplayIdsMethod();
            return true;
        } catch (NoSuchMethodException unused) {
            return false;
        }
    }

    public static long[] getPhysicalDisplayIds() {
        try {
            return (long[]) getGetPhysicalDisplayIdsMethod().invoke(null, null);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return null;
        }
    }

    private static Method getSetDisplayPowerModeMethod() throws NoSuchMethodException {
        if (setDisplayPowerModeMethod == null) {
            setDisplayPowerModeMethod = CLASS.getMethod("setDisplayPowerMode", IBinder.class, Integer.TYPE);
        }
        return setDisplayPowerModeMethod;
    }

    public static boolean setDisplayPowerMode(IBinder iBinder, int i) {
        try {
            getSetDisplayPowerModeMethod().invoke(null, iBinder, Integer.valueOf(i));
            return true;
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return false;
        }
    }

    public static void destroyDisplay(IBinder iBinder) {
        try {
            CLASS.getMethod("destroyDisplay", IBinder.class).invoke(null, iBinder);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
