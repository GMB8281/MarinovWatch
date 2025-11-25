package com.marinov.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.widget.Toast
import org.json.JSONObject

class WifiConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothService.ACTION_CONNECT_WIFI) {
            val jsonString = intent.getStringExtra(BluetoothService.EXTRA_WIFI_DATA) ?: return

            try {
                val json = JSONObject(jsonString)
                val ssid = json.getString("ssid")
                val password = json.optString("password", "")

                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

                if (!wifiManager.isWifiEnabled) {
                    wifiManager.isWifiEnabled = true
                }

                val wifiConfig = WifiConfiguration()
                wifiConfig.SSID = String.format("\"%s\"", ssid)

                if (password.isNotEmpty()) {
                    wifiConfig.preSharedKey = String.format("\"%s\"", password)
                } else {
                    wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }

                // Adiciona a rede
                val netId = wifiManager.addNetwork(wifiConfig)

                if (netId != -1) {
                    wifiManager.disconnect()
                    wifiManager.enableNetwork(netId, true)
                    wifiManager.reconnect()
                    Toast.makeText(context, "Conectando a $ssid...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Erro ao salvar rede Wi-Fi.", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Toast.makeText(context, "Falha na configuração: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}