package com.android.helper.video;

import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import com.android.helper.AsyncProcessor;
import com.android.helper.Options;
import com.android.helper.device.ConfigurationException;
import com.android.helper.device.Size;
import com.android.helper.device.Streamer;
import com.android.helper.util.Codec;
import com.android.helper.util.CodecOption;
import com.android.helper.util.CodecUtils;
import com.android.helper.util.IO;
import com.android.helper.util.Ln;
import com.android.helper.util.LogUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/* JADX INFO: loaded from: classes.dex */
public class SurfaceEncoder implements AsyncProcessor {
    private static final int DEFAULT_I_FRAME_INTERVAL = 10;
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";
    private static final int MAX_CONSECUTIVE_ERRORS = 3;
    private static final int REPEAT_FRAME_DELAY_US = 100000;
    private static MediaCodec activeCodec;
    private static boolean pendingKeyFrame;
    private final SurfaceCapture capture;
    private final List<CodecOption> codecOptions;
    private int consecutiveErrors;
    private final boolean downsizeOnError;
    private final String encoderName;
    private boolean firstFrameSent;
    private final float maxFps;
    private final Streamer streamer;
    private Thread thread;
    private final int videoBitRate;
    private static final int[] MAX_SIZE_FALLBACK = {2560, 1920, 1600, 1280, 1024, 800};
    private static final Object codecLock = new Object();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final CaptureReset reset = new CaptureReset();

    public static void requestKeyFrame() {
        synchronized (codecLock) {
            if (activeCodec != null) {
                try {
                    long jElapsedRealtime = SystemClock.elapsedRealtime();
                    Bundle bundle = new Bundle();
                    bundle.putInt("request-sync", 0);
                    activeCodec.setParameters(bundle);
                    Ln.d("KeyframeTrace request-sync-frame write_ms=" + (SystemClock.elapsedRealtime() - jElapsedRealtime));
                } catch (IllegalStateException e) {
                    Ln.w("KeyframeTrace request-sync-frame failed: " + e.getMessage());
                }
            } else {
                pendingKeyFrame = true;
                Ln.d("KeyframeTrace request-sync-frame deferred: no active codec yet, marked pending");
            }
        }
    }

    public static void setVideoBitrate(int i) {
        synchronized (codecLock) {
            if (activeCodec != null) {
                try {
                    long jElapsedRealtime = SystemClock.elapsedRealtime();
                    Bundle bundle = new Bundle();
                    bundle.putInt("video-bitrate", i);
                    activeCodec.setParameters(bundle);
                    Ln.d("BweTrace set-bitrate bitrate=" + i + " write_ms=" + (SystemClock.elapsedRealtime() - jElapsedRealtime));
                } catch (IllegalStateException e) {
                    Ln.w("BweTrace set-bitrate failed: " + e.getMessage());
                }
            } else {
                Ln.w("BweTrace set-bitrate skipped: no active codec bitrate=" + i);
            }
        }
    }

    public SurfaceEncoder(SurfaceCapture surfaceCapture, Streamer streamer, Options options) {
        this.capture = surfaceCapture;
        this.streamer = streamer;
        this.videoBitRate = options.getVideoBitRate();
        this.maxFps = options.getMaxFps();
        this.codecOptions = options.getVideoCodecOptions();
        this.encoderName = options.getVideoEncoder();
        this.downsizeOnError = options.getDownsizeOnError();
    }

