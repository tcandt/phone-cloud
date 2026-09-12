package com.cloudphone.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cloudphone.app.R
import com.cloudphone.app.databinding.ActivityMainBinding
import com.cloudphone.app.root.RootRunner
import com.cloudphone.app.service.BootReceiver
import com.cloudphone.app.service.CloudPhoneHostService
import com.cloudphone.app.shizuku.ShizukuManager
import com.cloudphone.app.standalone.EmbeddedSignalingServer
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), ShizukuManager.StatusListener {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPreferences()
        setupListeners()
        checkBatteryOptimization()
        ShizukuManager.registerListener(this)
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
        ShizukuManager.checkStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        ShizukuManager.unregisterListener(this)
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        binding.etServerUrl.setText(prefs.getString(BootReceiver.KEY_SERVER_URL, "ws://192.168.1.100:8000"))
        binding.etDeviceId.setText(prefs.getString(BootReceiver.KEY_DEVICE_ID, Build.MODEL))
        binding.etAuthToken.setText(prefs.getString(BootReceiver.KEY_AUTH_TOKEN, ""))
        binding.etAgentSecret.setText(prefs.getString(BootReceiver.KEY_AGENT_SECRET, ""))
        binding.cbStandalone.isChecked = prefs.getBoolean(BootReceiver.KEY_STANDALONE, false)
    }

    private fun savePreferences() {
        val prefs = getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(BootReceiver.KEY_SERVER_URL, binding.etServerUrl.text.toString().trim())
            putString(BootReceiver.KEY_DEVICE_ID, binding.etDeviceId.text.toString().trim())
            putString(BootReceiver.KEY_AUTH_TOKEN, binding.etAuthToken.text.toString().trim())
            putString(BootReceiver.KEY_AGENT_SECRET, binding.etAgentSecret.text.toString().trim())
            putBoolean(BootReceiver.KEY_STANDALONE, binding.cbStandalone.isChecked)
            putBoolean(BootReceiver.KEY_AUTO_START, true)
            apply()
        }
    }

    private fun setupListeners() {
        binding.btnRequestShizuku.setOnClickListener {
            if (!ShizukuManager.isAvailable()) {
                Toast.makeText(this, "Shizuku is not running on this device. Please start Shizuku app first.", Toast.LENGTH_LONG).show()
            } else {
                ShizukuManager.requestPermission()
            }
        }

        binding.btnToggleService.setOnClickListener {
            if (CloudPhoneHostService.isRunning) {
                stopHostService()
            } else {
                startHostService()
            }
        }

        binding.btnOpenController.setOnClickListener {
            savePreferences()
            val intent = Intent(this, ControllerActivity::class.java).apply {
                putExtra(CloudPhoneHostService.EXTRA_SERVER_URL, binding.etServerUrl.text.toString().trim())
                putExtra(CloudPhoneHostService.EXTRA_USER_TOKEN, binding.etAuthToken.text.toString().trim())
                putExtra(CloudPhoneHostService.EXTRA_TOKEN, binding.etAuthToken.text.toString().trim())
                val targetId = binding.etDeviceId.text.toString().trim()
                if (targetId.isNotEmpty()) {
                    putExtra(CloudPhoneHostService.EXTRA_DEVICE_ID, targetId)
                }
            }
            startActivity(intent)
        }
    }

    private fun startHostService() {
        savePreferences()
        val serverUrl = binding.etServerUrl.text.toString().trim()
        val deviceId = binding.etDeviceId.text.toString().trim()
        val agentSecret = binding.etAgentSecret.text.toString().trim()
        val token = binding.etAuthToken.text.toString().trim()
        val standalone = binding.cbStandalone.isChecked

        if (standalone) {
            lifecycleScope.launch {
                EmbeddedSignalingServer.start(applicationContext)
            }
        }

        val serviceIntent = Intent(this, CloudPhoneHostService::class.java).apply {
            action = CloudPhoneHostService.ACTION_START
            putExtra(CloudPhoneHostService.EXTRA_SERVER_URL, serverUrl)
            putExtra(CloudPhoneHostService.EXTRA_DEVICE_ID, deviceId)
            putExtra(CloudPhoneHostService.EXTRA_AGENT_SECRET, agentSecret)
            putExtra(CloudPhoneHostService.EXTRA_TOKEN, if (agentSecret.isNotEmpty()) agentSecret else token)
            putExtra(CloudPhoneHostService.EXTRA_USER_TOKEN, token)
            putExtra(CloudPhoneHostService.EXTRA_STANDALONE, standalone)
        }

        ContextCompat.startForegroundService(this, serviceIntent)
        updateUIState()
    }

    private fun stopHostService() {
        val serviceIntent = Intent(this, CloudPhoneHostService::class.java).apply {
            action = CloudPhoneHostService.ACTION_STOP
        }
        startService(serviceIntent)
        EmbeddedSignalingServer.stop()
        updateUIState()
    }

    private fun updateUIState() {
        val running = CloudPhoneHostService.isRunning
        if (running) {
            binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.tvServiceStatus.text = getString(R.string.service_running)
            binding.btnToggleService.text = getString(R.string.stop_service)
            binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_red)
        } else {
            binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_red))
            binding.tvServiceStatus.text = getString(R.string.service_stopped)
            binding.btnToggleService.text = getString(R.string.start_service)
            binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
        }

        // Root status
        lifecycleScope.launch {
            val hasRoot = RootRunner.checkRootPermission()
            binding.tvRootStatus.text = if (hasRoot) getString(R.string.root_granted) else getString(R.string.root_unavailable)
            binding.tvRootStatus.setTextColor(
                ContextCompat.getColor(this@MainActivity, if (hasRoot) R.color.accent_green else R.color.text_secondary)
            )
        }
    }

    override fun onShizukuStatusChanged(available: Boolean, granted: Boolean) {
        runOnUiThread {
            if (granted) {
                binding.tvShizukuStatus.text = getString(R.string.shizuku_authorized)
                binding.tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.btnRequestShizuku.isEnabled = false
                binding.btnRequestShizuku.text = "Shizuku Active (UID 2000)"
            } else if (available) {
                binding.tvShizukuStatus.text = "Running (Permission required)"
                binding.tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_yellow))
                binding.btnRequestShizuku.isEnabled = true
                binding.btnRequestShizuku.text = getString(R.string.request_shizuku)
            } else {
                binding.tvShizukuStatus.text = getString(R.string.shizuku_unavailable)
                binding.tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.btnRequestShizuku.isEnabled = true
                binding.btnRequestShizuku.text = "Launch Shizuku"
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Throwable) {
                    // Ignore on devices without standard intent
                }
            }
        }
    }
}
