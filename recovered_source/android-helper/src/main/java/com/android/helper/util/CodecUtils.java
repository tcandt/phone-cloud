package com.android.helper.util;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import java.util.ArrayList;
import java.util.Arrays;

/* JADX INFO: loaded from: classes.dex */
public final class CodecUtils {
    private CodecUtils() {
    }

    public static void setCodecOption(MediaFormat mediaFormat, String str, Object obj) {
        if (obj instanceof Integer) {
            mediaFormat.setInteger(str, ((Integer) obj).intValue());
            return;
        }
        if (obj instanceof Long) {
            mediaFormat.setLong(str, ((Long) obj).longValue());
        } else if (obj instanceof Float) {
            mediaFormat.setFloat(str, ((Float) obj).floatValue());
        } else if (obj instanceof String) {
            mediaFormat.setString(str, (String) obj);
        }
    }

    public static MediaCodecInfo[] getEncoders(MediaCodecList mediaCodecList, String str) {
        ArrayList arrayList = new ArrayList();
        for (MediaCodecInfo mediaCodecInfo : mediaCodecList.getCodecInfos()) {
            if (mediaCodecInfo.isEncoder() && Arrays.asList(mediaCodecInfo.getSupportedTypes()).contains(str)) {
                arrayList.add(mediaCodecInfo);
            }
        }
        return (MediaCodecInfo[]) arrayList.toArray(new MediaCodecInfo[arrayList.size()]);
    }
}
