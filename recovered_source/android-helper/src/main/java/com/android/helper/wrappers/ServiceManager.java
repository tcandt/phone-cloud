package com.android.helper.wrappers;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.IBinder;
import android.os.IInterface;
import com.android.helper.FakeContext;
import java.lang.reflect.Method;

/* JADX INFO: loaded from: classes.dex */
public final class ServiceManager {
    private static final Method GET_SERVICE_METHOD;
    private static ActivityManager activityManager;
    private static CameraManager cameraManager;
    private static ClipboardManager clipboardManager;
    private static DisplayManager displayManager;
    private static InputManager inputManager;
    private static PowerManager powerManager;
    private static StatusBarManager statusBarManager;
    private static WindowManager windowManager;

    static {
        try {
            GET_SERVICE_METHOD = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private ServiceManager() {
    }

    static IInterface getService(String str, String str2) {
        try {
            return (IInterface) Class.forName(str2 + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, (IBinder) GET_SERVICE_METHOD.invoke(null, str));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static WindowManager getWindowManager() {
        if (windowManager == null) {
            windowManager = WindowManager.create();
        }
        return windowManager;
    }

    public static synchronized DisplayManager getDisplayManager() {
        if (displayManager == null) {
            displayManager = DisplayManager.create();
        }
        return displayManager;
    }

    public static InputManager getInputManager() {
        if (inputManager == null) {
            inputManager = InputManager.create();
        }
        return inputManager;
    }

    public static PowerManager getPowerManager() {
        if (powerManager == null) {
            powerManager = PowerManager.create();
        }
        return powerManager;
    }

    public static StatusBarManager getStatusBarManager() {
        if (statusBarManager == null) {
            statusBarManager = StatusBarManager.create();
        }
        return statusBarManager;
    }

    public static ClipboardManager getClipboardManager() {
        if (clipboardManager == null) {
            clipboardManager = ClipboardManager.create();
        }
        return clipboardManager;
    }

    public static ActivityManager getActivityManager() {
        if (activityManager == null) {
            activityManager = ActivityManager.create();
        }
        return activityManager;
    }

    public static CameraManager getCameraManager() {
        if (cameraManager == null) {
            try {
                cameraManager = (CameraManager) CameraManager.class.getDeclaredConstructor(Context.class).newInstance(FakeContext.get());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return cameraManager;
    }
}
