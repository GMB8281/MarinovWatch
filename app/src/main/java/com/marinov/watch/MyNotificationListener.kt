package com.marinov.watch

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class MyNotificationListener : NotificationListenerService() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        if (sbn == null) return

        // Verifica se o recurso está ativado
        val isMirroringEnabled = prefs.getBoolean("notification_mirroring_enabled", false)
        if (!isMirroringEnabled) return

        // Evita loop infinito: não reenvia notificações do próprio app
        if (sbn.packageName == packageName) return

        // Só espelha se não estiver em andamento (ongoing) - opcional, mas evita lixo como players de música
        if (sbn.isOngoing) return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getString("android.text") ?: ""

        if (title.isNotEmpty() || text.isNotEmpty()) {
            // Cria o JSON para enviar
            val json = JSONObject()
            json.put("package", sbn.packageName)
            json.put("title", title)
            json.put("text", text)

            // Envia Broadcast para o BluetoothService pegar e transmitir
            val intent = Intent(BluetoothService.ACTION_SEND_NOTIF_TO_WATCH)
            intent.putExtra(BluetoothService.EXTRA_NOTIF_JSON, json.toString())
            // Define o package para garantir segurança (só nosso app recebe)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
    }
}