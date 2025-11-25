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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
            // Tenta verificar root com um pouco mais de persistência
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
        // Método mais robusto: verifica se consegue executar 'ls /data/data' que requer privilégios
        // ou checa explicitamente o exitValue do comando id
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls /data/data"))
            // Espera um pouco para garantir que o processo termine, evitando race condition
            // que causa o falso negativo enquanto o usuário aceita o prompt
            val exitCode = p.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            // Tentativa secundária leve
            try {
                val p2 = Runtime.getRuntime().exec("su")
                val os = p2.outputStream
                os.write("exit\n".toByteArray())
                os.flush()
                os.close()
                p2.waitFor() == 0
            } catch (e2: Exception) {
                false
            }
        }
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
                        // Mantém a lógica original para redes salvas (sem perguntar criptografia)
                        confirmSendWifi(config.ssid, config.psk, config.security)
                    }
                    Toast.makeText(this@WiFiAdd, "${rootNetworks.size} redes encontradas!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // DIÁLOGO ATUALIZADO: Com seleção de Criptografia
    private fun showManualAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_manual_wifi, null)
        val etSsid = view.findViewById<EditText>(R.id.etSsid)
        val etPassword = view.findViewById<EditText>(R.id.etPassword)
        val spinnerSecurity = view.findViewById<Spinner>(R.id.spinnerSecurity)
        val inputLayoutPassword = view.findViewById<TextInputLayout>(R.id.inputLayoutPassword)

        // Configura o Spinner
        val securityTypes = arrayOf("WPA/WPA2", "WEP", "Sem Senha (Open)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, securityTypes)
        spinnerSecurity.adapter = adapter

        // Listener para esconder campo de senha se for Open
        spinnerSecurity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 2) { // Sem Senha
                    inputLayoutPassword.visibility = View.GONE
                    etPassword.setText("")
                } else {
                    inputLayoutPassword.visibility = View.VISIBLE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle("Adicionar Wi-Fi Manualmente")
            .setView(view)
            .setPositiveButton("Enviar") { _, _ ->
                val ssid = etSsid.text.toString()
                val pass = etPassword.text.toString()
                val securityIndex = spinnerSecurity.selectedItemPosition

                // Mapeia a seleção para as Strings que o Watch espera
                val security = when (securityIndex) {
                    0 -> "WPA"
                    1 -> "WEP"
                    else -> "OPEN"
                }

                if (ssid.isNotEmpty()) {
                    confirmSendWifi(ssid, pass, security)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmSendWifi(ssid: String, pass: String, security: String) {
        // Texto simples conforme solicitado, sem detalhes técnicos extras na mensagem
        val msg = "Enviar rede '$ssid' para o Watch?"

        AlertDialog.Builder(this)
            .setTitle("Enviar Wi-Fi")
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