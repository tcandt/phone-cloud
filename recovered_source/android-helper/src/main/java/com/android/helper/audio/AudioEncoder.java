package com.android.helper.audio;

import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import com.android.helper.AsyncProcessor;
import com.android.helper.Options;
import com.android.helper.device.ConfigurationException;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/* JADX INFO: loaded from: classes.dex */
public final class AudioEncoder implements AsyncProcessor {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    private static final int CHANNELS = 2;
    private static final int SAMPLE_RATE = 48000;
    private final int bitRate;
    private final AudioCapture capture;
    private final List<CodecOption> codecOptions;
    private final String encoderName;
    private boolean ended;
    private Thread inputThread;
    private HandlerThread mediaCodecThread;
    private Thread outputThread;
    private long previousPts;
    private boolean recreatePts;
    private final Streamer streamer;
    private Thread thread;
    private final BlockingQueue<InputTask> inputTasks = new ArrayBlockingQueue(64);
    private final BlockingQueue<OutputTask> outputTasks = new ArrayBlockingQueue(64);

    private static class InputTask {
        private final int index;

        InputTask(int i) {
            this.index = i;
        }
    }

    private static class OutputTask {
        private final MediaCodec.BufferInfo bufferInfo;
        private final int index;

        OutputTask(int i, MediaCodec.BufferInfo bufferInfo) {
            this.index = i;
            this.bufferInfo = bufferInfo;
        }
    }

    public AudioEncoder(AudioCapture audioCapture, Streamer streamer, Options options) {
        this.capture = audioCapture;
        this.streamer = streamer;
        this.bitRate = options.getAudioBitRate();
        this.codecOptions = options.getAudioCodecOptions();
        this.encoderName = options.getAudioEncoder();
    }

    private static MediaFormat createFormat(String str, int i, List<CodecOption> list) {
        MediaFormat mediaFormat = new MediaFormat();
        mediaFormat.setString("mime", str);
        mediaFormat.setInteger("bitrate", i);
        mediaFormat.setInteger("channel-count", 2);
        mediaFormat.setInteger("sample-rate", 48000);
        if (list != null) {
            for (CodecOption codecOption : list) {
                String key = codecOption.getKey();
                Object value = codecOption.getValue();
                CodecUtils.setCodecOption(mediaFormat, key, value);
                Ln.d("Audio codec option set: " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
            }
        }
        return mediaFormat;
    }

    private void inputThread(MediaCodec mediaCodec, AudioCapture audioCapture) throws InterruptedException, IOException {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (!Thread.currentThread().isInterrupted()) {
            InputTask inputTaskTake = this.inputTasks.take();
            int i = audioCapture.read(mediaCodec.getInputBuffer(inputTaskTake.index), bufferInfo);
            if (i > 0) {
                mediaCodec.queueInputBuffer(inputTaskTake.index, bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags);
            } else {
                throw new IOException("Could not read audio: " + i);
            }
        }
    }

    private void outputThread(MediaCodec mediaCodec) throws InterruptedException, IOException {
        this.streamer.writeAudioHeader();
        while (!Thread.currentThread().isInterrupted()) {
            OutputTask outputTaskTake = this.outputTasks.take();
            ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputTaskTake.index);
            try {
                if (this.recreatePts) {
                    fixTimestamp(outputTaskTake.bufferInfo);
                }
                this.streamer.writePacket(outputBuffer, outputTaskTake.bufferInfo);
                mediaCodec.releaseOutputBuffer(outputTaskTake.index, false);
            } catch (Throwable th) {
                mediaCodec.releaseOutputBuffer(outputTaskTake.index, false);
                throw th;
            }
        }
    }

    private void fixTimestamp(MediaCodec.BufferInfo bufferInfo) {
        if ((bufferInfo.flags & 2) != 0) {
            return;
        }
        long j = bufferInfo.presentationTimeUs;
        if (this.previousPts != 0) {
            bufferInfo.presentationTimeUs = (System.nanoTime() / 1000) - (j - this.previousPts);
        }
        this.previousPts = j;
    }

    @Override // com.android.helper.AsyncProcessor
    public void start(final AsyncProcessor.TerminationListener terminationListener) {
        Thread thread = new Thread(() -> m10lambda$start$0$comandroidhelperaudioAudioEncoder(terminationListener), "audio-encoder");
        this.thread = thread;
        thread.start();
    }

