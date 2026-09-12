package com.android.helper.audio;

import com.android.helper.util.Codec;

/* JADX INFO: loaded from: classes.dex */
public enum AudioCodec implements Codec {
    OPUS(1869641075, "opus", "audio/opus"),
    AAC(6381923, "aac", "audio/mp4a-latm"),
    FLAC(1718378851, "flac", "audio/flac"),
    RAW(7496055, "raw", "audio/raw");

    private final int id;
    private final String mimeType;
    private final String name;

    AudioCodec(int i, String str, String str2) {
        this.id = i;
        this.name = str;
        this.mimeType = str2;
    }

    @Override // com.android.helper.util.Codec
    public Codec.Type getType() {
        return Codec.Type.AUDIO;
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

    public static AudioCodec findByName(String str) {
        for (AudioCodec audioCodec : values()) {
            if (audioCodec.name.equals(str)) {
                return audioCodec;
            }
        }
        return null;
    }
}
