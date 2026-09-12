package com.android.helper.util;

/* JADX INFO: loaded from: classes.dex */
public final class Binary {
    public static float i16FixedPointToFloat(short s) {
        if (s == Short.MAX_VALUE) {
            return 1.0f;
        }
        return s / 32768.0f;
    }

    public static int toUnsigned(byte b) {
        return b & 255;
    }

    public static int toUnsigned(short s) {
        return s & 65535;
    }

    private Binary() {
    }

    public static float u16FixedPointToFloat(short s) {
        int unsigned = toUnsigned(s);
        if (unsigned == 65535) {
            return 1.0f;
        }
        return unsigned / 65536.0f;
    }
}
