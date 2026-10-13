/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.smartmedicinebox.alarm.AlarmReceiver
import com.smartmedicinebox.alarm.AlarmScheduler
import com.smartmedicinebox.data.api.ApiClient
import com.smartmedicinebox.data.api.PairingClient
import com.smartmedicinebox.data.repository.FamilyRemoteRepository
import com.smartmedicinebox.data.repository.MedicineRepository
import com.smartmedicinebox.sync.SyncScheduler
import com.smartmedicinebox.util.DeviceCompat
import com.smartmedicinebox.util.Prefs
import com.smartmedicinebox.util.ServerTrust
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val REPO_URL = "https://github.com/Toryyyyyyyyyyyyyy/Smart-Medicine-Box"

/**
 * 我的 tab：账号、家属控制、回收站、服务器、语言、测试闹钟、版权信息
 */
class MeFragment : Fragment() {

    private lateinit var tvAccountValue: TextView
    private lateinit var tvServerValue: TextView
    private lateinit var tvLanguageValue: TextView
    private lateinit var tvFontSizeValue: TextView
    private lateinit var tvThresholdValue: TextView
    private lateinit var rowLogout: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_me, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvAccountValue = view.findViewById(R.id.tvAccountValue)
        tvServerValue = view.findViewById(R.id.tvServerValue)
        tvLanguageValue = view.findViewById(R.id.tvLanguageValue)
        tvFontSizeValue = view.findViewById(R.id.tvFontSizeValue)
        tvThresholdValue = view.findViewById(R.id.tvThresholdValue)

        view.findViewById<View>(R.id.rowAccount).setOnClickListener { onAccountClick() }
        rowLogout = view.findViewById(R.id.rowLogout)
        rowLogout.setOnClickListener { onLogoutClick() }
        view.findViewById<View>(R.id.rowFamilyControl).setOnClickListener {
            if (hasServer()) startActivity(Intent(requireContext(), FamilyAccessActivity::class.java))
        }
        view.findViewById<View>(R.id.rowTrash).setOnClickListener {
            startActivity(Intent(requireContext(), TrashActivity::class.java))
        }
        view.findViewById<View>(R.id.rowServer).setOnClickListener { showServerDialog() }
        view.findViewById<View>(R.id.rowLanguage).setOnClickListener { chooseLanguage() }
        view.findViewById<View>(R.id.rowFontSize).setOnClickListener { chooseFontSize() }
        view.findViewById<View>(R.id.rowThreshold).setOnClickListener { chooseThreshold() }
        view.findViewById<View>(R.id.rowPermissions).setOnClickListener { showPermissionsDialog() }
        view.findViewById<View>(R.id.rowTestAlarm).setOnClickListener { testAlarm() }
        view.findViewById<View>(R.id.rowCopyright).setOnClickListener { showCopyrightDialog() }

