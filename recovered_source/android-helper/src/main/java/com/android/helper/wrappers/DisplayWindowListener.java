package com.android.helper.wrappers;

import android.content.res.Configuration;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.IDisplayWindowListener;
import com.android.helper.util.Ln;

/* JADX INFO: loaded from: classes.dex */
public class DisplayWindowListener extends IDisplayWindowListener.Stub {
    @Override // android.view.IDisplayWindowListener
    public void onDisplayAdded(int i) {
    }

    public void onDisplayConfigurationChanged(int i, Configuration configuration) {
    }

    @Override // android.view.IDisplayWindowListener
    public void onDisplayRemoved(int i) {
    }

    @Override // android.view.IDisplayWindowListener.Stub, android.os.Binder
    public boolean onTransact(int i, Parcel parcel, Parcel parcel2, int i2) throws RemoteException {
        try {
            return super.onTransact(i, parcel, parcel2, i2);
        } catch (AbstractMethodError e) {
            Ln.v("Ignoring AbstractMethodError: " + e.getMessage());
            parcel2.writeNoException();
            return true;
        }
    }
}
