package com.android.helper.wrappers;

import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.view.Display;
import android.view.Surface;
import com.android.helper.FakeContext;
import com.android.helper.device.DisplayInfo;
import com.android.helper.device.Size;
import com.android.helper.util.Command;
import com.android.helper.util.Ln;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* JADX INFO: loaded from: classes.dex */
public final class DisplayManager {
    public static final long EVENT_FLAG_DISPLAY_CHANGED = 4;
    private Method createVirtualDisplayMethod;
    private Method getDisplayInfoMethod;
    private final Object manager;
    private Method requestDisplayPowerMethod;

    public interface DisplayListener {
        void onDisplayChanged(int i);
    }

    public static final class DisplayListenerHandle {
        private final Object displayListenerProxy;

        private DisplayListenerHandle(Object obj) {
            this.displayListenerProxy = obj;
        }
    }

    static DisplayManager create() {
        try {
            return new DisplayManager(Class.forName("android.hardware.display.DisplayManagerGlobal").getDeclaredMethod("getInstance", null).invoke(null, null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private DisplayManager(Object obj) {
        this.manager = obj;
    }

    public static DisplayInfo parseDisplayInfo(String str, int i) {
        Matcher matcher = Pattern.compile("^    mOverrideDisplayInfo=DisplayInfo\\{\".*?, displayId " + i + ".*?(, FLAG_.*)?, real ([0-9]+) x ([0-9]+).*?, rotation ([0-9]+).*?, density ([0-9]+).*?, layerStack ([0-9]+)", 8).matcher(str);
        if (!matcher.find()) {
            return null;
        }
        int displayFlags = parseDisplayFlags(matcher.group(1));
        int i2 = Integer.parseInt(matcher.group(2));
        int i3 = Integer.parseInt(matcher.group(3));
        return new DisplayInfo(i, new Size(i2, i3), Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(6)), displayFlags, Integer.parseInt(matcher.group(5)), null);
    }

    private static DisplayInfo getDisplayInfoFromDumpsysDisplay(int i) {
        try {
            return parseDisplayInfo(Command.execReadOutput("dumpsys", "display"), i);
        } catch (Exception e) {
            Ln.e("Could not get display info from \"dumpsys display\" output", e);
            return null;
        }
    }

    private static int parseDisplayFlags(String str) {
        int i = 0;
        if (str == null) {
            return 0;
        }
        Matcher matcher = Pattern.compile("FLAG_[A-Z_]+").matcher(str);
        while (matcher.find()) {
            try {
                i |= Display.class.getDeclaredField(matcher.group()).getInt(null);
            } catch (ReflectiveOperationException unused) {
            }
        }
        return i;
    }

    private synchronized Method getGetDisplayInfoMethod() throws NoSuchMethodException {
        if (this.getDisplayInfoMethod == null) {
            this.getDisplayInfoMethod = this.manager.getClass().getMethod("getDisplayInfo", Integer.TYPE);
        }
        return this.getDisplayInfoMethod;
    }

    public DisplayInfo getDisplayInfo(int i) {
        String str;
        try {
            Object objInvoke = getGetDisplayInfoMethod().invoke(this.manager, Integer.valueOf(i));
            if (objInvoke == null) {
                return getDisplayInfoFromDumpsysDisplay(i);
            }
            Class<?> cls = objInvoke.getClass();
            int i2 = cls.getDeclaredField("logicalWidth").getInt(objInvoke);
            int i3 = cls.getDeclaredField("logicalHeight").getInt(objInvoke);
            int i4 = cls.getDeclaredField("rotation").getInt(objInvoke);
            int i5 = cls.getDeclaredField("layerStack").getInt(objInvoke);
            int i6 = cls.getDeclaredField("flags").getInt(objInvoke);
            int i7 = cls.getDeclaredField("logicalDensityDpi").getInt(objInvoke);
            try {
                str = (String) cls.getDeclaredField("uniqueId").get(objInvoke);
            } catch (NoSuchFieldException unused) {
                str = null;
            }
            return new DisplayInfo(i, new Size(i2, i3), i4, i5, i6, i7, str);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public int[] getDisplayIds() {
        try {
            return (int[]) this.manager.getClass().getMethod("getDisplayIds", null).invoke(this.manager, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Method getCreateVirtualDisplayMethod() throws NoSuchMethodException {
        if (this.createVirtualDisplayMethod == null) {
            Class cls = Integer.TYPE;
            this.createVirtualDisplayMethod = android.hardware.display.DisplayManager.class.getMethod("createVirtualDisplay", String.class, cls, cls, cls, Surface.class);
        }
        return this.createVirtualDisplayMethod;
    }

    public VirtualDisplay createVirtualDisplay(String str, int i, int i2, int i3, Surface surface) throws Exception {
        return (VirtualDisplay) getCreateVirtualDisplayMethod().invoke(null, str, Integer.valueOf(i), Integer.valueOf(i2), Integer.valueOf(i3), surface);
    }

    public VirtualDisplay createNewVirtualDisplay(String str, int i, int i2, int i3, Surface surface, int i4) throws Exception {
        Constructor declaredConstructor = android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class);
        declaredConstructor.setAccessible(true);
        return ((android.hardware.display.DisplayManager) declaredConstructor.newInstance(FakeContext.get())).createVirtualDisplay(str, i, i2, i3, surface, i4);
    }

    private Method getRequestDisplayPowerMethod() throws NoSuchMethodException {
        if (this.requestDisplayPowerMethod == null) {
            this.requestDisplayPowerMethod = this.manager.getClass().getMethod("requestDisplayPower", Integer.TYPE, Boolean.TYPE);
        }
        return this.requestDisplayPowerMethod;
    }

    public boolean requestDisplayPower(int i, boolean z) {
        try {
            return ((Boolean) getRequestDisplayPowerMethod().invoke(this.manager, Integer.valueOf(i), Boolean.valueOf(z))).booleanValue();
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return false;
        }
    }

    public DisplayListenerHandle registerDisplayListener(final DisplayListener displayListener, Handler handler) {
        try {
            Class<?> cls = Class.forName("android.hardware.display.DisplayManager$DisplayListener");
            Object objNewProxyInstance = Proxy.newProxyInstance(ClassLoader.getSystemClassLoader(), new Class[]{cls}, (proxy, m, args) -> {
                if ("onDisplayChanged".equals(m.getName())) {
                    displayListener.onDisplayChanged(((Integer) args[0]).intValue());
                }
                if ("toString".equals(m.getName())) {
                    return "DisplayListener";
                }
                return null;
            });
            try {
                try {
                    this.manager.getClass().getMethod("registerDisplayListener", cls, Handler.class, Long.TYPE, String.class).invoke(this.manager, objNewProxyInstance, handler, 4L, FakeContext.PACKAGE_NAME);
                } catch (NoSuchMethodException unused) {
                    this.manager.getClass().getMethod("registerDisplayListener", cls, Handler.class).invoke(this.manager, objNewProxyInstance, handler);
                }
            } catch (NoSuchMethodException unused2) {
                this.manager.getClass().getMethod("registerDisplayListener", cls, Handler.class, Long.TYPE).invoke(this.manager, objNewProxyInstance, handler, 4L);
            }
            return new DisplayListenerHandle(objNewProxyInstance);
        } catch (Exception e) {
            Ln.e("Could not register display listener", e);
            return null;
        }
    }

    public void unregisterDisplayListener(DisplayListenerHandle displayListenerHandle) {
        try {
            this.manager.getClass().getMethod("unregisterDisplayListener", Class.forName("android.hardware.display.DisplayManager$DisplayListener")).invoke(this.manager, displayListenerHandle.displayListenerProxy);
        } catch (Exception e) {
            Ln.e("Could not unregister display listener", e);
        }
    }
}