    /* JADX WARN: Code duplicated, block: B:102:0x01ad A[Catch: all -> 0x01f7, TRY_LEAVE, TryCatch #8 {all -> 0x01f7, blocks: (B:3:0x0023, B:5:0x0036, B:6:0x003c, B:50:0x011b, B:51:0x011d, B:54:0x0123, B:55:0x012d, B:56:0x0130, B:58:0x0135, B:62:0x013c, B:118:0x01d2, B:119:0x01d4, B:122:0x01da, B:124:0x01e1, B:126:0x01e8, B:127:0x01eb, B:129:0x01f0, B:130:0x01f3, B:133:0x01f6, B:96:0x019e, B:97:0x01a0, B:100:0x01a6, B:102:0x01ad, B:104:0x01b4, B:105:0x01b7, B:107:0x01bc, B:113:0x01cc, B:120:0x01d5, B:121:0x01d9, B:52:0x011e, B:53:0x0122, B:98:0x01a1, B:99:0x01a5), top: B:146:0x0023, inners: #4, #9, #17 }] */
    /* JADX WARN: Code duplicated, block: B:107:0x01bc A[Catch: all -> 0x01f7, TRY_LEAVE, TryCatch #8 {all -> 0x01f7, blocks: (B:3:0x0023, B:5:0x0036, B:6:0x003c, B:50:0x011b, B:51:0x011d, B:54:0x0123, B:55:0x012d, B:56:0x0130, B:58:0x0135, B:62:0x013c, B:118:0x01d2, B:119:0x01d4, B:122:0x01da, B:124:0x01e1, B:126:0x01e8, B:127:0x01eb, B:129:0x01f0, B:130:0x01f3, B:133:0x01f6, B:96:0x019e, B:97:0x01a0, B:100:0x01a6, B:102:0x01ad, B:104:0x01b4, B:105:0x01b7, B:107:0x01bc, B:113:0x01cc, B:120:0x01d5, B:121:0x01d9, B:52:0x011e, B:53:0x0122, B:98:0x01a1, B:99:0x01a5), top: B:146:0x0023, inners: #4, #9, #17 }] */
    /* JADX WARN: Code duplicated, block: B:124:0x01e1 A[Catch: all -> 0x01f7, TRY_LEAVE, TryCatch #8 {all -> 0x01f7, blocks: (B:3:0x0023, B:5:0x0036, B:6:0x003c, B:50:0x011b, B:51:0x011d, B:54:0x0123, B:55:0x012d, B:56:0x0130, B:58:0x0135, B:62:0x013c, B:118:0x01d2, B:119:0x01d4, B:122:0x01da, B:124:0x01e1, B:126:0x01e8, B:127:0x01eb, B:129:0x01f0, B:130:0x01f3, B:133:0x01f6, B:96:0x019e, B:97:0x01a0, B:100:0x01a6, B:102:0x01ad, B:104:0x01b4, B:105:0x01b7, B:107:0x01bc, B:113:0x01cc, B:120:0x01d5, B:121:0x01d9, B:52:0x011e, B:53:0x0122, B:98:0x01a1, B:99:0x01a5), top: B:146:0x0023, inners: #4, #9, #17 }] */
    /* JADX WARN: Code duplicated, block: B:129:0x01f0 A[Catch: all -> 0x01f7, TryCatch #8 {all -> 0x01f7, blocks: (B:3:0x0023, B:5:0x0036, B:6:0x003c, B:50:0x011b, B:51:0x011d, B:54:0x0123, B:55:0x012d, B:56:0x0130, B:58:0x0135, B:62:0x013c, B:118:0x01d2, B:119:0x01d4, B:122:0x01da, B:124:0x01e1, B:126:0x01e8, B:127:0x01eb, B:129:0x01f0, B:130:0x01f3, B:133:0x01f6, B:96:0x019e, B:97:0x01a0, B:100:0x01a6, B:102:0x01ad, B:104:0x01b4, B:105:0x01b7, B:107:0x01bc, B:113:0x01cc, B:120:0x01d5, B:121:0x01d9, B:52:0x011e, B:53:0x0122, B:98:0x01a1, B:99:0x01a5), top: B:146:0x0023, inners: #4, #9, #17 }] */
    /* JADX WARN: Code duplicated, block: B:142:0x01b4 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Code duplicated, block: B:144:0x01d5 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Code duplicated, block: B:149:0x01e8 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Code duplicated, block: B:155:0x01a1 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Code duplicated, block: B:169:0x01ce A[SYNTHETIC] */
    /* JADX WARN: Code duplicated, block: B:170:0x01cd A[SYNTHETIC] */
    /* JADX WARN: Code duplicated, block: B:174:0x01bf A[SYNTHETIC] */
    /* JADX WARN: Code duplicated, block: B:177:? A[Catch: all -> 0x01f7, SYNTHETIC, TRY_LEAVE, TryCatch #8 {all -> 0x01f7, blocks: (B:3:0x0023, B:5:0x0036, B:6:0x003c, B:50:0x011b, B:51:0x011d, B:54:0x0123, B:55:0x012d, B:56:0x0130, B:58:0x0135, B:62:0x013c, B:118:0x01d2, B:119:0x01d4, B:122:0x01da, B:124:0x01e1, B:126:0x01e8, B:127:0x01eb, B:129:0x01f0, B:130:0x01f3, B:133:0x01f6, B:96:0x019e, B:97:0x01a0, B:100:0x01a6, B:102:0x01ad, B:104:0x01b4, B:105:0x01b7, B:107:0x01bc, B:113:0x01cc, B:120:0x01d5, B:121:0x01d9, B:52:0x011e, B:53:0x0122, B:98:0x01a1, B:99:0x01a5), top: B:146:0x0023, inners: #4, #9, #17 }] */
    /* JADX WARN: Code duplicated, block: B:42:0x00fc  */
    /* JADX WARN: Code duplicated, block: B:94:0x0170 A[Catch: all -> 0x01cf, TRY_LEAVE, TryCatch #19 {all -> 0x01cf, blocks: (B:92:0x016a, B:94:0x0170, B:114:0x01cd, B:115:0x01ce), top: B:157:0x016a }] */
    /* JADX WARN: Code duplicated, block: B:96:0x019e A[Catch: all -> 0x01f7, TRY_ENTER, TryCatch #8 {all -> 0x01f7, blocks: (B:3:0x0023, B:5:0x0036, B:6:0x003c, B:50:0x011b, B:51:0x011d, B:54:0x0123, B:55:0x012d, B:56:0x0130, B:58:0x0135, B:62:0x013c, B:118:0x01d2, B:119:0x01d4, B:122:0x01da, B:124:0x01e1, B:126:0x01e8, B:127:0x01eb, B:129:0x01f0, B:130:0x01f3, B:133:0x01f6, B:96:0x019e, B:97:0x01a0, B:100:0x01a6, B:102:0x01ad, B:104:0x01b4, B:105:0x01b7, B:107:0x01bc, B:113:0x01cc, B:120:0x01d5, B:121:0x01d9, B:52:0x011e, B:53:0x0122, B:98:0x01a1, B:99:0x01a5), top: B:146:0x0023, inners: #4, #9, #17 }] */
    /* JADX WARN: Instruction removed from duplicated block: B:94:0x0170, please report this as an issue */
    private void streamCapture() throws ConfigurationException, IOException {
        boolean z;
        Surface surfaceCreateInputSurface = null;
        boolean z2;
        boolean z3;
        Exception e;
        Throwable th;
        Codec codec = this.streamer.getCodec();
        MediaCodec mediaCodecCreateMediaCodec = createMediaCodec(codec, this.encoderName);
        MediaFormat mediaFormatCreateFormat = createFormat(codec.getMimeType(), this.videoBitRate, this.maxFps, this.codecOptions);
        this.capture.init(this.reset);
        boolean z4 = false;
        do {
            try {
                this.reset.consumeReset();
                this.capture.prepare();
                Size size = this.capture.getSize();
                z = true;
                if (!z4) {
                    this.streamer.writeVideoHeader(size);
                    z4 = true;
                }
                mediaFormatCreateFormat.setInteger("width", size.getWidth());
                mediaFormatCreateFormat.setInteger("height", size.getHeight());
                try {
                    try {
                        mediaCodecCreateMediaCodec.configure(mediaFormatCreateFormat, (Surface) null, (MediaCrypto) null, 1);
                    } catch (MediaCodec.CodecException codecEx) {
                        e = codecEx;
                        if (this.codecOptions == null || this.codecOptions.isEmpty()) {
                            throw e;
                        }
                        Ln.w("Codec configuration failed with custom options, retrying with default format fallback. Error: " + e.getMessage());
                        MediaFormat mediaFormatCreateFormat2 = createFormat(codec.getMimeType(), this.videoBitRate, this.maxFps, null);
                        mediaFormatCreateFormat2.setInteger("width", size.getWidth());
                        mediaFormatCreateFormat2.setInteger("height", size.getHeight());
                        mediaCodecCreateMediaCodec.configure(mediaFormatCreateFormat2, (Surface) null, (MediaCrypto) null, 1);
                        z2 = false;
                        z3 = false;
                        try {
                            if (IO.isBrokenPipe(e)) {
                                throw e;
                            }
                            Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                            if (!prepareRetry(size)) {
                                throw e;
                            }
                            synchronized (codecLock) {
                                activeCodec = null;
                                pendingKeyFrame = false;
                            }
                            this.reset.setRunningMediaCodec(null);
                            if (z3) {
                                this.capture.stop();
                            }
                            if (z2) {
                                try {
                                    mediaCodecCreateMediaCodec.stop();
                                } catch (IllegalStateException unused) {
                                }
                            }
                            mediaCodecCreateMediaCodec.reset();
                            if (surfaceCreateInputSurface != null) {
                                surfaceCreateInputSurface.release();
                            }
                        } catch (Throwable t) {
                            th = t;
                            surfaceCreateInputSurface = surfaceCreateInputSurface;
                            z = z3;
                            synchronized (codecLock) {
                                activeCodec = null;
                                pendingKeyFrame = false;
                            }
                            this.reset.setRunningMediaCodec(null);
                            if (z) {
                                this.capture.stop();
                            }
                            if (z2) {
                                try {
                                    mediaCodecCreateMediaCodec.stop();
                                } catch (IllegalStateException unused2) {
                                }
                            }
                            mediaCodecCreateMediaCodec.reset();
                            if (surfaceCreateInputSurface == null) {
                                throw th;
                            }
                            surfaceCreateInputSurface.release();
                            throw th;
                        }
                    }
                    surfaceCreateInputSurface = mediaCodecCreateMediaCodec.createInputSurface();
                    try {
                        this.capture.start(surfaceCreateInputSurface);
                        try {
                            mediaCodecCreateMediaCodec.start();
                            try {
                                synchronized (codecLock) {
                                    try {
                                        activeCodec = mediaCodecCreateMediaCodec;
                                        if (pendingKeyFrame) {
                                            pendingKeyFrame = false;
                                            try {
                                                Bundle bundle = new Bundle();
                                                bundle.putInt("request-sync", 0);
                                                activeCodec.setParameters(bundle);
                                                Ln.d("KeyframeTrace request-sync-frame (deferred) applied successfully");
                                            } catch (IllegalStateException e2) {
                                                Ln.w("KeyframeTrace request-sync-frame (deferred) failed: " + e2.getMessage());
                                            }
                                        }
                                    } catch (Throwable th2) {
                                        throw th2;
                                    }
                                }
                                this.reset.setRunningMediaCodec(mediaCodecCreateMediaCodec);
                                if (!this.stopped.get()) {
                                    if (!this.reset.consumeReset()) {
                                        encode(mediaCodecCreateMediaCodec, this.streamer);
                                    }
                                    z = (this.stopped.get() || this.capture.isClosed()) ? false : true;
                                }
                                synchronized (codecLock) {
                                    activeCodec = null;
                                    pendingKeyFrame = false;
                                }
                                this.reset.setRunningMediaCodec(null);
                                this.capture.stop();
                                try {
                                    mediaCodecCreateMediaCodec.stop();
                                } catch (IllegalStateException unused3) {
                                }
                                mediaCodecCreateMediaCodec.reset();
                                if (surfaceCreateInputSurface != null) {
                                    surfaceCreateInputSurface.release();
                                }
                            } catch (IOException e3) {
                                e = e3;
                                e = e;
                                z2 = true;
                                z3 = true;
                                if (IO.isBrokenPipe(e)) {
                                    throw e;
                                }
                                Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                                if (!prepareRetry(size)) {
                                    throw e;
                                }
                                synchronized (codecLock) {
                                    activeCodec = null;
                                    pendingKeyFrame = false;
                                    this.reset.setRunningMediaCodec(null);
                                    if (z3) {
                                        this.capture.stop();
                                    }
                                    if (z2) {
                                        mediaCodecCreateMediaCodec.stop();
                                    }
                                    mediaCodecCreateMediaCodec.reset();
                                    if (surfaceCreateInputSurface != null) {
                                        surfaceCreateInputSurface.release();
                                    }
                                }
                            } catch (IllegalArgumentException e4) {
                                e = e4;
                                e = e;
                                z2 = true;
                                z3 = true;
                                if (IO.isBrokenPipe(e)) {
                                    throw e;
                                }
                                Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                                if (!prepareRetry(size)) {
                                    throw e;
                                }
                                synchronized (codecLock) {
                                    activeCodec = null;
                                    pendingKeyFrame = false;
                                    this.reset.setRunningMediaCodec(null);
                                    if (z3) {
                                        this.capture.stop();
                                    }
                                    if (z2) {
                                        mediaCodecCreateMediaCodec.stop();
                                    }
                                    mediaCodecCreateMediaCodec.reset();
                                    if (surfaceCreateInputSurface != null) {
                                        surfaceCreateInputSurface.release();
                                    }
                                }
                            } catch (IllegalStateException e5) {
                                e = e5;
                                e = e;
                                z2 = true;
                                z3 = true;
                                if (IO.isBrokenPipe(e)) {
                                    throw e;
                                }
                                Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                                if (!prepareRetry(size)) {
                                    throw e;
                                }
                                synchronized (codecLock) {
                                    activeCodec = null;
                                    pendingKeyFrame = false;
                                    this.reset.setRunningMediaCodec(null);
                                    if (z3) {
                                        this.capture.stop();
                                    }
                                    if (z2) {
                                        mediaCodecCreateMediaCodec.stop();
                                    }
                                    mediaCodecCreateMediaCodec.reset();
                                    if (surfaceCreateInputSurface != null) {
                                        surfaceCreateInputSurface.release();
                                    }
                                }
                            } catch (Throwable th3) {
                                th = th3;
                                z2 = true;
                                synchronized (codecLock) {
                                    activeCodec = null;
                                    pendingKeyFrame = false;
                                    this.reset.setRunningMediaCodec(null);
                                    if (z) {
                                        this.capture.stop();
                                    }
                                    if (z2) {
                                        mediaCodecCreateMediaCodec.stop();
                                    }
                                    mediaCodecCreateMediaCodec.reset();
                                    if (surfaceCreateInputSurface == null) {
                                        throw th;
                                    }
                                    surfaceCreateInputSurface.release();
                                    throw th;
                                }
                            }
                        } catch (IOException e6) {
                            e = e6;
                            e = e;
                            z2 = false;
                            z3 = true;
                            if (IO.isBrokenPipe(e)) {
                                throw e;
                            }
                            Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                            if (!prepareRetry(size)) {
                                throw e;
                            }
                            synchronized (codecLock) {
                                activeCodec = null;
                                pendingKeyFrame = false;
                                this.reset.setRunningMediaCodec(null);
                                if (z3) {
                                    this.capture.stop();
                                }
                                if (z2) {
                                    mediaCodecCreateMediaCodec.stop();
                                }
                                mediaCodecCreateMediaCodec.reset();
                                if (surfaceCreateInputSurface != null) {
                                    surfaceCreateInputSurface.release();
                                }
                            }
                        } catch (IllegalArgumentException e7) {
                            e = e7;
                            e = e;
                            z2 = false;
                            z3 = true;
                            if (IO.isBrokenPipe(e)) {
                                throw e;
                            }
                            Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                            if (!prepareRetry(size)) {
                                throw e;
                            }
                            synchronized (codecLock) {
                                activeCodec = null;
                                pendingKeyFrame = false;
                                this.reset.setRunningMediaCodec(null);
                                if (z3) {
                                    this.capture.stop();
                                }
                                if (z2) {
                                    mediaCodecCreateMediaCodec.stop();
                                }
                                mediaCodecCreateMediaCodec.reset();
                                if (surfaceCreateInputSurface != null) {
                                    surfaceCreateInputSurface.release();
                                }
                            }
                        } catch (IllegalStateException e8) {
                            e = e8;
                            e = e;
                            z2 = false;
                            z3 = true;
                            if (IO.isBrokenPipe(e)) {
                                throw e;
                            }
                            Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                            if (!prepareRetry(size)) {
                                throw e;
                            }
                            synchronized (codecLock) {
                                activeCodec = null;
                                pendingKeyFrame = false;
                                this.reset.setRunningMediaCodec(null);
                                if (z3) {
                                    this.capture.stop();
                                }
                                if (z2) {
                                    mediaCodecCreateMediaCodec.stop();
                                }
                                mediaCodecCreateMediaCodec.reset();
                                if (surfaceCreateInputSurface != null) {
                                    surfaceCreateInputSurface.release();
                                }
                            }
                        } catch (Throwable th4) {
                            th = th4;
                            z2 = false;
                            synchronized (codecLock) {
                                activeCodec = null;
                                pendingKeyFrame = false;
                                this.reset.setRunningMediaCodec(null);
                                if (z) {
                                    this.capture.stop();
                                }
                                if (z2) {
                                    mediaCodecCreateMediaCodec.stop();
                                }
                                mediaCodecCreateMediaCodec.reset();
                                if (surfaceCreateInputSurface == null) {
                                    throw th;
                                }
                                surfaceCreateInputSurface.release();
                                throw th;
                            }
                        }
                    } catch (IOException e9) {
                        e = e9;
                        Exception exc = e;
                        surfaceCreateInputSurface = surfaceCreateInputSurface;
                        e = exc;
                        z2 = false;
                        z3 = false;
                        if (IO.isBrokenPipe(e)) {
                            throw e;
                        }
                        Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                        if (!prepareRetry(size)) {
                            throw e;
                        }
                        synchronized (codecLock) {
                            activeCodec = null;
                            pendingKeyFrame = false;
                            this.reset.setRunningMediaCodec(null);
                            if (z3) {
                                this.capture.stop();
                            }
                            if (z2) {
                                mediaCodecCreateMediaCodec.stop();
                            }
                            mediaCodecCreateMediaCodec.reset();
                            if (surfaceCreateInputSurface != null) {
                                surfaceCreateInputSurface.release();
                            }
                        }
                    } catch (IllegalArgumentException e10) {
                        e = e10;
                        Exception exc2 = e;
                        surfaceCreateInputSurface = surfaceCreateInputSurface;
                        e = exc2;
                        z2 = false;
                        z3 = false;
                        if (IO.isBrokenPipe(e)) {
                            throw e;
                        }
                        Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                        if (!prepareRetry(size)) {
                            throw e;
                        }
                        synchronized (codecLock) {
                            activeCodec = null;
                            pendingKeyFrame = false;
                            this.reset.setRunningMediaCodec(null);
                            if (z3) {
                                this.capture.stop();
                            }
                            if (z2) {
                                mediaCodecCreateMediaCodec.stop();
                            }
                            mediaCodecCreateMediaCodec.reset();
                            if (surfaceCreateInputSurface != null) {
                                surfaceCreateInputSurface.release();
                            }
                        }
                    } catch (IllegalStateException e11) {
                        e = e11;
                        Exception exc3 = e;
                        surfaceCreateInputSurface = surfaceCreateInputSurface;
                        e = exc3;
                        z2 = false;
                        z3 = false;
                        if (IO.isBrokenPipe(e)) {
                            throw e;
                        }
                        Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                        if (!prepareRetry(size)) {
                            throw e;
                        }
                        synchronized (codecLock) {
                            activeCodec = null;
                            pendingKeyFrame = false;
                            this.reset.setRunningMediaCodec(null);
                            if (z3) {
                                this.capture.stop();
                            }
                            if (z2) {
                                mediaCodecCreateMediaCodec.stop();
                            }
                            mediaCodecCreateMediaCodec.reset();
                            if (surfaceCreateInputSurface != null) {
                                surfaceCreateInputSurface.release();
                            }
                        }
                    } catch (Throwable th5) {
                        th = th5;
                        z = false;
                        z2 = false;
                        synchronized (codecLock) {
                            activeCodec = null;
                            pendingKeyFrame = false;
                            this.reset.setRunningMediaCodec(null);
                            if (z) {
                                this.capture.stop();
                            }
                            if (z2) {
                                mediaCodecCreateMediaCodec.stop();
                            }
                            mediaCodecCreateMediaCodec.reset();
                            if (surfaceCreateInputSurface == null) {
                                throw th;
                            }
                            surfaceCreateInputSurface.release();
                            throw th;
                        }
                    }
                } catch (IOException e12) {
                    e = e12;
                    surfaceCreateInputSurface = null;
                    z2 = false;
                    z3 = false;
                    if (IO.isBrokenPipe(e)) {
                        throw e;
                    }
                    Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                    if (!prepareRetry(size)) {
                        throw e;
                    }
                    synchronized (codecLock) {
                        activeCodec = null;
                        pendingKeyFrame = false;
                        this.reset.setRunningMediaCodec(null);
                        if (z3) {
                            this.capture.stop();
                        }
                        if (z2) {
                            mediaCodecCreateMediaCodec.stop();
                        }
                        mediaCodecCreateMediaCodec.reset();
                        if (surfaceCreateInputSurface != null) {
                            surfaceCreateInputSurface.release();
                        }
                    }
                } catch (IllegalArgumentException e13) {
                    e = e13;
                    surfaceCreateInputSurface = null;
                    z2 = false;
                    z3 = false;
                    if (IO.isBrokenPipe(e)) {
                        throw e;
                    }
                    Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                    if (!prepareRetry(size)) {
                        throw e;
                    }
                    synchronized (codecLock) {
                        activeCodec = null;
                        pendingKeyFrame = false;
                        this.reset.setRunningMediaCodec(null);
                        if (z3) {
                            this.capture.stop();
                        }
                        if (z2) {
                            mediaCodecCreateMediaCodec.stop();
                        }
                        mediaCodecCreateMediaCodec.reset();
                        if (surfaceCreateInputSurface != null) {
                            surfaceCreateInputSurface.release();
                        }
                    }
                } catch (IllegalStateException e14) {
                    e = e14;
                    surfaceCreateInputSurface = null;
                    z2 = false;
                    z3 = false;
                    if (IO.isBrokenPipe(e)) {
                        throw e;
                    }
                    Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                    if (!prepareRetry(size)) {
                        throw e;
                    }
                    synchronized (codecLock) {
                        activeCodec = null;
                        pendingKeyFrame = false;
                        this.reset.setRunningMediaCodec(null);
                        if (z3) {
                            this.capture.stop();
                        }
                        if (z2) {
                            mediaCodecCreateMediaCodec.stop();
                        }
                        mediaCodecCreateMediaCodec.reset();
                        if (surfaceCreateInputSurface != null) {
                            surfaceCreateInputSurface.release();
                        }
                    }
                } catch (Throwable th6) {
                    th = th6;
                    surfaceCreateInputSurface = null;
                    z = false;
                    z2 = false;
                    synchronized (codecLock) {
                        activeCodec = null;
                        pendingKeyFrame = false;
                        this.reset.setRunningMediaCodec(null);
                        if (z) {
                            this.capture.stop();
                        }
                        if (z2) {
                            mediaCodecCreateMediaCodec.stop();
                        }
                        mediaCodecCreateMediaCodec.reset();
                        if (surfaceCreateInputSurface == null) {
                            throw th;
                        }
                        surfaceCreateInputSurface.release();
                        throw th;
                    }
                }
            } catch (Throwable th7) {
                mediaCodecCreateMediaCodec.release();
                this.capture.release();
                if (th7 instanceof IOException) {
                    throw (IOException) th7;
                }
                if (th7 instanceof ConfigurationException) {
                    throw (ConfigurationException) th7;
                }
                throw new RuntimeException(th7);
            }
        } while (z);
        mediaCodecCreateMediaCodec.release();
        this.capture.release();
    }

