package com.android.helper.audio;

import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.os.Build;
import android.os.SystemClock;
import com.android.helper.FakeContext;
import com.android.helper.FakeContext$$ExternalSyntheticApiModelOutline0;
import com.android.helper.Workarounds;
import com.android.helper.util.Ln;
import com.android.helper.wrappers.ServiceManager;
import java.nio.ByteBuffer;

/* JADX INFO: loaded from: classes.dex */
public class AudioDirectCapture implements AudioCapture {
    private static final int CHANNELS = 2;
    private static final int CHANNEL_CONFIG = 12;
    private static final int CHANNEL_MASK = 12;
    private static final int ENCODING = 2;
    private static final int SAMPLE_RATE = 48000;
    private final int audioSource;
    private AudioRecordReader reader;
    private AudioRecord recorder;

    public AudioDirectCapture(AudioSource audioSource) {
        this.audioSource = audioSource.getDirectAudioSource();
    }

    private static AudioRecord createAudioRecord(int i) {
        AudioRecord.Builder builderM2m = FakeContext$$ExternalSyntheticApiModelOutline0.m2m();
        if (Build.VERSION.SDK_INT >= 31) {
            builderM2m.setContext(FakeContext.get());
        }
        builderM2m.setAudioSource(i);
        builderM2m.setAudioFormat(AudioConfig.createAudioFormat());
        int minBufferSize = AudioRecord.getMinBufferSize(48000, 12, 2);
        if (minBufferSize > 0) {
            builderM2m.setBufferSizeInBytes(minBufferSize * 8);
        }
        return builderM2m.build();
    }

    private static void startWorkaroundAndroid11() {
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.addFlags(268435456);
        intent.addCategory("android.intent.category.LAUNCHER");
        intent.setComponent(new ComponentName(FakeContext.PACKAGE_NAME, "com.android.shell.HeapDumpActivity"));
        ServiceManager.getActivityManager().startActivity(intent);
    }

    private static void stopWorkaroundAndroid11() {
        ServiceManager.getActivityManager().forceStopPackage(FakeContext.PACKAGE_NAME);
    }

    private void tryStartRecording(int i, int i2) throws AudioCaptureException {
        while (true) {
            int i3 = i - 1;
            if (i <= 0) {
                return;
            }
            SystemClock.sleep(i2);
            try {
                startRecording();
                return;
            } catch (UnsupportedOperationException unused) {
                if (i3 == 0) {
                    Ln.e("Failed to start audio capture");
                    Ln.e("On Android 11, audio capture must be started in the foreground, make sure that the device is unlocked when starting scrcpy.");
                    throw new AudioCaptureException();
                }
                Ln.d("Failed to start audio capture, retrying...");
                i = i3;
            }
        }
    }

    private void startRecording() throws AudioCaptureException {
        try {
            this.recorder = createAudioRecord(this.audioSource);
        } catch (NullPointerException unused) {
            this.recorder = Workarounds.createAudioRecord(this.audioSource, 48000, 12, 2, 12, 2);
        }
        this.recorder.startRecording();
        this.reader = new AudioRecordReader(this.recorder);
    }

    @Override // com.android.helper.audio.AudioCapture
    public void checkCompatibility() throws AudioCaptureException {
        if (Build.VERSION.SDK_INT >= 30) {
            return;
        }
        Ln.w("Audio disabled: it is not supported before Android 11");
        throw new AudioCaptureException();
    }

    @Override // com.android.helper.audio.AudioCapture
    public void start() throws AudioCaptureException {
        if (Build.VERSION.SDK_INT == 30) {
            startWorkaroundAndroid11();
            try {
                tryStartRecording(5, 100);
                return;
            } finally {
                stopWorkaroundAndroid11();
            }
        }
        startRecording();
    }

    @Override // com.android.helper.audio.AudioCapture
    public void stop() {
        AudioRecord audioRecord = this.recorder;
        if (audioRecord != null) {
            audioRecord.release();
        }
    }

    @Override // com.android.helper.audio.AudioCapture
    public int read(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
        return this.reader.read(byteBuffer, bufferInfo);
    }
}
