package com.android.helper.control;

import android.os.Build;
import android.os.HandlerThread;
import android.os.MessageQueue;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;
import com.android.helper.audio.AudioConfig;
import com.android.helper.util.Ln;
import com.android.helper.util.StringUtils;
import com.android.helper.wrappers.ServiceManager;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/* JADX INFO: loaded from: classes.dex */
public final class UhidManager {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    private static final short BUS_VIRTUAL = 6;
    private static final String INPUT_PORT = "scrcpy:" + Os.getpid();
    private static final int SIZE_OF_UHID_EVENT = 4380;
    private static final int UHID_CREATE2 = 11;
    private static final int UHID_INPUT2 = 12;
    private static final int UHID_OUTPUT = 6;
    private final String displayUniqueId;
    private final MessageQueue queue;
    private final DeviceMessageSender sender;
    private final ArrayMap<Integer, FileDescriptor> fds = new ArrayMap<>();
    private final ByteBuffer buffer = ByteBuffer.allocate(SIZE_OF_UHID_EVENT).order(ByteOrder.nativeOrder());

    public UhidManager(DeviceMessageSender deviceMessageSender, String str) {
        this.sender = deviceMessageSender;
        this.displayUniqueId = str;
        if (Build.VERSION.SDK_INT >= 23) {
            HandlerThread handlerThread = new HandlerThread("UHidManager");
            handlerThread.start();
            this.queue = handlerThread.getLooper().getQueue();
            return;
        }
        this.queue = null;
    }

    public void open(int i, int i2, int i3, String str, byte[] bArr) throws Exception {
        try {
            FileDescriptor fileDescriptorOpen = Os.open("/dev/uhid", OsConstants.O_RDWR, 0);
            try {
                boolean zIsEmpty = this.fds.isEmpty();
                FileDescriptor fileDescriptorPut = this.fds.put(Integer.valueOf(i), fileDescriptorOpen);
                if (fileDescriptorPut != null) {
                    Ln.w("Duplicate UHID id: " + i);
                    close(fileDescriptorPut);
                }
                byte[] bArrBuildUhidCreate2Req = buildUhidCreate2Req(i2, i3, str, bArr, mustUseInputPort() ? INPUT_PORT : null);
                Os.write(fileDescriptorOpen, bArrBuildUhidCreate2Req, 0, bArrBuildUhidCreate2Req.length);
                if (zIsEmpty) {
                    addUniqueIdAssociation();
                }
                registerUhidListener(i, fileDescriptorOpen);
            } catch (Exception e) {
                close(fileDescriptorOpen);
                throw e;
            }
        } catch (ErrnoException e2) {
            throw new IOException(e2);
        }
    }

    private void registerUhidListener(final int i, FileDescriptor fileDescriptor) {
        if (Build.VERSION.SDK_INT >= 23) {
            this.queue.addOnFileDescriptorEventListener(fileDescriptor, 1, (fileDescriptor2, i2) -> m16xe22f230(i, fileDescriptor2, i2));
        }
    }

    /* JADX INFO: renamed from: lambda$registerUhidListener$0$com-android-helper-control-UhidManager, reason: not valid java name */
    /* synthetic */ int m16xe22f230(int i, FileDescriptor fileDescriptor, int i2) {
        byte[] bArrExtractHidOutputData;
        try {
            this.buffer.clear();
            int i3 = Os.read(fileDescriptor, this.buffer);
            this.buffer.flip();
            if (i3 > 0 && this.buffer.getInt() == 6 && (bArrExtractHidOutputData = extractHidOutputData(this.buffer)) != null) {
                this.sender.send(DeviceMessage.createUhidOutput(i, bArrExtractHidOutputData));
            }
            return i2;
        } catch (ErrnoException | InterruptedIOException e) {
            Ln.e("Failed to read UHID output", e);
            return 0;
        }
    }

    private void unregisterUhidListener(FileDescriptor fileDescriptor) {
        if (Build.VERSION.SDK_INT >= 23) {
            this.queue.removeOnFileDescriptorEventListener(fileDescriptor);
        }
    }

