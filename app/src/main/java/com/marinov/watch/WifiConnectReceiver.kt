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

            val notifId = intent.getIntExtra("notif_id", -1)
            if (notifId != -1) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notifId)
            }

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
            // CENÁRIO 2: ANDROID 10 E INFERIOR -> TENTATIVA "LEGACY" (Lógica do Colaborador)
            // ============================================================================
            val legacySuccess = connectLegacy(context, ssid, password, security)
            if (legacySuccess) {
                showToast(context, "Conexão nativa iniciada!")
                return
            }

            // ============================================================================
            // CENÁRIO 3: ROOT (FALLBACK NUCLEAR)
            // ============================================================================
            // Se chegou aqui, a API nativa falhou (comum no Android 10).
            // Usamos Root para forçar a gravação no arquivo de configuração e reiniciar o WiFi.
            val rootSuccess = connectViaRootRobust(ssid, password, security)

            if (rootSuccess) {
                showToast(context, "Configuração Root aplicada. O Wi-Fi irá reiniciar.")
            } else {
                showToast(context, "Falha total. Tente adicionar manualmente.")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            showToast(context, "Erro: ${e.message}")
        }
    }

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

            // As aspas são cruciais, conforme lógica do colaborador
            wifiConfig.SSID = "\"$ssid\""

            when (security) {
                "OPEN" -> wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
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

            // Lógica exata do colaborador: Disconnect -> Enable -> Reconnect
            wifiManager.disconnect()
            wifiManager.enableNetwork(netId, true)
            wifiManager.reconnect()

            // Tenta salvar explicitamente (necessário em alguns Androids antigos)
            wifiManager.saveConfiguration()

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Estratégia de Root Agressiva e Abrangente
     */
    private fun connectViaRootRobust(ssid: String, password: String, security: String): Boolean {
        val commands = mutableListOf<String>()
        val isOpen = (security == "OPEN")
        val isWep = (security == "WEP")

        // 1. CMD WIFI (Sintaxe Android 10+)
        // Adicionada sintaxe simplificada 'connect' que às vezes funciona melhor que 'connect-network'
        if (isOpen) {
            commands.add("cmd wifi connect-network \"$ssid\" open")
            commands.add("cmd wifi connect \"$ssid\" open")
        } else {
            commands.add("cmd wifi connect-network \"$ssid\" wpa2 \"$password\"")
            commands.add("cmd wifi connect \"$ssid\" \"$password\"")
        }

        // 2. WPA_CLI (Várias tentativas de interface)
        val wpaInterfaces = listOf("wlan0", "swlan0", "tiwlan0")
        for (iface in wpaInterfaces) {
            val wpaCmd = StringBuilder()
            wpaCmd.append("id=$(wpa_cli -i $iface add_network | tail -n 1); ")
            wpaCmd.append("wpa_cli -i $iface set_network \$id ssid '\"$ssid\"'; ")
            if (isOpen) {
                wpaCmd.append("wpa_cli -i $iface set_network \$id key_mgmt NONE; ")
            } else {
                wpaCmd.append("wpa_cli -i $iface set_network \$id psk '\"$password\"'; ")
            }
            wpaCmd.append("wpa_cli -i $iface enable_network \$id; ")
            wpaCmd.append("wpa_cli -i $iface save_config; ")
            wpaCmd.append("wpa_cli -i $iface select_network \$id")
            commands.add(wpaCmd.toString())
        }

        // 3. INJEÇÃO DIRETA "NUCLEAR" (Se não salvar, escrevemos direto no arquivo)
        // Tentamos escrever em TODOS os caminhos possíveis onde o Android costuma guardar configurações
        val possiblePaths = listOf(
            "/data/misc/wifi/wpa_supplicant.conf",
            "/data/vendor/wifi/wpa/wpa_supplicant.conf",
            "/vendor/etc/wifi/wpa_supplicant.conf"
        )

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
        confEntry.append("    priority=100\\n") // Alta prioridade
        confEntry.append("}\\n")

        // Cria um comando gigante que tenta dar append em cada arquivo se ele existir
        val injectCmds = possiblePaths.map { path ->
            "if [ -f \"$path\" ]; then echo -e \"$confEntry\" >> \"$path\"; fi"
        }.joinToString(";")

        // Adiciona o comando de injeção seguido de restart do serviço wifi para recarregar configs
        commands.add("$injectCmds; svc wifi disable; sleep 3; svc wifi enable")

        var anyCommandWorked = false

        for (cmd in commands) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val exitCode = p.waitFor()
                if (exitCode == 0) {
                    anyCommandWorked = true
                    // Se o cmd wifi funcionou, paramos. Se for wpa_cli ou injeção, continuamos
                    // para garantir que o restart final (svc wifi) ocorra.
                    if (cmd.startsWith("cmd")) break
                }
            } catch (e: Exception) {
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