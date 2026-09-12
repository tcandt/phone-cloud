package com.android.helper.wrappers;

import android.content.IContentProvider;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import com.android.helper.FakeContext;
import com.android.helper.util.Ln;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/* JADX INFO: loaded from: classes.dex */
public final class ActivityManager {
    private Method forceStopPackageMethod;
    private Method getContentProviderExternalMethod;
    private boolean getContentProviderExternalMethodNewVersion = true;
    private final IInterface manager;
    private Method removeContentProviderExternalMethod;
    private Method startActivityAsUserMethod;

    static ActivityManager create() {
        try {
            return new ActivityManager((IInterface) Class.forName("android.app.ActivityManagerNative").getDeclaredMethod("getDefault", null).invoke(null, null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private ActivityManager(IInterface iInterface) {
        this.manager = iInterface;
    }

    private Method getGetContentProviderExternalMethod() throws NoSuchMethodException {
        if (this.getContentProviderExternalMethod == null) {
            try {
                this.getContentProviderExternalMethod = this.manager.getClass().getMethod("getContentProviderExternal", String.class, Integer.TYPE, IBinder.class, String.class);
            } catch (NoSuchMethodException unused) {
                this.getContentProviderExternalMethod = this.manager.getClass().getMethod("getContentProviderExternal", String.class, Integer.TYPE, IBinder.class);
                this.getContentProviderExternalMethodNewVersion = false;
            }
        }
        return this.getContentProviderExternalMethod;
    }

    private Method getRemoveContentProviderExternalMethod() throws NoSuchMethodException {
        if (this.removeContentProviderExternalMethod == null) {
            this.removeContentProviderExternalMethod = this.manager.getClass().getMethod("removeContentProviderExternal", String.class, IBinder.class);
        }
        return this.removeContentProviderExternalMethod;
    }

    public IContentProvider getContentProviderExternal(String str, IBinder iBinder) {
        try {
            Object objInvoke = getGetContentProviderExternalMethod().invoke(this.manager, this.getContentProviderExternalMethodNewVersion ? new Object[]{str, 0, iBinder, null} : new Object[]{str, 0, iBinder});
            if (objInvoke == null) {
                return null;
            }
            Field declaredField = objInvoke.getClass().getDeclaredField("provider");
            declaredField.setAccessible(true);
            return (IContentProvider) declaredField.get(objInvoke);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return null;
        }
    }

    void removeContentProviderExternal(String str, IBinder iBinder) {
        try {
            getRemoveContentProviderExternalMethod().invoke(this.manager, str, iBinder);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
        }
    }

    public ContentProvider createSettingsProvider() {
        Binder binder = new Binder();
        IContentProvider contentProviderExternal = getContentProviderExternal("settings", binder);
        if (contentProviderExternal == null) {
            return null;
        }
        return new ContentProvider(this, contentProviderExternal, "settings", binder);
    }

    private Method getStartActivityAsUserMethod() throws NoSuchMethodException, ClassNotFoundException {
        if (this.startActivityAsUserMethod == null) {
            Class<?> cls = Class.forName("android.app.IApplicationThread");
            Class<?> cls2 = Class.forName("android.app.ProfilerInfo");
            Class<?> cls3 = this.manager.getClass();
            Class<?> cls4 = Integer.TYPE;
            this.startActivityAsUserMethod = cls3.getMethod("startActivityAsUser", cls, String.class, Intent.class, String.class, IBinder.class, String.class, cls4, cls4, cls2, Bundle.class, cls4);
        }
        return this.startActivityAsUserMethod;
    }

    public int startActivity(Intent intent) {
        return startActivity(intent, null);
    }

    public int startActivity(Intent intent, Bundle bundle) {
        try {
            return ((Integer) getStartActivityAsUserMethod().invoke(this.manager, null, FakeContext.PACKAGE_NAME, intent, null, null, null, 0, 0, null, bundle, -2)).intValue();
        } catch (Throwable th) {
            Ln.e("Could not invoke method", th);
            return 0;
        }
    }

    private Method getForceStopPackageMethod() throws NoSuchMethodException {
        if (this.forceStopPackageMethod == null) {
            this.forceStopPackageMethod = this.manager.getClass().getMethod("forceStopPackage", String.class, Integer.TYPE);
        }
        return this.forceStopPackageMethod;
    }

    public void forceStopPackage(String str) {
        try {
            getForceStopPackageMethod().invoke(this.manager, str, -2);
        } catch (Throwable th) {
            Ln.e("Could not invoke method", th);
        }
    }
}
