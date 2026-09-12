package com.android.helper.util;

import android.os.Handler;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/* JADX INFO: loaded from: classes.dex */
public final class Threads {
    private Threads() {
    }

    public static <T> T executeSynchronouslyOn(Handler handler, final Callable<T> callable) throws Throwable {
        final Semaphore semaphore = new Semaphore(0);
        final Object[] objArr = new Object[1];
        final Throwable[] thArr = new Throwable[1];
        handler.post(new Runnable() { // from class: com.android.helper.util.Threads$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                Threads.lambda$executeSynchronouslyOn$0(objArr, callable, thArr, semaphore);
            }
        });
        try {
            semaphore.acquire();
        } catch (InterruptedException unused) {
            Thread.currentThread().interrupt();
        }
        Throwable th = thArr[0];
        if (th != null) {
            throw th;
        }
        return (T) objArr[0];
    }

    static /* synthetic */ void lambda$executeSynchronouslyOn$0(Object[] objArr, Callable callable, Throwable[] thArr, Semaphore semaphore) {
        try {
            objArr[0] = callable.call();
            semaphore.release();
        } catch (Throwable th) {
            try {
                thArr[0] = th;
            } finally {
                semaphore.release();
            }
        }
    }
}
