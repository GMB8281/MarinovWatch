package com.marinov.watch

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class WifiConnectReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothService.ACTION_CONNECT_WIFI) {
            val jsonString = intent.getStringExtra(BluetoothService.EXTRA_WIFI_DATA) ?: return

            // Fecha a notificação automaticamente ao clicar
            val notifId = intent.getIntExtra("notif_id", -1)
            if (notifId != -1) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notifId)
            }

            try {
                val json = JSONObject(jsonString)
                val ssid = json.getString("ssid")
                val password = json.optString("password", "")
                // Security pode ser "WPA", "WEP" ou "OPEN"
                val security = json.optString("security", "WPA")

                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

                if (!wifiManager.isWifiEnabled) {
                    wifiManager.isWifiEnabled = true
                    Toast.makeText(context, "Ativando Wi-Fi...", Toast.LENGTH_SHORT).show()
                }

                // ESTRATÉGIA MODERNA (Android 10 / Q / API 29+)
                // Equivalente a escanear um QR Code nativo
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val suggestionBuilder = WifiNetworkSuggestion.Builder()
                        .setSsid(ssid)
                        .setIsAppInteractionRequired(false) // Conecta automaticamente

                    if (password.isNotEmpty()) {
                        suggestionBuilder.setWpa2Passphrase(password)
                    } else {
                        // Se for aberta, não define senha
                    }

                    val suggestion = suggestionBuilder.build()
                    val status = wifiManager.addNetworkSuggestions(listOf(suggestion))

                    if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                        Toast.makeText(context, "Rede $ssid sugerida ao sistema!", Toast.LENGTH_LONG).show()
                        // O Android vai conectar automaticamente quando encontrar a rede
                    } else {
                        Toast.makeText(context, "Erro ao sugerir rede (Status: $status)", Toast.LENGTH_LONG).show()
                    }

                } else {
                    // ESTRATÉGIA LEGADA (Android 9 e inferior)
                    // Usa addNetwork diretamente
                    val wifiConfig = WifiConfiguration()
                    wifiConfig.SSID = String.format("\"%s\"", ssid)

                    if (password.isNotEmpty()) {
                        wifiConfig.preSharedKey = String.format("\"%s\"", password)
                    } else {
                        wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }

                    val netId = wifiManager.addNetwork(wifiConfig)

                    if (netId != -1) {
                        wifiManager.disconnect()
                        wifiManager.enableNetwork(netId, true)
                        wifiManager.reconnect()
                        Toast.makeText(context, "Conectando a $ssid (Legado)...", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Falha ao adicionar rede (Legado).", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Toast.makeText(context, "Erro processar Wi-Fi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}