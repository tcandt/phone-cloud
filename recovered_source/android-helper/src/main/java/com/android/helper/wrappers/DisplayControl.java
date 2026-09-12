package com.android.helper.wrappers;

import android.os.IBinder;
import android.system.Os;
import com.android.helper.util.Ln;
import java.lang.reflect.Method;

/* JADX INFO: loaded from: classes.dex */
public final class DisplayControl {
    private static final Class<?> CLASS;
    private static Method getPhysicalDisplayIdsMethod;
    private static Method getPhysicalDisplayTokenMethod;

    static {
        Class<?> clsLoadClass = null;
        try {
            clsLoadClass = ((ClassLoader) Class.forName("com.android.internal.os.ClassLoaderFactory").getDeclaredMethod("createClassLoader", String.class, String.class, String.class, ClassLoader.class, Integer.TYPE, Boolean.TYPE, String.class).invoke(null, Os.getenv("SYSTEMSERVERCLASSPATH"), null, null, ClassLoader.getSystemClassLoader(), 0, true, null)).loadClass("com.android.server.display.DisplayControl");
            Method declaredMethod = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            declaredMethod.setAccessible(true);
            declaredMethod.invoke(Runtime.getRuntime(), clsLoadClass, "android_servers");
        } catch (Throwable th) {
            Ln.e("Could not initialize DisplayControl", th);
        }
        CLASS = clsLoadClass;
    }

    private DisplayControl() {
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

    public static long[] getPhysicalDisplayIds() {
        try {
            return (long[]) getGetPhysicalDisplayIdsMethod().invoke(null, null);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return null;
        }
    }
}
