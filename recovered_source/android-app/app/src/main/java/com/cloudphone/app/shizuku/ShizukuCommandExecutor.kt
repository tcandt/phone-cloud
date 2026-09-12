package com.cloudphone.app.shizuku

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuCommandExecutor {
    private const val TAG = "ShizukuExecutor"

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    private val newProcessMethod by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to resolve Shizuku.newProcess via reflection", e)
            null
        }
    }

    private fun spawnProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        val method = newProcessMethod ?: throw IllegalStateException("Shizuku newProcess method unavailable")
        return method.invoke(null, cmd, env, dir) as Process
    }

    suspend fun execute(command: String): CommandResult = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isAuthorized()) {
            return@withContext CommandResult(-1, "", "Shizuku not authorized (UID 2000 required)")
        }

        try {
            Log.d(TAG, "Executing via Shizuku: $command")
            val process = spawnProcess(arrayOf("sh", "-c", command), null, null)

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            val stdoutThread = Thread {
                var line: String?
                while (stdoutReader.readLine().also { line = it } != null) {
                    stdoutBuilder.appendLine(line)
                }
            }

            val stderrThread = Thread {
                var line: String?
                while (stderrReader.readLine().also { line = it } != null) {
                    stderrBuilder.appendLine(line)
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val exitCode = process.waitFor()
            stdoutThread.join(2000)
            stderrThread.join(2000)

            CommandResult(exitCode, stdoutBuilder.toString().trim(), stderrBuilder.toString().trim())
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to execute command via Shizuku", e)
            CommandResult(-1, "", e.message ?: "Unknown error")
        }
    }

    /**
     * Spawns a long-running daemon process under Shell UID 2000
     */
    fun startDaemonProcess(command: String): Process? {
        if (!ShizukuManager.isAuthorized()) {
            Log.e(TAG, "Cannot start daemon: Shizuku not authorized")
            return null
        }
        return try {
            Log.i(TAG, "Spawning daemon process via Shizuku: $command")
            spawnProcess(arrayOf("sh", "-c", command), null, null)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to spawn daemon process via Shizuku", e)
            null
        }
    }
}
