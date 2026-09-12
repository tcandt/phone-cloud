package com.android.helper.audio;

/* JADX INFO: loaded from: classes.dex */
public enum AudioSource {
    OUTPUT("output", 8),
    MIC("mic", 1),
    PLAYBACK("playback", -1),
    MIC_UNPROCESSED("mic-unprocessed", 9),
    MIC_CAMCORDER("mic-camcorder", 5),
    MIC_VOICE_RECOGNITION("mic-voice-recognition", 6),
    MIC_VOICE_COMMUNICATION("mic-voice-communication", 7),
    VOICE_CALL("voice-call", 4),
    VOICE_CALL_UPLINK("voice-call-uplink", 2),
    VOICE_CALL_DOWNLINK("voice-call-downlink", 3),
    VOICE_PERFORMANCE("voice-performance", 10);

    private final int directAudioSource;
    private final String name;

    AudioSource(String str, int i) {
        this.name = str;
        this.directAudioSource = i;
    }

    public boolean isDirect() {
        return this != PLAYBACK;
    }

    public int getDirectAudioSource() {
        return this.directAudioSource;
    }

    public static AudioSource findByName(String str) {
        for (AudioSource audioSource : values()) {
            if (str.equals(audioSource.name)) {
                return audioSource;
            }
        }
        return null;
    }
}
