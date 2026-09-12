package com.android.helper.device;

import android.media.MediaCodec;
import com.android.helper.audio.AudioCodec;
import com.android.helper.util.Codec;
import com.android.helper.util.IO;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/* JADX INFO: loaded from: classes.dex */
public final class Streamer {
    private static final long PACKET_FLAG_CONFIG = Long.MIN_VALUE;
    private static final long PACKET_FLAG_KEY_FRAME = 4611686018427387904L;
    private final Codec codec;
    private final FileDescriptor fd;
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);
    private final boolean sendCodecMeta;
    private final boolean sendFrameMeta;

    public Streamer(FileDescriptor fileDescriptor, Codec codec, boolean z, boolean z2) {
        this.fd = fileDescriptor;
        this.codec = codec;
        this.sendCodecMeta = z;
        this.sendFrameMeta = z2;
    }

    public Codec getCodec() {
        return this.codec;
    }

    public void writeAudioHeader() throws IOException {
        if (this.sendCodecMeta) {
            ByteBuffer byteBufferAllocate = ByteBuffer.allocate(4);
            byteBufferAllocate.putInt(this.codec.getId());
            byteBufferAllocate.flip();
            IO.writeFully(this.fd, byteBufferAllocate);
        }
    }

    public void writeVideoHeader(Size size) throws IOException {
        if (this.sendCodecMeta) {
            ByteBuffer byteBufferAllocate = ByteBuffer.allocate(12);
            byteBufferAllocate.putInt(this.codec.getId());
            byteBufferAllocate.putInt(size.getWidth());
            byteBufferAllocate.putInt(size.getHeight());
            byteBufferAllocate.flip();
            IO.writeFully(this.fd, byteBufferAllocate);
        }
    }

    public void writeDisableStream(boolean z) throws IOException {
        byte[] bArr = new byte[4];
        if (z) {
            bArr[3] = 1;
        }
        IO.writeFully(this.fd, bArr, 0, 4);
    }

    public void writePacket(ByteBuffer byteBuffer, long j, boolean z, boolean z2) throws IOException {
        Streamer streamer;
        if (z) {
            if (this.codec == AudioCodec.OPUS) {
                fixOpusConfigPacket(byteBuffer);
            } else if (this.codec == AudioCodec.FLAC) {
                fixFlacConfigPacket(byteBuffer);
            }
        }
        if (this.sendFrameMeta) {
            streamer = this;
            streamer.writeFrameMeta(this.fd, byteBuffer.remaining(), j, z, z2);
        } else {
            streamer = this;
        }
        IO.writeFully(streamer.fd, byteBuffer);
    }

    public void writePacket(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) throws IOException {
        writePacket(byteBuffer, bufferInfo.presentationTimeUs, (bufferInfo.flags & 2) != 0, (bufferInfo.flags & 1) != 0);
    }

    private void writeFrameMeta(FileDescriptor fileDescriptor, int i, long j, boolean z, boolean z2) throws IOException {
        this.headerBuffer.clear();
        if (z) {
            j = PACKET_FLAG_CONFIG;
        } else if (z2) {
            j |= PACKET_FLAG_KEY_FRAME;
        }
        this.headerBuffer.putLong(j);
        this.headerBuffer.putInt(i);
        this.headerBuffer.flip();
        IO.writeFully(fileDescriptor, this.headerBuffer);
    }

    private static void fixOpusConfigPacket(ByteBuffer byteBuffer) throws IOException {
        if (byteBuffer.remaining() < 16) {
            throw new IOException("Not enough data in OPUS config packet");
        }
        byte[] bArr = new byte[8];
        byteBuffer.get(bArr);
        if (!Arrays.equals(bArr, new byte[]{65, 79, 80, 85, 83, 72, 68, 82})) {
            throw new IOException("OPUS header not found");
        }
        long j = byteBuffer.getLong();
        if (j < 0 || j >= 2147483647L) {
            throw new IOException("Invalid block size in OPUS header: " + j);
        }
        int i = (int) j;
        if (byteBuffer.remaining() < i) {
            throw new IOException("Not enough data in OPUS header (invalid size: " + i + ")");
        }
        byteBuffer.limit(byteBuffer.position() + i);
    }

    private static void fixFlacConfigPacket(ByteBuffer byteBuffer) throws IOException {
        if (byteBuffer.remaining() < 8) {
            throw new IOException("Not enough data in FLAC config packet");
        }
        byte[] bArr = new byte[4];
        byteBuffer.get(bArr);
        if (!Arrays.equals(bArr, new byte[]{102, 76, 97, 67})) {
            throw new IOException("FLAC header not found");
        }
        byteBuffer.order(ByteOrder.BIG_ENDIAN);
        int i = byteBuffer.getInt();
        if (byteBuffer.remaining() < i) {
            throw new IOException("Not enough data in FLAC header (invalid size: " + i + ")");
        }
        byteBuffer.limit(byteBuffer.position() + i);
    }
}
