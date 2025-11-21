package com.marinov.watch

import android.Manifest
import android.annotation.SuppressLint
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
            // Independente do resultado, prosseguimos (o app trata falta de permissão depois se precisar)
        }

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Após o usuário interagir com a tela de permissão, finalizamos o setup
            finishSetup("WATCH")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        checkBatteryOptimizationDirect()
        // Pede permissões básicas logo de cara para evitar fluxos complexos depois
        checkAndRequestPermissions()

        val cardSmartphone = findViewById<MaterialCardView>(R.id.cardSmartphone)
        val cardWatch = findViewById<MaterialCardView>(R.id.cardWatch)

        cardSmartphone.setOnClickListener { finishSetup("PHONE") }
        cardWatch.setOnClickListener {
            // 1. Inicia com a verificação de Root
            checkRootAndSetup()
        }
    }

    // 3. Chamado após a verificação de root.
    private fun requestInstallPermission() {
        if (!packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            intent.data = "package:$packageName".toUri()
            installPermissionLauncher.launch(intent)
        } else {
            // Se a permissão já existe ou a versão do Android é antiga, finaliza
            finishSetup("WATCH")
        }
    }

    // 2. Faz a verificação de Root e DEPOIS chama a permissão de instalação.
    private fun checkRootAndSetup() {
        lifecycleScope.launch(Dispatchers.IO) {
            checkRootAccess() // Essa chamada dispara o prompt de root

            withContext(Dispatchers.Main) {
                // Após o prompt de root ser tratado (concedido ou negado), pedimos a outra permissão
                requestInstallPermission()
            }
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

    // 4. Apenas finaliza o processo
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
