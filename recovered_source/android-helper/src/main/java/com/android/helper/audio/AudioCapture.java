package com.android.helper.audio;

import android.media.MediaCodec;
import java.nio.ByteBuffer;

/* JADX INFO: loaded from: classes.dex */
public interface AudioCapture {
    void checkCompatibility() throws AudioCaptureException;

    int read(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo);

    void start() throws AudioCaptureException;

    void stop();
}
