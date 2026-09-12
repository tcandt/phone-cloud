package com.android.helper.util;

import android.media.MediaCodec;

/* JADX INFO: loaded from: classes.dex */
public interface Codec {

    public enum Type {
        VIDEO,
        AUDIO
    }

    int getId();

    String getMimeType();

    String getName();

    Type getType();

    /* JADX INFO: renamed from: com.android.helper.util.Codec$-CC, reason: invalid class name */
    public final /* synthetic */ class CC {
        public static String getMimeType(MediaCodec mediaCodec) {
            String[] supportedTypes = mediaCodec.getCodecInfo().getSupportedTypes();
            if (supportedTypes.length > 0) {
                return supportedTypes[0];
            }
            return null;
        }
    }
}
