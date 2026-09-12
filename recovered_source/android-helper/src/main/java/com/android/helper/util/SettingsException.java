package com.android.helper.util;

/* JADX INFO: loaded from: classes.dex */
public class SettingsException extends Exception {
    private static String createMessage(String str, String str2, String str3, String str4) {
        String str5;
        StringBuilder sb = new StringBuilder("Could not access settings: ");
        sb.append(str);
        sb.append(" ");
        sb.append(str2);
        sb.append(" ");
        sb.append(str3);
        if (str4 != null) {
            str5 = " " + str4;
        } else {
            str5 = "";
        }
        sb.append(str5);
        return sb.toString();
    }

    public SettingsException(String str, String str2, String str3, String str4, Throwable th) {
        super(createMessage(str, str2, str3, str4), th);
    }
}
