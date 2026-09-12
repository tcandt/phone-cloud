package com.android.helper.control;

/* JADX INFO: loaded from: classes.dex */
public final class DeviceMessage {
    public static final int TYPE_ACK_CLIPBOARD = 1;
    public static final int TYPE_CLIPBOARD = 0;
    public static final int TYPE_UHID_OUTPUT = 2;
    private byte[] data;
    private int id;
    private long sequence;
    private String text;
    private int type;

    private DeviceMessage() {
    }

    public static DeviceMessage createClipboard(String str) {
        DeviceMessage deviceMessage = new DeviceMessage();
        deviceMessage.type = 0;
        deviceMessage.text = str;
        return deviceMessage;
    }

    public static DeviceMessage createAckClipboard(long j) {
        DeviceMessage deviceMessage = new DeviceMessage();
        deviceMessage.type = 1;
        deviceMessage.sequence = j;
        return deviceMessage;
    }

    public static DeviceMessage createUhidOutput(int i, byte[] bArr) {
        DeviceMessage deviceMessage = new DeviceMessage();
        deviceMessage.type = 2;
        deviceMessage.id = i;
        deviceMessage.data = bArr;
        return deviceMessage;
    }

    public int getType() {
        return this.type;
    }

    public String getText() {
        return this.text;
    }

    public long getSequence() {
        return this.sequence;
    }

    public int getId() {
        return this.id;
    }

    public byte[] getData() {
        return this.data;
    }
}
