package com.android.helper.audio;

import android.media.AudioFormat;

/* JADX INFO: loaded from: classes.dex */
public final class AudioConfig {
    public static final int BYTES_PER_SAMPLE = 2;
    public static final int CHANNELS = 2;
    public static final int CHANNEL_CONFIG = 12;
    public static final int CHANNEL_MASK = 12;
    public static final int ENCODING = 2;
    public static final int MAX_READ_SIZE = 4096;
    public static final int SAMPLE_RATE = 48000;

    private AudioConfig() {
    }

    public static AudioFormat createAudioFormat() {
        AudioFormat.Builder builder = new AudioFormat.Builder();
        builder.setEncoding(2);
        builder.setSampleRate(SAMPLE_RATE);
        builder.setChannelMask(12);
        return builder.build();
    }
}
