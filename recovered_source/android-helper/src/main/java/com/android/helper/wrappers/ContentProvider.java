package com.android.helper.wrappers;

import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import com.android.helper.FakeContext;
import com.android.helper.FakeContext$$ExternalSyntheticApiModelOutline0;
import com.android.helper.util.Ln;
import com.android.helper.util.SettingsException;
import java.io.Closeable;
import java.lang.reflect.Method;

/* JADX INFO: loaded from: classes.dex */
public final class ContentProvider implements Closeable {
    private static final String CALL_METHOD_GET_GLOBAL = "GET_global";
    private static final String CALL_METHOD_GET_SECURE = "GET_secure";
    private static final String CALL_METHOD_GET_SYSTEM = "GET_system";
    private static final String CALL_METHOD_PUT_GLOBAL = "PUT_global";
    private static final String CALL_METHOD_PUT_SECURE = "PUT_secure";
    private static final String CALL_METHOD_PUT_SYSTEM = "PUT_system";
    private static final String CALL_METHOD_USER_KEY = "_user";
    private static final String NAME_VALUE_TABLE_VALUE = "value";
    public static final String TABLE_GLOBAL = "global";
    public static final String TABLE_SECURE = "secure";
    public static final String TABLE_SYSTEM = "system";
    private Method callMethod;
    private int callMethodVersion;
    private final ActivityManager manager;
    private final String name;
    private final Object provider;
    private final IBinder token;

    ContentProvider(ActivityManager activityManager, Object obj, String str, IBinder iBinder) {
        this.manager = activityManager;
        this.provider = obj;
        this.name = str;
        this.token = iBinder;
    }

    private Method getCallMethod() throws NoSuchMethodException {
        if (this.callMethod == null) {
            if (Build.VERSION.SDK_INT >= 31) {
                this.callMethod = this.provider.getClass().getMethod("call", FakeContext$$ExternalSyntheticApiModelOutline0.m3m(), String.class, String.class, String.class, Bundle.class);
                this.callMethodVersion = 0;
            } else {
                try {
                    try {
                        this.callMethod = this.provider.getClass().getMethod("call", String.class, String.class, String.class, String.class, String.class, Bundle.class);
                        this.callMethodVersion = 1;
                    } catch (NoSuchMethodException unused) {
                        this.callMethod = this.provider.getClass().getMethod("call", String.class, String.class, String.class, String.class, Bundle.class);
                        this.callMethodVersion = 2;
                    }
                } catch (NoSuchMethodException unused2) {
                    this.callMethod = this.provider.getClass().getMethod("call", String.class, String.class, String.class, Bundle.class);
                    this.callMethodVersion = 3;
                }
            }
        }
        return this.callMethod;
    }

    private Bundle call(String str, String str2, Bundle bundle) throws ReflectiveOperationException {
        Object[] objArr;
        Object[] objArr2;
        try {
            Method callMethod = getCallMethod();
            if (Build.VERSION.SDK_INT < 31 || this.callMethodVersion != 0) {
                int i = this.callMethodVersion;
                if (i == 1) {
                    objArr = new Object[]{FakeContext.PACKAGE_NAME, null, "settings", str, str2, bundle};
                } else if (i == 2) {
                    objArr = new Object[]{FakeContext.PACKAGE_NAME, "settings", str, str2, bundle};
                } else {
                    objArr = new Object[]{FakeContext.PACKAGE_NAME, str, str2, bundle};
                }
                objArr2 = objArr;
            } else {
                objArr2 = new Object[]{FakeContext.get().getAttributionSource(), "settings", str, str2, bundle};
            }
            return (Bundle) callMethod.invoke(this.provider, objArr2);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            throw e;
        }
    }

    @Override // java.io.Closeable, java.lang.AutoCloseable
    public void close() {
        this.manager.removeContentProviderExternal(this.name, this.token);
    }

    private static String getGetMethod(String str) {
        str.hashCode();
        switch (str) {
            case "global":
                return CALL_METHOD_GET_GLOBAL;
            case "secure":
                return CALL_METHOD_GET_SECURE;
            case "system":
                return CALL_METHOD_GET_SYSTEM;
            default:
                throw new IllegalArgumentException("Invalid table: " + str);
        }
    }

    private static String getPutMethod(String str) {
        str.hashCode();
        switch (str) {
            case "global":
                return CALL_METHOD_PUT_GLOBAL;
            case "secure":
                return CALL_METHOD_PUT_SECURE;
            case "system":
                return CALL_METHOD_PUT_SYSTEM;
            default:
                throw new IllegalArgumentException("Invalid table: " + str);
        }
    }

    public String getValue(String str, String str2) throws SettingsException {
        String getMethod = getGetMethod(str);
        Bundle bundle = new Bundle();
        bundle.putInt(CALL_METHOD_USER_KEY, 0);
        try {
            Bundle bundleCall = call(getMethod, str2, bundle);
            if (bundleCall == null) {
                return null;
            }
            return bundleCall.getString(NAME_VALUE_TABLE_VALUE);
        } catch (Exception e) {
            throw new SettingsException(str, "get", str2, null, e);
        }
    }

    public void putValue(String str, String str2, String str3) throws SettingsException {
        String putMethod = getPutMethod(str);
        Bundle bundle = new Bundle();
        bundle.putInt(CALL_METHOD_USER_KEY, 0);
        bundle.putString(NAME_VALUE_TABLE_VALUE, str3);
        try {
            call(putMethod, str2, bundle);
        } catch (Exception e) {
            throw new SettingsException(str, "put", str2, str3, e);
        }
    }
}
