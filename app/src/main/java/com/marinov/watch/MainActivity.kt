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
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
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

    private lateinit var prefs: SharedPreferences
    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private var isPhoneMode = true

    // Diálogo de Upload
    private var uploadDialog: AlertDialog? = null
    private var uploadProgressBar: ProgressBar? = null
    private var uploadPercentageText: TextView? = null
    private var uploadDescriptionText: TextView? = null
    private var uploadTitleText: TextView? = null
    private var uploadIconView: ImageView? = null
    private var uploadOkButton: Button? = null

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
                "Desligar Smartwatch",
                "Desligar completamente o smartwatch",
                android.R.drawable.ic_lock_power_off,
                { confirmShutdownWatch() }
            ),
            MenuOption(
                "Desconectar",
                "Parar serviço Bluetooth",
                android.R.drawable.ic_menu_close_clear_cancel,
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
                showUploadDialog()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmShutdownWatch() {
        if (!bluetoothService?.isConnected!!) {
            Toast.makeText(this, "Watch não conectado", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Desligar Smartwatch?")
            .setMessage("O smartwatch será completamente desligado. Esta ação requer acesso root no dispositivo.\n\nDeseja continuar?")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("Desligar") { _, _ ->
                bluetoothService?.sendShutdownCommand()
                Toast.makeText(this, "Comando enviado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showUploadDialog() {
        // Cria o layout customizado
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_upload_progress, null)

        // Obtém as referências dos elementos
        uploadProgressBar = dialogView.findViewById(R.id.progressBarUpload)
        uploadPercentageText = dialogView.findViewById(R.id.tvUploadPercentage)
        uploadDescriptionText = dialogView.findViewById(R.id.tvUploadDescription)
        uploadTitleText = dialogView.findViewById(R.id.tvUploadTitle)
        uploadIconView = dialogView.findViewById(R.id.imgUploadIcon)
        uploadOkButton = dialogView.findViewById(R.id.btnUploadOk)

        // Configura o botão OK (inicialmente invisível)
        uploadOkButton?.setOnClickListener {
            dismissUploadDialog()
        }

        // Cria e mostra o diálogo
        uploadDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        uploadDialog?.show()
    }

    private fun updateUploadProgress(progress: Int) {
        when {
            progress in 0..99 -> {
                // Transferência em andamento
                uploadProgressBar?.progress = progress
                uploadPercentageText?.text = "$progress%"
                uploadDescriptionText?.text = "Transferindo arquivo para o Watch..."
            }

            progress == 100 -> {
                // Sucesso!
                uploadProgressBar?.progress = 100
                uploadPercentageText?.text = "100%"
                uploadTitleText?.text = "Envio concluído"
                uploadDescriptionText?.text = "APK enviado com sucesso! Verifique o Watch."

                // Muda o ícone para checkmark
                uploadIconView?.setImageResource(android.R.drawable.stat_sys_upload_done)
                uploadIconView?.setColorFilter(colorSuccess)
                uploadPercentageText?.setTextColor(colorSuccess)

                // Mostra o botão OK
                uploadProgressBar?.visibility = View.GONE
                uploadOkButton?.visibility = View.VISIBLE
            }

            progress == -1 -> {
                // Erro - fecha o diálogo e mostra Toast
                dismissUploadDialog()
                Toast.makeText(this, "Falha no envio. Verifique a conexão e tente novamente.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dismissUploadDialog() {
        uploadDialog?.dismiss()
        uploadDialog = null
        uploadProgressBar = null
        uploadPercentageText = null
        uploadDescriptionText = null
        uploadTitleText = null
        uploadIconView = null
        uploadOkButton = null
    }

    // Callbacks do Serviço
    override fun onStatusChanged(status: String) {
        runOnUiThread { updateStatusUI(status, bluetoothService?.isConnected == true) }
    }

    override fun onDeviceConnected(deviceName: String) {
        runOnUiThread { updateStatusUI("Conectado", true) }
    }

    override fun onDeviceDisconnected() {
        runOnUiThread {
            updateStatusUI("Desconectado", false)
            // Se o diálogo de upload estiver aberto e houver desconexão, fecha com erro
            if (uploadDialog?.isShowing == true) {
                updateUploadProgress(-1)
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            // Se houver erro durante o upload, fecha com mensagem
            if (uploadDialog?.isShowing == true) {
                updateUploadProgress(-1)
            }
        }
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
        runOnUiThread {
            if (uploadDialog?.isShowing == true) {
                updateUploadProgress(progress)
            }
        }
    }

    override fun onAppListReceived(appsJson: String) {}

    override fun onDestroy() {
        super.onDestroy()
        dismissUploadDialog()
        if (isBound) {
            bluetoothService?.callback = null
            unbindService(connection)
            isBound = false
        }
    }
}