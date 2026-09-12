package com.android.helper.control;

import com.android.helper.util.StringUtils;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/* JADX INFO: loaded from: classes.dex */
public class DeviceMessageWriter {
    public static final int CLIPBOARD_TEXT_MAX_LENGTH = 262139;
    private static final int MESSAGE_MAX_SIZE = 262144;
    private final DataOutputStream dos;

    public DeviceMessageWriter(OutputStream outputStream) {
        this.dos = new DataOutputStream(new BufferedOutputStream(outputStream));
    }

    public void write(DeviceMessage deviceMessage) throws IOException {
        int type = deviceMessage.getType();
        this.dos.writeByte(type);
        if (type == 0) {
            byte[] bytes = deviceMessage.getText().getBytes(StandardCharsets.UTF_8);
            int utf8TruncationIndex = StringUtils.getUtf8TruncationIndex(bytes, CLIPBOARD_TEXT_MAX_LENGTH);
            this.dos.writeInt(utf8TruncationIndex);
            this.dos.write(bytes, 0, utf8TruncationIndex);
        } else if (type == 1) {
            this.dos.writeLong(deviceMessage.getSequence());
        } else if (type == 2) {
            this.dos.writeShort(deviceMessage.getId());
            byte[] data = deviceMessage.getData();
            this.dos.writeShort(data.length);
            this.dos.write(data);
        } else {
            throw new ControlProtocolException("Unknown event type: " + type);
        }
        this.dos.flush();
    }
}
