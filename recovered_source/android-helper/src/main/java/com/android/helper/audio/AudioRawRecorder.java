package com.android.helper.audio;

import android.media.MediaCodec;
import android.os.Build;
import com.android.helper.AsyncProcessor;
import com.android.helper.device.Streamer;
import com.android.helper.util.IO;
import com.android.helper.util.Ln;
import java.io.IOException;
import java.nio.ByteBuffer;

/* JADX INFO: loaded from: classes.dex */
public final class AudioRawRecorder implements AsyncProcessor {
    private final AudioCapture capture;
    private final Streamer streamer;
    private Thread thread;

    public AudioRawRecorder(AudioCapture audioCapture, Streamer streamer) {
        this.capture = audioCapture;
        this.streamer = streamer;
    }

    private void record() throws AudioCaptureException, IOException {
        if (Build.VERSION.SDK_INT < 30) {
            Ln.w("Audio disabled: it is not supported before Android 11");
            this.streamer.writeDisableStream(false);
            return;
        }
        ByteBuffer byteBufferAllocateDirect = ByteBuffer.allocateDirect(AudioConfig.MAX_READ_SIZE);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        try {
            try {
                this.capture.start();
                this.streamer.writeAudioHeader();
                while (!Thread.currentThread().isInterrupted()) {
                    byteBufferAllocateDirect.position(0);
                    int i = this.capture.read(byteBufferAllocateDirect, bufferInfo);
                    if (i < 0) {
                        throw new IOException("Could not read audio: " + i);
                    }
                    byteBufferAllocateDirect.limit(i);
                    this.streamer.writePacket(byteBufferAllocateDirect, bufferInfo);
                }
                this.capture.stop();
            } catch (Throwable th) {
                this.streamer.writeDisableStream(false);
                throw th;
            }
        } catch (IOException e) {
            if (!IO.isBrokenPipe(e)) {
                Ln.e("Audio capture error", e);
            }
        } finally {
            this.capture.stop();
        }
    }

    @Override // com.android.helper.AsyncProcessor
    public void start(final AsyncProcessor.TerminationListener terminationListener) {
        Thread thread = new Thread(() -> m11lambda$start$0$comandroidhelperaudioAudioRawRecorder(terminationListener), "audio-raw");
        this.thread = thread;
        thread.start();
    }

    /* JADX INFO: renamed from: lambda$start$0$com-android-helper-audio-AudioRawRecorder, reason: not valid java name */
    /* synthetic */ void m11lambda$start$0$comandroidhelperaudioAudioRawRecorder(AsyncProcessor.TerminationListener terminationListener) {
        boolean z = false;
        try {
            record();
            Ln.d("Audio recorder stopped");
            terminationListener.onTerminated(z);
        } catch (AudioCaptureException unused) {
            Ln.d("Audio recorder stopped");
            terminationListener.onTerminated(z);
        } catch (Throwable th) {
            try {
                Ln.e("Audio recording error", th);
                Ln.d("Audio recorder stopped");
                z = true;
            } finally {
                Ln.d("Audio recorder stopped");
                terminationListener.onTerminated(z);
            }
        }
    }

    @Override // com.android.helper.AsyncProcessor
    public void stop() {
        Thread thread = this.thread;
        if (thread != null) {
            thread.interrupt();
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
