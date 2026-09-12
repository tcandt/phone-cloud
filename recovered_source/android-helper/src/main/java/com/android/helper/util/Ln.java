package com.android.helper.util;

import android.util.Log;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/* JADX INFO: loaded from: classes.dex */
public final class Ln {
    private static final String PREFIX = "[server] ";
    private static final String TAG = "scrcpy";
    private static final PrintStream CONSOLE_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out));
    private static final PrintStream CONSOLE_ERR = new PrintStream(new FileOutputStream(FileDescriptor.err));
    private static Level threshold = Level.INFO;

    public enum Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    private Ln() {
    }

    public static void disableSystemStreams() {
        PrintStream printStream = new PrintStream(new NullOutputStream());
        System.setOut(printStream);
        System.setErr(printStream);
    }

    public static void initLogLevel(Level level) {
        threshold = level;
    }

    public static boolean isEnabled(Level level) {
        return level.ordinal() >= threshold.ordinal();
    }

    public static void v(String str) {
        if (isEnabled(Level.VERBOSE)) {
            Log.v(TAG, str);
            CONSOLE_OUT.print("[server] VERBOSE: " + str + '\n');
        }
    }

    public static void d(String str) {
        if (isEnabled(Level.DEBUG)) {
            Log.d(TAG, str);
            CONSOLE_OUT.print("[server] DEBUG: " + str + '\n');
        }
    }

    public static void i(String str) {
        if (isEnabled(Level.INFO)) {
            Log.i(TAG, str);
            CONSOLE_OUT.print("[server] INFO: " + str + '\n');
        }
    }

    public static void w(String str, Throwable th) {
        if (isEnabled(Level.WARN)) {
            Log.w(TAG, str, th);
            PrintStream printStream = CONSOLE_ERR;
            synchronized (printStream) {
                printStream.print("[server] WARN: " + str + '\n');
                if (th != null) {
                    th.printStackTrace(printStream);
                }
            }
        }
    }

    public static void w(String str) {
        w(str, null);
    }

    public static void e(String str, Throwable th) {
        if (isEnabled(Level.ERROR)) {
            Log.e(TAG, str, th);
            PrintStream printStream = CONSOLE_ERR;
            synchronized (printStream) {
                printStream.print("[server] ERROR: " + str + '\n');
                if (th != null) {
                    th.printStackTrace(printStream);
                }
            }
        }
    }

    public static void e(String str) {
        e(str, null);
    }

    static class NullOutputStream extends OutputStream {
        @Override // java.io.OutputStream
        public void write(int i) {
        }

        @Override // java.io.OutputStream
        public void write(byte[] bArr) {
        }

        @Override // java.io.OutputStream
        public void write(byte[] bArr, int i, int i2) {
        }

        NullOutputStream() {
        }
    }
}
