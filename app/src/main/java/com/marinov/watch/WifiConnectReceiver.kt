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
import android.widget.Toast
import org.json.JSONObject
import java.util.ArrayList
import java.util.concurrent.Executors

class WifiConnectReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothService.ACTION_CONNECT_WIFI) {
            val jsonString = intent.getStringExtra(BluetoothService.EXTRA_WIFI_DATA) ?: return

            // Fecha notificação
            val notifId = intent.getIntExtra("notif_id", -1)
            if (notifId != -1) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notifId)
            }

            // Executa em thread separada para não travar UI com comandos Root
            val pendingResult = goAsync()
            Executors.newSingleThreadExecutor().execute {
                try {
                    handleWifiRequest(context, jsonString)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun handleWifiRequest(context: Context, jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val ssid = json.getString("ssid")
            val password = json.optString("password", "")
            val security = json.optString("security", if (password.isEmpty()) "OPEN" else "WPA")

            // ============================================================================
            // CENÁRIO 1: ANDROID 11+ (API 30+) -> API NATIVA (Suggestions UI)
            // ============================================================================
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openSystemWifiDialog(context, ssid, password, security)
                return
            }

            // ============================================================================
            // CENÁRIO 2: ANDROID 10 E INFERIOR -> TENTATIVA "LEGACY"
            // ============================================================================
            // Tenta o método nativo antigo. Se funcionar, ótimo.
            val legacySuccess = connectLegacy(context, ssid, password, security)
            if (legacySuccess) {
                showToast(context, "Conectado via Método Nativo!")
                return
            }

            // ============================================================================
            // CENÁRIO 3: ROOT (FALLBACK ROBUSTO)
            // ============================================================================
            // Se chegou aqui, usamos força bruta via Root.
            val rootSuccess = connectViaRootRobust(ssid, password, security)

            if (rootSuccess) {
                showToast(context, "Comando Root enviado! Verifique o Wi-Fi.")
            } else {
                showToast(context, "Falha ao conectar via Root. Tente manualmente.")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            showToast(context, "Erro: ${e.message}")
        }
    }

    // Abre o popup oficial "Deseja salvar esta rede?" (Android 11+)
    @SuppressLint("NewApi")
    private fun openSystemWifiDialog(context: Context, ssid: String, password: String, security: String) {
        try {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)

            if (security == "WPA" || security == "WPA2") {
                builder.setWpa2Passphrase(password)
            } else if (security == "WEP") {
                builder.setWpa2Passphrase(password)
            }

            val suggestion = builder.build()
            val list = ArrayList<WifiNetworkSuggestion>()
            list.add(suggestion)

            val bundle = Bundle()
            bundle.putParcelableArrayList(Settings.EXTRA_WIFI_NETWORK_LIST, list)

            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
            intent.putExtras(bundle)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(intent)
        } catch (e: Exception) {
            showToast(context, "Erro ao abrir diálogo do sistema: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(context: Context, ssid: String, password: String, security: String): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiConfig = WifiConfiguration()
            wifiConfig.SSID = "\"$ssid\""

            when (security) {
                "OPEN" -> {
                    wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                "WEP" -> {
                    wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    wifiConfig.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                    wifiConfig.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                    if (password.isNotEmpty()) {
                        wifiConfig.wepKeys[0] = "\"$password\""
                        wifiConfig.wepTxKeyIndex = 0
                    }
                }
                else -> { // WPA / WPA2
                    wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    wifiConfig.preSharedKey = "\"$password\""
                }
            }

            val netId = wifiManager.addNetwork(wifiConfig)
            if (netId == -1) return false

            wifiManager.disconnect()
            val enabled = wifiManager.enableNetwork(netId, true)
            wifiManager.reconnect()

            enabled
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Estratégia de Root Agressiva
     * Tenta 3 métodos: CMD WIFI -> WPA_CLI -> INJEÇÃO DIRETA EM ARQUIVO
     */
    private fun connectViaRootRobust(ssid: String, password: String, security: String): Boolean {
        // Removemos a verificação prévia 'hasRootAccess()' para evitar overhead e
        // permitir que o fluxo siga direto para os comandos 'su'.
        // Se o su falhar, o exitCode será != 0 e o loop continua.

        val commands = mutableListOf<String>()
        val isOpen = (security == "OPEN")
        val isWep = (security == "WEP")

        // --- MÉTODO 1: CMD WIFI (Android 10+) ---
        if (isOpen) {
            commands.add("cmd wifi connect-network \"$ssid\" open")
        } else if (isWep) {
            commands.add("cmd wifi connect-network \"$ssid\" wep \"$password\"")
        } else {
            commands.add("cmd wifi connect-network \"$ssid\" wpa2 \"$password\"")
        }

        // --- MÉTODO 2: WPA_CLI (Legado Robusto) ---
        // Tenta detectar interface, se falhar, tenta wlan0
        val wpaCmd = StringBuilder()
        wpaCmd.append("iface=wlan0; ") // Assume wlan0 como padrão seguro
        wpaCmd.append("id=$(wpa_cli -i \$iface add_network | tail -n 1); ")
        wpaCmd.append("wpa_cli -i \$iface set_network \$id ssid '\"$ssid\"'; ")

        when {
            isOpen -> wpaCmd.append("wpa_cli -i \$iface set_network \$id key_mgmt NONE; ")
            isWep -> {
                wpaCmd.append("wpa_cli -i \$iface set_network \$id key_mgmt NONE; ")
                wpaCmd.append("wpa_cli -i \$iface set_network \$id wep_key0 '\"$password\"'; ")
            }
            else -> wpaCmd.append("wpa_cli -i \$iface set_network \$id psk '\"$password\"'; ")
        }

        wpaCmd.append("wpa_cli -i \$iface enable_network \$id; ")
        wpaCmd.append("wpa_cli -i \$iface select_network \$id; ")
        wpaCmd.append("wpa_cli -i \$iface save_config; ")
        wpaCmd.append("wpa_cli -i \$iface reassociate")

        commands.add(wpaCmd.toString())

        // --- MÉTODO 3: INJEÇÃO DIRETA (O "Nuclear") ---
        // Escreve direto no wpa_supplicant.conf e reinicia o Wi-Fi.
        // Isso resolve casos onde wpa_cli e cmd wifi não têm permissão de socket.
        val confEntry = StringBuilder()
        confEntry.append("\\nnetwork={\\n")
        confEntry.append("    ssid=\\\"$ssid\\\"\\n")
        if (isOpen) {
            confEntry.append("    key_mgmt=NONE\\n")
        } else if (isWep) {
            confEntry.append("    key_mgmt=NONE\\n")
            confEntry.append("    wep_key0=\\\"$password\\\"\\n")
        } else {
            confEntry.append("    psk=\\\"$password\\\"\\n")
            confEntry.append("    key_mgmt=WPA-PSK\\n")
        }
        confEntry.append("}\\n")

        val injectCmd = "echo -e \"$confEntry\" >> /data/misc/wifi/wpa_supplicant.conf; svc wifi disable; sleep 2; svc wifi enable"
        commands.add(injectCmd)

        var anyCommandWorked = false

        for (cmd in commands) {
            try {
                // Executa o comando e espera. Se o usuário precisar dar permissão no prompt do Magisk,
                // o waitFor() vai segurar a execução até ele responder.
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val exitCode = p.waitFor()

                if (exitCode == 0) {
                    anyCommandWorked = true
                    // Se funcionou via CMD ou WPA_CLI, paramos.
                    // Se for injeção (último), ele já rodou.
                    if (cmd.startsWith("cmd") || cmd.startsWith("iface")) break
                }
            } catch (e: Exception) {
                // Tenta o próximo método silenciosamente
            }
        }

        return anyCommandWorked
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}