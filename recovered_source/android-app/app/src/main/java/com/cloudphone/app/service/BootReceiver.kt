package com.cloudphone.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val PREFS_NAME = "cloudphone_prefs"
        const val KEY_AUTO_START = "auto_start_on_boot"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_STANDALONE = "standalone"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received boot/replacement broadcast: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean(KEY_AUTO_START, true)

            if (autoStart) {
                val serverUrl = prefs.getString(KEY_SERVER_URL, "ws://127.0.0.1:8000") ?: "ws://127.0.0.1:8000"
                val deviceId = prefs.getString(KEY_DEVICE_ID, Build.MODEL) ?: Build.MODEL
                val token = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
                val standalone = prefs.getBoolean(KEY_STANDALONE, false)

                Log.i(TAG, "Auto-starting CloudPhoneHostService for device $deviceId")

                val serviceIntent = Intent(context, CloudPhoneHostService::class.java).apply {
                    this.action = CloudPhoneHostService.ACTION_START
                    putExtra(CloudPhoneHostService.EXTRA_SERVER_URL, serverUrl)
                    putExtra(CloudPhoneHostService.EXTRA_DEVICE_ID, deviceId)
                    putExtra(CloudPhoneHostService.EXTRA_TOKEN, token)
                    putExtra(CloudPhoneHostService.EXTRA_STANDALONE, standalone)
                }

                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
