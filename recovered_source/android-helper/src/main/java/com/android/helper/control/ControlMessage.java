package com.android.helper.control;

import com.android.helper.device.Position;

/* JADX INFO: loaded from: classes.dex */
public final class ControlMessage {
    public static final int COPY_KEY_COPY = 1;
    public static final int COPY_KEY_CUT = 2;
    public static final int COPY_KEY_NONE = 0;
    public static final long SEQUENCE_INVALID = 0;
    public static final int TYPE_BACK_OR_SCREEN_ON = 4;
    public static final int TYPE_COLLAPSE_PANELS = 7;
    public static final int TYPE_EXPAND_NOTIFICATION_PANEL = 5;
    public static final int TYPE_EXPAND_SETTINGS_PANEL = 6;
    public static final int TYPE_GET_CLIPBOARD = 8;
    public static final int TYPE_INJECT_KEYCODE = 0;
    public static final int TYPE_INJECT_SCROLL_EVENT = 3;
    public static final int TYPE_INJECT_TEXT = 1;
    public static final int TYPE_INJECT_TOUCH_EVENT = 2;
    public static final int TYPE_OPEN_HARD_KEYBOARD_SETTINGS = 15;
    public static final int TYPE_REQUEST_KEYFRAME = 18;
    public static final int TYPE_RESET_VIDEO = 17;
    public static final int TYPE_ROTATE_DEVICE = 11;
    public static final int TYPE_SET_BITRATE = 19;
    public static final int TYPE_SET_CLIPBOARD = 9;
    public static final int TYPE_SET_DISPLAY_POWER = 10;
    public static final int TYPE_START_APP = 16;
    public static final int TYPE_UHID_CREATE = 12;
    public static final int TYPE_UHID_DESTROY = 14;
    public static final int TYPE_UHID_INPUT = 13;
    private int action;
    private int actionButton;
    private int bitrate;
    private int buttons;
    private int copyKey;
    private byte[] data;
    private float hScroll;
    private int id;
    private int keycode;
    private int metaState;
    private boolean on;
    private boolean paste;
    private long pointerId;
    private Position position;
    private float pressure;
    private int productId;
    private int repeat;
    private long sequence;
    private String text;
    private int type;
    private float vScroll;
    private int vendorId;

    private ControlMessage() {
    }

    public static ControlMessage createInjectKeycode(int i, int i2, int i3, int i4) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 0;
        controlMessage.action = i;
        controlMessage.keycode = i2;
        controlMessage.repeat = i3;
        controlMessage.metaState = i4;
        return controlMessage;
    }

    public static ControlMessage createInjectText(String str) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 1;
        controlMessage.text = str;
        return controlMessage;
    }

    public static ControlMessage createInjectTouchEvent(int i, long j, Position position, float f, int i2, int i3) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 2;
        controlMessage.action = i;
        controlMessage.pointerId = j;
        controlMessage.pressure = f;
        controlMessage.position = position;
        controlMessage.actionButton = i2;
        controlMessage.buttons = i3;
        return controlMessage;
    }

    public static ControlMessage createInjectScrollEvent(Position position, float f, float f2, int i) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 3;
        controlMessage.position = position;
        controlMessage.hScroll = f;
        controlMessage.vScroll = f2;
        controlMessage.buttons = i;
        return controlMessage;
    }

    public static ControlMessage createBackOrScreenOn(int i) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 4;
        controlMessage.action = i;
        return controlMessage;
    }

    public static ControlMessage createGetClipboard(int i) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 8;
        controlMessage.copyKey = i;
        return controlMessage;
    }

    public static ControlMessage createSetClipboard(long j, String str, boolean z) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 9;
        controlMessage.sequence = j;
        controlMessage.text = str;
        controlMessage.paste = z;
        return controlMessage;
    }

    public static ControlMessage createSetDisplayPower(boolean z) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 10;
        controlMessage.on = z;
        return controlMessage;
    }

    public static ControlMessage createEmpty(int i) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = i;
        return controlMessage;
    }

    public static ControlMessage createUhidCreate(int i, int i2, int i3, String str, byte[] bArr) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 12;
        controlMessage.id = i;
        controlMessage.vendorId = i2;
        controlMessage.productId = i3;
        controlMessage.text = str;
        controlMessage.data = bArr;
        return controlMessage;
    }

    public static ControlMessage createUhidInput(int i, byte[] bArr) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 13;
        controlMessage.id = i;
        controlMessage.data = bArr;
        return controlMessage;
    }

    public static ControlMessage createUhidDestroy(int i) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 14;
        controlMessage.id = i;
        return controlMessage;
    }

    public static ControlMessage createStartApp(String str) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 16;
        controlMessage.text = str;
        return controlMessage;
    }

    public static ControlMessage createSetBitrate(int i) {
        ControlMessage controlMessage = new ControlMessage();
        controlMessage.type = 19;
        controlMessage.bitrate = i;
        return controlMessage;
    }

    public int getType() {
        return this.type;
    }

    public String getText() {
        return this.text;
    }

    public int getMetaState() {
        return this.metaState;
    }

    public int getAction() {
        return this.action;
    }

    public int getKeycode() {
        return this.keycode;
    }

    public int getActionButton() {
        return this.actionButton;
    }

    public int getButtons() {
        return this.buttons;
    }

    public long getPointerId() {
        return this.pointerId;
    }

    public float getPressure() {
        return this.pressure;
    }

    public Position getPosition() {
        return this.position;
    }

    public float getHScroll() {
        return this.hScroll;
    }

    public float getVScroll() {
        return this.vScroll;
    }

    public int getCopyKey() {
        return this.copyKey;
    }

    public boolean getPaste() {
        return this.paste;
    }

    public int getRepeat() {
        return this.repeat;
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

    public boolean getOn() {
        return this.on;
    }

    public int getVendorId() {
        return this.vendorId;
    }

    public int getProductId() {
        return this.productId;
    }

    public int getBitrate() {
        return this.bitrate;
    }
}
