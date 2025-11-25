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
            // Removemos a dependência do campo security, deduzindo pela senha
            val isOpen = password.isEmpty()

            // ============================================================================
            // CENÁRIO 1: ANDROID 11+ (API 30+) -> API NATIVA DO SISTEMA
            // ============================================================================
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openSystemWifiDialog(context, ssid, password, isOpen)
                return
            }

            // ============================================================================
            // CENÁRIO 2: ANDROID 10 E INFERIOR -> ROOT OBRIGATÓRIO
            // ============================================================================
            // O usuário solicitou explicitamente não usar WifiSuggestion API aqui.
            // Tentamos salvar efetivamente a rede usando comandos de superusuário.

            val success = connectViaRootRobust(ssid, password, isOpen)

            if (success) {
                showToast(context, "Rede salva e conectando via Root!")
            } else {
                showToast(context, "Falha no Root. Verifique se o Magisk concedeu permissão.")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            showToast(context, "Erro: ${e.message}")
        }
    }

    // Abre o popup oficial "Deseja salvar esta rede?"
    @SuppressLint("NewApi")
    private fun openSystemWifiDialog(context: Context, ssid: String, password: String, isOpen: Boolean) {
        try {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)

            if (!isOpen) {
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

    /**
     * Tenta múltiplas estratégias de Root para garantir que a rede seja salva.
     * Estratégia:
     * 1. 'cmd wifi' (Padrão Android 10+)
     * 2. 'wpa_cli' (Padrão Linux/Android legado, muito robusto se disponível)
     * 3. 'svc wifi' (Recarregar driver)
     */
    private fun connectViaRootRobust(ssid: String, password: String, isOpen: Boolean): Boolean {
        // Verifica acesso root antes de tentar
        if (!hasRootAccess()) return false

        val commands = mutableListOf<String>()

        // --- ESTRATÉGIA A: CMD WIFI (Android 10+) ---
        // Tenta conectar e salvar. O comando connect geralmente salva no ConfigStore.
        if (isOpen) {
            commands.add("cmd wifi connect-network \"$ssid\" open")
            commands.add("cmd wifi connect \"$ssid\" open") // Sintaxe alternativa
        } else {
            commands.add("cmd wifi connect-network \"$ssid\" wpa2 \"$password\"")
            commands.add("cmd wifi connect \"$ssid\" \"$password\"") // Sintaxe alternativa
        }

        // --- ESTRATÉGIA B: WPA_CLI (Legado robusto) ---
        // Adiciona a rede diretamente no wpa_supplicant, salva config e reconecta.
        // Isso força a rede a ficar salva mesmo se o Android UI não atualizar na hora.
        val wpaCmd = StringBuilder()
        wpaCmd.append("id=$(wpa_cli add_network | tail -n 1); ")
        wpaCmd.append("wpa_cli set_network \$id ssid '\"$ssid\"'; ")
        if (!isOpen) {
            wpaCmd.append("wpa_cli set_network \$id psk '\"$password\"'; ")
        } else {
            wpaCmd.append("wpa_cli set_network \$id key_mgmt NONE; ")
        }
        wpaCmd.append("wpa_cli enable_network \$id; ")
        wpaCmd.append("wpa_cli save_config; ") // OBRIGATÓRIO PARA PERSISTIR
        wpaCmd.append("wpa_cli select_network \$id; ")
        wpaCmd.append("wpa_cli reassociate")

        commands.add(wpaCmd.toString())

        // Executa os comandos em sequência até um não retornar erro crítico
        // Nota: cmd wifi retorna 0 mesmo se falhar em alguns casos, então tentamos wpa_cli também se possível.

        var anySuccess = false
        for (cmd in commands) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val exitCode = p.waitFor()
                if (exitCode == 0) {
                    anySuccess = true
                    // Não damos break imediato no wpa_cli pois ele é complementar em alguns sistemas
                    // Mas se o cmd wifi funcionou, geralmente é suficiente.
                    if (cmd.startsWith("cmd")) break
                }
            } catch (e: Exception) {
                // Falha silenciosa, tenta próximo
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