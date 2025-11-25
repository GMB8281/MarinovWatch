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
import androidx.appcompat.app.AlertDialog
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
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            if (fineLocationGranted) {
                checkAndRequestBackgroundLocation()
            }
        }
    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        }

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkDndPermissionAndFinish()
        }

    private val dndPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishSetup(pendingDeviceType)
        }

    // Variável temporária para guardar a escolha enquanto pedimos permissões
    private var pendingDeviceType = "PHONE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        checkBatteryOptimizationDirect()
        checkAndRequestPermissions()

        val cardSmartphone = findViewById<MaterialCardView>(R.id.cardSmartphone)
        val cardWatch = findViewById<MaterialCardView>(R.id.cardWatch)

        // AGORA AMBOS OS FLUXOS PASSAM PELA VERIFICAÇÃO DE ROOT
        // Isso garante que o Smartphone consiga ler senhas de Wi-Fi
        cardSmartphone.setOnClickListener {
            pendingDeviceType = "PHONE"
            checkRootAndSetup()
        }

        cardWatch.setOnClickListener {
            pendingDeviceType = "WATCH"
            checkRootAndSetup()
        }
    }

    private fun checkAndRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackground = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasBackground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    AlertDialog.Builder(this)
                        .setTitle("Permissão necessária")
                        .setMessage("Para exibir o SSID do Wi-Fi ou sincronizar redes, selecione 'Permitir o tempo todo' na próxima tela.")
                        .setPositiveButton("Entendi") { _, _ ->
                            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                        .setNegativeButton("Agora não", null)
                        .show()
                } else {
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        }
    }

    private fun checkRootAndSetup() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Tenta obter root. Se falhar, continuamos mesmo assim,
            // mas algumas features (como ler senha de wifi) podem não funcionar.
            val hasRoot = checkRootAccess()

            withContext(Dispatchers.Main) {
                if (!hasRoot && pendingDeviceType == "PHONE") {
                    // Aviso opcional para o usuário do telefone
                    // (Poderíamos mostrar um Toast aqui, mas vamos prosseguir para instalação)
                }
                requestInstallPermission()
            }
        }
    }

    private fun requestInstallPermission() {
        if (true && !packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            intent.data = "package:$packageName".toUri()
            installPermissionLauncher.launch(intent)
        } else {
            checkDndPermissionAndFinish()
        }
    }

    private fun checkDndPermissionAndFinish() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            dndPermissionLauncher.launch(intent)
        } else {
            finishSetup(pendingDeviceType)
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

    private fun finishSetup(type: String) {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        prefs.edit { putString("device_type", type) }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            checkAndRequestBackgroundLocation()
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