package com.marinov.watch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var switchEnable: SwitchMaterial
    private lateinit var layoutPermissionWarning: LinearLayout
    private lateinit var layoutAppsSection: LinearLayout
    private lateinit var btnOpenSettings: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar

    private lateinit var prefs: SharedPreferences
    private lateinit var appAdapter: AppNotificationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        initViews()
        setupMasterSwitch()
        setupRecyclerView()
        loadInstalledApps()
    }

    private fun initViews() {
        switchEnable = findViewById(R.id.switchEnableMirroring)
        layoutPermissionWarning = findViewById(R.id.layoutPermissionWarning)
        layoutAppsSection = findViewById(R.id.layoutAppsSection)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        recyclerView = findViewById(R.id.recyclerViewApps)
        progressBar = findViewById(R.id.progressBarApps)
    }

    override fun onResume() {
        super.onResume()
        // Verifica permissões e atualiza estado sempre que a tela ganha foco
        updateUiState()
    }

    private fun setupMasterSwitch() {
        // Configuração inicial (padrão false se não existir)
        if (!prefs.contains("notification_mirroring_enabled")) {
            prefs.edit().putBoolean("notification_mirroring_enabled", false).apply()
        }

        // Define estado inicial visual sem disparar listener ainda (será tratado no updateUiState)
        val isEnabled = prefs.getBoolean("notification_mirroring_enabled", false)
        switchEnable.isChecked = isEnabled

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notification_mirroring_enabled", isChecked).apply()
            updateUiState() // Atualiza a UI (alpha da lista, etc)
        }
    }

    // --- LÓGICA CENTRAL DE ESTADO ---
    private fun updateUiState() {
        val hasPermission = isNotificationServiceEnabled()

        // Lógica de Auto-Ativação:
        // Se o usuário clicou em "Conceder" anteriormente (flag waiting_for_permission)
        // E agora voltou com a permissão concedida -> Ativa o switch automaticamente.
        if (hasPermission && prefs.getBoolean("waiting_for_permission", false)) {
            prefs.edit()
                .remove("waiting_for_permission") // Limpa a flag
                .putBoolean("notification_mirroring_enabled", true) // Salva como ativado
                .apply()

            // Atualiza visualmente o switch (o listener vai disparar, mas o valor já está salvo)
            switchEnable.isChecked = true
        }

        val isSwitchOn = prefs.getBoolean("notification_mirroring_enabled", false)

        // Sincroniza visualmente caso tenha mudado via auto-ativação ou preferência externa
        if (switchEnable.isChecked != isSwitchOn) {
            switchEnable.isChecked = isSwitchOn
        }

        if (!hasPermission) {
            // --- SEM PERMISSÃO ---
            // 1. Bloqueia o switch: Usuário não pode ativar sem permissão
            switchEnable.isEnabled = false
            switchEnable.isChecked = false // Visualmente desligado (forçado)

            // 2. Mostra aviso e botão de ação
            layoutPermissionWarning.visibility = View.VISIBLE
            layoutAppsSection.visibility = View.GONE

            btnOpenSettings.setOnClickListener {
                // Marca que estamos indo buscar a permissão para auto-ativar na volta
                prefs.edit().putBoolean("waiting_for_permission", true).apply()
                openNotificationAccessSettings()
            }
        } else {
            // --- COM PERMISSÃO ---
            // 1. Libera o switch
            switchEnable.isEnabled = true

            // 2. Esconde aviso, Mostra lista
            layoutPermissionWarning.visibility = View.GONE
            layoutAppsSection.visibility = View.VISIBLE

            // 3. Controla opacidade da lista baseado no switch (On/Off)
            if (isSwitchOn) {
                recyclerView.alpha = 1.0f
                recyclerView.isEnabled = true
                // Opcional: Se quiser bloquear cliques nos itens quando desligado, precisaria ajustar o Adapter
            } else {
                recyclerView.alpha = 0.4f
                // Lista visível mas "apagada" para indicar que o mestre está desligado
            }
        }
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        appAdapter = AppNotificationAdapter(prefs)
        recyclerView.adapter = appAdapter
    }

    private fun loadInstalledApps() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)

            val appsList = mutableListOf<AppNotificationItem>()

            val allPackages = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }

            val packageNamesFound = mutableSetOf<String>()

            for (appInfo in allPackages) {
                if (appInfo.packageName == packageName) continue

                try {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    appsList.add(AppNotificationItem(label, appInfo.packageName, icon))
                    packageNamesFound.add(appInfo.packageName)
                } catch (e: Exception) {
                    continue
                }
            }
            appsList.sortBy { it.appName.lowercase() }

            // Se é a primeira vez (nenhuma config salva), seleciona TODOS por padrão
            if (!prefs.contains("allowed_notif_packages")) {
                prefs.edit().putStringSet("allowed_notif_packages", packageNamesFound).apply()
                appAdapter.reloadAllowedPackages()
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                appAdapter.setData(appsList)
            }
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, MyNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    // Nota: O diálogo de permissão "automático" ao clicar no switch foi removido
    // pois agora o switch fica bloqueado até ter permissão. Apenas o botão "Conceder" chama isso.
    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao abrir configurações.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

// --- Classes do Adapter permanecem as mesmas ---
data class AppNotificationItem(
    val appName: String,
    val packageName: String,
    val icon: Drawable
)

class AppNotificationAdapter(private val prefs: SharedPreferences) : RecyclerView.Adapter<AppNotificationAdapter.ViewHolder>() {

    private var items = listOf<AppNotificationItem>()
    private val allowedPackages = mutableSetOf<String>()

    init {
        reloadAllowedPackages()
    }

    fun reloadAllowedPackages() {
        val savedSet = prefs.getStringSet("allowed_notif_packages", null)
        allowedPackages.clear()
        if (savedSet != null) {
            allowedPackages.addAll(savedSet)
        }
    }

    fun setData(newItems: List<AppNotificationItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val switchApp: SwitchMaterial = itemView.findViewById(R.id.switchAppNotification)

        fun bind(item: AppNotificationItem) {
            tvName.text = item.appName
            imgIcon.setImageDrawable(item.icon)

            switchApp.setOnCheckedChangeListener(null)
            switchApp.isChecked = allowedPackages.contains(item.packageName)

            switchApp.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    allowedPackages.add(item.packageName)
                } else {
                    allowedPackages.remove(item.packageName)
                }
                prefs.edit().putStringSet("allowed_notif_packages", allowedPackages.toSet()).apply()
            }
        }
    }
}