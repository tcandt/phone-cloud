package com.android.helper.wrappers;

import android.os.Build;
import android.os.IInterface;
import android.view.IDisplayWindowListener;
import com.android.helper.util.Ln;
import java.lang.reflect.Method;

/* JADX INFO: loaded from: classes.dex */
public final class WindowManager {
    public static final int DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1;
    public static final int DISPLAY_IME_POLICY_HIDE = 2;
    public static final int DISPLAY_IME_POLICY_LOCAL = 0;
    private Method freezeDisplayRotationMethod;
    private int freezeDisplayRotationMethodVersion;
    private Method getDisplayImePolicyMethod;
    private Method getRotationMethod;
    private Method isDisplayRotationFrozenMethod;
    private int isDisplayRotationFrozenMethodVersion;
    private final IInterface manager;
    private Method setDisplayImePolicyMethod;
    private Method thawDisplayRotationMethod;
    private int thawDisplayRotationMethodVersion;

    static WindowManager create() {
        return new WindowManager(ServiceManager.getService("window", "android.view.IWindowManager"));
    }

    private WindowManager(IInterface iInterface) {
        this.manager = iInterface;
    }

    private Method getGetRotationMethod() throws NoSuchMethodException {
        if (this.getRotationMethod == null) {
            Class<?> cls = this.manager.getClass();
            try {
                this.getRotationMethod = cls.getMethod("getDefaultDisplayRotation", null);
            } catch (NoSuchMethodException unused) {
                this.getRotationMethod = cls.getMethod("getRotation", null);
            }
        }
        return this.getRotationMethod;
    }

    private Method getFreezeDisplayRotationMethod() throws NoSuchMethodException {
        if (this.freezeDisplayRotationMethod == null) {
            try {
                try {
                    Class<?> cls = this.manager.getClass();
                    Class<?> cls2 = Integer.TYPE;
                    this.freezeDisplayRotationMethod = cls.getMethod("freezeDisplayRotation", cls2, cls2, String.class);
                    this.freezeDisplayRotationMethodVersion = 0;
                } catch (NoSuchMethodException unused) {
                    this.freezeDisplayRotationMethod = this.manager.getClass().getMethod("freezeRotation", Integer.TYPE);
                    this.freezeDisplayRotationMethodVersion = 2;
                }
            } catch (NoSuchMethodException unused2) {
                Class<?> cls3 = this.manager.getClass();
                Class<?> cls4 = Integer.TYPE;
                this.freezeDisplayRotationMethod = cls3.getMethod("freezeDisplayRotation", cls4, cls4);
                this.freezeDisplayRotationMethodVersion = 1;
            }
        }
        return this.freezeDisplayRotationMethod;
    }

    private Method getIsDisplayRotationFrozenMethod() throws NoSuchMethodException {
        if (this.isDisplayRotationFrozenMethod == null) {
            try {
                this.isDisplayRotationFrozenMethod = this.manager.getClass().getMethod("isDisplayRotationFrozen", Integer.TYPE);
                this.isDisplayRotationFrozenMethodVersion = 0;
            } catch (NoSuchMethodException unused) {
                this.isDisplayRotationFrozenMethod = this.manager.getClass().getMethod("isRotationFrozen", null);
                this.isDisplayRotationFrozenMethodVersion = 1;
            }
        }
        return this.isDisplayRotationFrozenMethod;
    }

    private Method getThawDisplayRotationMethod() throws NoSuchMethodException {
        if (this.thawDisplayRotationMethod == null) {
            try {
                try {
                    this.thawDisplayRotationMethod = this.manager.getClass().getMethod("thawDisplayRotation", Integer.TYPE, String.class);
                    this.thawDisplayRotationMethodVersion = 0;
                } catch (NoSuchMethodException unused) {
                    this.thawDisplayRotationMethod = this.manager.getClass().getMethod("thawDisplayRotation", Integer.TYPE);
                    this.thawDisplayRotationMethodVersion = 1;
                }
            } catch (NoSuchMethodException unused2) {
                this.thawDisplayRotationMethod = this.manager.getClass().getMethod("thawRotation", null);
                this.thawDisplayRotationMethodVersion = 2;
            }
        }
        return this.thawDisplayRotationMethod;
    }

    public int getRotation() {
        try {
            return ((Integer) getGetRotationMethod().invoke(this.manager, null)).intValue();
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return 0;
        }
    }

