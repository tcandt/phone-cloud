package com.android.helper.util;

import com.android.helper.wrappers.ContentProvider;
import com.android.helper.wrappers.ServiceManager;

/* JADX INFO: loaded from: classes.dex */
public final class Settings {
    public static final String TABLE_GLOBAL = "global";
    public static final String TABLE_SECURE = "secure";
    public static final String TABLE_SYSTEM = "system";

    private Settings() {
    }

    public static String getValue(String str, String str2) throws SettingsException {
        ContentProvider contentProviderCreateSettingsProvider = ServiceManager.getActivityManager().createSettingsProvider();
        try {
            String value = contentProviderCreateSettingsProvider.getValue(str, str2);
            if (contentProviderCreateSettingsProvider != null) {
                contentProviderCreateSettingsProvider.close();
            }
            return value;
        } catch (Throwable th) {
            if (contentProviderCreateSettingsProvider != null) {
                try {
                    contentProviderCreateSettingsProvider.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public static void putValue(String str, String str2, String str3) throws SettingsException {
        ContentProvider contentProviderCreateSettingsProvider = ServiceManager.getActivityManager().createSettingsProvider();
        try {
            contentProviderCreateSettingsProvider.putValue(str, str2, str3);
            if (contentProviderCreateSettingsProvider != null) {
                contentProviderCreateSettingsProvider.close();
            }
        } catch (Throwable th) {
            if (contentProviderCreateSettingsProvider != null) {
                try {
                    contentProviderCreateSettingsProvider.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public static String getAndPutValue(String str, String str2, String str3) throws SettingsException {
        ContentProvider contentProviderCreateSettingsProvider = ServiceManager.getActivityManager().createSettingsProvider();
        try {
            String value = contentProviderCreateSettingsProvider.getValue(str, str2);
            if (!str3.equals(value)) {
                contentProviderCreateSettingsProvider.putValue(str, str2, str3);
            }
            if (contentProviderCreateSettingsProvider != null) {
                contentProviderCreateSettingsProvider.close();
            }
            return value;
        } catch (Throwable th) {
            if (contentProviderCreateSettingsProvider != null) {
                try {
                    contentProviderCreateSettingsProvider.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }
}
