package com.cloudphone.app.root

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

object RootRunner {
    private const val TAG = "RootRunner"

    fun isRootAvailable(): Boolean {
        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su-backup"
        )
        for (path in suPaths) {
            if (File(path).exists()) return true
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.readLine() != null
        } catch (e: Throwable) {
            false
        }
    }

    suspend fun checkRootPermission(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("id\nexit\n")
            os.flush()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.waitFor()
            line != null && line.contains("uid=0")
        } catch (e: Throwable) {
            Log.w(TAG, "Root permission check failed: ${e.message}")
            false
        }
    }

    fun startRootDaemon(command: String): Process? {
        return try {
            Log.i(TAG, "Spawning daemon via su: $command")
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command &\nexit\n")
            os.flush()
            process
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to spawn daemon via su", e)
            null
        }
    }
}