    private boolean prepareRetry(Size size) {
        int iChooseMaxSizeFallback;
        if (this.firstFrameSent) {
            int i = this.consecutiveErrors + 1;
            this.consecutiveErrors = i;
            if (i >= 3) {
                return false;
            }
            SystemClock.sleep(50L);
            return true;
        }
        if (!this.downsizeOnError || (iChooseMaxSizeFallback = chooseMaxSizeFallback(size)) == 0 || !this.capture.setMaxSize(iChooseMaxSizeFallback)) {
            return false;
        }
        Ln.i("Retrying with -m" + iChooseMaxSizeFallback + "...");
        return true;
    }

    private static int chooseMaxSizeFallback(Size size) {
        int iMax = Math.max(size.getWidth(), size.getHeight());
        for (int i : MAX_SIZE_FALLBACK) {
            if (i < iMax) {
                return i;
            }
        }
        return 0;
    }

    private void encode(MediaCodec mediaCodec, Streamer streamer) throws IOException {
        long j;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long jCurrentTimeMillis = System.currentTimeMillis();
        long j2 = 0;
        long j3 = 0;
        while (true) {
            long jElapsedRealtime = SystemClock.elapsedRealtime();
            int iDequeueOutputBuffer = mediaCodec.dequeueOutputBuffer(bufferInfo, -1L);
            long jElapsedRealtime2 = SystemClock.elapsedRealtime();
            try {
                boolean z = (bufferInfo.flags & 4) != 0;
                if (iDequeueOutputBuffer < 0 || bufferInfo.size <= 0) {
                    j = jCurrentTimeMillis;
                } else {
                    ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(iDequeueOutputBuffer);
                    j = jCurrentTimeMillis;
                    boolean z2 = (bufferInfo.flags & 2) != 0;
                    boolean z3 = (bufferInfo.flags & 1) != 0;
                    if (!z2) {
                        this.firstFrameSent = true;
                        this.consecutiveErrors = 0;
                        j2++;
                    }
                    long jElapsedRealtime3 = SystemClock.elapsedRealtime();
                    streamer.writePacket(outputBuffer, bufferInfo);
                    long jElapsedRealtime4 = SystemClock.elapsedRealtime();
                    long j4 = j3 == 0 ? 0L : jElapsedRealtime2 - j3;
                    long j5 = jElapsedRealtime2 - jElapsedRealtime;
                    long j6 = jElapsedRealtime4 - jElapsedRealtime3;
                    if (!z2 && Ln.isEnabled(Ln.Level.DEBUG) && (z3 || j4 >= 200 || j5 >= 200 || j6 >= 20)) {
                        Ln.d("VideoTrace encoder-out frame=" + j2 + " pts=" + bufferInfo.presentationTimeUs + " size=" + bufferInfo.size + " key=" + z3 + " dequeue_ms=" + j5 + " write_ms=" + j6 + " inter_ms=" + j4 + " eos=" + z);
                    }
                    if (j2 % 120 == 0) {
                        long jCurrentTimeMillis2 = System.currentTimeMillis();
                        long j7 = jCurrentTimeMillis2 - j;
                        if (j7 > 0) {
                            Ln.d("Encoder Stats: FPS=" + String.format("%.1f", Float.valueOf(120000.0f / j7)));
                        }
                        j = jCurrentTimeMillis2;
                    }
                    j2 = j2;
                    j3 = jElapsedRealtime2;
                }
                if (iDequeueOutputBuffer >= 0) {
                    mediaCodec.releaseOutputBuffer(iDequeueOutputBuffer, false);
                }
                if (z) {
                    return;
                } else {
                    jCurrentTimeMillis = j;
                }
            } catch (Throwable th) {
                if (iDequeueOutputBuffer >= 0) {
                    mediaCodec.releaseOutputBuffer(iDequeueOutputBuffer, false);
                }
                throw th;
            }
        }
    }

