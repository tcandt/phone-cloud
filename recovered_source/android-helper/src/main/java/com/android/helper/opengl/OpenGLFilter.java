package com.android.helper.opengl;

/* JADX INFO: loaded from: classes.dex */
public interface OpenGLFilter {
    void draw(int i, float[] fArr);

    void init() throws OpenGLException;

    void release();
}
