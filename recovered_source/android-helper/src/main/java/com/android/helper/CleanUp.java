package com.android.helper;

import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import com.android.helper.device.Device;
import com.android.helper.util.Ln;
import com.android.helper.util.Settings;
import com.android.helper.util.SettingsException;
import com.android.helper.wrappers.ServiceManager;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/* JADX INFO: loaded from: classes.dex */
public final class CleanUp {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    private static final int PENDING_CHANGE_DISPLAY_POWER = 1;
    private boolean interrupted;
    private int pendingChanges;
    private boolean pendingRestoreDisplayPower;
    private Thread thread;

    private CleanUp(final Options options) {
        Thread thread = new Thread(() -> m0lambda$new$0$comandroidhelperCleanUp(options), "cleanup");
        this.thread = thread;
        thread.start();
    }

    public static CleanUp start(Options options) {
        return new CleanUp(options);
    }

    public synchronized void interrupt() {
        this.interrupted = true;
        notify();
    }

    public void join() throws InterruptedException {
        this.thread.join();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX INFO: renamed from: runCleanUp, reason: merged with bridge method [inline-methods] */
    public void m0lambda$new$0$comandroidhelperCleanUp(Options options) {
        boolean z;
        int i = -1;
        int i2;
        int i3 = -1;
        int i4;
        int i5;
        int displayImePolicy;
        int displayImePolicy2;
        if (options.getShowTouches()) {
            try {
                z = !"1".equals(Settings.getAndPutValue("system", "show_touches", "1"));
            } catch (SettingsException e) {
                Ln.e("Could not change \"show_touches\"", e);
                z = false;
            }
        } else {
            z = false;
        }
        if (options.getStayAwake()) {
            try {
                try {
                    i = Integer.parseInt(Settings.getAndPutValue("global", "stay_on_while_plugged_in", String.valueOf(7)));
                    if (i == 7) {
                        i = -1;
                    }
                } catch (NumberFormatException unused) {
                }
                i2 = i;
            } catch (SettingsException e2) {
                Ln.e("Could not change \"stay_on_while_plugged_in\"", e2);
                i2 = -1;
            }
        } else {
            i2 = -1;
        }
        int screenOffTimeout = options.getScreenOffTimeout();
        if (screenOffTimeout != -1) {
            try {
                try {
                    i3 = Integer.parseInt(Settings.getAndPutValue("system", "screen_off_timeout", String.valueOf(screenOffTimeout)));
                    if (i3 == screenOffTimeout) {
                        i3 = -1;
                    }
                } catch (NumberFormatException unused2) {
                }
                i4 = i3;
            } catch (SettingsException e3) {
                Ln.e("Could not change \"screen_off_timeout\"", e3);
                i4 = -1;
            }
        } else {
            i4 = -1;
        }
        int displayId = options.getDisplayId();
        if (displayId <= 0 || (displayImePolicy = options.getDisplayImePolicy()) == -1 || (displayImePolicy2 = ServiceManager.getWindowManager().getDisplayImePolicy(displayId)) == displayImePolicy) {
            i5 = -1;
        } else {
            ServiceManager.getWindowManager().setDisplayImePolicy(displayId, displayImePolicy);
            i5 = displayImePolicy2;
        }
        try {
            run(displayId, i2, z, options.getPowerOffScreenOnClose(), i4, i5);
        } catch (IOException e4) {
            Ln.e("Clean up I/O exception", e4);
        }
    }

    private void run(int i, int i2, boolean z, boolean z2, int i3, int i4) throws IOException {
        boolean z3;
        int i5;
        boolean z4;
        ProcessBuilder processBuilder = new ProcessBuilder("app_process", "/", CleanUp.class.getName(), String.valueOf(i), String.valueOf(i2), String.valueOf(z), String.valueOf(z2), String.valueOf(i3), String.valueOf(i4));
        processBuilder.environment().put("CLASSPATH", CoreService.SERVER_PATH);
        OutputStream outputStream = processBuilder.start().getOutputStream();
        while (true) {
            synchronized (this) {
                while (true) {
                    z3 = this.interrupted;
                    if (z3 || this.pendingChanges != 0) {
                        break;
                    }
                    try {
                        wait();
                    } catch (InterruptedException unused) {
                        throw new AssertionError("Clean up thread MUST NOT be interrupted");
                    }
                }
                if (z3) {
                    return;
                }
                i5 = this.pendingChanges;
                z4 = this.pendingRestoreDisplayPower;
                this.pendingChanges = 0;
            }
            if ((i5 & 1) != 0) {
                outputStream.write(z4 ? 1 : 0);
                outputStream.flush();
            }
        }
    }

    public synchronized void setRestoreDisplayPower(boolean z) {
        this.pendingRestoreDisplayPower = z;
        this.pendingChanges |= 1;
        notify();
    }

    public static void unlinkSelf() {
        try {
            new File(CoreService.SERVER_PATH).delete();
        } catch (Exception e) {
            Ln.e("Could not unlink server", e);
        }
    }

    private static void prepareMainLooper() {
        Looper.prepareMainLooper();
    }

    public static void main(String... strArr) {
        boolean z = false;
        try {
            Os.setsid();
            loop0: while (true) {
                while (true) {
                    try {
                        int i = System.in.read();
                        if (i == -1) {
                            break loop0;
                        } else {
                            z = i != 0;
                        }
                    } catch (IOException unused) {
                    }
                }
            }
        } catch (ErrnoException e) {
            Ln.e("setsid() failed", e);
        }
        unlinkSelf();
        prepareMainLooper();
        Workarounds.apply();
        int i2 = Integer.parseInt(strArr[0]);
        int i3 = Integer.parseInt(strArr[1]);
        boolean z2 = Boolean.parseBoolean(strArr[2]);
        boolean z3 = Boolean.parseBoolean(strArr[3]);
        int i4 = Integer.parseInt(strArr[4]);
        int i5 = Integer.parseInt(strArr[5]);
        Ln.i("Cleaning up");
        if (z2) {
            Ln.i("Disabling \"show touches\"");
            try {
                Settings.putValue("system", "show_touches", "0");
            } catch (SettingsException e2) {
                Ln.e("Could not restore \"show_touches\"", e2);
            }
        }
        if (i3 != -1) {
            Ln.i("Restoring \"stay awake\"");
            try {
                Settings.putValue("global", "stay_on_while_plugged_in", String.valueOf(i3));
            } catch (SettingsException e3) {
                Ln.e("Could not restore \"stay_on_while_plugged_in\"", e3);
            }
        }
        if (i4 != -1) {
            Ln.i("Restoring \"screen off timeout\"");
            try {
                Settings.putValue("system", "screen_off_timeout", String.valueOf(i4));
            } catch (SettingsException e4) {
                Ln.e("Could not restore \"screen_off_timeout\"", e4);
            }
        }
        if (i5 != -1) {
            Ln.i("Restoring \"display IME policy\"");
            ServiceManager.getWindowManager().setDisplayImePolicy(i2, i5);
        }
        if (i2 == -1) {
            i2 = 0;
        }
        if (Device.isScreenOn(i2)) {
            if (z3) {
                Ln.i("Power off screen");
                Device.powerOffScreen(i2);
            } else if (z) {
                Ln.i("Restoring display power");
                Device.setDisplayPower(i2, true);
            }
        }
        System.exit(0);
    }
}
