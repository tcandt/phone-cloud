package com.android.helper.video;

/* JADX INFO: loaded from: classes.dex */
public enum VideoSource {
    DISPLAY("display"),
    CAMERA("camera");

    private final String name;

    VideoSource(String str) {
        this.name = str;
    }

    public static VideoSource findByName(String str) {
        for (VideoSource videoSource : values()) {
            if (str.equals(videoSource.name)) {
                return videoSource;
            }
        }
        return null;
    }
}
