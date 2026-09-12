package com.android.helper.util;

import java.io.IOException;
import java.util.Arrays;
import java.util.Scanner;

/* JADX INFO: loaded from: classes.dex */
public final class Command {
    private Command() {
    }

    public static void exec(String... strArr) throws InterruptedException, IOException {
        int iWaitFor = Runtime.getRuntime().exec(strArr).waitFor();
        if (iWaitFor == 0) {
            return;
        }
        throw new IOException("Command " + Arrays.toString(strArr) + " returned with value " + iWaitFor);
    }

    public static String execReadLine(String... strArr) throws InterruptedException, IOException {
        Process processExec = Runtime.getRuntime().exec(strArr);
        Scanner scanner = new Scanner(processExec.getInputStream());
        String strNextLine = scanner.hasNextLine() ? scanner.nextLine() : null;
        int iWaitFor = processExec.waitFor();
        if (iWaitFor == 0) {
            return strNextLine;
        }
        throw new IOException("Command " + Arrays.toString(strArr) + " returned with value " + iWaitFor);
    }

    public static String execReadOutput(String... strArr) throws InterruptedException, IOException {
        Process processExec = Runtime.getRuntime().exec(strArr);
        String string = IO.toString(processExec.getInputStream());
        int iWaitFor = processExec.waitFor();
        if (iWaitFor == 0) {
            return string;
        }
        throw new IOException("Command " + Arrays.toString(strArr) + " returned with value " + iWaitFor);
    }
}
