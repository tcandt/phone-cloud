package com.android.helper.wrappers;

import android.os.Build;
import android.os.IInterface;
import com.android.helper.util.Ln;
import java.lang.reflect.Method;

/* JADX INFO: loaded from: classes.dex */
public final class PowerManager {
    private Method isScreenOnMethod;
    private final IInterface manager;

    static PowerManager create() {
        return new PowerManager(ServiceManager.getService("power", "android.os.IPowerManager"));
    }

    private PowerManager(IInterface iInterface) {
        this.manager = iInterface;
    }

    private Method getIsScreenOnMethod() throws NoSuchMethodException {
        if (this.isScreenOnMethod == null) {
            if (Build.VERSION.SDK_INT >= 34) {
                this.isScreenOnMethod = this.manager.getClass().getMethod("isDisplayInteractive", Integer.TYPE);
            } else {
                this.isScreenOnMethod = this.manager.getClass().getMethod("isInteractive", null);
            }
        }
        return this.isScreenOnMethod;
    }

    public boolean isScreenOn(int i) {
        try {
            Method isScreenOnMethod = getIsScreenOnMethod();
            return Build.VERSION.SDK_INT >= 34 ? ((Boolean) isScreenOnMethod.invoke(this.manager, Integer.valueOf(i))).booleanValue() : ((Boolean) isScreenOnMethod.invoke(this.manager, null)).booleanValue();
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return false;
        }
    }
}
