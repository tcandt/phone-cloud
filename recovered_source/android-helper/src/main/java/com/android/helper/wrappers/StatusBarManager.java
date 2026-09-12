package com.android.helper.wrappers;

import android.os.IInterface;
import com.android.helper.util.Ln;
import java.lang.reflect.Method;

/* JADX INFO: loaded from: classes.dex */
public final class StatusBarManager {
    private Method collapsePanelsMethod;
    private boolean expandNotificationPanelMethodCustomVersion;
    private Method expandNotificationsPanelMethod;
    private Method expandSettingsPanelMethod;
    private boolean expandSettingsPanelMethodNewVersion = true;
    private final IInterface manager;

    static StatusBarManager create() {
        return new StatusBarManager(ServiceManager.getService("statusbar", "com.android.internal.statusbar.IStatusBarService"));
    }

    private StatusBarManager(IInterface iInterface) {
        this.manager = iInterface;
    }

    private Method getExpandNotificationsPanelMethod() throws NoSuchMethodException {
        if (this.expandNotificationsPanelMethod == null) {
            try {
                this.expandNotificationsPanelMethod = this.manager.getClass().getMethod("expandNotificationsPanel", null);
            } catch (NoSuchMethodException unused) {
                this.expandNotificationsPanelMethod = this.manager.getClass().getMethod("expandNotificationsPanel", Integer.TYPE);
                this.expandNotificationPanelMethodCustomVersion = true;
            }
        }
        return this.expandNotificationsPanelMethod;
    }

    private Method getExpandSettingsPanel() throws NoSuchMethodException {
        if (this.expandSettingsPanelMethod == null) {
            try {
                this.expandSettingsPanelMethod = this.manager.getClass().getMethod("expandSettingsPanel", String.class);
            } catch (NoSuchMethodException unused) {
                this.expandSettingsPanelMethod = this.manager.getClass().getMethod("expandSettingsPanel", null);
                this.expandSettingsPanelMethodNewVersion = false;
            }
        }
        return this.expandSettingsPanelMethod;
    }

    private Method getCollapsePanelsMethod() throws NoSuchMethodException {
        if (this.collapsePanelsMethod == null) {
            this.collapsePanelsMethod = this.manager.getClass().getMethod("collapsePanels", null);
        }
        return this.collapsePanelsMethod;
    }

    public void expandNotificationsPanel() {
        try {
            Method expandNotificationsPanelMethod = getExpandNotificationsPanelMethod();
            if (this.expandNotificationPanelMethodCustomVersion) {
                expandNotificationsPanelMethod.invoke(this.manager, 0);
            } else {
                expandNotificationsPanelMethod.invoke(this.manager, null);
            }
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
        }
    }

    public void expandSettingsPanel() {
        try {
            Method expandSettingsPanel = getExpandSettingsPanel();
            if (this.expandSettingsPanelMethodNewVersion) {
                expandSettingsPanel.invoke(this.manager, null);
            } else {
                expandSettingsPanel.invoke(this.manager, null);
            }
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
        }
    }

    public void collapsePanels() {
        try {
            getCollapsePanelsMethod().invoke(this.manager, null);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
        }
    }
}
