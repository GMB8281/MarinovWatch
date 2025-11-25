package com.marinov.watch

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
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

            // Executa em background para não travar a UI com comandos Root
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
            val security = json.optString("security", "WPA") // WPA, WEP, OPEN, SAE

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // Garante que o Wi-Fi esteja ligado
            if (!wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = true
                showToast(context, "Ativando Wi-Fi...")
                Thread.sleep(2000) // Espera um pouco para o driver subir
            }

            // --------------------------------------------------------------------------
            // TENTATIVA 1: VIA ROOT (A Preferida para "Salvar de Verdade")
            // --------------------------------------------------------------------------
            // O comando 'cmd wifi' existe no Android 10+ e salva/conecta a rede permanentemente.
            if (connectViaRoot(ssid, password, security)) {
                showToast(context, "Rede salva e conectada via Root!")
                return
            }

            // --------------------------------------------------------------------------
            // TENTATIVA 2: POPUP NATIVO (Android 11 / API 30+)
            // --------------------------------------------------------------------------
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val suggestion = buildSuggestion(ssid, password, security)
                val bundle = Bundle()
                val suggestionsList = ArrayList<WifiNetworkSuggestion>()
                suggestionsList.add(suggestion)

                bundle.putParcelableArrayList(Settings.EXTRA_WIFI_NETWORK_LIST, suggestionsList)
                val settingsIntent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                settingsIntent.putExtras(bundle)
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                context.startActivity(settingsIntent)
            }
            // --------------------------------------------------------------------------
            // TENTATIVA 3: LEGADO / SUGESTÃO (Android 10 e inferiores sem Root)
            // --------------------------------------------------------------------------
            else {
                // No Android 10 sem root, infelizmente só temos Suggestion (não salva na lista visível até conectar)
                // No Android 9-, usamos addNetwork que salva de verdade.

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10 (Q) sem root -> Sugestão (Melhor que nada)
                    val suggestion = buildSuggestion(ssid, password, security)
                    val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
                    if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                        showToast(context, "Rede sugerida ao sistema (Sem Root).")
                    } else {
                        showToast(context, "Erro ao sugerir rede.")
                    }
                } else {
                    // Android 9 (Pie) e inferior -> addNetwork (Salva de verdade!)
                    connectLegacy(wifiManager, ssid, password, security, context)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            showToast(context, "Erro: ${e.message}")
        }
    }

    // Tenta conectar usando comandos de shell do Android
    private fun connectViaRoot(ssid: String, password: String, security: String): Boolean {
        try {
            // Verifica se tem su
            val check = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            if (check.waitFor() != 0) return false

            // Android 10+ possui o comando 'cmd wifi'
            // Sintaxe comum: cmd wifi connect <ssid> <password>
            // Sintaxe alternativa: cmd wifi connect-network <ssid> <wpa2|open> <password>

            val cmd: String = if (security == "OPEN" || password.isEmpty()) {
                "cmd wifi connect \"$ssid\" open"
            } else {
                // Tenta sintaxe WPA2 padrão. Se falhar, tenta genérica.
                "cmd wifi connect \"$ssid\" \"$password\""
            }

            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val exitCode = p.waitFor()

            // Se o comando 'cmd wifi' não existir (Android antigo), vai falhar aqui e retornar false
            // permitindo o fallback para o método legado (que funciona bem em Android antigo).
            return exitCode == 0

        } catch (e: Exception) {
            return false
        }
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(wifiManager: WifiManager, ssid: String, password: String, security: String, context: Context) {
        try {
            val wifiConfig = WifiConfiguration()
            wifiConfig.SSID = String.format("\"%s\"", ssid)

            when (security) {
                "WPA", "SAE" -> {
                    wifiConfig.preSharedKey = String.format("\"%s\"", password)
                }
                "WEP" -> {
                    wifiConfig.wepKeys[0] = String.format("\"%s\"", password)
                    wifiConfig.wepTxKeyIndex = 0
                    wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    wifiConfig.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                }
                "OPEN" -> {
                    wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
            }

            val netId = wifiManager.addNetwork(wifiConfig)
            if (netId != -1) {
                wifiManager.disconnect()
                wifiManager.enableNetwork(netId, true)
                wifiManager.reconnect()
                showToast(context, "Salvando e conectando (Legado)...")
            } else {
                showToast(context, "Falha na API Legada.")
            }
        } catch (e: Exception) {
            showToast(context, "Erro legado: ${e.message}")
        }
    }

    @SuppressLint("NewApi")
    private fun buildSuggestion(ssid: String, password: String, security: String): WifiNetworkSuggestion {
        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        when (security) {
            "WPA", "SAE" -> if (password.isNotEmpty()) builder.setWpa2Passphrase(password)
            "WEP" -> if (password.isNotEmpty()) builder.setWpa2Passphrase(password) // Tentativa de compatibilidade
            "OPEN" -> { }
        }
        return builder.build()
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}