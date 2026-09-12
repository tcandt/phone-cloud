package com.android.helper;

import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import com.android.helper.audio.AudioCapture;
import com.android.helper.audio.AudioCodec;
import com.android.helper.audio.AudioDirectCapture;
import com.android.helper.audio.AudioEncoder;
import com.android.helper.audio.AudioPlaybackCapture;
import com.android.helper.audio.AudioRawRecorder;
import com.android.helper.audio.AudioSource;
import com.android.helper.control.ControlChannel;
import com.android.helper.control.Controller;
import com.android.helper.device.ConfigurationException;
import com.android.helper.device.DesktopConnection;
import com.android.helper.device.Device;
import com.android.helper.device.Streamer;
import com.android.helper.opengl.OpenGLRunner;
import com.android.helper.util.Ln;
import com.android.helper.util.LogUtils;
import com.android.helper.video.CameraCapture;
import com.android.helper.video.NewDisplayCapture;
import com.android.helper.video.ScreenCapture;
import com.android.helper.video.SurfaceCapture;
import com.android.helper.video.SurfaceEncoder;
import com.android.helper.video.VideoSource;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;

/* JADX INFO: loaded from: classes.dex */
public final class CoreService {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    public static final String SERVER_PATH = System.getProperty("java.class.path").split(File.pathSeparator)[0];

    /* JADX INFO: Access modifiers changed from: private */
    static class Completion {
        private boolean fatalError;
        private int running;

        Completion(int i) {
            this.running = i;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public synchronized void addCompleted(boolean z) {
            int i = this.running - 1;
            this.running = i;
            if (z) {
                this.fatalError = true;
            }
            if (i == 0 || this.fatalError) {
                Looper.getMainLooper().quitSafely();
            }
        }
    }

    private CoreService() {
    }

    private static void scrcpy(Options options) throws Throwable {
        PowerManager.WakeLock wakeLockNewWakeLock;
        SurfaceCapture cameraCapture;
        AudioCapture audioPlaybackCapture;
        AsyncProcessor audioEncoder;
        if (Build.VERSION.SDK_INT < 31 && options.getVideoSource() == VideoSource.CAMERA) {
            Ln.e("Camera mirroring is not supported before Android 12");
            throw new ConfigurationException("Camera mirroring is not supported");
        }
        if (Build.VERSION.SDK_INT < 29) {
            if (options.getNewDisplay() != null) {
                Ln.e("New virtual display is not supported before Android 10");
                throw new ConfigurationException("New virtual display is not supported");
            }
            if (options.getDisplayImePolicy() != -1) {
                Ln.e("Display IME policy is not supported before Android 10");
                throw new ConfigurationException("Display IME policy is not supported");
            }
        }
        Controller controller = null;
        CleanUp cleanUpStart = options.getCleanup() ? CleanUp.start(options) : null;
        options.getScid();
        options.isTunnelForward();
        boolean control = options.getControl();
        boolean video = options.getVideo();
        boolean audio = options.getAudio();
        options.getSendDummyByte();
        Workarounds.apply();
        if (options.getStayAwake()) {
            try {
                PowerManager powerManager = (PowerManager) FakeContext.get().getSystemService("power");
                if (powerManager != null) {
                    wakeLockNewWakeLock = powerManager.newWakeLock(805306394, "scrcpy:stay_awake");
                    try {
                        wakeLockNewWakeLock.acquire();
                        Ln.i("WakeLock acquired successfully to keep device awake");
                    } catch (Throwable th) {
                        Ln.e("Failed to acquire WakeLock", th);
                    }
                } else {
                    Ln.w("PowerManager is null, cannot acquire WakeLock");
                    wakeLockNewWakeLock = null;
                }
            } catch (Throwable th2) {
                Ln.e("Failed to obtain PowerManager", th2);
                wakeLockNewWakeLock = null;
            }
        } else {
            wakeLockNewWakeLock = null;
        }
        ArrayList arrayList = new ArrayList();
        DesktopConnection desktopConnectionOpen = DesktopConnection.open(options);
        try {
            if (options.getSendDeviceMeta()) {
                desktopConnectionOpen.sendDeviceMeta(Device.getDeviceName());
            }
            if (control) {
                Controller controller2 = new Controller(desktopConnectionOpen.getControlChannel(), cleanUpStart, options);
                arrayList.add(controller2);
                ControlChannel touchChannel = desktopConnectionOpen.getTouchChannel();
                if (touchChannel != null) {
                    arrayList.add(new Controller(touchChannel, cleanUpStart, options));
                }
                controller = controller2;
            }
            if (audio) {
                AudioCodec audioCodec = options.getAudioCodec();
                AudioSource audioSource = options.getAudioSource();
                if (audioSource.isDirect()) {
                    audioPlaybackCapture = new AudioDirectCapture(audioSource);
                } else {
                    audioPlaybackCapture = new AudioPlaybackCapture(options.getAudioDup());
                }
                Streamer streamer = new Streamer(desktopConnectionOpen.getAudioFd(), audioCodec, options.getSendCodecMeta(), options.getSendFrameMeta());
                if (audioCodec == AudioCodec.RAW) {
                    audioEncoder = new AudioRawRecorder(audioPlaybackCapture, streamer);
                } else {
                    audioEncoder = new AudioEncoder(audioPlaybackCapture, streamer, options);
                }
                arrayList.add(audioEncoder);
            }
            if (video) {
                Streamer streamer2 = new Streamer(desktopConnectionOpen.getVideoFd(), options.getVideoCodec(), options.getSendCodecMeta(), options.getSendFrameMeta());
                if (options.getVideoSource() == VideoSource.DISPLAY) {
                    if (options.getNewDisplay() != null) {
                        cameraCapture = new NewDisplayCapture(controller, options);
                    } else {
                        cameraCapture = new ScreenCapture(controller, options);
                    }
                } else {
                    cameraCapture = new CameraCapture(options);
                }
                arrayList.add(new SurfaceEncoder(cameraCapture, streamer2, options));
                if (controller != null) {
                    controller.setSurfaceCapture(cameraCapture);
                }
            }
            final Completion completion = new Completion(arrayList.size());
            Iterator it = arrayList.iterator();
            while (it.hasNext()) {
                ((AsyncProcessor) it.next()).start(new AsyncProcessor.TerminationListener() { // from class: com.android.helper.CoreService$$ExternalSyntheticLambda0
                    @Override // com.android.helper.AsyncProcessor.TerminationListener
                    public final void onTerminated(boolean z) {
                        completion.addCompleted(z);
                    }
                });
            }
            Looper.loop();
        } finally {
            if (wakeLockNewWakeLock != null) {
                try {
                    if (wakeLockNewWakeLock.isHeld()) {
                        wakeLockNewWakeLock.release();
                        Ln.i("WakeLock released successfully");
                    }
                } catch (Throwable th3) {
                    Ln.e("Failed to release WakeLock", th3);
                }
            }
            if (cleanUpStart != null) {
                cleanUpStart.interrupt();
            }
            Iterator it2 = arrayList.iterator();
            while (it2.hasNext()) {
                ((AsyncProcessor) it2.next()).stop();
            }
            OpenGLRunner.quit();
            desktopConnectionOpen.shutdown();
            if (cleanUpStart != null) {
                try {
                    cleanUpStart.join();
                } catch (InterruptedException unused) {
                    desktopConnectionOpen.close();
                }
            }
            Iterator it3 = arrayList.iterator();
            while (it3.hasNext()) {
                ((AsyncProcessor) it3.next()).join();
            }
            OpenGLRunner.join();
            desktopConnectionOpen.close();
        }
    }