    public void freezeRotation(int i, int i2) {
        try {
            Method freezeDisplayRotationMethod = getFreezeDisplayRotationMethod();
            int i3 = this.freezeDisplayRotationMethodVersion;
            if (i3 == 0) {
                freezeDisplayRotationMethod.invoke(this.manager, Integer.valueOf(i), Integer.valueOf(i2), "scrcpy#freezeRotation");
                return;
            }
            if (i3 == 1) {
                freezeDisplayRotationMethod.invoke(this.manager, Integer.valueOf(i), Integer.valueOf(i2));
            } else if (i != 0) {
                Ln.e("Secondary display rotation not supported on this device");
            } else {
                freezeDisplayRotationMethod.invoke(this.manager, Integer.valueOf(i2));
            }
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
        }
    }

    public boolean isRotationFrozen(int i) {
        try {
            Method isDisplayRotationFrozenMethod = getIsDisplayRotationFrozenMethod();
            if (this.isDisplayRotationFrozenMethodVersion == 0) {
                return ((Boolean) isDisplayRotationFrozenMethod.invoke(this.manager, Integer.valueOf(i))).booleanValue();
            }
            if (i != 0) {
                Ln.e("Secondary display rotation not supported on this device");
                return false;
            }
            return ((Boolean) isDisplayRotationFrozenMethod.invoke(this.manager, null)).booleanValue();
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return false;
        }
    }

    public void thawRotation(int i) {
        try {
            Method thawDisplayRotationMethod = getThawDisplayRotationMethod();
            int i2 = this.thawDisplayRotationMethodVersion;
            if (i2 == 0) {
                thawDisplayRotationMethod.invoke(this.manager, Integer.valueOf(i), "scrcpy#thawRotation");
                return;
            }
            if (i2 == 1) {
                thawDisplayRotationMethod.invoke(this.manager, Integer.valueOf(i));
            } else if (i != 0) {
                Ln.e("Secondary display rotation not supported on this device");
            } else {
                thawDisplayRotationMethod.invoke(this.manager, null);
            }
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
        }
    }

    public int[] registerDisplayWindowListener(IDisplayWindowListener iDisplayWindowListener) {
        try {
            return (int[]) this.manager.getClass().getMethod("registerDisplayWindowListener", IDisplayWindowListener.class).invoke(this.manager, iDisplayWindowListener);
        } catch (Exception e) {
            Ln.e("Could not register display window listener", e);
            return null;
        }
    }

    public void unregisterDisplayWindowListener(IDisplayWindowListener iDisplayWindowListener) {
        try {
            this.manager.getClass().getMethod("unregisterDisplayWindowListener", IDisplayWindowListener.class).invoke(this.manager, iDisplayWindowListener);
        } catch (Exception e) {
            Ln.e("Could not unregister display window listener", e);
        }
    }

    private Method getGetDisplayImePolicyMethod() throws NoSuchMethodException {
        if (this.getDisplayImePolicyMethod == null) {
            if (Build.VERSION.SDK_INT >= 31) {
                this.getDisplayImePolicyMethod = this.manager.getClass().getMethod("getDisplayImePolicy", Integer.TYPE);
            } else {
                this.getDisplayImePolicyMethod = this.manager.getClass().getMethod("shouldShowIme", Integer.TYPE);
            }
        }
        return this.getDisplayImePolicyMethod;
    }

    public int getDisplayImePolicy(int i) {
        try {
            Method getDisplayImePolicyMethod = getGetDisplayImePolicyMethod();
            return Build.VERSION.SDK_INT >= 31 ? ((Integer) getDisplayImePolicyMethod.invoke(this.manager, Integer.valueOf(i))).intValue() : !((Boolean) getDisplayImePolicyMethod.invoke(this.manager, Integer.valueOf(i))).booleanValue() ? 1 : 0;
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return -1;
        }
    }

    private Method getSetDisplayImePolicyMethod() throws NoSuchMethodException {
        if (this.setDisplayImePolicyMethod == null) {
            if (Build.VERSION.SDK_INT >= 31) {
                Class<?> cls = this.manager.getClass();
                Class<?> cls2 = Integer.TYPE;
                this.setDisplayImePolicyMethod = cls.getMethod("setDisplayImePolicy", cls2, cls2);
            } else {
                this.setDisplayImePolicyMethod = this.manager.getClass().getMethod("setShouldShowIme", Integer.TYPE, Boolean.TYPE);
            }
        }
        return this.setDisplayImePolicyMethod;
    }

    public void setDisplayImePolicy(int i, int i2) {
        try {
            Method setDisplayImePolicyMethod = getSetDisplayImePolicyMethod();
            if (Build.VERSION.SDK_INT >= 31) {
                setDisplayImePolicyMethod.invoke(this.manager, Integer.valueOf(i), Integer.valueOf(i2));
            } else if (i2 != 2) {
                setDisplayImePolicyMethod.invoke(this.manager, Integer.valueOf(i), Boolean.valueOf(i2 == 0));
            } else {
                Ln.w("DISPLAY_IME_POLICY_HIDE is not supported before Android 12");
            }
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
        }
    }
}