        refreshServerValue()
        refreshLanguageValue()
        refreshFontSizeValue()
        refreshThresholdValue()
        refreshAccountValue()
        refreshLogoutRow()
    }

    override fun onResume() {
        super.onResume()
        if (::tvAccountValue.isInitialized) {
            refreshAccountValue()
            refreshLogoutRow()
        }
        // 从系统设置返回后：权限管理对话框中的状态按实际授权情况立即刷新
        refreshPermissionStatuses()
    }

    private fun onAccountClick() {
        if (!hasServer()) return
        if (Prefs.isLoggedIn) {
            // 已登录：进入账号页（自定义用户名 / 允许家人控制 / 注销账号）
            startActivity(Intent(requireContext(), AccountActivity::class.java))
        } else {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
        }
    }

    private fun onLogoutClick() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.common_confirm)
            .setMessage(R.string.confirm_logout)
            .setPositiveButton(R.string.btn_logout) { _, _ ->
                Prefs.logout()
                SyncScheduler.cancelPeriodicSync()
                refreshAccountValue()
                refreshLogoutRow()
                Toast.makeText(requireContext(), getString(R.string.toast_logged_out), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun refreshAccountValue() {
        val identity = Prefs.username
        tvAccountValue.text = if (Prefs.isLoggedIn && !identity.isNullOrBlank()) identity else getString(R.string.not_logged_in)
    }

    private fun refreshLogoutRow() {
        rowLogout.visibility = if (Prefs.isLoggedIn) View.VISIBLE else View.GONE
    }

    private fun refreshServerValue() {
        tvServerValue.text = Prefs.serverBaseUrl
    }

    private fun refreshLanguageValue() {
        tvLanguageValue.text = when (Prefs.language) {
            Prefs.LANG_ZH -> getString(R.string.language_chinese)
            Prefs.LANG_EN -> getString(R.string.language_english)
            else -> getString(R.string.language_system)
        }
    }

    private fun refreshFontSizeValue() {
        tvFontSizeValue.text = when (Prefs.fontSize) {
            Prefs.FONT_LARGE -> getString(R.string.font_size_large)
            Prefs.FONT_XLARGE -> getString(R.string.font_size_xlarge)
            else -> getString(R.string.font_size_normal)
        }
    }

    private fun refreshThresholdValue() {
        tvThresholdValue.text = when (Prefs.thresholdMinutes) {
            30 -> getString(R.string.threshold_30)
            120 -> getString(R.string.threshold_120)
            180 -> getString(R.string.threshold_180)
            else -> getString(R.string.threshold_60)
        }
    }

    /** 选择字体大小：默认 / 大 / 特大，切换后重启生效 */
    private fun chooseFontSize() {
        val options = arrayOf(
            getString(R.string.font_size_normal),
            getString(R.string.font_size_large),
            getString(R.string.font_size_xlarge)
        )
        val checked = when (Prefs.fontSize) {
            Prefs.FONT_LARGE -> 1
            Prefs.FONT_XLARGE -> 2
            else -> 0
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.label_font_size)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val size = when (which) {
                    1 -> Prefs.FONT_LARGE
                    2 -> Prefs.FONT_XLARGE
                    else -> Prefs.FONT_NORMAL
                }
                if (size != Prefs.fontSize) {
                    Prefs.fontSize = size
                    restartApp()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 选择按时服药阈值：30分钟 / 1小时 / 2小时 / 3小时 */
    private fun chooseThreshold() {
        val options = arrayOf(
            getString(R.string.threshold_30),
            getString(R.string.threshold_60),
            getString(R.string.threshold_120),
            getString(R.string.threshold_180)
        )
        val checked = when (Prefs.thresholdMinutes) {
            30 -> 0
            120 -> 2
            180 -> 3
            else -> 1
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.label_threshold)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val minutes = when (which) {
                    0 -> 30
                    2 -> 120
                    3 -> 180
                    else -> 60
                }
                if (minutes != Prefs.thresholdMinutes) {
                    Prefs.thresholdMinutes = minutes
                    refreshThresholdValue()
                    saveThreshold(minutes)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 立即把新阈值同步到服务器（家属端远程服药按同一阈值判定）；失败提示，之后每次 syncAll 会自动重试当前值 */
    private fun saveThreshold(minutes: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                MedicineRepository.updateThreshold(minutes)
                Toast.makeText(requireContext(), getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 权限管理 ====================

    /** 权限状态行的「重新检查」绑定：从系统设置返回时（onResume）按实际状态刷新显示 */
    private val permissionStatusBindings = mutableListOf<Pair<TextView, () -> Boolean?>>()

    /** 权限管理列表：逐项显示可可靠判断的授权状态；无法检测的权限不显示状态、点击给说明再跳转 */
    private fun showPermissionsDialog() {
        permissionStatusBindings.clear()
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 通知（所有版本都可能被用户关闭：以系统实际开关为准）
        addPermissionRow(container, getString(R.string.permission_notifications), {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        }) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            } else {
                openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            }
        }

        // 全屏通知（Android 14+ 特殊权限，可检测）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            addPermissionRow(container, getString(R.string.permission_fullscreen), {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
            }) {
                openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            }
        }

        // 悬浮窗（可检测，所有设备显示：部分设备锁屏全屏闹钟需要）
        addPermissionRow(container, getString(R.string.permission_overlay), {
            Settings.canDrawOverlays(ctx)
        }) {
            openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        }

        // 华为/鸿蒙：后台弹窗（不可检测，仅引导）
        if (DeviceCompat.isHuaweiDevice()) {
            addPermissionRow(container, getString(R.string.permission_background_popup), null) {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.permission_background_popup)
                    .setMessage(R.string.permission_background_popup_desc)
                    .setPositiveButton(R.string.common_confirm) { _, _ ->
                        openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
        }

        // 华为/鸿蒙：自启动（不可检测，仅引导；尽力跳转手机管家启动管理页，失败回退提示手动路径）
        if (DeviceCompat.isHuaweiDevice()) {
            addPermissionRow(container, getString(R.string.startup_item_title), null) {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.startup_item_title)
                    .setMessage(R.string.startup_item_message)
                    .setPositiveButton(R.string.battery_allow) { _, _ -> openHuaweiStartupManager() }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
        }

        // 忽略电池优化（可检测）
        addPermissionRow(container, getString(R.string.permission_battery), {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
        }) {
            openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        }

        // 闹钟与提醒（Android 12+ 精确闹钟特殊权限，可检测）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addPermissionRow(container, getString(R.string.permission_exact_alarm), {
                (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
            }) {
                openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.label_permissions)
            .setView(container)
            .setPositiveButton(R.string.common_confirm, null)
            .setOnDismissListener { permissionStatusBindings.clear() }
            .show()
    }

    /** 从系统设置返回后：按系统实际授权状态刷新各权限行的显示 */
    private fun refreshPermissionStatuses() {
        for ((statusView, check) in permissionStatusBindings) {
            applyStatus(statusView, check())
        }
    }

    private fun applyStatus(statusView: TextView, ok: Boolean?) {
        if (ok == null) {
            statusView.text = getString(R.string.status_manual)
            statusView.setTextColor(Color.parseColor("#999999"))
        } else {
            statusView.text = statusText(ok)
            statusView.setTextColor(if (ok) Color.parseColor("#52C41A") else Color.parseColor("#FF4D4F"))
        }
    }

    private fun statusText(ok: Boolean): String =
        getString(if (ok) R.string.status_granted else R.string.status_not_granted)

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action, Uri.fromParts("package", requireContext().packageName, null)))
        } catch (_: Exception) {
        }
    }

    /** 尽力跳转华为「手机管家 → 应用启动管理」（厂商未公开接口，各版本路径可能不同）；跳转失败回退提示手动路径 */
    private fun openHuaweiStartupManager() {
        try {
            startActivity(
                Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                }
            )
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.startup_item_message), Toast.LENGTH_LONG).show()
        }
    }

    /** 权限行：名称 + 状态（statusCheck=null 时显示「需手动开启」，表示不可检测）；点击执行对应授权流程 */
    private fun addPermissionRow(
        container: LinearLayout, name: String, statusCheck: (() -> Boolean?)?, onClick: () -> Unit
    ) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
        }
        val nameView = TextView(requireContext()).apply {
            text = name
            setTextColor(Color.parseColor("#333333"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_size_18))
        }
        row.addView(nameView)
        val statusView = TextView(requireContext()).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_size_15))
        }
        applyStatus(statusView, statusCheck?.invoke())
        if (statusCheck != null) {
            permissionStatusBindings.add(statusView to statusCheck)
        }
        row.addView(statusView)
        row.setOnClickListener { onClick() }
        container.addView(row)
    }

    /** 服务器地址是否已设置；未设置时弹提示并返回 false */
    private fun hasServer(): Boolean {        if (Prefs.serverBaseUrl.isBlank()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.label_server_url)
                .setMessage(getString(R.string.server_not_set, REPO_URL))
                .setPositiveButton(R.string.common_confirm, null)
                .show()
            return false
        }
        return true
    }

    /** 显示版权信息对话框：应用版本 + 版权声明 */
    private fun showCopyrightDialog() {
        val message = getString(R.string.copyright_version) + "\n" + getString(R.string.copyright)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.label_copyright)
            .setMessage(message)
            .setPositiveButton(R.string.common_confirm, null)
            .show()
    }

    private fun showServerDialog() {
        val input = EditText(requireContext()).apply {
            setText(Prefs.serverBaseUrl)
            setSingleLine(true)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.label_server_url)
            .setView(input)
            .setPositiveButton(R.string.btn_save_server) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                // 明文 HTTP 一律拒绝：全部流量必须走 HTTPS（传输加密 + 服务器身份验证）
                if (url.toHttpUrlOrNull()?.scheme == "http") {
                    Toast.makeText(requireContext(), getString(R.string.toast_server_url_https_only), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 非法地址直接拒绝保存并提示，避免保存后刷新仍连旧服务器/假成功
                if (!isValidServerUrl(url)) {
                    Toast.makeText(requireContext(), getString(R.string.toast_server_url_invalid), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 服务器地址变化：旧凭据绝不发给未验证身份的新地址——清除登录态与家属内存令牌，
                // 本地药品/记录/回收站数据不动；pin 按 host:port 隔离，旧 pin 自然失效不用
                val oldUrl = Prefs.serverBaseUrl.trim().trimEnd('/')
                if (oldUrl.isNotEmpty() && oldUrl != url.trimEnd('/')) {
                    Prefs.logout()
                    FamilyRemoteRepository.invalidateToken()
                    SyncScheduler.cancelPeriodicSync()
                }
                Prefs.serverBaseUrl = url
                ApiClient.invalidate()
                refreshServerValue()
                Toast.makeText(requireContext(), getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                // 探测连接：证书不受信任 → 引导配对；身份已变化 → 要求重新配对
                probeServer()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 保存服务器地址后探测：区分「证书不受信任（首次/未配对）」与「身份已改变（pin 不匹配）」 */
    private fun probeServer() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = ApiClient.getService().health()
                if (resp.isSuccessful) return@launch
            } catch (e: Exception) {
                when (ServerTrust.classify(e)) {
                    ServerTrust.TrustFailure.PIN_MISMATCH -> showIdentityChangedDialog()
                    ServerTrust.TrustFailure.UNTRUSTED -> showPairingDialog()
                    else -> { /* 网络错误：由后续操作自行提示 */ }
                }
            }
        }
    }

    /** 局域网配对引导：输入服务器终端（python cli.py pair）显示的一次性配对码 */
    private fun showPairingDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.pair_code_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pair_dialog_title)
            .setMessage(R.string.pair_dialog_message)
            .setView(input)
            .setPositiveButton(R.string.btn_pair) { _, _ ->
                val code = input.text.toString().trim()
                if (code.isEmpty()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    when (val r = PairingClient.verify(Prefs.serverBaseUrl, code)) {
                        is PairingClient.Result.Success -> {
                            Toast.makeText(requireContext(), getString(R.string.pair_success), Toast.LENGTH_SHORT).show()
                        }
                        is PairingClient.Result.Failed ->
                            Toast.makeText(requireContext(), r.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 服务器身份已改变：绝不静默接受/覆盖，必须用新配对码重新配对 */
    private fun showIdentityChangedDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pair_dialog_title)
            .setMessage(R.string.err_server_identity_changed)
            .setPositiveButton(R.string.btn_repair) { _, _ -> showPairingDialog() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 服务器地址合法性校验：必须是 https 且含主机名（Retrofit 可解析） */
    private fun isValidServerUrl(url: String): Boolean {
        return try {
            val parsed = url.toHttpUrlOrNull() ?: return false
            parsed.scheme == "https" && parsed.host.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun chooseLanguage() {
        val options = arrayOf(
            getString(R.string.language_chinese),
            getString(R.string.language_english),
            getString(R.string.language_system)
        )
        val checked = when (Prefs.language) {
            Prefs.LANG_ZH -> 0
            Prefs.LANG_EN -> 1
            else -> 2
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.label_language)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val lang = when (which) {
                    0 -> Prefs.LANG_ZH
                    1 -> Prefs.LANG_EN
                    else -> Prefs.LANG_SYSTEM
                }
                if (lang != Prefs.language) {
                    Prefs.language = lang
                    restartApp()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 重启到主界面以应用新语言 */
    private fun restartApp() {
        val intent = Intent(requireContext(), MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        requireActivity().finish()
    }

    /** 设置 5 秒后的测试闹钟 */
    private fun testAlarm() {
        val alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(requireContext(), AlarmReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_MEDICINE_ID, -1L)
            putExtra(AlarmScheduler.EXTRA_MEDICINE_NAME, getString(R.string.test_medicine_name))
            putExtra(AlarmScheduler.EXTRA_DOSAGE, getString(R.string.test_dosage))
            putExtra(AlarmScheduler.EXTRA_TIME, getString(R.string.test_time_now))
            putExtra(AlarmScheduler.EXTRA_ALARM_TYPE, "sound")
            putExtra(AlarmScheduler.EXTRA_VIDEO_PATH, null as String?)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(requireContext(), 9999, intent, flags)

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 5000,
            pendingIntent
        )
        Toast.makeText(requireContext(), getString(R.string.toast_test_alarm), Toast.LENGTH_LONG).show()
    }
}
