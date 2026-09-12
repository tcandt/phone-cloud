package com.android.helper;

/* JADX INFO: loaded from: classes.dex */
public interface AsyncProcessor {

    public interface TerminationListener {
        void onTerminated(boolean z);
    }

    void join() throws InterruptedException;

    void start(TerminationListener terminationListener);

    void stop();
}
