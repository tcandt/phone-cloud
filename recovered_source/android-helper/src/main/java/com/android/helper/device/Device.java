package com.android.helper.device;

import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import com.android.helper.FakeContext;
import com.android.helper.util.Ln;
import com.android.helper.wrappers.ActivityManager;
import com.android.helper.wrappers.ClipboardManager;
import com.android.helper.wrappers.DisplayControl;
import com.android.helper.wrappers.InputManager;
import com.android.helper.wrappers.ServiceManager;
import com.android.helper.wrappers.SurfaceControl;
import com.android.helper.wrappers.WindowManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/* JADX INFO: loaded from: classes.dex */
public final class Device {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    public static final int DISPLAY_ID_NONE = -1;
    public static final int INJECT_MODE_ASYNC = 0;
    public static final int INJECT_MODE_WAIT_FOR_FINISH = 2;
    public static final int INJECT_MODE_WAIT_FOR_RESULT = 1;
    public static final int POWER_MODE_NORMAL = 2;
    public static final int POWER_MODE_OFF = 0;
    private static final boolean USE_ANDROID_15_DISPLAY_POWER = false;

    private Device() {
    }

    public static String getDeviceName() {
        return Build.MODEL;
    }

    public static boolean supportsInputEvents(int i) {
        return i == 0 || Build.VERSION.SDK_INT >= 29;
    }

    public static boolean injectEvent(InputEvent inputEvent, int i, int i2) {
        if (!supportsInputEvents(i)) {
            throw new AssertionError("Could not inject input event if !supportsInputEvents()");
        }
        if (i == 0 || InputManager.setDisplayId(inputEvent, i)) {
            return ServiceManager.getInputManager().injectInputEvent(inputEvent, i2);
        }
        return false;
    }

    public static boolean injectKeyEvent(int i, int i2, int i3, int i4, int i5, int i6) {
        long jUptimeMillis = SystemClock.uptimeMillis();
        return injectEvent(new KeyEvent(jUptimeMillis, jUptimeMillis, i, i2, i3, i4, -1, 0, 0, 257), i5, i6);
    }

    public static boolean pressReleaseKeycode(int i, int i2, int i3) {
        return injectKeyEvent(0, i, 0, 0, i2, i3) && injectKeyEvent(1, i, 0, 0, i2, i3);
    }

    public static boolean isScreenOn(int i) {
        return ServiceManager.getPowerManager().isScreenOn(i);
    }

    public static void expandNotificationPanel() {
        ServiceManager.getStatusBarManager().expandNotificationsPanel();
    }

    public static void expandSettingsPanel() {
        ServiceManager.getStatusBarManager().expandSettingsPanel();
    }

    public static void collapsePanels() {
        ServiceManager.getStatusBarManager().collapsePanels();
    }

    public static String getClipboardText() {
        CharSequence text;
        ClipboardManager clipboardManager = ServiceManager.getClipboardManager();
        if (clipboardManager == null || (text = clipboardManager.getText()) == null) {
            return null;
        }
        return text.toString();
    }

    public static boolean setClipboardText(String str) {
        ClipboardManager clipboardManager = ServiceManager.getClipboardManager();
        if (clipboardManager == null) {
            return false;
        }
        String clipboardText = getClipboardText();
        if (clipboardText == null || !clipboardText.equals(str)) {
            return clipboardManager.setText(str);
        }
        return false;
    }

    public static boolean setDisplayPower(int i, boolean z) {
        IBinder physicalDisplayToken;
        boolean displayPowerMode = true;
        boolean z2 = Build.VERSION.SDK_INT >= 29;
        if (z2 && Build.VERSION.SDK_INT >= 34 && Build.BRAND.equalsIgnoreCase("honor") && SurfaceControl.hasGetBuildInDisplayMethod()) {
            z2 = false;
        }
        int i2 = z ? 2 : 0;
        if (z2) {
            boolean z3 = Build.VERSION.SDK_INT >= 34 && !SurfaceControl.hasGetPhysicalDisplayIdsMethod();
            long[] physicalDisplayIds = z3 ? DisplayControl.getPhysicalDisplayIds() : SurfaceControl.getPhysicalDisplayIds();
            if (physicalDisplayIds == null) {
                Ln.e("Could not get physical display ids");
                return false;
            }
            for (long j : physicalDisplayIds) {
                if (z3) {
                    physicalDisplayToken = DisplayControl.getPhysicalDisplayToken(j);
                } else {
                    physicalDisplayToken = SurfaceControl.getPhysicalDisplayToken(j);
                }
                displayPowerMode &= SurfaceControl.setDisplayPowerMode(physicalDisplayToken, i2);
            }
            return displayPowerMode;
        }
        IBinder builtInDisplay = SurfaceControl.getBuiltInDisplay();
        if (builtInDisplay == null) {
            Ln.e("Could not get built-in display");
            return false;
        }
        return SurfaceControl.setDisplayPowerMode(builtInDisplay, i2);
    }

