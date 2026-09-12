package com.cloudphone.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cloudphone.app.CloudPhoneApp
import com.cloudphone.app.root.RootRunner
import com.cloudphone.app.shizuku.ShizukuCommandExecutor
import com.cloudphone.app.shizuku.ShizukuManager
import com.cloudphone.app.ui.MainActivity
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class CloudPhoneHostService : Service() {

    companion object {
        private const val TAG = "CloudPhoneHostService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.cloudphone.app.action.START"
        const val ACTION_STOP = "com.cloudphone.app.action.STOP"

        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_AGENT_SECRET = "extra_agent_secret"
        const val EXTRA_USER_TOKEN = "extra_user_token"
        const val EXTRA_STANDALONE = "extra_standalone"

        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var agentProcess: Process? = null
    private var watchdogActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Host Service created")
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.i(TAG, "onStartCommand action: $action")

        if (action == ACTION_STOP) {
            stopForegroundDaemon()
            return START_NOT_STICKY
        }

        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: "ws://127.0.0.1:8000"
        val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID) ?: Build.MODEL
        val agentSecret = intent?.getStringExtra(EXTRA_AGENT_SECRET)
            ?: intent?.getStringExtra(EXTRA_TOKEN) ?: ""
        val standalone = intent?.getBooleanExtra(EXTRA_STANDALONE, false) ?: false

        startForeground(NOTIFICATION_ID, buildNotification("Initializing CloudPhone Host..."))
        isRunning = true

        serviceScope.launch {
            deployAssetsAndStart(serverUrl, deviceId, agentSecret, standalone)
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CloudPhone::HostWakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24 hours
        }
    }

    private suspend fun deployAssetsAndStart(
        serverUrl: String,
        deviceId: String,
        agentSecret: String,
        standalone: Boolean
    ) {
        val targetDir = "/data/local/tmp"
        val agentBin = "$targetDir/cloudphone-agent"
        val helperJar = "$targetDir/libsys_core.so"

        // Ensure binaries deployed to /data/local/tmp
        deployFileFromAssets("cloudphone-agent", agentBin)
        deployFileFromAssets("libsys_core.so", helperJar)

        // Build startup command
        val cmd = StringBuilder()
        cmd.append("chmod 755 $agentBin\n")
        cmd.append("export CLOUDPHONE_DEVICE_ID=\"$deviceId\"\n")
        cmd.append("$agentBin -signaling \"$serverUrl\" -id \"$deviceId\" -device-id \"$deviceId\" -jar \"$helperJar\" -helper \"$helperJar\"")
        if (agentSecret.isNotEmpty()) {
            cmd.append(" -agent-secret \"$agentSecret\" -secret \"$agentSecret\" -token \"$agentSecret\"")
        }
        if (standalone) {
            cmd.append(" -standalone")
        }

        val launchCommand = cmd.toString()
        Log.i(TAG, "Starting CloudPhone Agent with command:\n$launchCommand")

        watchdogActive = true
        var retryDelay = 2000L

        while (watchdogActive) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification("Device [$deviceId] connected to $serverUrl")
            )

            agentProcess = if (ShizukuManager.isAuthorized()) {
                Log.i(TAG, "Launching via Shizuku (UID 2000)...")
                ShizukuCommandExecutor.startDaemonProcess(launchCommand)
            } else if (RootRunner.isRootAvailable()) {
                Log.i(TAG, "Launching via Root (UID 0)...")
                RootRunner.startRootDaemon(launchCommand)
            } else {
                Log.w(TAG, "Neither Shizuku nor Root is available yet. Waiting for binder connection...")
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification("Waiting for Shizuku or Wireless Debugging to activate...")
                )
                delay(5000L)
                continue
            }

            if (agentProcess == null) {
                Log.e(TAG, "Failed to spawn agent process. Retrying in ${retryDelay}ms...")
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30000L)
                continue
            }

            retryDelay = 2000L // Reset on successful spawn
            val exitCode = agentProcess?.waitFor() ?: -1
            Log.w(TAG, "Agent process exited with code $exitCode")

            if (!watchdogActive) break
            delay(3000L) // Wait before respawn
        }
    }

    private fun deployFileFromAssets(assetName: String, targetPath: String) {
        try {
            val destFile = File(targetPath)
            if (destFile.exists() && destFile.length() > 0) {
                return // Already present
            }
            var stream: java.io.InputStream? = null
            for (abi in Build.SUPPORTED_ABIS) {
                try {
                    val abiAsset = "bin/$abi/$assetName"
                    stream = assets.open(abiAsset)
                    Log.i(TAG, "Selected ABI asset for device: $abiAsset")
                    break
                } catch (ignored: Throwable) {}
            }
            if (stream == null) {
                stream = assets.open(assetName)
            }
            stream.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.setReadable(true, false)
            destFile.setExecutable(true, false)
            Log.i(TAG, "Deployed asset $assetName to $targetPath")
        } catch (e: Throwable) {
            Log.w(TAG, "Asset $assetName not bundled or direct copy failed: ${e.message}")
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CloudPhoneHostService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CloudPhoneApp.CHANNEL_ID)
            .setContentTitle("CloudPhone Host Daemon")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopForegroundDaemon() {
        Log.i(TAG, "Stopping CloudPhone Host Service...")
        watchdogActive = false
        isRunning = false

        try {
            agentProcess?.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "Error destroying agent process: ${e.message}")
        }
        agentProcess = null

        wakeLock?.let {
            if (it.isHeld) it.release()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundDaemon()
        serviceScope.cancel()
        Log.i(TAG, "Host Service destroyed")
    }
}
