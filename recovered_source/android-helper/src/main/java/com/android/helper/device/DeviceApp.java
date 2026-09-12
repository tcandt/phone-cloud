package com.android.helper.device;

/* JADX INFO: loaded from: classes.dex */
public final class DeviceApp {
    private final String name;
    private final String packageName;
    private final boolean system;

    public DeviceApp(String str, String str2, boolean z) {
        this.packageName = str;
        this.name = str2;
        this.system = z;
    }

    public String getPackageName() {
        return this.packageName;
    }

    public String getName() {
        return this.name;
    }

    public boolean isSystem() {
        return this.system;
    }
}
