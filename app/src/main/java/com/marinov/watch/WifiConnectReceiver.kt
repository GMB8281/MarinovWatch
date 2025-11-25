package com.marinov.watch

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.util.ArrayList
import java.util.concurrent.Executors

class WifiConnectReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothService.ACTION_CONNECT_WIFI) {
            val jsonString = intent.getStringExtra(BluetoothService.EXTRA_WIFI_DATA) ?: return

            // Cancela a notificação
            val notifId = intent.getIntExtra("notif_id", -1)
            if (notifId != -1) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notifId)
            }

            // Executa em background
            val pendingResult = goAsync()
            Executors.newSingleThreadExecutor().execute {
                try {
                    processWifiConnection(context, jsonString)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun processWifiConnection(context: Context, jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val ssid = json.getString("ssid")
            val password = json.optString("password", "")
            // Segurança simplificada: Se senha vazia -> OPEN, senão -> WPA
            // (Ignora o campo 'security' vindo do JSON se necessário, ou usa como fallback)
            val security = if (password.isEmpty()) "OPEN" else "WPA"

            // --------------------------------------------------------------------------
            // CENÁRIO 1: ANDROID 11+ (API 30+) -> USAR POPUP NATIVO "SALVAR REDE"
            // --------------------------------------------------------------------------
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val suggestion = buildSuggestion(ssid, password)
                val bundle = Bundle()
                val suggestionsList = ArrayList<WifiNetworkSuggestion>()
                suggestionsList.add(suggestion)

                bundle.putParcelableArrayList(Settings.EXTRA_WIFI_NETWORK_LIST, suggestionsList)
                val settingsIntent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                settingsIntent.putExtras(bundle)
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                context.startActivity(settingsIntent)
                return
            }

            // --------------------------------------------------------------------------
            // CENÁRIO 2: ANDROID 10 E INFERIORES -> FORÇAR VIA ROOT
            // --------------------------------------------------------------------------
            // Nestas versões, addNetwork é bloqueado (Android 10) ou queremos garantir
            // que salve de verdade sem interação (Android 9-).

            if (connectViaRoot(ssid, password)) {
                showToast(context, "Conectado via Root!")
            } else {
                showToast(context, "Falha ao aplicar via Root. Verifique permissões.")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            showToast(context, "Erro: ${e.message}")
        }
    }

    /**
     * Tenta conectar usando sintaxes variadas do comando 'cmd wifi'.
     * Esta é a maneira mais robusta de "Burlar" as restrições do Android 10.
     */
    private fun connectViaRoot(ssid: String, password: String): Boolean {
        try {
            // 1. Verifica acesso Root
            if (Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() != 0) return false

            val commandsToTry = mutableListOf<String>()

            if (password.isNotEmpty()) {
                // Sintaxe Moderna (Android 10+): cmd wifi connect-network <ssid> <wpa2|open> <password>
                commandsToTry.add("cmd wifi connect-network \"$ssid\" wpa2 \"$password\"")
                // Sintaxe Alternativa
                commandsToTry.add("cmd wifi connect \"$ssid\" \"$password\"")
                // Sintaxe Legada (wpa_cli) - útil para Android 9 e inferior
                commandsToTry.add("wpa_cli add_network && wpa_cli set_network 0 ssid '\"$ssid\"' && wpa_cli set_network 0 psk '\"$password\"' && wpa_cli enable_network 0 && wpa_cli save_config && wpa_cli reassociate")
            } else {
                // Rede Aberta
                commandsToTry.add("cmd wifi connect-network \"$ssid\" open")
                commandsToTry.add("cmd wifi connect \"$ssid\" open")
            }

            for (cmd in commandsToTry) {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                    val exitCode = p.waitFor()
                    // Se o código de saída for 0, geralmente o comando foi aceito
                    if (exitCode == 0) return true
                } catch (e: Exception) {
                    // Continua para o próximo comando
                }
            }
            return false

        } catch (e: Exception) {
            return false
        }
    }

    @SuppressLint("NewApi")
    private fun buildSuggestion(ssid: String, password: String): WifiNetworkSuggestion {
        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        if (password.isNotEmpty()) {
            builder.setWpa2Passphrase(password)
        }
        return builder.build()
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}