package com.android.helper.audio;

import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import com.android.helper.util.Ln;
import java.nio.ByteBuffer;

/* JADX INFO: loaded from: classes.dex */
public class AudioRecordReader {
    private static final long ONE_SAMPLE_US = 21;
    private final AudioRecord recorder;
    private final AudioTimestamp timestamp = new AudioTimestamp();
    private long previousRecorderTimestamp = -1;
    private long previousPts = 0;
    private long nextPts = 0;

    public AudioRecordReader(AudioRecord audioRecord) {
        this.recorder = audioRecord;
    }

    public int read(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
        long j;
        int i = this.recorder.read(byteBuffer, AudioConfig.MAX_READ_SIZE);
        if (i <= 0) {
            return i;
        }
        if (this.recorder.getTimestamp(this.timestamp, 0) == 0 && this.timestamp.nanoTime != this.previousRecorderTimestamp) {
            j = this.timestamp.nanoTime / 1000;
            this.previousRecorderTimestamp = this.timestamp.nanoTime;
        } else {
            if (this.nextPts == 0) {
                Ln.w("Could not get initial audio timestamp");
                this.nextPts = System.nanoTime() / 1000;
            }
            j = this.nextPts;
        }
        this.nextPts = ((((long) i) * 1000000) / 192000) + j;
        long j2 = this.previousPts;
        if (j2 != 0 && j < j2 + ONE_SAMPLE_US) {
            j = j2 + ONE_SAMPLE_US;
        }
        this.previousPts = j;
        bufferInfo.set(0, i, j, 0);
        return i;
    }
}