    private static MediaCodec createMediaCodec(Codec codec, String str) throws ConfigurationException, IOException {
        if (str == null) {
            try {
                MediaCodec mediaCodecCreateEncoderByType = MediaCodec.createEncoderByType(codec.getMimeType());
                Ln.d("Using video encoder: '" + mediaCodecCreateEncoderByType.getName() + "'");
                return mediaCodecCreateEncoderByType;
            } catch (IOException | IllegalArgumentException e) {
                Ln.e("Could not create default video encoder for " + codec.getName() + "\n" + LogUtils.buildVideoEncoderListMessage());
                throw e;
            }
        }
        Ln.d("Creating encoder by name: '" + str + "'");
        try {
            MediaCodec mediaCodecCreateByCodecName = MediaCodec.createByCodecName(str);
            String mimeType = Codec.CC.getMimeType(mediaCodecCreateByCodecName);
            if (codec.getMimeType().equals(mimeType)) {
                return mediaCodecCreateByCodecName;
            }
            Ln.e("Video encoder type for \"" + str + "\" (" + mimeType + ") does not match codec type (" + codec.getMimeType() + ")");
            throw new ConfigurationException("Incorrect encoder type: " + str);
        } catch (IOException e2) {
            Ln.e("Could not create video encoder '" + str + "' for " + codec.getName() + "\n" + LogUtils.buildVideoEncoderListMessage());
            throw e2;
        } catch (IllegalArgumentException unused) {
            Ln.e("Video encoder '" + str + "' for " + codec.getName() + " not found\n" + LogUtils.buildVideoEncoderListMessage());
            throw new ConfigurationException("Unknown encoder: " + str);
        }
    }