    private static void prepareMainLooper() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        synchronized (Looper.class) {
            try {
                try {
                    Field declaredField = Looper.class.getDeclaredField("sMainLooper");
                    declaredField.setAccessible(true);
                    declaredField.set(null, Looper.myLooper());
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    public static void main(String... strArr) {
        int i = 0;
        try {
            internalMain(strArr);
            System.exit(i);
        } catch (Throwable th) {
            try {
                Ln.e(th.getMessage(), th);
                i = 1;
            } finally {
                System.exit(i);
            }
        }
    }

    private static void internalMain(String... strArr) throws Exception {
        prepareMainLooper();
        Workarounds.apply();
        final Thread.UncaughtExceptionHandler defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() { // from class: com.android.helper.CoreService$$ExternalSyntheticLambda1
            @Override // java.lang.Thread.UncaughtExceptionHandler
            public final void uncaughtException(Thread thread, Throwable th) {
                CoreService.lambda$internalMain$1(defaultUncaughtExceptionHandler, thread, th);
            }
        });
        prepareMainLooper();
        Options options = Options.parse(strArr);
        Ln.disableSystemStreams();
        Ln.initLogLevel(options.getLogLevel());
        Ln.i("Device: [" + Build.MANUFACTURER + "] " + Build.BRAND + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
        if (options.getList()) {
            if (options.getCleanup()) {
                CleanUp.unlinkSelf();
            }
            if (options.getListEncoders()) {
                Ln.i(LogUtils.buildVideoEncoderListMessage());
                Ln.i(LogUtils.buildAudioEncoderListMessage());
            }
            if (options.getListDisplays()) {
                Ln.i(LogUtils.buildDisplayListMessage());
            }
            if (options.getListCameras() || options.getListCameraSizes()) {
                Workarounds.apply();
                Ln.i(LogUtils.buildCameraListMessage(options.getListCameraSizes()));
            }
            if (options.getListApps()) {
                Workarounds.apply();
                Ln.i("Processing Android apps... (this may take some time)");
                Ln.i(LogUtils.buildAppListMessage());
                return;
            }
            return;
        }
        try {
            scrcpy(options);
        } catch (ConfigurationException unused) {
        } catch (Throwable th) {
            Ln.e("Scrcpy error", th);
        }
    }

    static /* synthetic */ void lambda$internalMain$1(Thread.UncaughtExceptionHandler uncaughtExceptionHandler, Thread thread, Throwable th) {
        Ln.e("Exception on thread " + thread, th);
        if (uncaughtExceptionHandler != null) {
            uncaughtExceptionHandler.uncaughtException(thread, th);
        }
    }
}
