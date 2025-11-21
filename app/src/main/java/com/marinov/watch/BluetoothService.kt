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
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.content.edit

class BluetoothService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var connectionJob: Job? = null
    private var serverJob: Job? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var globalOutputStream: DataOutputStream? = null

    private val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val APP_NAME = "MarinovWatchApp"

    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "AppPrefs"

    @Volatile
    private var lastMessageTime: Long = 0L

    @Volatile
    private var isTransferring: Boolean = false

    // Variáveis de Recepção de Arquivo
    private var receiveFile: File? = null
    private var receiveFileOutputStream: FileOutputStream? = null
    private var receiveTotalSize: Long = 0
    private var receiveBytesRead: Long = 0

    private val HEARTBEAT_INTERVAL = 20000L
    private val CONNECTION_TIMEOUT = 60000L

    private val notificationMap = ConcurrentHashMap<String, Int>()

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

    // Protocolo V2 (Chunk Based)
    private val TYPE_TEXT_CMD = 1
    private val TYPE_NOTIFICATION_POSTED = 3
    private val TYPE_HEARTBEAT = 4
    private val TYPE_NOTIFICATION_REMOVED = 5
    private val TYPE_REQUEST_DISMISS = 6

    private val TYPE_FILE_START = 7
    private val TYPE_FILE_CHUNK = 8
    private val TYPE_FILE_END = 9

    // NOVO: Comando de Shutdown
    private val TYPE_SHUTDOWN_COMMAND = 10

    private val CMD_REQUEST_APPS = "CMD_REQUEST_APPS"
    private val CMD_RESPONSE_APPS = "CMD_RESPONSE_APPS:"
    private val CMD_PING = "PING"

    companion object {
        const val ACTION_SEND_NOTIF_TO_WATCH = "com.marinov.watch.ACTION_SEND_NOTIF"
        const val ACTION_SEND_REMOVE_TO_WATCH = "com.marinov.watch.ACTION_SEND_REMOVE"
        const val ACTION_WATCH_DISMISSED_LOCAL = "com.marinov.watch.DISMISSED_LOCAL"
        const val ACTION_CMD_DISMISS_ON_PHONE = "com.marinov.watch.CMD_DISMISS_PHONE"

        const val EXTRA_NOTIF_JSON = "extra_notif_json"
        const val EXTRA_NOTIF_KEY = "extra_notif_key"

        const val NOTIFICATION_ID = 1
        const val INSTALL_NOTIFICATION_ID = 2
        const val MIRRORED_NOTIFICATION_ID_START = 1000

        const val CHANNEL_ID = "bluetooth_service_channel"
        const val INSTALL_CHANNEL_ID = "install_channel"
        const val MIRRORED_CHANNEL_ID = "mirrored_notifications"

        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }

    private val internalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isConnected) return
            when (intent?.action) {
                ACTION_SEND_NOTIF_TO_WATCH -> {
                    val jsonString = intent.getStringExtra(EXTRA_NOTIF_JSON)
                    if (jsonString != null) sendPacket(TYPE_NOTIFICATION_POSTED, jsonString.toByteArray(Charsets.UTF_8))
                }
                ACTION_SEND_REMOVE_TO_WATCH -> {
                    val key = intent.getStringExtra(EXTRA_NOTIF_KEY)
                    if (key != null) sendPacket(TYPE_NOTIFICATION_REMOVED, key.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private val watchDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_WATCH_DISMISSED_LOCAL) {
                val key = intent.getStringExtra(EXTRA_NOTIF_KEY)
                if (key != null && isConnected) {
                    sendPacket(TYPE_REQUEST_DISMISS, key.toByteArray(Charsets.UTF_8))
                    notificationMap.remove(key)
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
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        createNotificationChannel()

        val filterInternal = IntentFilter().apply {
            addAction(ACTION_SEND_NOTIF_TO_WATCH)
            addAction(ACTION_SEND_REMOVE_TO_WATCH)
        }
        val filterWatch = IntentFilter(ACTION_WATCH_DISMISSED_LOCAL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(internalReceiver, filterInternal, RECEIVER_NOT_EXPORTED)
            registerReceiver(watchDismissReceiver, filterWatch, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(internalReceiver, filterInternal)
            registerReceiver(watchDismissReceiver, filterWatch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isConnected) {
            updateStatus(currentStatus)
            return START_STICKY
        }
        startForeground(NOTIFICATION_ID, createNotification("Aguardando conexão..."))
        initializeLogicFromPrefs()
        return START_STICKY
    }

    private fun initializeLogicFromPrefs() {
        val deviceType = prefs.getString("device_type", null)
        if (deviceType != null && bluetoothAdapter?.isEnabled == true) {
            if (deviceType == "PHONE") startSmartphoneLogic() else startWatchLogic()
        }
    }

    @SuppressLint("MissingPermission")
    fun startSmartphoneLogic() {
        if (connectionJob?.isActive == true) return
        val lastMac = prefs.getString("last_mac", null)
        if (lastMac != null) {
            val device = bluetoothAdapter?.getRemoteDevice(lastMac)
            if (device != null) startAutoReconnectLoop(device) else startScanForDevices()
        } else {
            startScanForDevices()
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanForDevices() {
        updateStatus("Escaneando...")
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
                        } catch (_: IOException) { null }
                    }
                }
                jobs.awaitAll().filterNotNull().forEach { successfulConnections.add(it) }
            }
            withContext(Dispatchers.Main) {
                if (successfulConnections.isEmpty()) {
                    updateStatus("Nenhum Watch encontrado.")
                    callback?.onError("Abra o app no Watch e tente novamente.")
                } else if (successfulConnections.size == 1) {
                    val (device, socket) = successfulConnections[0]
                    savePreference("last_mac", device.address)
                    startAutoReconnectLoop(device, socket)
                } else {
                    successfulConnections.forEach { try { it.second.close() } catch (_: Exception){} }
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
                    handleConnectedSocket(socketToUse, device.name)
                    socketToUse = null
                    if (isActive) { updateStatus("Reconectando..."); delay(3000) }
                } catch (_: IOException) {
                    socketToUse = null
                    if (isActive) { updateStatus("Tentando conectar..."); delay(5000) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startWatchLogic() {
        if (serverJob?.isActive == true) return
        serverJob?.cancel()
        serverJob = serviceScope.launch {
            while (isActive) {
                updateStatus("Aguardando conexão...")
                var serverSocket: BluetoothServerSocket?
                try { serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID) }
                catch (_: IOException) { delay(3000); continue }

                var socket: BluetoothSocket? = null
                while (socket == null && isActive) {
                    try { socket = serverSocket?.accept(5000) } catch (_: IOException) {}
                }
                if (socket != null) {
                    try { serverSocket?.close() } catch (_: Exception) {}
                    handleConnectedSocket(socket, socket.remoteDevice?.name ?: "Dispositivo")
                }
            }
        }
    }

    private suspend fun handleConnectedSocket(socket: BluetoothSocket, deviceName: String) {
        this.bluetoothSocket = socket
        globalOutputStream = DataOutputStream(socket.outputStream)
        val inputStream = DataInputStream(socket.inputStream)

        isConnected = true
        isTransferring = false
        lastMessageTime = System.currentTimeMillis()
        currentDeviceName = deviceName
        updateStatus("Conectado a $deviceName")
        withContext(Dispatchers.Main) { callback?.onDeviceConnected(deviceName) }
        serviceScope.launch { heartbeatLoop() }

        try {
            while (currentCoroutineContext().isActive) {
                if (isTransferring) { delay(100); continue }

                val type = inputStream.readByte().toInt()
                lastMessageTime = System.currentTimeMillis()

                when (type) {
                    TYPE_TEXT_CMD -> handleTextMessage(readString(inputStream))
                    TYPE_NOTIFICATION_POSTED -> showMirroredNotification(readString(inputStream))
                    TYPE_NOTIFICATION_REMOVED -> dismissLocalNotification(readString(inputStream))
                    TYPE_REQUEST_DISMISS -> requestDismissOnPhone(readString(inputStream))
                    TYPE_HEARTBEAT -> { /* IGNORE */ }
                    TYPE_FILE_START -> handleFileStart(inputStream.readLong())
                    TYPE_FILE_CHUNK -> {
                        val chunkSize = inputStream.readInt()
                        val buffer = ByteArray(chunkSize)
                        inputStream.readFully(buffer)
                        handleFileChunk(buffer)
                    }
                    TYPE_FILE_END -> handleFileEnd()
                    TYPE_SHUTDOWN_COMMAND -> handleShutdownCommand()
                }
            }
        } catch (_: IOException) {
            // Conexão perdida
        } finally {
            forceDisconnect()
            isConnected = false
            withContext(Dispatchers.Main) { callback?.onDeviceDisconnected() }
        }
    }

    @Throws(IOException::class)
    private fun readString(inputStream: DataInputStream): String {
        val length = inputStream.readInt()
        if (length !in 0..10_000_000) throw IOException("Tamanho string inválido: $length")
        val buffer = ByteArray(length)
        inputStream.readFully(buffer)
        return String(buffer, Charsets.UTF_8)
    }

    private suspend fun heartbeatLoop() {
        while (currentCoroutineContext().isActive) {
            delay(HEARTBEAT_INTERVAL)
            if (isTransferring || !isConnected) continue
            if (System.currentTimeMillis() - lastMessageTime > CONNECTION_TIMEOUT) {
                forceDisconnect()
                break
            }
            try { sendPacket(TYPE_HEARTBEAT, CMD_PING.toByteArray()) }
            catch (_: Exception) { break }
        }
    }

    private fun forceDisconnect() {
        try { bluetoothSocket?.close() } catch (_: Exception) {}
    }

    private fun sendPacket(type: Int, data: ByteArray) {
        serviceScope.launch(Dispatchers.IO) {
            if (isTransferring || !isConnected) return@launch
            val out = globalOutputStream ?: return@launch
            try {
                synchronized(out) {
                    out.writeByte(type)
                    out.writeInt(data.size)
                    out.write(data)
                    out.flush()
                }
            } catch (_: Exception) {
                forceDisconnect()
            }
        }
    }

    // ========================================================================
    // LÓGICA DE RECEPÇÃO (BLINDADA)
    // ========================================================================

    private fun handleFileStart(expectedSize: Long) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            receiveFile = File(dir, "temp_install.apk")
            if (receiveFile?.exists() == true) {
                receiveFile?.delete()
            }
            receiveFile?.createNewFile()

            receiveFileOutputStream = FileOutputStream(receiveFile)
            receiveTotalSize = expectedSize
            receiveBytesRead = 0
        } catch (_: Exception) {
            receiveFileOutputStream = null
        }
    }

    private fun handleFileChunk(data: ByteArray) {
        if (receiveFileOutputStream != null) {
            try {
                receiveFileOutputStream!!.write(data)
                receiveBytesRead += data.size
                lastMessageTime = System.currentTimeMillis()
            } catch (_: Exception) {
                // Erro silencioso
            }
        }
    }

    private fun handleFileEnd() {
        try {
            receiveFileOutputStream?.flush()
            receiveFileOutputStream?.close()
            receiveFileOutputStream = null

            if (receiveFile != null && receiveFile!!.exists()) {
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        // Verifica permissão ANTES de tentar mostrar notificação de instalação
                        if (!packageManager.canRequestPackageInstalls()) {
                            showPermissionNotification()
                            return@launch
                        }
                        showInstallNotification(receiveFile!!)
                    } catch (e: Exception) {
                        showErrorNotification("Erro: ${e.message}")
                    }
                }
            }
        } catch (_: Exception) {
            // Erro
        }
    }

    // ========================================================================
    // NOVA FUNÇÃO: SHUTDOWN DO SMARTWATCH
    // ========================================================================

    private fun handleShutdownCommand() {
        Log.i("BluetoothService", "Comando de shutdown recebido")

        // Executa em background para não bloquear o bluetooth
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Tenta executar o comando de shutdown com root
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot -p"))

                // Aguarda até 3 segundos pela execução
                withTimeout(3000) {
                    process.waitFor()
                }

                Log.i("BluetoothService", "Comando shutdown executado")
            } catch (e: Exception) {
                Log.e("BluetoothService", "Erro ao executar shutdown: ${e.message}")

                // Fallback: tenta sem -p (alguns devices não suportam)
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
                } catch (ex: Exception) {
                    Log.e("BluetoothService", "Erro no fallback shutdown: ${ex.message}")
                }
            }
        }
    }

    // Função pública para enviar comando de shutdown (chamada do smartphone)
    fun sendShutdownCommand() {
        sendPacket(TYPE_SHUTDOWN_COMMAND, ByteArray(0))
    }

    // ========================================================================
    // LÓGICA DE ENVIO
    // ========================================================================

    fun sendApkFile(uri: Uri) {
        serviceScope.launch(Dispatchers.IO) {
            if (!isConnected) {
                withContext(Dispatchers.Main) { callback?.onError("Não conectado.") }
                return@launch
            }

            val out = globalOutputStream ?: return@launch
            isTransferring = true

            try {
                val contentResolver = contentResolver
                val fileSize = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L

                contentResolver.openInputStream(uri)?.use { fileIS ->
                    withContext(Dispatchers.Main) { callback?.onUploadProgress(0) }

                    synchronized(out) {
                        out.writeByte(TYPE_FILE_START)
                        out.writeLong(fileSize)
                        out.flush()
                    }

                    val buffer = ByteArray(8192)
                    var totalSent = 0L
                    var bytesRead: Int
                    var lastUpdate = 0L

                    while (fileIS.read(buffer).also { bytesRead = it } != -1) {
                        synchronized(out) {
                            out.writeByte(TYPE_FILE_CHUNK)
                            out.writeInt(bytesRead)
                            out.write(buffer, 0, bytesRead)
                        }

                        lastMessageTime = System.currentTimeMillis()
                        totalSent += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 300) {
                            val progress = if (fileSize > 0) ((totalSent * 100) / fileSize).toInt() else 0
                            withContext(Dispatchers.Main) { callback?.onUploadProgress(progress) }
                            lastUpdate = now
                        }
                    }

                    synchronized(out) {
                        out.writeByte(TYPE_FILE_END)
                        out.flush()
                    }

                    withContext(Dispatchers.Main) { callback?.onUploadProgress(100) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback?.onUploadProgress(-1)
                    callback?.onError("Falha no envio: ${e.message}")
                }
            } finally {
                isTransferring = false
                lastMessageTime = System.currentTimeMillis()
            }
        }
    }

    private fun showInstallNotification(tempFile: File) {
        val authority = "${packageName}.fileprovider"
        val installUri = FileProvider.getUriForFile(this, authority, tempFile)

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(installUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, INSTALL_CHANNEL_ID)
            .setContentTitle("APK Recebido")
            .setContentText("Toque para instalar")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(INSTALL_NOTIFICATION_ID, builder.build())
    }

    // Notificação especial para pedir permissão no Watch
    private fun showPermissionNotification() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:$packageName".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, INSTALL_CHANNEL_ID)
            .setContentTitle("Configuração Necessária")
            .setContentText("Toque para permitir instalação")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(INSTALL_NOTIFICATION_ID, builder.build())
    }

    private fun showErrorNotification(msg: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Erro")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(999, builder.build())
    }

    // --- Demais Funções Auxiliares ---

    private fun handleTextMessage(message: String) {
        if (message == CMD_REQUEST_APPS) {
            val appListJson = getInstalledAppsJsonWithIcons()
            sendPacket(TYPE_TEXT_CMD, (CMD_RESPONSE_APPS + appListJson).toByteArray())
        }
        else if (message.startsWith(CMD_RESPONSE_APPS)) {
            val jsonPart = message.substring(CMD_RESPONSE_APPS.length)
            CoroutineScope(Dispatchers.Main).launch { callback?.onAppListReceived(jsonPart) }
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun getInstalledAppsJsonWithIcons(): String {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val appsList = JSONArray()
        for (appInfo in packages) {
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                try {
                    val appJson = JSONObject()
                    appJson.put("name", pm.getApplicationLabel(appInfo).toString())
                    appJson.put("package", appInfo.packageName)
                    appJson.put("icon", drawableToBase64(pm.getApplicationIcon(appInfo)))
                    appsList.put(appJson)
                } catch (_: Exception) {}
            }
        }
        return appsList.toString()
    }

    private fun drawableToBase64(drawable: Drawable): String {
        val bitmap: Bitmap = if (drawable is BitmapDrawable) drawable.bitmap else {
            val bmp = createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1)
            )
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        }
        val resized = bitmap.scale(48, 48, false)
        val outputStream = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun showMirroredNotification(jsonStr: String) {
        try {
            val obj = JSONObject(jsonStr)
            val title = obj.optString("title", "Notificação")
            val text = obj.optString("text", "")
            val pkg = obj.optString("package", "")
            val key = obj.optString("key", "")

            val notifId = notificationMap.getOrPut(key) { (System.currentTimeMillis() % 10000).toInt() + MIRRORED_NOTIFICATION_ID_START }

            val deleteIntent = Intent(ACTION_WATCH_DISMISSED_LOCAL).apply { putExtra(EXTRA_NOTIF_KEY, key); setPackage(packageName) }
            val deletePending = PendingIntent.getBroadcast(this, notifId, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val notification = NotificationCompat.Builder(this, MIRRORED_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(pkg)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDeleteIntent(deletePending)
                .build()

            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(notifId, notification)
        } catch (_: Exception) {}
    }

    private fun dismissLocalNotification(key: String) {
        notificationMap[key]?.let { id ->
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
            notificationMap.remove(key)
        }
    }

    private fun requestDismissOnPhone(key: String) {
        val intent = Intent(ACTION_CMD_DISMISS_ON_PHONE).apply { putExtra(EXTRA_NOTIF_KEY, key); setPackage(packageName) }
        sendBroadcast(intent)
    }

    fun requestRemoteAppList() { sendPacket(TYPE_TEXT_CMD, CMD_REQUEST_APPS.toByteArray()) }

    fun resetApp() {
        stopConnectionLoopOnly()
        prefs.edit { clear() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun stopConnectionLoopOnly() {
        connectionJob?.cancel()
        serverJob?.cancel()
        forceDisconnect()
        bluetoothSocket = null
        updateStatus("Parado.")
    }

    private fun updateStatus(text: String) {
        currentStatus = text
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(text))
        CoroutineScope(Dispatchers.Main).launch { callback?.onStatusChanged(text) }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Relógio Inteligente")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_smartwatch_notification)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Notificação persistente", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(INSTALL_CHANNEL_ID, "APKs", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(MIRRORED_CHANNEL_ID, "Notificações do celular", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun savePreference(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(internalReceiver) } catch(_:Exception){}
        try { unregisterReceiver(watchDismissReceiver) } catch(_:Exception){}
        serviceScope.cancel()
        forceDisconnect()
    }
}