    public static boolean powerOffScreen(int i) {
        if (isScreenOn(i)) {
            return pressReleaseKeycode(26, i, 0);
        }
        return true;
    }

    public static void rotateDevice(int i) {
        WindowManager windowManager = ServiceManager.getWindowManager();
        boolean zIsRotationFrozen = windowManager.isRotationFrozen(i);
        int currentRotation = (getCurrentRotation(i) & 1) ^ 1;
        Ln.i("Device rotation requested: ".concat(currentRotation == 0 ? "portrait" : "landscape"));
        windowManager.freezeRotation(i, currentRotation);
        if (zIsRotationFrozen) {
            return;
        }
        windowManager.thawRotation(i);
    }

    private static int getCurrentRotation(int i) {
        if (i == 0) {
            return ServiceManager.getWindowManager().getRotation();
        }
        return ServiceManager.getDisplayManager().getDisplayInfo(i).getRotation();
    }

    public static List<DeviceApp> listApps() {
        ArrayList arrayList = new ArrayList();
        PackageManager packageManager = FakeContext.get().getPackageManager();
        Iterator<ApplicationInfo> it = getLaunchableApps(packageManager).iterator();
        while (it.hasNext()) {
            arrayList.add(toApp(packageManager, it.next()));
        }
        return arrayList;
    }

    private static List<ApplicationInfo> getLaunchableApps(PackageManager packageManager) {
        ArrayList arrayList = new ArrayList();
        for (ApplicationInfo applicationInfo : packageManager.getInstalledApplications(128)) {
            if (applicationInfo.enabled && getLaunchIntent(packageManager, applicationInfo.packageName) != null) {
                arrayList.add(applicationInfo);
            }
        }
        return arrayList;
    }

    public static Intent getLaunchIntent(PackageManager packageManager, String str) {
        Intent launchIntentForPackage = packageManager.getLaunchIntentForPackage(str);
        return launchIntentForPackage != null ? launchIntentForPackage : packageManager.getLeanbackLaunchIntentForPackage(str);
    }

    private static DeviceApp toApp(PackageManager packageManager, ApplicationInfo applicationInfo) {
        return new DeviceApp(applicationInfo.packageName, packageManager.getApplicationLabel(applicationInfo).toString(), (applicationInfo.flags & 1) != 0);
    }

    public static DeviceApp findByPackageName(String str) {
        PackageManager packageManager = FakeContext.get().getPackageManager();
        for (ApplicationInfo applicationInfo : packageManager.getInstalledApplications(128)) {
            if (str.equals(applicationInfo.packageName)) {
                return toApp(packageManager, applicationInfo);
            }
        }
        return null;
    }

    public static List<DeviceApp> findByName(String str) {
        ArrayList arrayList = new ArrayList();
        String lowerCase = str.toLowerCase(Locale.getDefault());
        PackageManager packageManager = FakeContext.get().getPackageManager();
        for (ApplicationInfo applicationInfo : getLaunchableApps(packageManager)) {
            String string = packageManager.getApplicationLabel(applicationInfo).toString();
            if (string.toLowerCase(Locale.getDefault()).startsWith(lowerCase)) {
                arrayList.add(new DeviceApp(applicationInfo.packageName, string, (applicationInfo.flags & 1) != 0));
            }
        }
        return arrayList;
    }

    public static void startApp(String str, int i, boolean z) {
        Bundle bundle;
        Intent launchIntent = getLaunchIntent(FakeContext.get().getPackageManager(), str);
        if (launchIntent == null) {
            Ln.w("Cannot create launch intent for app " + str);
            return;
        }
        launchIntent.addFlags(268435456);
        if (Build.VERSION.SDK_INT >= 26) {
            ActivityOptions activityOptionsMakeBasic = ActivityOptions.makeBasic();
            activityOptionsMakeBasic.setLaunchDisplayId(i);
            bundle = activityOptionsMakeBasic.toBundle();
        } else {
            bundle = null;
        }
        ActivityManager activityManager = ServiceManager.getActivityManager();
        if (z) {
            activityManager.forceStopPackage(str);
        }
        activityManager.startActivity(launchIntent, bundle);
    }
}
