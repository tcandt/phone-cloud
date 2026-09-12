package com.cloudphone.app.standalone

import android.content.Context
import android.util.Log
import com.cloudphone.app.root.RootRunner
import com.cloudphone.app.shizuku.ShizukuCommandExecutor
import com.cloudphone.app.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object EmbeddedSignalingServer {
    private const val TAG = "EmbeddedSignaling"
    private var serverProcess: Process? = null

    var isRunning = false
        private set

    suspend fun start(context: Context, port: Int = 8000): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext true

        val targetPath = "/data/local/tmp/webrtc-signaling"
        try {
            val destFile = File(targetPath)
            if (!destFile.exists() || destFile.length() == 0L) {
                context.assets.open("webrtc-signaling").use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                destFile.setReadable(true, false)
                destFile.setExecutable(true, false)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to copy embedded signaling asset: ${e.message}")
        }

        val cmd = "chmod 755 $targetPath\n$targetPath -port $port -no-auth"
        Log.i(TAG, "Starting embedded signaling on port $port")

        serverProcess = if (ShizukuManager.isAuthorized()) {
            ShizukuCommandExecutor.startDaemonProcess(cmd)
        } else if (RootRunner.isRootAvailable()) {
            RootRunner.startRootDaemon(cmd)
        } else {
            null
        }

        isRunning = (serverProcess != null)
        isRunning
    }

    fun stop() {
        Log.i(TAG, "Stopping embedded signaling server")
        try {
            serverProcess?.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping signaling server: ${e.message}")
        }
        serverProcess = null
        isRunning = false
    }
}
