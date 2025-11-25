package com.marinov.watch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class RootWifiConfig(
    val ssid: String,
    val psk: String,
    val security: String = "WPA"
)

class WiFiAdd : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var fabAddManual: FloatingActionButton
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

        initUI()
        checkRootAndScan()
    }

    private fun initUI() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerViewWifi)
        recyclerView.layoutManager = LinearLayoutManager(this)

        fabAddManual = findViewById(R.id.fabAddManual)
        fabAddManual.setOnClickListener { showManualAddDialog() }

        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun checkRootAndScan() {
        CoroutineScope(Dispatchers.IO).launch {
            val hasRoot = checkRoot()
            withContext(Dispatchers.Main) {
                if (!hasRoot) {
                    Toast.makeText(this@WiFiAdd, "Root não detectado. Use o botão (+) para adicionar manualmente.", Toast.LENGTH_LONG).show()
                    findViewById<TextView>(R.id.tvEmptyState).apply {
                        visibility = View.VISIBLE
                        text = "Sem acesso Root.\nAdicione manualmente pelo botão (+)."
                    }
                } else {
                    loadWifiNetworksRoot()
                }
            }
        }
    }

    private fun checkRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.waitFor() == 0
        } catch (_: Exception) { false }
    }

    private fun loadWifiNetworksRoot() {
        CoroutineScope(Dispatchers.IO).launch {
            val rootNetworks = RootWifiScanner.scanAllWifiConfigs()

            withContext(Dispatchers.Main) {
                if (rootNetworks.isEmpty()) {
                    findViewById<TextView>(R.id.tvEmptyState).apply {
                        visibility = View.VISIBLE
                        text = "Nenhuma rede encontrada via Root.\n(Tente o botão +)"
                    }
                } else {
                    findViewById<TextView>(R.id.tvEmptyState).visibility = View.GONE
                    recyclerView.adapter = WifiAdapter(rootNetworks) { config ->
                        confirmSendWifi(config.ssid, config.psk, config.security)
                    }
                    Toast.makeText(this@WiFiAdd, "${rootNetworks.size} redes encontradas!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // DIÁLOGO SIMPLIFICADO: Sem Spinner de Criptografia
    private fun showManualAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_manual_wifi, null)
        val etSsid = view.findViewById<EditText>(R.id.etSsid)
        val etPassword = view.findViewById<EditText>(R.id.etPassword)

        AlertDialog.Builder(this)
            .setTitle("Adicionar Wi-Fi Manualmente")
            .setView(view)
            .setPositiveButton("Enviar") { _, _ ->
                val ssid = etSsid.text.toString()
                val pass = etPassword.text.toString()

                if (ssid.isNotEmpty()) {
                    // Lógica automática: Senha vazia = OPEN, Senha preenchida = WPA
                    val security = if (pass.isEmpty()) "OPEN" else "WPA"
                    confirmSendWifi(ssid, pass, security)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmSendWifi(ssid: String, pass: String, security: String) {
        val msg = if (security == "OPEN") "Enviar rede aberta '$ssid'?" else "Enviar rede '$ssid'?"

        AlertDialog.Builder(this)
            .setTitle("Enviar para Watch")
            .setMessage(msg)
            .setPositiveButton("Sim") { _, _ ->
                if (bluetoothService?.isConnected == true) {
                    bluetoothService?.sendWifiData(ssid, pass, security)
                    Toast.makeText(this, "Enviado!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Watch desconectado.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(connection)
    }

    object RootWifiScanner {
        private val CONF_PATHS = listOf(
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml",
            "/data/misc/wifi/wpa_supplicant.conf"
        )

        fun scanAllWifiConfigs(): List<RootWifiConfig> {
            val networks = mutableListOf<RootWifiConfig>()
            for (path in CONF_PATHS) {
                val content = readFile(path)
                if (content.isNotEmpty()) {
                    if (path.endsWith(".xml")) networks.addAll(parseWifiConfigStoreXml(content))
                    else networks.addAll(parseWpaSupplicant(content))
                    if (networks.isNotEmpty()) break
                }
            }
            return networks.distinctBy { it.ssid }.sortedBy { it.ssid }
        }

        private fun readFile(path: String): String {
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat \"$path\""))
                val sb = StringBuilder()
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line).append("\n")
                sb.toString()
            } catch (e: Exception) { "" }
        }

        private fun parseWifiConfigStoreXml(xml: String): List<RootWifiConfig> {
            val list = mutableListOf<RootWifiConfig>()
            try {
                val blockRegex = Regex("<WifiConfiguration>.*?</WifiConfiguration>", RegexOption.DOT_MATCHES_ALL)
                val blocks = blockRegex.findAll(xml)
                for (match in blocks) {
                    val block = match.value
                    val ssidMatch = Regex("<string name=\"SSID\">&quot;(.*?)&quot;</string>").find(block)
                        ?: Regex("<string name=\"SSID\">(.*?)</string>").find(block)
                    if (ssidMatch != null) {
                        val ssid = ssidMatch.groupValues[1].replace("&quot;", "")
                        val pskMatch = Regex("<string name=\"PreSharedKey\">&quot;(.*?)&quot;</string>").find(block)
                            ?: Regex("<string name=\"PreSharedKey\">(.*?)</string>").find(block)
                        val psk = pskMatch?.groupValues?.get(1)?.replace("&quot;", "") ?: ""
                        val security = if (psk.isEmpty()) "OPEN" else "WPA"
                        if (ssid.isNotEmpty()) list.add(RootWifiConfig(ssid, psk, security))
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            return list
        }

        private fun parseWpaSupplicant(conf: String): List<RootWifiConfig> {
            val list = mutableListOf<RootWifiConfig>()
            val networks = conf.split("network={")
            for (net in networks) {
                val ssidLine = net.lines().find { it.trim().startsWith("ssid=") }
                val pskLine = net.lines().find { it.trim().startsWith("psk=") }
                if (ssidLine != null) {
                    val ssid = ssidLine.substringAfter("=").trim().replace("\"", "")
                    val psk = pskLine?.substringAfter("=")?.trim()?.replace("\"", "") ?: ""
                    list.add(RootWifiConfig(ssid, psk, if (psk.isEmpty()) "OPEN" else "WPA"))
                }
            }
            return list
        }
    }

    inner class WifiAdapter(
        private val list: List<RootWifiConfig>,
        private val onClick: (RootWifiConfig) -> Unit
    ) : RecyclerView.Adapter<WifiAdapter.Holder>() {
        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvSsid: TextView = itemView.findViewById(R.id.tvWifiSsid)
            val root: View = itemView
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_wifi_network, parent, false)
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