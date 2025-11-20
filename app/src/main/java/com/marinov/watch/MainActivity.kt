package com.marinov.watch

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.bluetooth.BluetoothDevice
import org.json.JSONArray

class MainActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    // UI Elements
    private lateinit var tvStatus: TextView
    private lateinit var scrollSelection: ScrollView
    private lateinit var layoutDashboard: LinearLayout
    private lateinit var tvConnectedDevice: TextView
    private lateinit var progressBarParams: ProgressBar
    private lateinit var btnSmartphone: Button
    private lateinit var btnWatch: Button
    private lateinit var btnReset: Button
    private lateinit var btnDisconnect: Button

    // Gerenciamento App (Smartphone)
    private lateinit var layoutAppManager: LinearLayout
    private lateinit var btnListApps: Button
    private lateinit var btnInstallApk: Button
    private lateinit var btnNotifications: Button // NOVO BOTÃO
    private lateinit var tvUploadProgress: TextView

    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private lateinit var prefs: SharedPreferences

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            bluetoothService?.callback = this@MainActivity
            isBound = true
            syncUIState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            bluetoothService = null
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            checkBatteryOptimization()
        }

    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            confirmApkUpload(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        initViews()
        setupButtons()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (isBound && bluetoothService != null) {
            bluetoothService?.callback = this
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            bluetoothService?.callback = null
            unbindService(connection)
            isBound = false
        }
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        scrollSelection = findViewById(R.id.scrollSelection)
        layoutDashboard = findViewById(R.id.layoutDashboard)
        tvConnectedDevice = findViewById(R.id.tvConnectedDevice)
        progressBarParams = findViewById(R.id.progressBarParams)
        btnSmartphone = findViewById(R.id.btnSmartphone)
        btnWatch = findViewById(R.id.btnWatch)
        btnReset = findViewById(R.id.btnReset)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        layoutAppManager = findViewById(R.id.layoutAppManager)
        btnListApps = findViewById(R.id.btnListApps)
        btnInstallApk = findViewById(R.id.btnInstallApk)
        btnNotifications = findViewById(R.id.btnNotifications) // Link com XML
        tvUploadProgress = findViewById(R.id.tvUploadProgress)
    }

    private fun setupButtons() {
        btnSmartphone.setOnClickListener {
            savePreference("device_type", "PHONE")
            startServiceLogic(isPhone = true)
        }

        btnWatch.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!packageManager.canRequestPackageInstalls()) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    Toast.makeText(this, "Permita a instalação para receber APKs", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }
            savePreference("device_type", "WATCH")
            startServiceLogic(isPhone = false)
        }

        btnReset.setOnClickListener {
            bluetoothService?.resetApp()
            syncUIState()
        }

        btnDisconnect.setOnClickListener {
            bluetoothService?.stopConnectionLoopOnly()
            showSelectionScreen()
        }

        btnListApps.setOnClickListener {
            val intent = Intent(this, AppListActivity::class.java)
            startActivity(intent)
        }

        btnInstallApk.setOnClickListener {
            pickApkLauncher.launch("application/vnd.android.package-archive")
        }

        // Listener do novo botão de notificações
        btnNotifications.setOnClickListener {
            val intent = Intent(this, NotificationSettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun confirmApkUpload(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Enviar APK?")
            .setMessage("Deseja enviar e instalar este arquivo no Watch conectado?")
            .setPositiveButton("Enviar") { _, _ ->
                bluetoothService?.sendApkFile(uri)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            checkBatteryOptimization()
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            } else {
                bindToService()
            }
        } else {
            bindToService()
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissão Necessária")
            .setMessage("Para manter a conexão estável, permita ignorar otimizações.")
            .setPositiveButton("Permitir") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                bindToService()
            }
            .setNegativeButton("Agora não") { _, _ -> bindToService() }
            .show()
    }

    private fun bindToService() {
        val intent = Intent(this, BluetoothService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    // =================================================================
    // Lógica de UI
    // =================================================================
    private fun startServiceLogic(isPhone: Boolean) {
        setLoading(true)
        if (isPhone) {
            bluetoothService?.startSmartphoneLogic()
        } else {
            bluetoothService?.startWatchLogic()
        }
    }

    private fun syncUIState() {
        val service = bluetoothService ?: return
        tvStatus.text = "Status: ${service.currentStatus}"

        val type = prefs.getString("device_type", null)
        if (type == null) {
            showSelectionScreen()
            return
        }

        if (service.isConnected) {
            showDashboard(service.currentDeviceName ?: "Dispositivo")
        } else {
            scrollSelection.visibility = View.GONE
            layoutDashboard.visibility = View.VISIBLE
            layoutAppManager.visibility = View.GONE
            tvConnectedDevice.text = "Aguardando..."
            tvConnectedDevice.setTextColor(getColor(android.R.color.darker_gray))
        }
        setLoading(false)
    }

    // =================================================================
    // Callbacks
    // =================================================================
    override fun onStatusChanged(status: String) {
        runOnUiThread { tvStatus.text = "Status: $status" }
    }

    override fun onDeviceConnected(deviceName: String) {
        runOnUiThread { showDashboard(deviceName) }
    }

    override fun onDeviceDisconnected() {
        runOnUiThread {
            tvConnectedDevice.text = "Desconectado"
            tvConnectedDevice.setTextColor(getColor(android.R.color.holo_red_dark))
            layoutAppManager.visibility = View.GONE
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            setLoading(false)
            AlertDialog.Builder(this).setTitle("Aviso").setMessage(message).setPositiveButton("OK", null).show()
        }
    }

    override fun onScanResult(devices: List<BluetoothDevice>) {
        runOnUiThread {
            val deviceNames = devices.map { "${it.name} (${it.address})" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Escolha o Watch")
                .setItems(deviceNames) { _, which -> bluetoothService?.connectToDevice(devices[which]) }
                .setCancelable(false)
                .show()
        }
    }

    override fun onUploadProgress(progress: Int) {
        runOnUiThread {
            if (progress == -1) {
                tvUploadProgress.text = "Erro no envio."
                tvUploadProgress.setTextColor(getColor(android.R.color.holo_red_dark))
            } else if (progress == 100) {
                tvUploadProgress.text = "Envio Concluído!"
                tvUploadProgress.setTextColor(getColor(android.R.color.holo_green_dark))
            } else {
                tvUploadProgress.visibility = View.VISIBLE
                tvUploadProgress.text = "Enviando APK: $progress%"
                tvUploadProgress.setTextColor(getColor(android.R.color.black))
            }
        }
    }

    override fun onAppListReceived(appsJson: String) {}

    private fun showSelectionScreen() {
        scrollSelection.visibility = View.VISIBLE
        layoutDashboard.visibility = View.GONE
        setLoading(false)
    }

    private fun showDashboard(deviceName: String) {
        scrollSelection.visibility = View.GONE
        layoutDashboard.visibility = View.VISIBLE
        tvConnectedDevice.text = "Conectado a: $deviceName"
        tvConnectedDevice.setTextColor(getColor(android.R.color.holo_green_dark))
        setLoading(false)

        val type = prefs.getString("device_type", "")
        if (type == "PHONE") {
            layoutAppManager.visibility = View.VISIBLE
        } else {
            layoutAppManager.visibility = View.GONE
        }
    }

    private fun setLoading(isLoading: Boolean) {
        progressBarParams.visibility = if (isLoading) View.VISIBLE else View.GONE
        btnSmartphone.isEnabled = !isLoading
        btnWatch.isEnabled = !isLoading
    }

    private fun savePreference(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}