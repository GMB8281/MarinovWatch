package com.marinov.watch

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
        checkRootAndStart()
    }

    private fun checkRootAndStart() {
        CoroutineScope(Dispatchers.IO).launch {
            val hasRoot = checkRoot()
            withContext(Dispatchers.Main) {
                if (!hasRoot) {
                    AlertDialog.Builder(this@WiFiAdd)
                        .setTitle("Acesso Negado")
                        .setMessage("Este recurso requer acesso root para ler as senhas de Wi-Fi salvas no sistema.")
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

        // Bind ao serviço
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        loadWifiNetworks()
    }

    @SuppressLint("MissingPermission")
    private fun loadWifiNetworks() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permissão de localização necessária", Toast.LENGTH_LONG).show()
            return
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val networks = wifiManager.configuredNetworks ?: emptyList()

        val distinctNetworks = networks
            .filter { it.SSID != null }
            .distinctBy { it.SSID }
            .sortedBy { it.SSID }

        if (distinctNetworks.isEmpty()) {
            findViewById<TextView>(R.id.tvEmptyState).visibility = View.VISIBLE
        } else {
            recyclerView.adapter = WifiAdapter(distinctNetworks) { config ->
                confirmSendWifi(config)
            }
        }
    }

    private fun confirmSendWifi(config: android.net.wifi.WifiConfiguration) {
        val rawSsid = config.SSID.replace("\"", "")

        AlertDialog.Builder(this)
            .setTitle("Enviar Wi-Fi?")
            .setMessage("Deseja extrair a senha de '$rawSsid' e salvar no smartwatch?")
            .setPositiveButton("Sim") { _, _ ->
                if (bluetoothService?.isConnected == true) {
                    extractPasswordAndSend(rawSsid)
                } else {
                    Toast.makeText(this, "Watch não conectado.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun extractPasswordAndSend(ssid: String) {
        // Como o usuário garantiu Root, vamos tentar extrair a senha real
        CoroutineScope(Dispatchers.IO).launch {
            val password = getWifiPasswordRoot(ssid)

            withContext(Dispatchers.Main) {
                if (password != null) {
                    bluetoothService?.sendWifiData(ssid, password, "WPA")
                    Toast.makeText(this@WiFiAdd, "Senha encontrada e enviada!", Toast.LENGTH_SHORT).show()
                } else {
                    // Fallback para rede aberta ou erro
                    Toast.makeText(this@WiFiAdd, "Não foi possível ler a senha (Rede aberta?)", Toast.LENGTH_LONG).show()
                    bluetoothService?.sendWifiData(ssid, "", "OPEN")
                }
            }
        }
    }
    private fun getWifiPasswordRoot(targetSsid: String): String? {
        try {
            // Tenta ler o arquivo conf tradicional
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /data/misc/wifi/wpa_supplicant.conf"))
            val reader = process.inputStream.bufferedReader()
            val content = reader.readText()
            reader.close()
            process.waitFor()

            // Parser manual muito simples
            val networks = content.split("network={")
            for (net in networks) {
                if (net.contains("ssid=\"$targetSsid\"")) {
                    val pskLine = net.lines().find { it.trim().startsWith("psk=") }
                    return pskLine?.substringAfter("psk=")?.replace("\"", "")?.trim()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null // Retorna null se não achar ou falhar
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(connection)
    }

    // ADAPTER INTERNO
    inner class WifiAdapter(
        private val list: List<android.net.wifi.WifiConfiguration>,
        private val onClick: (android.net.wifi.WifiConfiguration) -> Unit
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
            holder.tvSsid.text = item.SSID.replace("\"", "")
            holder.root.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = list.size
    }
}