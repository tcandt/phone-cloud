package com.android.helper.util;

/* JADX INFO: loaded from: classes.dex */
public final class StringUtils {
    private StringUtils() {
    }

    public static int getUtf8TruncationIndex(byte[] bArr, int i) {
        int length = bArr.length;
        if (length <= i) {
            return length;
        }
        while (true) {
            byte b = bArr[i];
            if ((b & 128) == 0 || (b & 192) == 192) {
                break;
            }
            i--;
        }
        return i;
    }
}
