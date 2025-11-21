package com.marinov.watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit

class WelcomeActivity : AppCompatActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Independente do resultado, prosseguimos (o app trata falta de permissão depois se precisar)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        // Pede permissões básicas logo de cara para evitar fluxos complexos depois
        checkAndRequestPermissions()

        val cardSmartphone = findViewById<MaterialCardView>(R.id.cardSmartphone)
        val cardWatch = findViewById<MaterialCardView>(R.id.cardWatch)

        cardSmartphone.setOnClickListener { finishSetup("PHONE") }
        cardWatch.setOnClickListener {
            // Se for Watch, primeiro verifica/solicita root
            checkRootAndSetup()
        }
    }

    private fun checkRootAndSetup() {
        lifecycleScope.launch(Dispatchers.IO) {
            val hasRoot = checkRootAccess()

            withContext(Dispatchers.Main) {
                if (hasRoot) {
                    // Root concedido, prossegue
                    finishSetup("WATCH")
                } else {
                    finishSetup("WATCH")
                }
            }
        }
    }

    private suspend fun checkRootAccess(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Tenta executar um comando simples com su
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))

                // Aguarda até 3 segundos
                val result = process.waitFor()

                result == 0 // Retorna true se o comando foi executado com sucesso
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun finishSetup(type: String) {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        prefs.edit { putString("device_type", type) }

        // Se for Watch, mostra mensagem sobre root
        if (type == "WATCH") {
            Toast.makeText(this, "Conceda acesso root quando solicitado", Toast.LENGTH_LONG).show()
        }

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
}