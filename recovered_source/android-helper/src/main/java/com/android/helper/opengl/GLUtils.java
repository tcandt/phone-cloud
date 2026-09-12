package com.android.helper.opengl;

import android.opengl.GLES20;
import android.opengl.GLU;
import com.android.helper.util.Ln;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/* JADX INFO: loaded from: classes.dex */
public final class GLUtils {
    private static final boolean DEBUG = false;

    public static void checkGlError() {
    }

    private GLUtils() {
    }

    public static int createProgram(String str, String str2) {
        int iCreateShader = createShader(35633, str);
        if (iCreateShader == 0) {
            return 0;
        }
        int iCreateShader2 = createShader(35632, str2);
        if (iCreateShader2 == 0) {
            GLES20.glDeleteShader(iCreateShader);
            return 0;
        }
        int iGlCreateProgram = GLES20.glCreateProgram();
        if (iGlCreateProgram == 0) {
            GLES20.glDeleteShader(iCreateShader2);
            GLES20.glDeleteShader(iCreateShader);
            return 0;
        }
        GLES20.glAttachShader(iGlCreateProgram, iCreateShader);
        checkGlError();
        GLES20.glAttachShader(iGlCreateProgram, iCreateShader2);
        checkGlError();
        GLES20.glLinkProgram(iGlCreateProgram);
        checkGlError();
        int[] iArr = new int[1];
        GLES20.glGetProgramiv(iGlCreateProgram, 35714, iArr, 0);
        if (iArr[0] != 0) {
            return iGlCreateProgram;
        }
        Ln.e("Could not link program: " + GLES20.glGetProgramInfoLog(iGlCreateProgram));
        GLES20.glDeleteProgram(iGlCreateProgram);
        GLES20.glDeleteShader(iCreateShader2);
        GLES20.glDeleteShader(iCreateShader);
        return 0;
    }

    public static int createShader(int i, String str) {
        int iGlCreateShader = GLES20.glCreateShader(i);
        if (iGlCreateShader == 0) {
            Ln.e(getGlErrorMessage("Could not create shader"));
            return 0;
        }
        GLES20.glShaderSource(iGlCreateShader, str);
        GLES20.glCompileShader(iGlCreateShader);
        int[] iArr = new int[1];
        GLES20.glGetShaderiv(iGlCreateShader, 35713, iArr, 0);
        if (iArr[0] != 0) {
            return iGlCreateShader;
        }
        Ln.e("Could not compile " + getShaderTypeString(i) + ": " + GLES20.glGetShaderInfoLog(iGlCreateShader));
        GLES20.glDeleteShader(iGlCreateShader);
        return 0;
    }

    private static String getShaderTypeString(int i) {
        switch (i) {
            case 35632:
                return "fragment shader";
            case 35633:
                return "vertex shader";
            default:
                return "shader";
        }
    }

    public static String getGlErrorMessage(String str) {
        int iGlGetError = GLES20.glGetError();
        if (iGlGetError == 0) {
            return str;
        }
        return str + " (" + toErrorString(iGlGetError) + ")";
    }

    private static String toErrorString(int i) {
        return "glError 0x" + Integer.toHexString(i) + " " + GLU.gluErrorString(i);
    }

    public static FloatBuffer createFloatBuffer(float[] fArr) {
        FloatBuffer floatBufferAsFloatBuffer = ByteBuffer.allocateDirect(fArr.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        floatBufferAsFloatBuffer.put(fArr);
        floatBufferAsFloatBuffer.position(0);
        return floatBufferAsFloatBuffer;
    }
}
