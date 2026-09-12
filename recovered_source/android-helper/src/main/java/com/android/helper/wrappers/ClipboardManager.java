package com.android.helper.wrappers;

import android.content.ClipData;
import android.os.Process;
import com.android.helper.FakeContext;
import com.android.helper.util.Ln;

/* JADX INFO: loaded from: classes.dex */
public final class ClipboardManager {
    private final android.content.ClipboardManager manager;

    static ClipboardManager create() {
        try {
            android.content.ClipboardManager clipboardManager = (android.content.ClipboardManager) FakeContext.get().getSystemService("clipboard");
            if (clipboardManager == null) {
                return null;
            }
            return new ClipboardManager(clipboardManager);
        } catch (Throwable th) {
            logClipboardError("create ClipboardManager", th);
            return null;
        }
    }

    private ClipboardManager(android.content.ClipboardManager clipboardManager) {
        this.manager = clipboardManager;
    }

    public CharSequence getText() {
        try {
            ClipData primaryClip = this.manager.getPrimaryClip();
            if (primaryClip != null && primaryClip.getItemCount() != 0) {
                return primaryClip.getItemAt(0).getText();
            }
            return null;
        } catch (Throwable th) {
            logClipboardError("get clipboard text", th);
            return null;
        }
    }

    public boolean setText(CharSequence charSequence) {
        try {
            this.manager.setPrimaryClip(ClipData.newPlainText(null, charSequence));
            return true;
        } catch (Throwable th) {
            logClipboardError("set clipboard text", th);
            return false;
        }
    }

    public void addPrimaryClipChangedListener(android.content.ClipboardManager.OnPrimaryClipChangedListener onPrimaryClipChangedListener) {
        try {
            this.manager.addPrimaryClipChangedListener(onPrimaryClipChangedListener);
        } catch (Throwable th) {
            logClipboardError("add primary clip changed listener", th);
        }
    }

    private static void logClipboardError(String str, Throwable th) {
        String message = th.getMessage();
        if ((th instanceof SecurityException) && Process.myUid() == 0) {
            Ln.w("Could not " + str + " (SecurityException). Since scrcpy-server is running as root (uid 0), please try running cloudphone-agent with the '-root' flag to drop privileges to shell (uid 2000). Detail: " + message);
            return;
        }
        Ln.w("Could not " + str + ": " + message);
    }
}
