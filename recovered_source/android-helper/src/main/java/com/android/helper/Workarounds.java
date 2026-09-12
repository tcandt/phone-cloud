package com.android.helper;

import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.media.AudioRecord;
import android.os.Build;
import android.os.Looper;
import android.os.Parcel;
import com.android.helper.audio.AudioCaptureException;
import com.android.helper.util.Ln;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/* JADX INFO: loaded from: classes.dex */
public final class Workarounds {
    private static final Object ACTIVITY_THREAD;
    private static final Class<?> ACTIVITY_THREAD_CLASS;

    static {
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            ACTIVITY_THREAD_CLASS = cls;
            Constructor<?> declaredConstructor = cls.getDeclaredConstructor(null);
            declaredConstructor.setAccessible(true);
            Object objNewInstance = declaredConstructor.newInstance(null);
            ACTIVITY_THREAD = objNewInstance;
            Field declaredField = cls.getDeclaredField("sCurrentActivityThread");
            declaredField.setAccessible(true);
            declaredField.set(null, objNewInstance);
            Field declaredField2 = cls.getDeclaredField("mSystemThread");
            declaredField2.setAccessible(true);
            declaredField2.setBoolean(objNewInstance, true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Workarounds() {
    }

    public static void apply() {
        if (Build.VERSION.SDK_INT >= 31) {
            fillConfigurationController();
        }
        if (!Build.BRAND.equalsIgnoreCase("ONYX")) {
            fillAppInfo();
        }
        fillAppContext();
    }

    private static void fillAppInfo() {
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> declaredConstructor = cls.getDeclaredConstructor(null);
            declaredConstructor.setAccessible(true);
            Object objNewInstance = declaredConstructor.newInstance(null);
            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = FakeContext.PACKAGE_NAME;
            Field declaredField = cls.getDeclaredField("appInfo");
            declaredField.setAccessible(true);
            declaredField.set(objNewInstance, applicationInfo);
            Field declaredField2 = ACTIVITY_THREAD_CLASS.getDeclaredField("mBoundApplication");
            declaredField2.setAccessible(true);
            declaredField2.set(ACTIVITY_THREAD, objNewInstance);
        } catch (Throwable th) {
            Ln.d("Could not fill app info: " + th.getMessage());
        }
    }

    private static void fillAppContext() {
        try {
            Application applicationNewApplication = Instrumentation.newApplication(Application.class, FakeContext.get());
            Field declaredField = ACTIVITY_THREAD_CLASS.getDeclaredField("mInitialApplication");
            declaredField.setAccessible(true);
            declaredField.set(ACTIVITY_THREAD, applicationNewApplication);
        } catch (Throwable th) {
            Ln.d("Could not fill app context: " + th.getMessage());
        }
    }

    private static void fillConfigurationController() {
        try {
            Constructor<?> declaredConstructor = Class.forName("android.app.ConfigurationController").getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"));
            declaredConstructor.setAccessible(true);
            Object obj = ACTIVITY_THREAD;
            Object objNewInstance = declaredConstructor.newInstance(obj);
            Field declaredField = ACTIVITY_THREAD_CLASS.getDeclaredField("mConfigurationController");
            declaredField.setAccessible(true);
            declaredField.set(obj, objNewInstance);
        } catch (Throwable th) {
            Ln.d("Could not fill configuration: " + th.getMessage());
        }
    }

    static Context getSystemContext() {
        try {
            return (Context) ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext", null).invoke(ACTIVITY_THREAD, null);
        } catch (Throwable th) {
            Ln.d("Could not get system context: " + th.getMessage());
            return null;
        }
    }

    public static AudioRecord createAudioRecord(int i, int i2, int i3, int i4, int i5, int i6) throws AudioCaptureException {
        char c;
        int iIntValue;
        try {
            Constructor declaredConstructor = AudioRecord.class.getDeclaredConstructor(Long.TYPE);
            declaredConstructor.setAccessible(true);
            AudioRecord audioRecord = (AudioRecord) declaredConstructor.newInstance(0L);
            Field declaredField = AudioRecord.class.getDeclaredField("mRecordingState");
            declaredField.setAccessible(true);
            declaredField.set(audioRecord, 1);
            Looper looperMyLooper = Looper.myLooper();
            if (looperMyLooper == null) {
                looperMyLooper = Looper.getMainLooper();
            }
            Field declaredField2 = AudioRecord.class.getDeclaredField("mInitializationLooper");
            declaredField2.setAccessible(true);
            declaredField2.set(audioRecord, looperMyLooper);
            AudioAttributes.Builder builder = new AudioAttributes.Builder();
            AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", Integer.TYPE).invoke(builder, Integer.valueOf(i));
            AudioAttributes audioAttributesBuild = builder.build();
            Field declaredField3 = AudioRecord.class.getDeclaredField("mAudioAttributes");
            declaredField3.setAccessible(true);
            declaredField3.set(audioRecord, audioAttributesBuild);
            Class cls = Integer.TYPE;
            Method declaredMethod = AudioRecord.class.getDeclaredMethod("audioParamCheck", cls, cls, cls);
            declaredMethod.setAccessible(true);
            declaredMethod.invoke(audioRecord, Integer.valueOf(i), Integer.valueOf(i2), Integer.valueOf(i6));
            Field declaredField4 = AudioRecord.class.getDeclaredField("mChannelCount");
            declaredField4.setAccessible(true);
            declaredField4.set(audioRecord, Integer.valueOf(i4));
            Field declaredField5 = AudioRecord.class.getDeclaredField("mChannelMask");
            declaredField5.setAccessible(true);
            declaredField5.set(audioRecord, Integer.valueOf(i5));
            int minBufferSize = AudioRecord.getMinBufferSize(i2, i3, i6) * 8;
            Method declaredMethod2 = AudioRecord.class.getDeclaredMethod("audioBuffSizeCheck", Integer.TYPE);
            declaredMethod2.setAccessible(true);
            declaredMethod2.invoke(audioRecord, Integer.valueOf(minBufferSize));
            int[] iArr = {i2};
            int[] iArr2 = {0};
            if (Build.VERSION.SDK_INT < 31) {
                Class cls2 = Integer.TYPE;
                Method declaredMethod3 = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class, cls2, cls2, cls2, cls2, int[].class, String.class, Long.TYPE);
                declaredMethod3.setAccessible(true);
                iIntValue = ((Integer) declaredMethod3.invoke(audioRecord, new WeakReference(audioRecord), audioAttributesBuild, iArr, Integer.valueOf(i5), 0, Integer.valueOf(audioRecord.getAudioFormat()), Integer.valueOf(minBufferSize), iArr2, FakeContext.get().getOpPackageName(), 0L)).intValue();
                c = 0;
            } else {
                AttributionSource attributionSource = FakeContext.get().getAttributionSource();
                c = 0;
                Method declaredMethod4 = FakeContext$$ExternalSyntheticApiModelOutline0.m3m().getDeclaredMethod("asScopedParcelState", null);
                declaredMethod4.setAccessible(true);
                AutoCloseable autoCloseable = (AutoCloseable) declaredMethod4.invoke(attributionSource, null);
                try {
                    Parcel parcel = (Parcel) autoCloseable.getClass().getDeclaredMethod("getParcel", null).invoke(autoCloseable, null);
                    if (Build.VERSION.SDK_INT < 34) {
                        Class cls3 = Integer.TYPE;
                        Method declaredMethod5 = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class, cls3, cls3, cls3, cls3, int[].class, Parcel.class, Long.TYPE, cls3);
                        declaredMethod5.setAccessible(true);
                        iIntValue = ((Integer) declaredMethod5.invoke(audioRecord, new WeakReference(audioRecord), audioAttributesBuild, iArr, Integer.valueOf(i5), 0, Integer.valueOf(audioRecord.getAudioFormat()), Integer.valueOf(minBufferSize), iArr2, parcel, 0L, 0)).intValue();
                    } else {
                        Class cls4 = Integer.TYPE;
                        Method declaredMethod6 = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class, cls4, cls4, cls4, cls4, int[].class, Parcel.class, Long.TYPE, cls4, cls4);
                        declaredMethod6.setAccessible(true);
                        iIntValue = ((Integer) declaredMethod6.invoke(audioRecord, new WeakReference(audioRecord), audioAttributesBuild, iArr, Integer.valueOf(i5), 0, Integer.valueOf(audioRecord.getAudioFormat()), Integer.valueOf(minBufferSize), iArr2, parcel, 0L, 0, 0)).intValue();
                    }
                    if (autoCloseable != null) {
                        Workarounds$$ExternalSyntheticThrowIAE3.m(autoCloseable);
                    }
                } catch (Throwable th) {
                    if (autoCloseable == null) {
                        throw th;
                    }
                    try {
                        Workarounds$$ExternalSyntheticThrowIAE3.m(autoCloseable);
                        throw th;
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                        throw th;
                    }
                }
            }
            if (iIntValue != 0) {
                Ln.e("Error code " + iIntValue + " when initializing native AudioRecord object.");
                throw new RuntimeException("Cannot create AudioRecord");
            }
            Field declaredField6 = AudioRecord.class.getDeclaredField("mSampleRate");
            declaredField6.setAccessible(true);
            declaredField6.set(audioRecord, Integer.valueOf(iArr[c]));
            Field declaredField7 = AudioRecord.class.getDeclaredField("mSessionId");
            declaredField7.setAccessible(true);
            declaredField7.set(audioRecord, Integer.valueOf(iArr2[c]));
            Field declaredField8 = AudioRecord.class.getDeclaredField("mState");
            declaredField8.setAccessible(true);
            declaredField8.set(audioRecord, 1);
            return audioRecord;
        } catch (Exception e) {
            Ln.e("Cannot create AudioRecord", e);
            throw new AudioCaptureException();
        }
    }
}
