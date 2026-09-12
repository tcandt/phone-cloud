package com.cloudphone.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.cloudphone.app.shizuku.ShizukuManager

class CloudPhoneApp : Application() {

    companion object {
        const val TAG = "CloudPhoneApp"
        const val CHANNEL_ID = "cloudphone_host_channel"
        const val CHANNEL_NAME = "CloudPhone Host Service"
        lateinit var instance: CloudPhoneApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "CloudPhone application initializing...")

        createNotificationChannel()
        ShizukuManager.init()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background daemon maintaining WebRTC agent and scrcpy capture"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