    private static MediaFormat createFormat(String str, int i, float f, List<CodecOption> list) {
        MediaFormat mediaFormat = new MediaFormat();
        mediaFormat.setString("mime", str);
        mediaFormat.setInteger("bitrate", i);
        mediaFormat.setInteger("frame-rate", 60);
        mediaFormat.setInteger("color-format", 2130708361);
        if (Build.VERSION.SDK_INT >= 24) {
            mediaFormat.setInteger("color-range", 2);
        }
        mediaFormat.setInteger("i-frame-interval", 10);
        mediaFormat.setLong("repeat-previous-frame-after", 100000L);
        if (f > 0.0f) {
            mediaFormat.setFloat(KEY_MAX_FPS_TO_ENCODER, f);
        }
        if (list != null) {
            for (CodecOption codecOption : list) {
                String key = codecOption.getKey();
                Object value = codecOption.getValue();
                CodecUtils.setCodecOption(mediaFormat, key, value);
                Ln.d("Video codec option set: " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
            }
        }
        return mediaFormat;
    }

    @Override // com.android.helper.AsyncProcessor
    public void start(final AsyncProcessor.TerminationListener terminationListener) {
        Thread thread = new Thread(() -> m20lambda$start$0$comandroidhelpervideoSurfaceEncoder(terminationListener), "video");
        this.thread = thread;
        thread.start();
    }

    /* JADX WARN: Code duplicated, block: B:11:0x0019 A[Catch: all -> 0x0010, TRY_LEAVE, TryCatch #0 {all -> 0x0010, IOException -> 0x0012, ConfigurationException -> 0x0026, IOException -> 0x0012, blocks: (B:3:0x0006, B:9:0x0013, B:11:0x0019), top: B:18:0x0006 }] */
    /* JADX WARN: Code duplicated, block: B:8:0x0012 A[ExcHandler: IOException -> 0x0012] */
    /* JADX INFO: renamed from: lambda$start$0$com-android-helper-video-SurfaceEncoder, reason: not valid java name */
    /* synthetic */ void m20lambda$start$0$comandroidhelpervideoSurfaceEncoder(AsyncProcessor.TerminationListener terminationListener) {
        Looper.prepare();
        try {
            streamCapture();
        } catch (IOException e) {
            if (!IO.isBrokenPipe(e)) {
                Ln.e("Video encoding error", e);
            }
        } catch (ConfigurationException unused) {
        } finally {
            Ln.d("Screen streaming stopped");
            terminationListener.onTerminated(true);
        }
    }

    @Override // com.android.helper.AsyncProcessor
    public void stop() {
        if (this.thread != null) {
            this.stopped.set(true);
            this.reset.reset();
        }
    }

    @Override // com.android.helper.AsyncProcessor
    public void join() throws InterruptedException {
        Thread thread = this.thread;
        if (thread != null) {
            thread.join();
        }
    }
}
