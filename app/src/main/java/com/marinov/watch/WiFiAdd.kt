package com.marinov.watch

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

// Data class para segurar os dados extraídos via Root
data class RootWifiConfig(
    val ssid: String,
    val psk: String,
    val security: String = "WPA"
)

class WiFiAdd : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            bluetoothService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_add)

        // Verificação inicial
        checkRootAndStart()
    }

    private fun checkRootAndStart() {
        CoroutineScope(Dispatchers.IO).launch {
            val hasRoot = checkRoot()
            withContext(Dispatchers.Main) {
                if (!hasRoot) {
                    AlertDialog.Builder(this@WiFiAdd)
                        .setTitle("Acesso Negado")
                        .setMessage("Este recurso requer acesso ROOT. Em versões modernas do Android, é impossível listar senhas de Wi-Fi salvas sem privilégios elevados.")
                        .setPositiveButton("Fechar") { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                } else {
                    initUI()
                }
            }
        }
    }

    private fun checkRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val result = process.waitFor()
            result == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun initUI() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerViewWifi)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        loadWifiNetworks()
    }

    @SuppressLint("MissingPermission")
    private fun loadWifiNetworks() {
        // Exibe Loading? (Opcional, aqui vamos direto)

        CoroutineScope(Dispatchers.IO).launch {
            // Lista para armazenar redes encontradas via ROOT
            val rootNetworks = RootWifiScanner.scanAllWifiConfigs()

            withContext(Dispatchers.Main) {
                if (rootNetworks.isEmpty()) {
                    findViewById<TextView>(R.id.tvEmptyState).apply {
                        visibility = View.VISIBLE
                        text = "Nenhuma rede encontrada nos arquivos do sistema (Root)."
                    }
                } else {
                    findViewById<TextView>(R.id.tvEmptyState).visibility = View.GONE
                    recyclerView.adapter = WifiAdapter(rootNetworks) { config ->
                        confirmSendWifi(config)
                    }
                }
            }
        }
    }

    private fun confirmSendWifi(config: RootWifiConfig) {
        AlertDialog.Builder(this)
            .setTitle("Enviar Wi-Fi?")
            .setMessage("Deseja enviar a rede '${config.ssid}' para o smartwatch?")
            .setPositiveButton("Sim") { _, _ ->
                if (bluetoothService?.isConnected == true) {
                    // Chama o método no BluetoothService
                    bluetoothService?.sendWifiData(config.ssid, config.psk, config.security)
                    Toast.makeText(this, "Enviando...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Watch não conectado.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Não", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(connection)
    }

    // ========================================================================
    // SCANNER ROOT ROBUSTO (Compatível com Android 8, 9, 10, 11, 12, 13, 14)
    // ========================================================================
    object RootWifiScanner {

        // Caminhos possíveis para o arquivo de configuração de Wi-Fi
        private val CONF_PATHS = listOf(
            // Android 11+ (Project Mainline / Apex)
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            // Android 8.0 até 10
            "/data/misc/wifi/WifiConfigStore.xml",
            // Android 7.1 e inferior (Legacy)
            "/data/misc/wifi/wpa_supplicant.conf"
        )

        fun scanAllWifiConfigs(): List<RootWifiConfig> {
            val networks = mutableListOf<RootWifiConfig>()

            for (path in CONF_PATHS) {
                if (fileExists(path)) {
                    val content = readFile(path)
                    if (path.endsWith(".xml")) {
                        networks.addAll(parseWifiConfigStoreXml(content))
                    } else {
                        networks.addAll(parseWpaSupplicant(content))
                    }
                    // Se achamos redes em um arquivo prioritário, paramos (evita duplicatas de backups antigos)
                    if (networks.isNotEmpty()) break
                }
            }

            // Remove duplicatas e ordena
            return networks.distinctBy { it.ssid }.sortedBy { it.ssid }
        }

        private fun fileExists(path: String): Boolean {
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls \"$path\""))
                p.waitFor() == 0
            } catch (e: Exception) { false }
        }

        private fun readFile(path: String): String {
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat \"$path\""))
                val sb = StringBuilder()
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append("\n")
                }
                sb.toString()
            } catch (e: Exception) { "" }
        }

        // Parser para o formato XML do Android moderno (WifiConfigStore.xml)
        // O formato geralmente é:
        // <Network>
        //    <WifiConfiguration>
        //       <string name="SSID">"NomeDaRede"</string>
        //       <string name="PreSharedKey">"Senha123"</string>
        //    </WifiConfiguration>
        // </Network>
        private fun parseWifiConfigStoreXml(xml: String): List<RootWifiConfig> {
            val list = mutableListOf<RootWifiConfig>()

            // Regex simples para capturar blocos WifiConfiguration (funciona para a maioria dos casos simples)
            // Uma solução XML parser completa seria muito pesada para shell output sujo
            try {
                // Divide por blocos de configuração
                val blocks = xml.split("<WifiConfiguration>")

                for (block in blocks) {
                    if (!block.contains("SSID")) continue

                    val ssidMatch = Regex("<string name=\"SSID\">&quot;(.*?)&quot;</string>").find(block)
                    val pskMatch = Regex("<string name=\"PreSharedKey\">&quot;(.*?)&quot;</string>").find(block)

                    // As vezes a senha está em formato diferente ou é null (rede aberta)
                    // Se não tiver PreSharedKey com aspas, pode ser null (Open)

                    if (ssidMatch != null) {
                        val ssid = ssidMatch.groupValues[1]
                        val psk = pskMatch?.groupValues?.get(1) ?: ""

                        // Ignora redes sem nome
                        if (ssid.isNotEmpty()) {
                            list.add(RootWifiConfig(ssid, psk, if (psk.isEmpty()) "OPEN" else "WPA"))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return list
        }

        // Parser para o formato legado (wpa_supplicant.conf)
        // network={
        //    ssid="Nome"
        //    psk="Senha"
        // }
        private fun parseWpaSupplicant(conf: String): List<RootWifiConfig> {
            val list = mutableListOf<RootWifiConfig>()
            val networks = conf.split("network={")

            for (net in networks) {
                val ssidLine = net.lines().find { it.trim().startsWith("ssid=") }
                val pskLine = net.lines().find { it.trim().startsWith("psk=") }

                if (ssidLine != null) {
                    val ssid = ssidLine.substringAfter("=").trim().replace("\"", "")
                    val psk = pskLine?.substringAfter("=")?.trim()?.replace("\"", "") ?: ""

                    if (ssid.isNotEmpty()) {
                        list.add(RootWifiConfig(ssid, psk, if (psk.isEmpty()) "OPEN" else "WPA"))
                    }
                }
            }
            return list
        }
    }

    // ADAPTER
    inner class WifiAdapter(
        private val list: List<RootWifiConfig>,
        private val onClick: (RootWifiConfig) -> Unit
    ) : RecyclerView.Adapter<WifiAdapter.Holder>() {

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvSsid: TextView = itemView.findViewById(R.id.tvWifiSsid)
            val root: View = itemView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_wifi_network, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = list[position]
            holder.tvSsid.text = item.ssid
            holder.root.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = list.size
    }
}