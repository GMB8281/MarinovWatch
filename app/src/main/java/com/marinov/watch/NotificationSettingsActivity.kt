package com.marinov.watch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var switchEnable: SwitchMaterial
    private lateinit var tvStatusPermission: TextView
    private lateinit var btnOpenSettings: Button
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        switchEnable = findViewById(R.id.switchEnableMirroring)
        tvStatusPermission = findViewById(R.id.tvStatusPermission)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionStatus()
    }

    private fun setupListeners() {
        // Carrega estado salvo
        val isEnabled = prefs.getBoolean("notification_mirroring_enabled", false)
        switchEnable.isChecked = isEnabled

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notification_mirroring_enabled", isChecked).apply()

            if (isChecked && !isNotificationServiceEnabled()) {
                showPermissionDialog()
                // Reverte switch visualmente até dar permissão
                switchEnable.isChecked = false
            }
        }

        btnOpenSettings.setOnClickListener {
            openNotificationAccessSettings()
        }
    }

    private fun checkPermissionStatus() {
        if (isNotificationServiceEnabled()) {
            tvStatusPermission.text = "Permissão: CONCEDIDA"
            tvStatusPermission.setTextColor(getColor(android.R.color.holo_green_dark))
            btnOpenSettings.isEnabled = false
            btnOpenSettings.text = "Acesso Permitido"
        } else {
            tvStatusPermission.text = "Permissão: NEGADA"
            tvStatusPermission.setTextColor(getColor(android.R.color.holo_red_dark))
            btnOpenSettings.isEnabled = true
            btnOpenSettings.text = "Conceder Acesso"

            // Se não tem permissão, força o switch para off
            if (switchEnable.isChecked) {
                switchEnable.isChecked = false
                prefs.edit().putBoolean("notification_mirroring_enabled", false).apply()
            }
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, MyNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissão Necessária")
            .setMessage("Para ler as notificações do telefone e enviar ao relógio, você precisa conceder acesso nas configurações do Android.")
            .setPositiveButton("Abrir Configurações") { _, _ ->
                openNotificationAccessSettings()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openNotificationAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir as configurações automaticamente.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}