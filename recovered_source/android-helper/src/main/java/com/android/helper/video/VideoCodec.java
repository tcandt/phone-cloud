package com.android.helper.video;

import com.android.helper.util.Codec;

/* JADX INFO: loaded from: classes.dex */
public enum VideoCodec implements Codec {
    H264(1748121140, "h264", "video/avc"),
    H265(1748121141, "h265", "video/hevc"),
    AV1(6387249, "av1", "video/av01");

    private final int id;
    private final String mimeType;
    private final String name;

    VideoCodec(int i, String str, String str2) {
        this.id = i;
        this.name = str;
        this.mimeType = str2;
    }

    @Override // com.android.helper.util.Codec
    public Codec.Type getType() {
        return Codec.Type.VIDEO;
    }

    @Override // com.android.helper.util.Codec
    public int getId() {
        return this.id;
    }

    @Override // com.android.helper.util.Codec
    public String getName() {
        return this.name;
    }

    @Override // com.android.helper.util.Codec
    public String getMimeType() {
        return this.mimeType;
    }

    public static VideoCodec findByName(String str) {
        for (VideoCodec videoCodec : values()) {
            if (videoCodec.name.equals(str)) {
                return videoCodec;
            }
        }
        return null;
    }
}
