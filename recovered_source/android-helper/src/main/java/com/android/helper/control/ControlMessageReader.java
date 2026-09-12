package com.android.helper.control;

import com.android.helper.device.Position;
import com.android.helper.util.Binary;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/* JADX INFO: loaded from: classes.dex */
public class ControlMessageReader {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    public static final int CLIPBOARD_TEXT_MAX_LENGTH = 262130;
    public static final int INJECT_TEXT_MAX_LENGTH = 300;
    private static final int MESSAGE_MAX_SIZE = 262144;
    private final DataInputStream dis;

    public ControlMessageReader(InputStream inputStream) {
        this.dis = new DataInputStream(new BufferedInputStream(inputStream));
    }

    public ControlMessage read() throws IOException {
        int unsignedByte = this.dis.readUnsignedByte();
        switch (unsignedByte) {
            case 0:
                return parseInjectKeycode();
            case 1:
                return parseInjectText();
            case 2:
                return parseInjectTouchEvent();
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT /* 3 */:
                return parseInjectScrollEvent();
            case ControlMessage.TYPE_BACK_OR_SCREEN_ON /* 4 */:
                return parseBackOrScreenOnEvent();
            case ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL /* 5 */:
            case ControlMessage.TYPE_EXPAND_SETTINGS_PANEL /* 6 */:
            case ControlMessage.TYPE_COLLAPSE_PANELS /* 7 */:
            case ControlMessage.TYPE_ROTATE_DEVICE /* 11 */:
            case ControlMessage.TYPE_OPEN_HARD_KEYBOARD_SETTINGS /* 15 */:
            case ControlMessage.TYPE_RESET_VIDEO /* 17 */:
            case ControlMessage.TYPE_REQUEST_KEYFRAME /* 18 */:
                return ControlMessage.createEmpty(unsignedByte);
            case ControlMessage.TYPE_GET_CLIPBOARD /* 8 */:
                return parseGetClipboard();
            case ControlMessage.TYPE_SET_CLIPBOARD /* 9 */:
                return parseSetClipboard();
            case 10:
                return parseSetDisplayPower();
            case 12:
                return parseUhidCreate();
            case ControlMessage.TYPE_UHID_INPUT /* 13 */:
                return parseUhidInput();
            case ControlMessage.TYPE_UHID_DESTROY /* 14 */:
                return parseUhidDestroy();
            case ControlMessage.TYPE_START_APP /* 16 */:
                return parseStartApp();
            case ControlMessage.TYPE_SET_BITRATE /* 19 */:
                return parseSetBitrate();
            default:
                throw new ControlProtocolException("Unknown event type: " + unsignedByte);
        }
    }

    private ControlMessage parseSetBitrate() throws IOException {
        return ControlMessage.createSetBitrate(this.dis.readInt());
    }

    private ControlMessage parseInjectKeycode() throws IOException {
        return ControlMessage.createInjectKeycode(this.dis.readUnsignedByte(), this.dis.readInt(), this.dis.readInt(), this.dis.readInt());
    }

    private int parseBufferLength(int i) throws IOException {
        int unsignedByte = 0;
        for (int i2 = 0; i2 < i; i2++) {
            unsignedByte = (unsignedByte << 8) | this.dis.readUnsignedByte();
        }
        return unsignedByte;
    }

    private String parseString(int i) throws IOException {
        return new String(parseByteArray(i), StandardCharsets.UTF_8);
    }

    private String parseString() throws IOException {
        return parseString(4);
    }

    private byte[] parseByteArray(int i) throws IOException {
        byte[] bArr = new byte[parseBufferLength(i)];
        this.dis.readFully(bArr);
        return bArr;
    }

    private ControlMessage parseInjectText() throws IOException {
        return ControlMessage.createInjectText(parseString());
    }

    private ControlMessage parseInjectTouchEvent() throws IOException {
        return ControlMessage.createInjectTouchEvent(this.dis.readUnsignedByte(), this.dis.readLong(), parsePosition(), Binary.u16FixedPointToFloat(this.dis.readShort()), this.dis.readInt(), this.dis.readInt());
    }

    private ControlMessage parseInjectScrollEvent() throws IOException {
        return ControlMessage.createInjectScrollEvent(parsePosition(), Binary.i16FixedPointToFloat(this.dis.readShort()) * 16.0f, Binary.i16FixedPointToFloat(this.dis.readShort()) * 16.0f, this.dis.readInt());
    }

    private ControlMessage parseBackOrScreenOnEvent() throws IOException {
        return ControlMessage.createBackOrScreenOn(this.dis.readUnsignedByte());
    }

    private ControlMessage parseGetClipboard() throws IOException {
        return ControlMessage.createGetClipboard(this.dis.readUnsignedByte());
    }

    private ControlMessage parseSetClipboard() throws IOException {
        return ControlMessage.createSetClipboard(this.dis.readLong(), parseString(), this.dis.readByte() != 0);
    }

    private ControlMessage parseSetDisplayPower() throws IOException {
        return ControlMessage.createSetDisplayPower(this.dis.readBoolean());
    }

    private ControlMessage parseUhidCreate() throws IOException {
        return ControlMessage.createUhidCreate(this.dis.readUnsignedShort(), this.dis.readUnsignedShort(), this.dis.readUnsignedShort(), parseString(1), parseByteArray(2));
    }

    private ControlMessage parseUhidInput() throws IOException {
        return ControlMessage.createUhidInput(this.dis.readUnsignedShort(), parseByteArray(2));
    }

    private ControlMessage parseUhidDestroy() throws IOException {
        return ControlMessage.createUhidDestroy(this.dis.readUnsignedShort());
    }

    private ControlMessage parseStartApp() throws IOException {
        return ControlMessage.createStartApp(parseString(1));
    }

    private Position parsePosition() throws IOException {
        return new Position(this.dis.readInt(), this.dis.readInt(), this.dis.readUnsignedShort(), this.dis.readUnsignedShort());
    }
}
