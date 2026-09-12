package com.android.helper.opengl;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import com.android.helper.device.Size;
import com.android.helper.util.Threads;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/* JADX INFO: loaded from: classes.dex */
public final class OpenGLRunner {
    private static Handler handler;
    private static HandlerThread handlerThread;
    private static boolean quit;
    private EGLContext eglContext;
    private EGLDisplay eglDisplay;
    private EGLSurface eglSurface;
    private final OpenGLFilter filter;
    private Surface inputSurface;
    private final float[] overrideTransformMatrix;
    private boolean stopped;
    private SurfaceTexture surfaceTexture;
    private int textureId;

    public OpenGLRunner(OpenGLFilter openGLFilter, float[] fArr) {
        this.filter = openGLFilter;
        this.overrideTransformMatrix = fArr;
    }

    public OpenGLRunner(OpenGLFilter openGLFilter) {
        this(openGLFilter, null);
    }

    public static synchronized void initOnce() {
        if (handlerThread == null) {
            if (quit) {
                throw new IllegalStateException("Could not init OpenGLRunner after it is quit");
            }
            HandlerThread handlerThread2 = new HandlerThread("OpenGLRunner");
            handlerThread = handlerThread2;
            handlerThread2.start();
            handler = new Handler(handlerThread.getLooper());
        }
    }

    public static void quit() {
        HandlerThread handlerThread2;
        synchronized (OpenGLRunner.class) {
            handlerThread2 = handlerThread;
            quit = true;
        }
        if (handlerThread2 != null) {
            handlerThread2.quitSafely();
        }
    }

    public static void join() throws InterruptedException {
        HandlerThread handlerThread2;
        synchronized (OpenGLRunner.class) {
            handlerThread2 = handlerThread;
        }
        if (handlerThread2 != null) {
            handlerThread2.join();
        }
    }

