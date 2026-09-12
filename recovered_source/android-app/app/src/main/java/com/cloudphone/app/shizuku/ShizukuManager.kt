package com.cloudphone.app.shizuku

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    const val SHIZUKU_REQUEST_CODE = 1001

    interface StatusListener {
        fun onShizukuStatusChanged(available: Boolean, granted: Boolean)
    }

    private val listeners = mutableListOf<StatusListener>()
    private var isBinderAlive = false
    private var isGranted = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        checkStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        isBinderAlive = false
        isGranted = false
        notifyListeners()
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                isGranted = (grantResult == PackageManager.PERMISSION_GRANTED)
                Log.i(TAG, "Shizuku permission result: granted=$isGranted")
                notifyListeners()
            }
        }

    fun init() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Shizuku listeners", e)
        }
    }

    fun checkStatus(): Boolean {
        isBinderAlive = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }

        isGranted = if (isBinderAlive) {
            try {
                if (Shizuku.isPreV11()) {
                    false
                } else {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }
            } catch (e: Throwable) {
                false
            }
        } else {
            false
        }

        notifyListeners()
        return isGranted
    }

    fun requestPermission() {
        if (!isBinderAlive) {
            Log.w(TAG, "Cannot request permission: Shizuku binder not available")
            return
        }
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Log.i(TAG, "Should show request permission rationale")
            }
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (e: Throwable) {
            Log.e(TAG, "Error requesting Shizuku permission", e)
        }
    }

    fun isAvailable(): Boolean = isBinderAlive
    fun isAuthorized(): Boolean = isGranted

    fun registerListener(listener: StatusListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onShizukuStatusChanged(isBinderAlive, isGranted)
        }
    }

    fun unregisterListener(listener: StatusListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            listener.onShizukuStatusChanged(isBinderAlive, isGranted)
        }
    }
}
