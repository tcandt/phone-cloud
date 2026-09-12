package com.android.helper.control;

import com.android.helper.util.Ln;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/* JADX INFO: loaded from: classes.dex */
public final class DeviceMessageSender {
    private final ControlChannel controlChannel;
    private final BlockingQueue<DeviceMessage> queue = new ArrayBlockingQueue(16);
    private Thread thread;

    public DeviceMessageSender(ControlChannel controlChannel) {
        this.controlChannel = controlChannel;
    }

    public void send(DeviceMessage deviceMessage) {
        if (this.queue.offer(deviceMessage)) {
            return;
        }
        Ln.w("Device message dropped: " + deviceMessage.getType());
    }

    private void loop() throws InterruptedException, IOException {
        while (!Thread.currentThread().isInterrupted()) {
            this.controlChannel.send(this.queue.take());
        }
    }

    public void start() {
        Thread thread = new Thread(this::m15lambda$start$0$comandroidhelpercontrolDeviceMessageSender, "control-send");
        this.thread = thread;
        thread.start();
    }

    /* JADX INFO: renamed from: lambda$start$0$com-android-helper-control-DeviceMessageSender, reason: not valid java name */
    /* synthetic */ void m15lambda$start$0$comandroidhelpercontrolDeviceMessageSender() {
        try {
            loop();
        } catch (IOException | InterruptedException unused) {
        } finally {
            Ln.d("Device message sender stopped");
        }
    }

    public void stop() {
        Thread thread = this.thread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    public void join() throws InterruptedException {
        Thread thread = this.thread;
        if (thread != null) {
            thread.join();
        }
    }
}
