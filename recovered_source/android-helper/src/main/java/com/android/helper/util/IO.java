package com.android.helper.util;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Scanner;

/* JADX INFO: loaded from: classes.dex */
public final class IO {
    private IO() {
    }

    private static int write(FileDescriptor fileDescriptor, ByteBuffer byteBuffer) throws IOException {
        ErrnoException lastErr = null;
        do {
            try {
                return Os.write(fileDescriptor, byteBuffer);
            } catch (ErrnoException e) {
                lastErr = e;
            }
        } while (lastErr != null && lastErr.errno == OsConstants.EINTR);
        throw new IOException(lastErr);
    }

    public static void writeFully(FileDescriptor fileDescriptor, ByteBuffer byteBuffer) throws IOException {
        if (Build.VERSION.SDK_INT >= 23) {
            while (byteBuffer.hasRemaining()) {
                write(fileDescriptor, byteBuffer);
            }
            return;
        }
        int iPosition = byteBuffer.position();
        int iRemaining = byteBuffer.remaining();
        while (iRemaining > 0) {
            int iWrite = write(fileDescriptor, byteBuffer);
            iRemaining -= iWrite;
            iPosition += iWrite;
            byteBuffer.position(iPosition);
        }
    }

    public static void writeFully(FileDescriptor fileDescriptor, byte[] bArr, int i, int i2) throws IOException {
        writeFully(fileDescriptor, ByteBuffer.wrap(bArr, i, i2));
    }

    public static String toString(InputStream inputStream) {
        StringBuilder sb = new StringBuilder();
        Scanner scanner = new Scanner(inputStream);
        while (scanner.hasNextLine()) {
            sb.append(scanner.nextLine());
            sb.append('\n');
        }
        return sb.toString();
    }

    public static boolean isBrokenPipe(IOException iOException) {
        Throwable cause = iOException.getCause();
        return (cause instanceof ErrnoException) && ((ErrnoException) cause).errno == OsConstants.EPIPE;
    }

    public static boolean isBrokenPipe(Exception exc) {
        return (exc instanceof IOException) && isBrokenPipe((IOException) exc);
    }
}