    private static byte[] extractHidOutputData(ByteBuffer byteBuffer) {
        if (byteBuffer.remaining() < 4099) {
            Ln.w("Incomplete HID output");
            return null;
        }
        int i = byteBuffer.getShort(byteBuffer.position() + AudioConfig.MAX_READ_SIZE) & 65535;
        if (i > 4096) {
            Ln.w("Incorrect HID output size: " + i);
            return null;
        }
        byte[] bArr = new byte[i];
        byteBuffer.get(bArr);
        return bArr;
    }

    public void writeInput(int i, byte[] bArr) throws IOException {
        FileDescriptor fileDescriptor = this.fds.get(Integer.valueOf(i));
        if (fileDescriptor == null) {
            Ln.w("Unknown UHID id: " + i);
        } else {
            try {
                byte[] bArrBuildUhidInput2Req = buildUhidInput2Req(bArr);
                Os.write(fileDescriptor, bArrBuildUhidInput2Req, 0, bArrBuildUhidInput2Req.length);
            } catch (ErrnoException e) {
                throw new IOException(e);
            }
        }
    }

    private static byte[] buildUhidCreate2Req(int i, int i2, String str, byte[] bArr, String str2) {
        ByteBuffer byteBufferOrder = ByteBuffer.allocate(bArr.length + 280).order(ByteOrder.nativeOrder());
        byteBufferOrder.putInt(11);
        if (str.isEmpty()) {
            str = "scrcpy";
        }
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        byteBufferOrder.put(bytes, 0, StringUtils.getUtf8TruncationIndex(bytes, 127));
        if (str2 != null) {
            byteBufferOrder.position(132);
            byteBufferOrder.put(str2.getBytes(StandardCharsets.US_ASCII));
        }
        byteBufferOrder.position(260);
        byteBufferOrder.putShort((short) bArr.length);
        byteBufferOrder.putShort(BUS_VIRTUAL);
        byteBufferOrder.putInt(i);
        byteBufferOrder.putInt(i2);
        byteBufferOrder.putInt(0);
        byteBufferOrder.putInt(0);
        byteBufferOrder.put(bArr);
        return byteBufferOrder.array();
    }

    private static byte[] buildUhidInput2Req(byte[] bArr) {
        ByteBuffer byteBufferOrder = ByteBuffer.allocate(bArr.length + 6).order(ByteOrder.nativeOrder());
        byteBufferOrder.putInt(12);
        byteBufferOrder.putShort((short) bArr.length);
        byteBufferOrder.put(bArr);
        return byteBufferOrder.array();
    }

    public void close(int i) {
        FileDescriptor fileDescriptorRemove = this.fds.remove(Integer.valueOf(i));
        if (fileDescriptorRemove != null) {
            unregisterUhidListener(fileDescriptorRemove);
            close(fileDescriptorRemove);
            if (this.fds.isEmpty()) {
                removeUniqueIdAssociation();
                return;
            }
            return;
        }
        Ln.w("Closing unknown UHID device: " + i);
    }

    public void closeAll() {
        if (this.fds.isEmpty()) {
            return;
        }
        Iterator<FileDescriptor> it = this.fds.values().iterator();
        while (it.hasNext()) {
            close(it.next());
        }
        removeUniqueIdAssociation();
    }

    private static void close(FileDescriptor fileDescriptor) {
        try {
            Os.close(fileDescriptor);
        } catch (ErrnoException e) {
            Ln.e("Failed to close uhid: " + e.getMessage());
        }
    }

    private boolean mustUseInputPort() {
        return Build.VERSION.SDK_INT >= 35 && this.displayUniqueId != null;
    }

    private void addUniqueIdAssociation() {
        if (mustUseInputPort()) {
            ServiceManager.getInputManager().addUniqueIdAssociationByPort(INPUT_PORT, this.displayUniqueId);
        }
    }

    private void removeUniqueIdAssociation() {
        if (mustUseInputPort()) {
            ServiceManager.getInputManager().removeUniqueIdAssociationByPort(INPUT_PORT);
        }
    }
}
