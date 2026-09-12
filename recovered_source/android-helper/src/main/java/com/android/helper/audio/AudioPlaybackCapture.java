package com.android.helper.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.os.Build;
import com.android.helper.FakeContext;
import com.android.helper.util.Ln;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/* JADX INFO: loaded from: classes.dex */
public final class AudioPlaybackCapture implements AudioCapture {
    private AudioCapture fallbackDirectCapture;
    private final boolean keepPlayingOnDevice;
    private AudioRecordReader reader;
    private AudioRecord recorder;

    public AudioPlaybackCapture(boolean z) {
        this.keepPlayingOnDevice = z;
    }

    private AudioRecord createAudioRecord() throws AudioCaptureException {
        try {
            Class<?> cls = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> cls2 = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Object objNewInstance = cls2.getConstructor(null).newInstance(null);
            cls2.getMethod("setTargetMixRole", Integer.TYPE).invoke(objNewInstance, Integer.valueOf(cls.getField("MIX_ROLE_PLAYERS").getInt(null)));
            int i = cls.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null);
            Method method = cls2.getMethod("addMixRule", Integer.TYPE, Object.class);
            int[] iArr = {1, 14, 0};
            for (int i2 = 0; i2 < 3; i2++) {
                method.invoke(objNewInstance, Integer.valueOf(i), new AudioAttributes.Builder().setUsage(iArr[i2]).build());
            }
            Object objInvoke = cls2.getMethod("build", null).invoke(objNewInstance, null);
            cls2.getMethod("voiceCommunicationCaptureAllowed", Boolean.TYPE).invoke(objNewInstance, true);
            Class<?> cls3 = Class.forName("android.media.audiopolicy.AudioMix");
            Class<?> cls4 = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Object objNewInstance2 = cls4.getConstructor(cls).newInstance(objInvoke);
            objNewInstance2.getClass().getMethod("setFormat", AudioFormat.class).invoke(objNewInstance2, AudioConfig.createAudioFormat());
            objNewInstance2.getClass().getMethod("setRouteFlags", Integer.TYPE).invoke(objNewInstance2, Integer.valueOf(cls3.getField(this.keepPlayingOnDevice ? "ROUTE_FLAG_LOOP_BACK_RENDER" : "ROUTE_FLAG_LOOP_BACK").getInt(null)));
            Object objInvoke2 = cls4.getMethod("build", null).invoke(objNewInstance2, null);
            Class<?> cls5 = Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> cls6 = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Object objNewInstance3 = cls6.getConstructor(Context.class).newInstance(FakeContext.get());
            cls6.getMethod("addMix", cls3).invoke(objNewInstance3, objInvoke2);
            Object objInvoke3 = cls6.getMethod("build", null).invoke(objNewInstance3, null);
            Method declaredMethod = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", cls5);
            declaredMethod.setAccessible(true);
            int iIntValue = ((Integer) declaredMethod.invoke(null, objInvoke3)).intValue();
            if (iIntValue == 0) {
                return (AudioRecord) cls5.getMethod("createAudioRecordSink", cls3).invoke(objInvoke3, objInvoke2);
            }
            throw new RuntimeException("registerAudioPolicy() returned " + iIntValue);
        } catch (Exception e) {
            Ln.e("Could not capture audio playback", e);
            throw new AudioCaptureException();
        }
    }

    @Override // com.android.helper.audio.AudioCapture
    public void checkCompatibility() throws AudioCaptureException {
        if (Build.VERSION.SDK_INT >= 33) {
            return;
        }
        Ln.w("Audio disabled: audio playback capture source not supported before Android 13");
        throw new AudioCaptureException();
    }

    @Override // com.android.helper.audio.AudioCapture
    public void start() throws AudioCaptureException {
        try {
            AudioRecord audioRecordCreateAudioRecord = createAudioRecord();
            this.recorder = audioRecordCreateAudioRecord;
            audioRecordCreateAudioRecord.startRecording();
            this.reader = new AudioRecordReader(this.recorder);
        } catch (Exception e) {
            Ln.w("AudioPlaybackCapture failed, falling back to AudioDirectCapture(OUTPUT): " + e.getMessage());
            AudioDirectCapture audioDirectCapture = new AudioDirectCapture(AudioSource.OUTPUT);
            this.fallbackDirectCapture = audioDirectCapture;
            audioDirectCapture.checkCompatibility();
            this.fallbackDirectCapture.start();
        }
    }

    @Override // com.android.helper.audio.AudioCapture
    public void stop() {
        AudioCapture audioCapture = this.fallbackDirectCapture;
        if (audioCapture != null) {
            audioCapture.stop();
            return;
        }
        AudioRecord audioRecord = this.recorder;
        if (audioRecord != null) {
            audioRecord.release();
        }
    }

    @Override // com.android.helper.audio.AudioCapture
    public int read(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
        AudioCapture audioCapture = this.fallbackDirectCapture;
        if (audioCapture != null) {
            return audioCapture.read(byteBuffer, bufferInfo);
        }
        return this.reader.read(byteBuffer, bufferInfo);
    }
}