    public Surface start(final Size size, final Size size2, final Surface surface) throws OpenGLException {
        initOnce();
        try {
            Threads.executeSynchronouslyOn(handler, new Callable<Void>() { // from class: com.android.helper.opengl.OpenGLRunner.1
                @Override // java.util.concurrent.Callable
                public Void call() throws Exception {
                    OpenGLRunner.this.run(size, size2, surface);
                    return null;
                }
            });
            return this.inputSurface;
        } catch (Throwable th) {
            if (th instanceof OpenGLException) {
                throw (OpenGLException) th;
            }
            throw new OpenGLException("Asynchronous OpenGL runner init failed", th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void run(Size size, final Size size2, Surface surface) throws OpenGLException {
        EGLDisplay eGLDisplayEglGetDisplay = EGL14.eglGetDisplay(0);
        this.eglDisplay = eGLDisplayEglGetDisplay;
        if (eGLDisplayEglGetDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new OpenGLException("Unable to get EGL14 display");
        }
        int[] iArr = new int[2];
        if (!EGL14.eglInitialize(this.eglDisplay, iArr, 0, iArr, 1)) {
            throw new OpenGLException("Unable to initialize EGL14");
        }
        EGLConfig[] eGLConfigArr = new EGLConfig[1];
        int[] iArr2 = new int[1];
        EGL14.eglChooseConfig(this.eglDisplay, new int[]{12324, 8, 12323, 8, 12322, 8, 12321, 8, 12352, 4, 12344}, 0, eGLConfigArr, 0, 1, iArr2, 0);
        if (iArr2[0] <= 0) {
            EGL14.eglTerminate(this.eglDisplay);
            throw new OpenGLException("Unable to find ES2 EGL config");
        }
        EGLConfig eGLConfig = eGLConfigArr[0];
        EGLContext eGLContextEglCreateContext = EGL14.eglCreateContext(this.eglDisplay, eGLConfig, EGL14.EGL_NO_CONTEXT, new int[]{12440, 2, 12344}, 0);
        this.eglContext = eGLContextEglCreateContext;
        if (eGLContextEglCreateContext == null) {
            EGL14.eglTerminate(this.eglDisplay);
            throw new OpenGLException("Failed to create EGL context");
        }
        EGLSurface eGLSurfaceEglCreateWindowSurface = EGL14.eglCreateWindowSurface(this.eglDisplay, eGLConfig, surface, new int[]{12344}, 0);
        this.eglSurface = eGLSurfaceEglCreateWindowSurface;
        if (eGLSurfaceEglCreateWindowSurface == null) {
            EGL14.eglDestroyContext(this.eglDisplay, this.eglContext);
            EGL14.eglTerminate(this.eglDisplay);
            throw new OpenGLException("Failed to create EGL window surface");
        }
        if (!EGL14.eglMakeCurrent(this.eglDisplay, eGLSurfaceEglCreateWindowSurface, eGLSurfaceEglCreateWindowSurface, this.eglContext)) {
            EGL14.eglDestroySurface(this.eglDisplay, this.eglSurface);
            EGL14.eglDestroyContext(this.eglDisplay, this.eglContext);
            EGL14.eglTerminate(this.eglDisplay);
            throw new OpenGLException("Failed to make EGL context current");
        }
        int[] iArr3 = new int[1];
        GLES20.glGenTextures(1, iArr3, 0);
        GLUtils.checkGlError();
        this.textureId = iArr3[0];
        GLES20.glTexParameteri(36197, 10241, 9729);
        GLUtils.checkGlError();
        GLES20.glTexParameteri(36197, 10240, 9729);
        GLUtils.checkGlError();
        GLES20.glTexParameteri(36197, 10242, 33071);
        GLUtils.checkGlError();
        GLES20.glTexParameteri(36197, 10243, 33071);
        GLUtils.checkGlError();
        SurfaceTexture surfaceTexture = new SurfaceTexture(this.textureId);
        this.surfaceTexture = surfaceTexture;
        surfaceTexture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        this.inputSurface = new Surface(this.surfaceTexture);
        this.filter.init();
        this.surfaceTexture.setOnFrameAvailableListener(surfaceTexture2 -> m17lambda$run$0$comandroidhelperopenglOpenGLRunner(size2, surfaceTexture2), handler);
    }

    /* JADX INFO: renamed from: lambda$run$0$com-android-helper-opengl-OpenGLRunner, reason: not valid java name */
    /* synthetic */ void m17lambda$run$0$comandroidhelperopenglOpenGLRunner(Size size, SurfaceTexture surfaceTexture) {
        if (this.stopped) {
            return;
        }
        render(size);
    }

    private void render(Size size) {
        GLES20.glViewport(0, 0, size.getWidth(), size.getHeight());
        GLUtils.checkGlError();
        this.surfaceTexture.updateTexImage();
        float[] fArr = this.overrideTransformMatrix;
        if (fArr == null) {
            fArr = new float[16];
            this.surfaceTexture.getTransformMatrix(fArr);
        }
        this.filter.draw(this.textureId, fArr);
        EGLExt.eglPresentationTimeANDROID(this.eglDisplay, this.eglSurface, this.surfaceTexture.getTimestamp());
        EGL14.eglSwapBuffers(this.eglDisplay, this.eglSurface);
    }

    public void stopAndRelease() {
        final Semaphore semaphore = new Semaphore(0);
        handler.post(() -> m18lambda$stopAndRelease$1$comandroidhelperopenglOpenGLRunner(semaphore));
        try {
            semaphore.acquire();
        } catch (InterruptedException unused) {
            Thread.currentThread().interrupt();
        }
    }

    /* JADX INFO: renamed from: lambda$stopAndRelease$1$com-android-helper-opengl-OpenGLRunner, reason: not valid java name */
    /* synthetic */ void m18lambda$stopAndRelease$1$comandroidhelperopenglOpenGLRunner(Semaphore semaphore) {
        this.stopped = true;
        this.surfaceTexture.setOnFrameAvailableListener(null, handler);
        this.filter.release();
        GLES20.glDeleteTextures(1, new int[]{this.textureId}, 0);
        GLUtils.checkGlError();
        EGL14.eglDestroySurface(this.eglDisplay, this.eglSurface);
        EGL14.eglDestroyContext(this.eglDisplay, this.eglContext);
        EGL14.eglTerminate(this.eglDisplay);
        this.eglDisplay = EGL14.EGL_NO_DISPLAY;
        this.eglContext = EGL14.EGL_NO_CONTEXT;
        this.eglSurface = EGL14.EGL_NO_SURFACE;
        this.surfaceTexture.release();
        this.inputSurface.release();
        semaphore.release();
    }
}
