package com.marinov.watch

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WelcomeActivity : AppCompatActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Independente do resultado, prosseguimos
        }

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkDndPermissionAndFinish()
        }

    // Launcher para permissão de DND (Não Perturbe)
    private val dndPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishSetup("WATCH")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        checkBatteryOptimizationDirect()
        checkAndRequestPermissions()

        val cardSmartphone = findViewById<MaterialCardView>(R.id.cardSmartphone)
        val cardWatch = findViewById<MaterialCardView>(R.id.cardWatch)

        cardSmartphone.setOnClickListener { finishSetup("PHONE") }
        cardWatch.setOnClickListener {
            // 1. Inicia com a verificação de Root
            checkRootAndSetup()
        }
    }

    // 2. Faz a verificação de Root e DEPOIS chama a permissão de instalação.
    private fun checkRootAndSetup() {
        lifecycleScope.launch(Dispatchers.IO) {
            checkRootAccess() // Essa chamada dispara o prompt de root

            withContext(Dispatchers.Main) {
                // Após o prompt de root ser tratado, pedimos a permissão de instalação
                requestInstallPermission()
            }
        }
    }

    // 3. Chamado após a verificação de root.
    private fun requestInstallPermission() {
        if (!packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            intent.data = "package:$packageName".toUri()
            installPermissionLauncher.launch(intent)
        } else {
            checkDndPermissionAndFinish()
        }
    }

    // 4. Nova verificação para Não Perturbe (DND)
    private fun checkDndPermissionAndFinish() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            dndPermissionLauncher.launch(intent)
        } else {
            finishSetup("WATCH")
        }
    }

    private suspend fun checkRootAccess(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val result = process.waitFor()
                result == 0
            } catch (_: Exception) {
                false
            }
        }
    }

    // 5. Finaliza o processo
    private fun finishSetup(type: String) {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        prefs.edit { putString("device_type", type) }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Permissão necessária para ler SSID do Wi-Fi em versões mais antigas
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    @SuppressLint("BatteryLife")
    private fun checkBatteryOptimizationDirect() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }
}