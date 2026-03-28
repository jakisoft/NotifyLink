package com.jaky.notifylink

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var etBotToken: EditText
    private lateinit var etChatId: EditText
    private lateinit var etFilterInput: EditText
    private lateinit var etWebhookUrl: EditText
    private lateinit var etStatusApiUrl: EditText
    private lateinit var switchMaster: Switch
    private lateinit var switchTelegram: Switch
    private lateinit var switchWebhook: Switch
    private lateinit var btnSave: Button
    private lateinit var btnSelectPackages: Button
    private lateinit var btnAddFilter: Button
    private lateinit var packageChipContainer: LinearLayout
    private lateinit var filterChipContainer: LinearLayout
    private lateinit var navHome: ImageView
    private lateinit var navLogs: ImageView
    private lateinit var navAbout: ImageView
    private lateinit var navHomeTab: LinearLayout
    private lateinit var navLogsTab: LinearLayout
    private lateinit var navAboutTab: LinearLayout
    private lateinit var navHomeLabel: TextView
    private lateinit var navLogsLabel: TextView
    private lateinit var navAboutLabel: TextView
    private lateinit var statusDot: View
    private lateinit var layoutSuccess: LinearLayout
    private lateinit var tvSonnerMsg: TextView
    private lateinit var tvSonnerLabel: TextView
    private lateinit var layoutPackageLoading: LinearLayout
    private lateinit var tvPackageLoading: TextView
    private lateinit var mainConfigScroll: ScrollView
    private lateinit var layoutLogsPage: RelativeLayout
    private lateinit var layoutAboutPage: ScrollView
    private lateinit var containerLogList: LinearLayout
    private lateinit var btnTrash: ImageButton
    private lateinit var tvAppInfoDetail: TextView
    private lateinit var tvDevDetail: TextView
    private lateinit var tvLinkWebsite: TextView
    private lateinit var tvLinkGithub: TextView
    private lateinit var tvLinkTiktok: TextView
    private lateinit var tvLinkInstagram: TextView
    private lateinit var tvLinkWhatsapp: TextView

    private val selectedPackages = mutableListOf<String>()
    private val selectedFilters = mutableListOf<String>()
    private val appLabelByPackage = mutableMapOf<String, String>()

    data class ParsedLog(
        val time: String,
        val packageName: String,
        val title: String,
        val message: String,
        val fullContent: String
    )

    data class DeviceApp(
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etBotToken = findViewById(R.id.etBotToken)
        etChatId = findViewById(R.id.etChatId)
        etFilterInput = findViewById(R.id.etFilterInput)
        etWebhookUrl = findViewById(R.id.etWebhookUrl)
        etStatusApiUrl = findViewById(R.id.etStatusApiUrl)
        switchMaster = findViewById(R.id.switchMaster)
        switchTelegram = findViewById(R.id.switchTelegram)
        switchWebhook = findViewById(R.id.switchWebhook)
        btnSave = findViewById(R.id.btnSave)
        btnSelectPackages = findViewById(R.id.btnSelectPackages)
        btnAddFilter = findViewById(R.id.btnAddFilter)
        packageChipContainer = findViewById(R.id.packageChipContainer)
        filterChipContainer = findViewById(R.id.filterChipContainer)
        navHome = findViewById(R.id.nav_home)
        navLogs = findViewById(R.id.nav_logs)
        navAbout = findViewById(R.id.nav_about)
        navHomeTab = findViewById(R.id.nav_home_tab)
        navLogsTab = findViewById(R.id.nav_logs_tab)
        navAboutTab = findViewById(R.id.nav_about_tab)
        navHomeLabel = findViewById(R.id.nav_home_label)
        navLogsLabel = findViewById(R.id.nav_logs_label)
        navAboutLabel = findViewById(R.id.nav_about_label)
        statusDot = findViewById(R.id.statusDot)
        layoutSuccess = findViewById(R.id.layoutSuccess)
        tvSonnerMsg = findViewById(R.id.tvSonnerMsg)
        tvSonnerLabel = findViewById(R.id.tvSonnerLabel)
        layoutPackageLoading = findViewById(R.id.layoutPackageLoading)
        tvPackageLoading = findViewById(R.id.tvPackageLoading)
        mainConfigScroll = findViewById(R.id.main_scroll_config)
        layoutLogsPage = findViewById(R.id.layout_logs_page)
        layoutAboutPage = findViewById(R.id.layout_about_page)
        containerLogList = findViewById(R.id.container_log_list)
        btnTrash = findViewById(R.id.btn_trash_reset)
        tvAppInfoDetail = findViewById(R.id.tv_app_info_detail)
        tvDevDetail = findViewById(R.id.tv_dev_detail)
        tvLinkWebsite = findViewById(R.id.tv_link_website)
        tvLinkGithub = findViewById(R.id.tv_link_github)
        tvLinkTiktok = findViewById(R.id.tv_link_tiktok)
        tvLinkInstagram = findViewById(R.id.tv_link_instagram)
        tvLinkWhatsapp = findViewById(R.id.tv_link_whatsapp)

        val sharedPref = getSharedPreferences("NotifyLinkPref", Context.MODE_PRIVATE)
        loadSettings(sharedPref)
        updateStatusDot()

        btnSave.setOnClickListener { saveSettings(sharedPref) }
        btnSelectPackages.setOnClickListener { openPackagePickerDialog() }
        btnAddFilter.setOnClickListener { addFilterTagFromInput() }
        etFilterInput.setOnEditorActionListener { _, _, _ ->
            addFilterTagFromInput()
            true
        }
        navHomeTab.setOnClickListener { showConfigPage() }
        navLogsTab.setOnClickListener { showLogsPage() }
        navAboutTab.setOnClickListener { showAboutPage() }
        showConfigPage()
        btnTrash.setOnClickListener { showClearLogsConfirmation(sharedPref) }
        bindAboutDetails()
    }

    private fun bindAboutDetails() {
        val packageInfo = kotlin.runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val versionName = packageInfo?.versionName ?: "1.0"
        val versionCode = packageInfo?.versionCode ?: 1
        val appInfo = "Package: $packageName\nVersion Name: $versionName\nVersion Code: $versionCode\nBuild Flavor: Production\nPlatform: Android\nTheme: Dark #191919\nCapabilities: Notification Listener, Telegram Forwarder, Webhook Gateway, Logger, Detail Inspector"
        tvAppInfoDetail.text = appInfo

        tvDevDetail.text = "Jaky adalah pengembang utama NotifyLink. Fokus pengembangan pada performa, keamanan payload, desain minimal modern, dan kemudahan integrasi otomatisasi notifikasi skala personal maupun bisnis."

        setLink(tvLinkWebsite, "https://www.jaky.dev")
        setLink(tvLinkGithub, "https://github.com/jakisoft")
        setLink(tvLinkTiktok, "https://www.tiktok.com/@jakysoft")
        setLink(tvLinkInstagram, "https://www.instagram.com/jakisoft")
        setLink(tvLinkWhatsapp, "https://chat.whatsapp.com/KTjrPdbxbvj93IvTvaMcWh?mode=hqctcla")
    }

    private fun setLink(textView: TextView, url: String) {
        textView.paintFlags = textView.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        textView.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }
    }

    private fun loadSettings(pref: android.content.SharedPreferences) {
        etBotToken.setText(pref.getString("bot_token", ""))
        etChatId.setText(pref.getString("chat_id", ""))
        etWebhookUrl.setText(pref.getString("webhook_url", ""))
        etStatusApiUrl.setText(pref.getString("status_api_url", ""))

        val packageValue = pref.getString("package_names", pref.getString("package_name", "") ?: "") ?: ""
        val filterValue = pref.getString("filter_keywords", pref.getString("filter_keyword", "") ?: "") ?: ""

        selectedPackages.clear()
        selectedPackages.addAll(parseTagValue(packageValue))
        selectedFilters.clear()
        selectedFilters.addAll(parseTagValue(filterValue))

        switchMaster.isChecked = pref.getBoolean("master_on", false)
        switchTelegram.isChecked = pref.getBoolean("telegram_on", false)
        switchWebhook.isChecked = pref.getBoolean("webhook_on", false)

        renderPackageChips()
        renderFilterChips()
    }

    private fun parseTagValue(value: String): List<String> {
        return value.split("||", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun saveSettings(pref: android.content.SharedPreferences) {
        val editor = pref.edit()
        editor.putString("bot_token", etBotToken.text.toString())
        editor.putString("chat_id", etChatId.text.toString())
        editor.putString("package_names", selectedPackages.joinToString("||"))
        editor.putString("filter_keywords", selectedFilters.joinToString("||"))
        editor.putString("package_name", selectedPackages.joinToString(","))
        editor.putString("filter_keyword", selectedFilters.joinToString(","))
        editor.putString("webhook_url", etWebhookUrl.text.toString().trim())
        editor.putString("status_api_url", etStatusApiUrl.text.toString().trim())
        editor.putBoolean("master_on", switchMaster.isChecked)
        editor.putBoolean("telegram_on", switchTelegram.isChecked)
        editor.putBoolean("webhook_on", switchWebhook.isChecked)
        editor.apply()

        updateStatusDot()
        showSonnerAlert("Pengaturan berhasil disimpan")

        if (!isNotificationServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        requestDisableBatteryOptimizationIfNeeded()

        val syncIntent = Intent(this, NotificationService::class.java).apply {
            action = NotificationService.ACTION_SYNC_DEVICE_STATUS
        }
        startService(syncIntent)
    }

    private fun requestDisableBatteryOptimizationIfNeeded() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            return
        }
        kotlin.runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun addFilterTagFromInput() {
        val value = etFilterInput.text.toString().trim()
        if (value.isEmpty()) {
            return
        }
        if (!selectedFilters.contains(value)) {
            selectedFilters.add(value)
        }
        etFilterInput.setText("")
        renderFilterChips()
    }

    private fun renderPackageChips() {
        packageChipContainer.removeAllViews()
        if (selectedPackages.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Belum ada package dipilih"
            empty.setTextColor(0xFF7D7D7D.toInt())
            empty.textSize = 12f
            packageChipContainer.addView(empty)
            return
        }
        selectedPackages.forEach { packageName ->
            val label = appLabelByPackage[packageName] ?: packageName
            packageChipContainer.addView(createChipView("$label\n$packageName") {
                selectedPackages.remove(packageName)
                renderPackageChips()
            })
        }
    }

    private fun renderFilterChips() {
        filterChipContainer.removeAllViews()
        if (selectedFilters.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Belum ada keyword filter"
            empty.setTextColor(0xFF7D7D7D.toInt())
            empty.textSize = 12f
            filterChipContainer.addView(empty)
            return
        }
        selectedFilters.forEach { keyword ->
            filterChipContainer.addView(createChipView(keyword) {
                selectedFilters.remove(keyword)
                renderFilterChips()
            })
        }
    }

    private fun createChipView(text: String, onRemove: () -> Unit): LinearLayout {
        val chip = LinearLayout(this)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.setBackgroundResource(R.drawable.bg_chip_tag)
        chip.setPadding(20, 12, 10, 12)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = 10
        chip.layoutParams = params

        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(0xFFF3F4F6.toInt())
        tv.textSize = 11f
        tv.setPadding(0, 0, 12, 0)

        val remove = TextView(this)
        remove.text = "✕"
        remove.setTextColor(0xFF111111.toInt())
        remove.textSize = 11f
        remove.setTypeface(null, Typeface.BOLD)
        remove.gravity = Gravity.CENTER
        remove.setBackgroundResource(R.drawable.bg_chip_remove)
        remove.layoutParams = LinearLayout.LayoutParams(32, 32)
        remove.setOnClickListener { onRemove() }

        chip.addView(tv)
        chip.addView(remove)
        return chip
    }

    private fun openPackagePickerDialog() {
        showPackageLoading(true, "Menyiapkan daftar package...")
        Thread {
            val allApps = getInstalledNonSystemApps()
            runOnUiThread {
                showPackageLoading(false)
                if (allApps.isEmpty()) {
                    showSonnerAlert("Daftar package kosong / belum bisa dibaca")
                    return@runOnUiThread
                }

                allApps.forEach { appLabelByPackage[it.packageName] = it.label }
                val selected = selectedPackages.toMutableSet()
                val filtered = allApps.toMutableList()

                val root = LinearLayout(this)
                root.orientation = LinearLayout.VERTICAL
                root.setPadding(24, 20, 24, 10)

                val searchInput = EditText(this)
                searchInput.hint = "Cari nama aplikasi atau package"
                searchInput.setBackgroundResource(R.drawable.bg_input_modern)
                searchInput.setTextColor(0xFFFFFFFF.toInt())
                searchInput.setHintTextColor(0xFF7D7D7D.toInt())
                searchInput.setPadding(22, 16, 22, 16)
                root.addView(searchInput)

                val listView = ListView(this)
                val listParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    900
                )
                listParams.topMargin = 14
                listView.layoutParams = listParams
                listView.divider = null

                val adapter = PackageListAdapter(filtered, selected)
                listView.adapter = adapter
                listView.setOnItemClickListener { _, _, position, _ ->
                    val app = filtered[position]
                    if (selected.contains(app.packageName)) {
                        selected.remove(app.packageName)
                    } else {
                        selected.add(app.packageName)
                    }
                    adapter.notifyDataSetChanged()
                }

                root.addView(listView)

                searchInput.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val query = s.toString().trim().lowercase()
                        filtered.clear()
                        if (query.isEmpty()) {
                            filtered.addAll(allApps)
                        } else {
                            filtered.addAll(
                                allApps.filter {
                                    it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
                                }
                            )
                        }
                        adapter.notifyDataSetChanged()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })

                AlertDialog.Builder(this)
                    .setTitle("Pilih Target Package")
                    .setView(root)
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Simpan") { _, _ ->
                        selectedPackages.clear()
                        selectedPackages.addAll(selected.sorted())
                        renderPackageChips()
                    }
                    .show()
            }
        }.start()
    }

    private fun showPackageLoading(show: Boolean, message: String = "") {
        layoutPackageLoading.visibility = if (show) View.VISIBLE else View.GONE
        if (message.isNotEmpty()) {
            tvPackageLoading.text = message
        }
    }

    private fun getInstalledNonSystemApps(): List<DeviceApp> {
        val apps = packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { app ->
                val hasLauncher = packageManager.getLaunchIntentForPackage(app.packageName) != null
                val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                val isUpdatedSystem = app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                hasLauncher && (!isSystem || isUpdatedSystem)
            }
            .mapNotNull { app ->
                try {
                    DeviceApp(
                        label = packageManager.getApplicationLabel(app).toString(),
                        packageName = app.packageName,
                        icon = packageManager.getApplicationIcon(app.packageName)
                    )
                } catch (e: Exception) {
                    null
                }
            }
            .sortedBy { it.label.lowercase() }
            .toList()
        return apps
    }

    inner class PackageListAdapter(
        private val data: List<DeviceApp>,
        private val selected: Set<String>
    ) : BaseAdapter() {

        override fun getCount(): Int = data.size
        override fun getItem(position: Int): Any = data[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14, 14, 14, 14)
            }

            row.removeAllViews()

            val app = data[position]
            val icon = ImageView(this@MainActivity)
            icon.layoutParams = LinearLayout.LayoutParams(72, 72)
            icon.setImageDrawable(app.icon)

            val textWrap = LinearLayout(this@MainActivity)
            textWrap.orientation = LinearLayout.VERTICAL
            val textParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textParams.marginStart = 14
            textWrap.layoutParams = textParams

            val tvName = TextView(this@MainActivity)
            tvName.text = app.label
            tvName.setTextColor(0xFFFFFFFF.toInt())
            tvName.textSize = 14f
            tvName.setTypeface(null, Typeface.BOLD)

            val tvPackage = TextView(this@MainActivity)
            tvPackage.text = app.packageName
            tvPackage.setTextColor(0xFF9CA3AF.toInt())
            tvPackage.textSize = 12f

            textWrap.addView(tvName)
            textWrap.addView(tvPackage)

            val check = TextView(this@MainActivity)
            check.layoutParams = LinearLayout.LayoutParams(70, 70)
            check.gravity = Gravity.CENTER
            val isSelected = selected.contains(app.packageName)
            check.text = if (isSelected) "✓" else "+"
            check.textSize = 18f
            check.setTypeface(null, Typeface.BOLD)
            check.setTextColor(if (isSelected) 0xFF111111.toInt() else 0xFFFFFFFF.toInt())
            check.setBackgroundResource(if (isSelected) R.drawable.bg_chip_remove else R.drawable.bg_status_chip)

            row.addView(icon)
            row.addView(textWrap)
            row.addView(check)
            return row
        }
    }

    private fun setTabState(page: String) {
        navHome.setBackgroundResource(if (page == "home") R.drawable.bg_bottom_tab_active else R.drawable.bg_bottom_tab_idle)
        navLogs.setBackgroundResource(if (page == "logs") R.drawable.bg_bottom_tab_active else R.drawable.bg_bottom_tab_idle)
        navAbout.setBackgroundResource(if (page == "about") R.drawable.bg_bottom_tab_active else R.drawable.bg_bottom_tab_idle)

        navHome.setColorFilter(if (page == "home") 0xFF191919.toInt() else 0xFFFFFFFF.toInt())
        navLogs.setColorFilter(if (page == "logs") 0xFF191919.toInt() else 0xFFFFFFFF.toInt())
        navAbout.setColorFilter(if (page == "about") 0xFF191919.toInt() else 0xFFFFFFFF.toInt())

        navHomeLabel.setTextColor(if (page == "home") 0xFFFFFFFF.toInt() else 0xFFD1D5DB.toInt())
        navLogsLabel.setTextColor(if (page == "logs") 0xFFFFFFFF.toInt() else 0xFFD1D5DB.toInt())
        navAboutLabel.setTextColor(if (page == "about") 0xFFFFFFFF.toInt() else 0xFFD1D5DB.toInt())
    }

    private fun showConfigPage() {
        layoutLogsPage.visibility = View.GONE
        layoutAboutPage.visibility = View.GONE
        mainConfigScroll.visibility = View.VISIBLE
        setTabState("home")
    }

    private fun showLogsPage() {
        mainConfigScroll.visibility = View.GONE
        layoutAboutPage.visibility = View.GONE
        layoutLogsPage.visibility = View.VISIBLE
        setTabState("logs")
        renderLogs()
    }

    private fun showAboutPage() {
        mainConfigScroll.visibility = View.GONE
        layoutLogsPage.visibility = View.GONE
        layoutAboutPage.visibility = View.VISIBLE
        setTabState("about")
    }

    private fun showClearLogsConfirmation(pref: android.content.SharedPreferences) {
        AlertDialog.Builder(this)
            .setTitle("Hapus logger?")
            .setMessage("Semua riwayat logger akan dihapus permanen.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                pref.edit().remove("last_logs").apply()
                showSonnerAlert("Logger berhasil dihapus")
                renderLogs()
            }
            .show()
    }

    private fun parseLog(entry: String): ParsedLog {
        val lines = entry.split("\n")
        val header = lines.firstOrNull()?.trim().orEmpty()
        val time = if (header.startsWith("[") && header.contains("]")) {
            header.substringAfter("[").substringBefore("]")
        } else {
            "--:--"
        }

        var packageName = "Unknown package"
        if (header.contains("]")) {
            val after = header.substringAfter("]").trim()
            if (after.isNotEmpty()) packageName = after
        }
        lines.firstOrNull { it.startsWith("Pkg:") }?.let {
            packageName = it.removePrefix("Pkg:").trim()
        }

        var title = "Notification"
        var message = lines.drop(1).joinToString(" ").trim()

        val titleLine = lines.firstOrNull { it.startsWith("Title:") }
        val messageLine = lines.firstOrNull { it.startsWith("Message:") }

        if (titleLine != null || messageLine != null) {
            title = titleLine?.removePrefix("Title:")?.trim().orEmpty().ifEmpty { "Notification" }
            message = messageLine?.removePrefix("Message:")?.trim().orEmpty()
        } else if (lines.size > 1 && lines[1].contains(":")) {
            title = lines[1].substringBefore(":").trim().ifEmpty { "Notification" }
            message = lines[1].substringAfter(":").trim()
        }

        if (lines.any { it.contains("WEBHOOK SUCCESS") }) {
            title = "Webhook Success"
            message = lines.find { it.startsWith("Result:") }?.removePrefix("Result:")?.trim().orEmpty()
        }

        if (lines.any { it.contains("WEBHOOK FAILED") }) {
            title = "Webhook Failed"
            message = lines.find { it.startsWith("Error:") }?.removePrefix("Error:")?.trim().orEmpty()
        }

        return ParsedLog(time, packageName, title, message.ifEmpty { "No message content" }, entry)
    }

    private fun getAppIcon(packageName: String): Drawable {
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            getDrawable(R.drawable.icon)!!
        }
    }

    private fun renderLogs() {
        containerLogList.removeAllViews()
        val pref = getSharedPreferences("NotifyLinkPref", Context.MODE_PRIVATE)
        val logs = pref.getString("last_logs", "") ?: ""

        if (logs.isEmpty()) {
            val emptyView = TextView(this)
            emptyView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            emptyView.setBackgroundResource(R.drawable.bg_log_item)
            emptyView.text = "Belum ada logger notifikasi."
            emptyView.setTextColor(0xFF9CA3AF.toInt())
            emptyView.textSize = 14f
            emptyView.gravity = Gravity.CENTER
            emptyView.setPadding(24, 80, 24, 80)
            containerLogList.addView(emptyView)
            return
        }

        logs.split("|||").forEach { entry ->
            if (entry.trim().isNotEmpty()) {
                val parsed = parseLog(entry)

                val card = LinearLayout(this)
                card.orientation = LinearLayout.HORIZONTAL
                card.gravity = Gravity.CENTER_VERTICAL
                card.setBackgroundResource(R.drawable.bg_log_item)
                val cardParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                cardParams.bottomMargin = 12
                card.layoutParams = cardParams
                card.setPadding(16, 16, 16, 16)

                val icon = ImageView(this)
                icon.layoutParams = LinearLayout.LayoutParams(60, 60)
                icon.setImageDrawable(getAppIcon(parsed.packageName))
                card.addView(icon)

                val body = LinearLayout(this)
                body.orientation = LinearLayout.VERTICAL
                val bodyParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                bodyParams.marginStart = 16
                body.layoutParams = bodyParams

                val tvPackage = TextView(this)
                tvPackage.text = parsed.packageName
                tvPackage.setTextColor(0xFFFFFFFF.toInt())
                tvPackage.textSize = 14f
                tvPackage.setTypeface(null, Typeface.BOLD)

                val tvTitle = TextView(this)
                tvTitle.text = parsed.title
                tvTitle.setTextColor(0xFFE5E7EB.toInt())
                tvTitle.textSize = 13f
                tvTitle.setTypeface(null, Typeface.BOLD)
                tvTitle.setPadding(0, 2, 0, 0)

                val rowBottom = LinearLayout(this)
                rowBottom.orientation = LinearLayout.HORIZONTAL
                rowBottom.gravity = Gravity.BOTTOM
                rowBottom.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                rowBottom.setPadding(0, 3, 0, 0)

                val tvMessage = TextView(this)
                tvMessage.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                tvMessage.text = parsed.message
                tvMessage.setTextColor(0xFF9CA3AF.toInt())
                tvMessage.textSize = 12f
                tvMessage.maxLines = 2

                val tvTime = TextView(this)
                tvTime.text = parsed.time
                tvTime.setTextColor(0xFF7E7E7E.toInt())
                tvTime.textSize = 11f
                tvTime.setPadding(10, 0, 0, 0)

                rowBottom.addView(tvMessage)
                rowBottom.addView(tvTime)

                body.addView(tvPackage)
                body.addView(tvTitle)
                body.addView(rowBottom)

                card.addView(body)
                card.setOnClickListener {
                    val intent = Intent(this, DetailActivity::class.java)
                    intent.putExtra("content", parsed.fullContent)
                    startActivity(intent)
                }

                containerLogList.addView(card)
            }
        }
    }

    private fun showSonnerAlert(msg: String) {
        tvSonnerLabel.text = "NotifyLink"
        tvSonnerMsg.text = msg
        layoutSuccess.visibility = View.VISIBLE
        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 220 }
        layoutSuccess.startAnimation(fadeIn)
        Handler(Looper.getMainLooper()).postDelayed({
            val fadeOut = AlphaAnimation(1f, 0f).apply { duration = 220 }
            layoutSuccess.startAnimation(fadeOut)
            layoutSuccess.visibility = View.GONE
        }, 1800)
    }

    private fun updateStatusDot() {
        val master = switchMaster.isChecked
        val active = switchTelegram.isChecked || switchWebhook.isChecked
        statusDot.setBackgroundResource(if (master && active) R.drawable.bg_status_active else R.drawable.bg_status_inactive)
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled != null && enabled.contains(packageName)
    }
}
