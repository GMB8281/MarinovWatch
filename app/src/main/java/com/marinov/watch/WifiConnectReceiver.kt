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

            // Executa em thread separada
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
            // Recupera o tipo de segurança enviado pelo novo diálogo manual
            // Se não vier (versão antiga), usa a lógica antiga (vazio = OPEN)
            val security = json.optString("security", if (password.isEmpty()) "OPEN" else "WPA")

            // ============================================================================
            // CENÁRIO 1: ANDROID 10+ (API 29+) -> API NATIVA (Suggestions)
            // ============================================================================
            // Nota: O usuário relatou problemas no Android 10 com o fluxo antigo.
            // A API de Suggestions é o padrão oficial para 10+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Se for Android 10 (Q) ou superior, tenta o método oficial primeiro.
                // Se o usuário preferir Root mesmo no 10, pode-se inverter essa lógica,
                // mas Suggestions é mais estável se o app não for de sistema.
                openSystemWifiDialog(context, ssid, password, security)
                return
            }

            // ============================================================================
            // CENÁRIO 2: ANDROID 9 OU INFERIOR -> TENTATIVA "LEGACY" (WifiConfiguration)
            // ============================================================================
            // Aqui usamos o código sugerido pelo colaborador. É o método padrão para
            // Androids antigos e geralmente não requer Root se o app tiver permissão
            // CHANGE_WIFI_STATE (que deve estar no manifesto).
            val legacySuccess = connectLegacy(context, ssid, password, security)
            if (legacySuccess) {
                showToast(context, "Conectando via Método Legado (Nativo)...")
                return
            }

            // ============================================================================
            // CENÁRIO 3: ROOT (FALLBACK ROBUSTO)
            // ============================================================================
            // Se nada acima funcionou (ou se falhou), tentamos força bruta via Root.
            val rootSuccess = connectViaRootRobust(ssid, password, security)

            if (rootSuccess) {
                showToast(context, "Rede salva e conectando via Root!")
            } else {
                showToast(context, "Falha ao conectar. Verifique Root ou senha.")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            showToast(context, "Erro: ${e.message}")
        }
    }

    // Abre o popup oficial "Deseja salvar esta rede?" (Android 10+)
    @SuppressLint("NewApi")
    private fun openSystemWifiDialog(context: Context, ssid: String, password: String, security: String) {
        try {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)

            if (security == "WPA" || security == "WPA2") {
                builder.setWpa2Passphrase(password)
            } else if (security == "WEP") {
                // Suggestions tem suporte limitado a WEP em algumas APIs, mas WPA é o padrão
                builder.setWpa2Passphrase(password) // Tentativa genérica, ou ignore se API não suportar
            }
            // Se for OPEN, não define senha.

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

    // IMPLEMENTAÇÃO DO CÓDIGO DO COLABORADOR
    @Suppress("DEPRECATION")
    private fun connectLegacy(context: Context, ssid: String, password: String, security: String): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            val wifiConfig = WifiConfiguration()
            // Aspas são obrigatórias para strings SSID no WifiConfiguration antigo
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
                        // WEP requer formato específico dependendo do tamanho, mas tentamos o padrão
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
     * Tenta múltiplas estratégias de Root.
     */
    private fun connectViaRootRobust(ssid: String, password: String, security: String): Boolean {
        if (!hasRootAccess()) return false

        val commands = mutableListOf<String>()
        val isOpen = (security == "OPEN")

        // --- ESTRATÉGIA A: CMD WIFI (Android 10+) ---
        if (isOpen) {
            commands.add("cmd wifi connect-network \"$ssid\" open")
            commands.add("cmd wifi connect \"$ssid\" open")
        } else {
            // Assume WPA se não for open no cmd wifi simples
            commands.add("cmd wifi connect-network \"$ssid\" wpa2 \"$password\"")
            commands.add("cmd wifi connect \"$ssid\" \"$password\"")
        }

        // --- ESTRATÉGIA B: WPA_CLI (Legado robusto) ---
        // Atualizado para usar a lógica de WEP/WPA/OPEN corretamente
        val wpaCmd = StringBuilder()
        wpaCmd.append("id=$(wpa_cli add_network | tail -n 1); ")
        wpaCmd.append("wpa_cli set_network \$id ssid '\"$ssid\"'; ")

        when (security) {
            "OPEN" -> {
                wpaCmd.append("wpa_cli set_network \$id key_mgmt NONE; ")
            }
            "WEP" -> {
                wpaCmd.append("wpa_cli set_network \$id key_mgmt NONE; ")
                wpaCmd.append("wpa_cli set_network \$id wep_key0 '\"$password\"'; ")
            }
            else -> { // WPA
                wpaCmd.append("wpa_cli set_network \$id psk '\"$password\"'; ")
            }
        }

        wpaCmd.append("wpa_cli enable_network \$id; ")
        wpaCmd.append("wpa_cli save_config; ")
        wpaCmd.append("wpa_cli select_network \$id; ")
        wpaCmd.append("wpa_cli reassociate")

        commands.add(wpaCmd.toString())

        var anySuccess = false
        for (cmd in commands) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val exitCode = p.waitFor()
                if (exitCode == 0) {
                    anySuccess = true
                    if (cmd.startsWith("cmd")) break
                }
            } catch (e: Exception) {
            }
        }

        return anySuccess
    }

    private fun hasRootAccess(): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}