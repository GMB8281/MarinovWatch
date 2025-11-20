package com.marinov.watch

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID

class BluetoothService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var connectionJob: Job? = null
    private var serverJob: Job? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null

    private val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val APP_NAME = "MarinovWatchApp"

    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "AppPrefs"

    interface ServiceCallback {
        fun onStatusChanged(status: String)
        fun onDeviceConnected(deviceName: String)
        fun onDeviceDisconnected()
        fun onError(message: String)
        fun onScanResult(devices: List<BluetoothDevice>)
        fun onAppListReceived(appsJson: String)
        fun onUploadProgress(progress: Int)
    }

    var callback: ServiceCallback? = null

    var currentStatus: String = "Serviço Iniciado"
    var currentDeviceName: String? = null
    var isConnected: Boolean = false

    // Protocolo
    private val TYPE_TEXT_CMD = 1
    private val TYPE_FILE_APK = 2
    private val TYPE_NOTIFICATION = 3 // NOVO TIPO

    private val CMD_REQUEST_APPS = "CMD_REQUEST_APPS"
    private val CMD_RESPONSE_APPS = "CMD_RESPONSE_APPS:"

    // Ação interna para comunicação entre ListenerService e BluetoothService
    companion object {
        const val ACTION_SEND_NOTIF_TO_WATCH = "com.marinov.watch.ACTION_SEND_NOTIF"
        const val EXTRA_NOTIF_JSON = "extra_notif_json"

        const val NOTIFICATION_ID = 1
        const val INSTALL_NOTIFICATION_ID = 2
        const val MIRRORED_NOTIFICATION_ID_START = 1000 // IDs para notificações espelhadas

        const val CHANNEL_ID = "bluetooth_service_channel"
        const val INSTALL_CHANNEL_ID = "install_channel"
        const val MIRRORED_CHANNEL_ID = "mirrored_notifications" // NOVO CANAL

        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }

    // Receiver para pegar notificações do ListenerService e enviar via BT
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SEND_NOTIF_TO_WATCH) {
                val jsonString = intent.getStringExtra(EXTRA_NOTIF_JSON)
                if (jsonString != null && isConnected) {
                    sendNotificationPacket(jsonString)
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        createNotificationChannel()

        // Registra receiver local
        val filter = IntentFilter(ACTION_SEND_NOTIF_TO_WATCH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notificationReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, createNotification("Aguardando configuração..."))
        initializeLogicFromPrefs()
        return START_STICKY
    }

    private fun initializeLogicFromPrefs() {
        val deviceType = prefs.getString("device_type", null)
        if (deviceType == null) {
            updateStatus("Aguardando configuração do usuário...")
        } else {
            if (bluetoothAdapter?.isEnabled != true) {
                updateStatus("Bluetooth desligado.")
                return
            }
            if (deviceType == "PHONE") {
                startSmartphoneLogic()
            } else {
                startWatchLogic()
            }
        }
    }

    // =================================================================
    // Lógica do CLIENTE (Smartphone)
    // =================================================================
    @SuppressLint("MissingPermission")
    fun startSmartphoneLogic() {
        val lastMac = prefs.getString("last_mac", null)
        if (lastMac != null) {
            val device = bluetoothAdapter?.getRemoteDevice(lastMac)
            if (device != null) {
                startAutoReconnectLoop(device)
            } else {
                startScanForDevices()
            }
        } else {
            startScanForDevices()
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanForDevices() {
        updateStatus("Escaneando pareados...")
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            val pairedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
            val successfulConnections = mutableListOf<Pair<BluetoothDevice, BluetoothSocket>>()

            supervisorScope {
                val jobs = pairedDevices.map { device ->
                    async {
                        try {
                            val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                            socket.connect()
                            device to socket
                        } catch (e: IOException) {
                            null
                        }
                    }
                }
                jobs.awaitAll().filterNotNull().forEach { successfulConnections.add(it) }
            }

            withContext(Dispatchers.Main) {
                if (successfulConnections.isEmpty()) {
                    updateStatus("Nenhum Watch com App aberto.")
                    callback?.onError("Abra o app no Watch e tente novamente.")
                } else if (successfulConnections.size == 1) {
                    val (device, socket) = successfulConnections[0]
                    savePreference("last_mac", device.address)
                    startAutoReconnectLoop(device, socket)
                } else {
                    successfulConnections.forEach { try { it.second.close() } catch (e: Exception){} }
                    callback?.onScanResult(successfulConnections.map { it.first })
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        savePreference("last_mac", device.address)
        startAutoReconnectLoop(device)
    }

    @SuppressLint("MissingPermission")
    private fun startAutoReconnectLoop(device: BluetoothDevice, initialSocket: BluetoothSocket? = null) {
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            var socketToUse = initialSocket

            while (isActive) {
                try {
                    if (socketToUse == null) {
                        updateStatus("Conectando a ${device.name}...")
                        socketToUse = device.createRfcommSocketToServiceRecord(APP_UUID)
                        socketToUse!!.connect()
                    }
                    handleConnectedSocket(socketToUse!!, device.name)
                    socketToUse = null
                    if (isActive) {
                        updateStatus("Conexão caiu. Reconectando em 3s...")
                        delay(3000)
                    }
                } catch (e: IOException) {
                    try { socketToUse?.close() } catch (e2: Exception) {}
                    socketToUse = null
                    if (isActive) {
                        updateStatus("Falha ao conectar. Tentando em 5s...")
                        delay(5000)
                    }
                }
            }
        }
    }

    // =================================================================
    // Lógica do SERVIDOR (Watch)
    // =================================================================
    @SuppressLint("MissingPermission")
    fun startWatchLogic() {
        serverJob?.cancel()
        serverJob = serviceScope.launch {
            while (isActive) {
                updateStatus("Modo Watch: Aguardando...")
                var serverSocket: BluetoothServerSocket? = null
                try {
                    serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
                } catch (e: IOException) {
                    delay(3000)
                    continue
                }

                var socket: BluetoothSocket? = null
                while (socket == null && isActive) {
                    try {
                        socket = serverSocket?.accept()
                    } catch (e: IOException) {
                        if (serverSocket == null) break
                    }
                }

                if (socket != null) {
                    try { serverSocket?.close() } catch (e: Exception) {}
                    val remoteName = socket.remoteDevice?.name ?: "Smartphone"
                    handleConnectedSocket(socket, remoteName)
                }
            }
        }
    }

    // =================================================================
    // Gerenciamento de Conexão
    // =================================================================
    private suspend fun handleConnectedSocket(socket: BluetoothSocket, deviceName: String) {
        this.bluetoothSocket = socket
        this.isConnected = true
        this.currentDeviceName = deviceName

        updateStatus("Conectado a $deviceName")
        withContext(Dispatchers.Main) {
            callback?.onDeviceConnected(deviceName)
        }

        monitorInputLoop(socket)

        this.isConnected = false
        this.currentDeviceName = null
        try { socket.close() } catch (e: Exception) {}
        withContext(Dispatchers.Main) {
            callback?.onDeviceDisconnected()
        }
    }

    private suspend fun monitorInputLoop(socket: BluetoothSocket) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = DataInputStream(socket.inputStream)
                while (isActive) {
                    val packetType = inputStream.readByte().toInt()
                    when (packetType) {
                        TYPE_TEXT_CMD -> {
                            val length = inputStream.readInt()
                            val buffer = ByteArray(length)
                            inputStream.readFully(buffer)
                            val msg = String(buffer, Charsets.UTF_8)
                            handleTextMessage(msg)
                        }
                        TYPE_FILE_APK -> {
                            val length = inputStream.readLong()
                            receiveApkFile(inputStream, length)
                        }
                        TYPE_NOTIFICATION -> {
                            val length = inputStream.readInt()
                            val buffer = ByteArray(length)
                            inputStream.readFully(buffer)
                            val jsonStr = String(buffer, Charsets.UTF_8)
                            showMirroredNotification(jsonStr)
                        }
                        else -> throw IOException("Protocolo inválido")
                    }
                }
            } catch (e: Exception) {
                Log.e("BluetoothService", "Conexão encerrada: ${e.message}")
            }
        }
    }

    // --- Funcionalidades ---
    fun requestRemoteAppList() {
        sendTextMessage(CMD_REQUEST_APPS)
    }

    // --- Envia Pacote de Notificação (Smartphone -> Watch) ---
    private fun sendNotificationPacket(jsonStr: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (bluetoothSocket == null) return@launch
                val outputStream = DataOutputStream(bluetoothSocket!!.outputStream)
                val bytes = jsonStr.toByteArray(Charsets.UTF_8)

                synchronized(outputStream) {
                    outputStream.writeByte(TYPE_NOTIFICATION)
                    outputStream.writeInt(bytes.size)
                    outputStream.write(bytes)
                    outputStream.flush()
                }
            } catch (e: Exception) {
                Log.e("BluetoothService", "Erro ao enviar notificação", e)
            }
        }
    }

    // --- Exibe Notificação no Watch ---
    private fun showMirroredNotification(jsonStr: String) {
        try {
            val obj = JSONObject(jsonStr)
            val title = obj.optString("title", "Notificação")
            val text = obj.optString("text", "")
            val pkg = obj.optString("package", "")

            // ID único para empilhar notificações (ou randômico para mostrar todas)
            val notifId = (System.currentTimeMillis() % 10000).toInt() + MIRRORED_NOTIFICATION_ID_START

            val notification = NotificationCompat.Builder(this, MIRRORED_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(pkg) // Mostra o nome do pacote pequeno
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notifId, notification)

        } catch (e: Exception) {
            Log.e("BluetoothService", "Erro ao exibir notificação espelhada", e)
        }
    }

    fun sendApkFile(uri: Uri) {
        if (bluetoothSocket == null || !bluetoothSocket!!.isConnected) {
            CoroutineScope(Dispatchers.Main).launch {
                callback?.onError("Não conectado.")
                callback?.onUploadProgress(-1)
            }
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            var inputStream: InputStream? = null
            try {
                val contentResolver = contentResolver
                val cursor = contentResolver.query(uri, null, null, null, null)
                val sizeIndex = cursor?.getColumnIndex(android.provider.OpenableColumns.SIZE)
                cursor?.moveToFirst()
                val fileSize = cursor?.getLong(sizeIndex ?: 0) ?: 0L
                cursor?.close()

                inputStream = contentResolver.openInputStream(uri) ?: return@launch
                val socketStream = bluetoothSocket!!.outputStream
                val outputStream = DataOutputStream(socketStream)

                updateStatus("Enviando APK...")
                withContext(Dispatchers.Main) { callback?.onUploadProgress(0) }

                synchronized(outputStream) {
                    outputStream.writeByte(TYPE_FILE_APK)
                    outputStream.writeLong(fileSize)
                    outputStream.flush()
                }

                val buffer = ByteArray(4096)
                var bytesRead: Int
                var totalSent: Long = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    try {
                        // Sincronizado para não misturar com notificações saindo ao mesmo tempo
                        synchronized(outputStream) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                    catch (e: IOException) { throw IOException("Conexão perdida") }
                    totalSent += bytesRead
                    if (totalSent % (fileSize / 20 + 1) < 4096) {
                        val progress = ((totalSent * 100) / fileSize).toInt()
                        withContext(Dispatchers.Main) { callback?.onUploadProgress(progress) }
                    }
                }
                synchronized(outputStream) { outputStream.flush() }
                inputStream.close()
                updateStatus("APK Enviado!")
                withContext(Dispatchers.Main) { callback?.onUploadProgress(100) }
            } catch (e: Exception) {
                updateStatus("Falha envio APK.")
                withContext(Dispatchers.Main) { callback?.onUploadProgress(-1) }
            } finally {
                inputStream?.close()
            }
        }
    }

    private fun receiveApkFile(inputStream: DataInputStream, fileSize: Long) {
        updateStatus("Recebendo APK...")
        val cacheDir = externalCacheDir ?: cacheDir
        val file = File(cacheDir, "temp_install.apk")
        if (file.exists()) file.delete()

        try {
            val fos = FileOutputStream(file)
            val buffer = ByteArray(4096)
            var totalRead: Long = 0
            var bytesRead: Int
            while (totalRead < fileSize) {
                val remaining = fileSize - totalRead
                val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
                bytesRead = inputStream.read(buffer, 0, toRead)
                if (bytesRead == -1) throw IOException("EOF inesperado")
                fos.write(buffer, 0, bytesRead)
                totalRead += bytesRead
            }
            fos.flush()
            fos.close()

            updateStatus("APK Recebido. Verifique notificações.")
            triggerInstallNotification(file)

        } catch (e: Exception) {
            updateStatus("Erro recebimento APK.")
            if (file.exists()) file.delete()
        }
    }

    private fun handleTextMessage(message: String) {
        if (message == CMD_REQUEST_APPS) {
            val appListJson = getInstalledAppsJsonWithIcons()
            sendTextMessage(CMD_RESPONSE_APPS + appListJson)
        }
        else if (message.startsWith(CMD_RESPONSE_APPS)) {
            val jsonPart = message.substring(CMD_RESPONSE_APPS.length)
            CoroutineScope(Dispatchers.Main).launch {
                callback?.onAppListReceived(jsonPart)
            }
        }
    }

    private fun sendTextMessage(text: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (bluetoothSocket == null) return@launch
                val outputStream = DataOutputStream(bluetoothSocket!!.outputStream)
                val bytes = text.toByteArray(Charsets.UTF_8)

                synchronized(outputStream) {
                    outputStream.writeByte(TYPE_TEXT_CMD)
                    outputStream.writeInt(bytes.size)
                    outputStream.write(bytes)
                    outputStream.flush()
                }
            } catch (e: Exception) {
                Log.e("BluetoothService", "Erro envio texto", e)
            }
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun getInstalledAppsJsonWithIcons(): String {
        val pm = packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }

        val jsonArray = JSONArray()
        for (appInfo in packages) {
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                val jsonObject = JSONObject()
                jsonObject.put("name", pm.getApplicationLabel(appInfo).toString())
                jsonObject.put("package", appInfo.packageName)

                try {
                    val iconDrawable = pm.getApplicationIcon(appInfo)
                    val bitmap = drawableToBitmap(iconDrawable)
                    val resized = Bitmap.createScaledBitmap(bitmap, 48, 48, false)
                    val base64Icon = bitmapToBase64(resized)
                    jsonObject.put("icon", base64Icon)
                } catch (e: Exception) {
                    jsonObject.put("icon", "")
                }

                jsonArray.put(jsonObject)
            }
        }
        return jsonArray.toString()
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun triggerInstallNotification(file: File) {
        try {
            val apkUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this, 0, installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, INSTALL_CHANNEL_ID)
                .setContentTitle("APK Recebido")
                .setContentText("Toque aqui para instalar o aplicativo.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(INSTALL_NOTIFICATION_ID, notification)

        } catch (e: Exception) {
            updateStatus("Erro ao criar notificação de instalação.")
        }
    }

    fun resetApp() {
        stopConnectionLoopOnly()
        prefs.edit().clear().apply()
        updateStatus("Resetado.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun stopConnectionLoopOnly() {
        connectionJob?.cancel()
        serverJob?.cancel()
        try { bluetoothSocket?.close() } catch (e: Exception) {}
        bluetoothSocket = null
        updateStatus("Parado pelo usuário.")
    }

    private fun updateStatus(text: String) {
        currentStatus = text
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
        CoroutineScope(Dispatchers.Main).launch { callback?.onStatusChanged(text) }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Marinov Watch Link")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Status Conexão", NotificationManager.IMPORTANCE_LOW
            )
            val installChannel = NotificationChannel(
                INSTALL_CHANNEL_ID, "Instalação de Apps", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificações para instalar aplicativos recebidos"
                enableVibration(true)
            }

            // NOVO CANAL PARA ESPELHAMENTO
            val mirrorChannel = NotificationChannel(
                MIRRORED_CHANNEL_ID, "Notificações Espelhadas", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Mostra notificações recebidas do smartphone"
                enableVibration(true)
            }

            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(installChannel)
            manager.createNotificationChannel(mirrorChannel)
        }
    }

    private fun savePreference(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(notificationReceiver)
        serviceScope.cancel()
        try { bluetoothSocket?.close() } catch (e: Exception) {}
    }
}