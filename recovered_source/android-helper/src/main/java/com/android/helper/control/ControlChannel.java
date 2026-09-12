package com.android.helper.control;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/* JADX INFO: loaded from: classes.dex */
public final class ControlChannel {
    private final ControlMessageReader reader;
    private final DeviceMessageWriter writer;

    public ControlChannel(InputStream inputStream, OutputStream outputStream) throws IOException {
        this.reader = new ControlMessageReader(inputStream);
        this.writer = new DeviceMessageWriter(outputStream);
    }

    public ControlMessage recv() throws IOException {
        return this.reader.read();
    }

    public void send(DeviceMessage deviceMessage) throws IOException {
        this.writer.write(deviceMessage);
    }
}