    /* JADX INFO: renamed from: lambda$start$0$com-android-helper-audio-AudioEncoder, reason: not valid java name */
    /* synthetic */ void m10lambda$start$0$comandroidhelperaudioAudioEncoder(AsyncProcessor.TerminationListener terminationListener) {
        boolean z = true;
        z = false;
        try {
            encode();
        } catch (AudioCaptureException unused) {
        } catch (ConfigurationException unused2) {
        } catch (IOException e) {
            Ln.e("Audio encoding error", e);
        } finally {
            Ln.d("Audio encoder stopped");
            terminationListener.onTerminated(z);
        }
    }

    @Override // com.android.helper.AsyncProcessor
    public void stop() {
        if (this.thread != null) {
            end();
        }
    }

    @Override // com.android.helper.AsyncProcessor
    public void join() throws InterruptedException {
        Thread thread = this.thread;
        if (thread != null) {
            thread.join();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void end() {
        this.ended = true;
        notify();
    }

    private synchronized void waitEnded() {
        while (!this.ended) {
            try {
                wait();
            } catch (InterruptedException unused) {
            }
        }
    }

    /* JADX WARN: Code duplicated, block: B:74:0x0117  */
    /* JADX WARN: Code duplicated, block: B:77:0x011e  */
    /* JADX WARN: Code duplicated, block: B:80:0x0125 A[Catch: InterruptedException -> 0x0148, TryCatch #9 {InterruptedException -> 0x0148, blocks: (B:78:0x0121, B:80:0x0125, B:81:0x0128, B:83:0x012c, B:84:0x012f, B:86:0x0133), top: B:104:0x0121 }] */
    /* JADX WARN: Code duplicated, block: B:83:0x012c A[Catch: InterruptedException -> 0x0148, TryCatch #9 {InterruptedException -> 0x0148, blocks: (B:78:0x0121, B:80:0x0125, B:81:0x0128, B:83:0x012c, B:84:0x012f, B:86:0x0133), top: B:104:0x0121 }] */
    /* JADX WARN: Code duplicated, block: B:86:0x0133 A[Catch: InterruptedException -> 0x0148, TRY_LEAVE, TryCatch #9 {InterruptedException -> 0x0148, blocks: (B:78:0x0121, B:80:0x0125, B:81:0x0128, B:83:0x012c, B:84:0x012f, B:86:0x0133), top: B:104:0x0121 }] */
    /* JADX WARN: Code duplicated, block: B:88:0x0138 A[DONT_INVERT] */
    /* JADX WARN: Code duplicated, block: B:89:0x013a  */
    private void encode() throws ConfigurationException, IOException, AudioCaptureException {
        if (Build.VERSION.SDK_INT < 30) {
            Ln.w("Audio disabled: it is not supported before Android 11");
            this.streamer.writeDisableStream(false);
            return;
        }

        this.capture.checkCompatibility();

        Codec codec = this.streamer.getCodec();
        MediaCodec mediaCodec = null;
        boolean formatConfigured = false;
        try {
            mediaCodec = createMediaCodec(codec, this.encoderName);
            String canonicalName = mediaCodec.getCanonicalName();
            this.recreatePts = "c2.android.opus.encoder".equals(canonicalName) || "c2.android.flac.encoder".equals(canonicalName);

            HandlerThread handlerThread = new HandlerThread("media-codec");
            this.mediaCodecThread = handlerThread;
            handlerThread.start();

            MediaFormat format = createFormat(codec.getMimeType(), this.bitRate, this.codecOptions);
            mediaCodec.setCallback(new EncoderCallback(), new Handler(this.mediaCodecThread.getLooper()));
            mediaCodec.configure(format, (Surface) null, (MediaCrypto) null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            formatConfigured = true;

            this.capture.start();
            final MediaCodec finalMediaCodec = mediaCodec;
            this.inputThread = new Thread(() -> m8lambda$encode$1$comandroidhelperaudioAudioEncoder(finalMediaCodec), "audio-in");
            this.outputThread = new Thread(() -> m9lambda$encode$2$comandroidhelperaudioAudioEncoder(finalMediaCodec), "audio-out");
            mediaCodec.start();

            this.inputThread.start();
            this.outputThread.start();

            waitEnded();
        } catch (ConfigurationException e) {
            this.streamer.writeDisableStream(true);
            throw e;
        } finally {
            if (this.mediaCodecThread != null) {
                Looper looper = this.mediaCodecThread.getLooper();
                if (looper != null) {
                    looper.quitSafely();
                }
            }
            if (this.inputThread != null) {
                this.inputThread.interrupt();
            }
            if (this.outputThread != null) {
                this.outputThread.interrupt();
            }
            try {
                if (this.mediaCodecThread != null) {
                    this.mediaCodecThread.join();
                }
                if (this.inputThread != null) {
                    this.inputThread.join();
                }
                if (this.outputThread != null) {
                    this.outputThread.join();
                }
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            if (mediaCodec != null) {
                if (formatConfigured) {
                    try {
                        mediaCodec.stop();
                    } catch (IllegalStateException ignored) {
                    }
                }
                mediaCodec.release();
            }
            if (this.capture != null) {
                this.capture.stop();
            }
        }
    }

    /* JADX INFO: renamed from: lambda$encode$1$com-android-helper-audio-AudioEncoder, reason: not valid java name */
    /* synthetic */ void m8lambda$encode$1$comandroidhelperaudioAudioEncoder(MediaCodec mediaCodec) {
        try {
            try {
                inputThread(mediaCodec, this.capture);
            } finally {
                end();
            }
        } catch (IOException | InterruptedException e) {
            Ln.e("Audio capture error", e);
            end();
        }
    }

    /* JADX INFO: renamed from: lambda$encode$2$com-android-helper-audio-AudioEncoder, reason: not valid java name */
    /* synthetic */ void m9lambda$encode$2$comandroidhelperaudioAudioEncoder(MediaCodec mediaCodec) {
        try {
            outputThread(mediaCodec);
        } catch (IOException e) {
            if (!IO.isBrokenPipe(e)) {
                Ln.e("Audio encoding error", e);
            }
        } catch (InterruptedException unused) {
        } finally {
            end();
        }
    }

    private static MediaCodec createMediaCodec(Codec codec, String str) throws ConfigurationException, IOException {
        if (str == null) {
            try {
                MediaCodec mediaCodecCreateEncoderByType = MediaCodec.createEncoderByType(codec.getMimeType());
                Ln.d("Using audio encoder: '" + mediaCodecCreateEncoderByType.getName() + "'");
                return mediaCodecCreateEncoderByType;
            } catch (IOException | IllegalArgumentException e) {
                Ln.e("Could not create default audio encoder for " + codec.getName() + "\n" + LogUtils.buildAudioEncoderListMessage());
                throw e;
            }
        }
        Ln.d("Creating audio encoder by name: '" + str + "'");
        try {
            MediaCodec mediaCodecCreateByCodecName = MediaCodec.createByCodecName(str);
            String mimeType = Codec.CC.getMimeType(mediaCodecCreateByCodecName);
            if (codec.getMimeType().equals(mimeType)) {
                return mediaCodecCreateByCodecName;
            }
            Ln.e("Audio encoder type for \"" + str + "\" (" + mimeType + ") does not match codec type (" + codec.getMimeType() + ")");
            throw new ConfigurationException("Incorrect encoder type: " + str);
        } catch (IOException e2) {
            Ln.e("Could not create audio encoder '" + str + "' for " + codec.getName() + "\n" + LogUtils.buildAudioEncoderListMessage());
            throw e2;
        } catch (IllegalArgumentException unused) {
            Ln.e("Audio encoder '" + str + "' for " + codec.getName() + " not found\n" + LogUtils.buildAudioEncoderListMessage());
            throw new ConfigurationException("Unknown encoder: " + str);
        }
    }

    private final class EncoderCallback extends MediaCodec.Callback {
        @Override // android.media.MediaCodec.Callback
        public void onOutputFormatChanged(MediaCodec mediaCodec, MediaFormat mediaFormat) {
        }

        private EncoderCallback() {
        }

        @Override // android.media.MediaCodec.Callback
        public void onInputBufferAvailable(MediaCodec mediaCodec, int i) {
            try {
                AudioEncoder.this.inputTasks.put(new InputTask(i));
            } catch (InterruptedException unused) {
                AudioEncoder.this.end();
            }
        }

        @Override // android.media.MediaCodec.Callback
        public void onOutputBufferAvailable(MediaCodec mediaCodec, int i, MediaCodec.BufferInfo bufferInfo) {
            try {
                AudioEncoder.this.outputTasks.put(new OutputTask(i, bufferInfo));
            } catch (InterruptedException unused) {
                AudioEncoder.this.end();
            }
        }

        @Override // android.media.MediaCodec.Callback
        public void onError(MediaCodec mediaCodec, MediaCodec.CodecException codecException) {
            Ln.e("MediaCodec error", codecException);
            AudioEncoder.this.end();
        }
    }
}
