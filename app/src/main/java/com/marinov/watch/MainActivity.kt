package com.marinov.watch

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.TypedValue
import android.widget.LinearLayout

class MainActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    // UI References
    private lateinit var tvHeaderDeviceName: TextView
    private lateinit var tvHeaderStatus: TextView
    private lateinit var progressBarMain: ProgressBar
    private lateinit var recyclerViewMenu: RecyclerView

    // Watch Mode UI
    private lateinit var layoutWatchMode: LinearLayout
    private lateinit var imgWatchStatus: ImageView
    private lateinit var tvWatchStatusBig: TextView
    private lateinit var btnResetWatch: android.widget.Button

    private lateinit var prefs: SharedPreferences
    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private var isPhoneMode = true

    // Cores dinâmicas (resolvidas em runtime)
    private var colorError = 0
    private var colorSuccess = 0
    private var colorTextPrimary = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            bluetoothService?.callback = this@MainActivity
            isBound = true

            // Inicia lógica baseada no tipo salvo
            val type = prefs.getString("device_type", "PHONE")
            if (type == "PHONE") bluetoothService?.startSmartphoneLogic()
            else bluetoothService?.startWatchLogic()

            updateStatusUI(bluetoothService?.currentStatus ?: "Iniciando...", bluetoothService?.isConnected == true)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            bluetoothService = null
        }
    }

    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { confirmApkUpload(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        // 1. Verifica se é a primeira vez. Se sim, vai para Welcome.
        if (!prefs.contains("device_type")) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        resolveThemeColors()
        initViews()

        isPhoneMode = prefs.getString("device_type", "PHONE") == "PHONE"
        setupLayoutMode()

        // 2. Otimização de Bateria (Direto, sem diálogo)
        checkBatteryOptimizationDirect()

        // 3. Inicia Serviço
        bindToService()
    }

    private fun resolveThemeColors() {
        // Pega cores do tema para usar programaticamente
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.colorError, typedValue, true)
        colorError = typedValue.data

        // Verde genérico para sucesso (pode ajustar para um atributo customizado se tiver)
        colorSuccess = ContextCompat.getColor(this, android.R.color.holo_green_dark)

        theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        colorTextPrimary = typedValue.data
    }

    private fun initViews() {
        tvHeaderDeviceName = findViewById(R.id.tvHeaderDeviceName)
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)
        progressBarMain = findViewById(R.id.progressBarMain)
        recyclerViewMenu = findViewById(R.id.recyclerViewMenu)

        layoutWatchMode = findViewById(R.id.layoutWatchMode)
        imgWatchStatus = findViewById(R.id.imgWatchStatus)
        tvWatchStatusBig = findViewById(R.id.tvWatchStatusBig)
        btnResetWatch = findViewById(R.id.btnResetWatch)

        btnResetWatch.setOnClickListener { resetApp() }
    }

    private fun setupLayoutMode() {
        if (isPhoneMode) {
            layoutWatchMode.visibility = View.GONE
            recyclerViewMenu.visibility = View.VISIBLE
            setupPhoneMenu()
        } else {
            // Esconde a lista e o CoordinatorLayout collapse behavior se possível,
            // ou apenas mostra o layout de overlay do watch
            layoutWatchMode.visibility = View.VISIBLE
            recyclerViewMenu.visibility = View.GONE
            // No modo Watch, o header padrão do layout main ainda aparece,
            // mas o layoutWatchMode cobre o conteúdo.
            // Vamos deixar o header visível para debug, ou você pode esconder o AppBarLayout.
            findViewById<View>(R.id.appBarLayout).visibility = View.GONE
        }
    }

    private fun setupPhoneMenu() {
        recyclerViewMenu.layoutManager = LinearLayoutManager(this)
        val options = listOf(
            MenuOption(
                "Gerenciar Aplicativos",
                "Ver lista de apps instalados no Watch",
                android.R.drawable.ic_menu_sort_by_size,
                { startActivity(Intent(this, AppListActivity::class.java)) }
            ),
            MenuOption(
                "Notificações",
                "Escolher quais apps enviam alertas",
                android.R.drawable.ic_popup_reminder,
                { startActivity(Intent(this, NotificationSettingsActivity::class.java)) }
            ),
            MenuOption(
                "Instalar APK",
                "Enviar arquivo .apk para o Watch",
                android.R.drawable.ic_input_add,
                { pickApkLauncher.launch("application/vnd.android.package-archive") }
            ),
            MenuOption(
                "Desconectar",
                "Parar serviço Bluetooth",
                android.R.drawable.ic_lock_power_off,
                {
                    bluetoothService?.stopConnectionLoopOnly()
                    updateStatusUI("Parado", false)
                }
            ),
            MenuOption(
                "Resetar Tudo",
                "Apagar configurações e voltar ao início",
                android.R.drawable.ic_menu_delete,
                { resetApp() }
            )
        )
        recyclerViewMenu.adapter = MenuAdapter(options)
    }

    private fun checkBatteryOptimizationDirect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Não foi possível solicitar otimização de bateria.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bindToService() {
        val intent = Intent(this, BluetoothService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun updateStatusUI(status: String, isConnected: Boolean) {
        val deviceName = bluetoothService?.currentDeviceName ?: "Dispositivo"

        // Atualiza Header (Phone Mode)
        tvHeaderDeviceName.text = if (isConnected) deviceName else "Aguardando..."
        tvHeaderStatus.text = if (isConnected) "Conectado" else status
        tvHeaderStatus.setTextColor(if (isConnected) colorSuccess else colorError)

        progressBarMain.visibility = if (!isConnected && status.contains("Conectando")) View.VISIBLE else View.INVISIBLE

        // Atualiza Watch Mode UI
        if (!isPhoneMode) {
            if (isConnected) {
                tvWatchStatusBig.text = "Conectado"
                tvWatchStatusBig.setTextColor(colorSuccess)
                imgWatchStatus.setImageTintList(ColorStateList.valueOf(colorSuccess))
            } else {
                tvWatchStatusBig.text = "Desconectado"
                tvWatchStatusBig.setTextColor(colorError)
                imgWatchStatus.setImageTintList(ColorStateList.valueOf(colorError))
            }
        }
    }

    private fun resetApp() {
        bluetoothService?.resetApp()
        startActivity(Intent(this, WelcomeActivity::class.java))
        finish()
    }

    private fun confirmApkUpload(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Enviar APK?")
            .setMessage("Deseja instalar este app no Watch?")
            .setPositiveButton("Enviar") { _, _ ->
                bluetoothService?.sendApkFile(uri)
                Toast.makeText(this, "Envio iniciado em segundo plano", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Callbacks do Serviço
    override fun onStatusChanged(status: String) {
        runOnUiThread { updateStatusUI(status, bluetoothService?.isConnected == true) }
    }

    override fun onDeviceConnected(deviceName: String) {
        runOnUiThread { updateStatusUI("Conectado", true) }
    }

    override fun onDeviceDisconnected() {
        runOnUiThread { updateStatusUI("Desconectado", false) }
    }

    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override fun onScanResult(devices: List<BluetoothDevice>) {
        runOnUiThread {
            if (devices.isEmpty()) return@runOnUiThread
            // Mostra diálogo para escolher dispositivo (apenas Phone Mode precisa disso)
            if (isPhoneMode) {
                val names = devices.map { "${it.name ?: "Sem nome"} (${it.address})" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Selecione o Watch")
                    .setItems(names) { _, which -> bluetoothService?.connectToDevice(devices[which]) }
                    .show()
            }
        }
    }

    override fun onUploadProgress(progress: Int) {
        // Opcional: Atualizar algum elemento da UI com progresso
        // Como movemos para lista, podemos usar um Toast ou atualizar o subtítulo de um item da lista se quiséssemos ser fancy
    }

    override fun onAppListReceived(appsJson: String) {}

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            bluetoothService?.callback = null
            unbindService(connection)
            isBound = false
        }
    }
}