/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.smartmedicinebox.data.repository.MedicineRepository
import com.smartmedicinebox.data.repository.SessionState
import com.smartmedicinebox.service.AlarmService
import com.smartmedicinebox.sync.SyncScheduler
import com.smartmedicinebox.util.DeviceCompat
import com.smartmedicinebox.util.Prefs
import com.smartmedicinebox.util.SingleFlight
import kotlinx.coroutines.launch

/**
 * 主界面：三栏底部导航容器（药品 / 记录 / 我的）
 */
class MainActivity : BaseActivity() {

    private lateinit var medicineFragment: MedicineFragment
    private lateinit var recordFragment: RecordFragment
    private lateinit var meFragment: MeFragment
    private lateinit var permissionBanner: View
    private lateinit var permissionBannerText: TextView

    /** 断网期间有本地改动时，恢复联网立即以本地为最新数据源覆盖推送服务器（无需手动同步） */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!Prefs.wasOffline) return
            lifecycleScope.launch {
                try { MedicineRepository.syncAll() } catch (_: Exception) {}
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionBanner = findViewById(R.id.permissionBanner)
        permissionBannerText = findViewById(R.id.tvPermissionBanner)
        permissionBanner.setOnClickListener { requestAlarmPermissions() }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        if (savedInstanceState == null) {
            medicineFragment = MedicineFragment()
            recordFragment = RecordFragment()
            meFragment = MeFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, medicineFragment, TAG_MEDICINES)
                .add(R.id.fragmentContainer, recordFragment, TAG_RECORDS).hide(recordFragment)
                .add(R.id.fragmentContainer, meFragment, TAG_ME).hide(meFragment)
                .commit()
        } else {
            medicineFragment = supportFragmentManager.findFragmentByTag(TAG_MEDICINES) as MedicineFragment
            recordFragment = supportFragmentManager.findFragmentByTag(TAG_RECORDS) as RecordFragment
            meFragment = supportFragmentManager.findFragmentByTag(TAG_ME) as MeFragment
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_medicines -> showFragment(medicineFragment)
                R.id.nav_records -> showFragment(recordFragment)
                R.id.nav_me -> showFragment(meFragment)
            }
            true
        }

        // 首次安装：申请通知权限；随后刷新权限警告条
        requestNotificationPermission()
        refreshPermissionBanner()

        // 确保前台服务运行（离线也能提醒）；登录后自动同步
        AlarmService.start(this)
        SyncScheduler.enqueuePeriodicSync()
        registerReconnectSync()
        lifecycleScope.launch {
            try { MedicineRepository.flushMissedDoses() } catch (_: Exception) {}
            try { MedicineRepository.purgeExpiredTrash() } catch (_: Exception) {}
            try { MedicineRepository.purgeExpiredRecordTrash() } catch (_: Exception) {}
            if (MedicineRepository.vaultLocked) {
                ensureVaultUnlocked()
            } else {
                try { MedicineRepository.syncAll() } catch (_: Exception) {}
            }
        }
    }

    /** 数据已加密但本进程未解锁时：检测是否需一次性迁移，再提示输入登录密码（或迁移双密码）解锁 */
    private fun ensureVaultUnlocked() {
        lifecycleScope.launch {
            val needsMigration = try {
                MedicineRepository.needsFamilyMigration()
            } catch (_: Exception) {
                false
            }
            if (needsMigration) promptFamilyMigration() else promptLoginUnlock()
        }
    }

    /** 提示输入登录密码解锁（数据恒用登录密码加密） */
    private fun promptLoginUnlock() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.label_enter_login_password)
            .setMessage(R.string.password_no_recovery)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                val pw = input.text.toString()
                lifecycleScope.launch {
                    try {
                        SingleFlight.run("unlockVault") {
                            MedicineRepository.unlockVault(pw)
                            MedicineRepository.syncAll()
                            SyncScheduler.enqueuePeriodicSync()
                        } ?: return@launch
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_SHORT).show()
                        ensureVaultUnlocked()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel) { _, _ -> finish() }
            .show()
    }

    /** 旧账号一次性迁移：旧数据按家人密码加密，需同时输入家人控制密码与登录密码完成重加密 */
    private fun promptFamilyMigration() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        val familyInput = EditText(this).apply {
            hint = getString(R.string.hint_family_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val loginInput = EditText(this).apply {
            hint = getString(R.string.hint_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        container.addView(familyInput)
        container.addView(loginInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.label_family_migration)
            .setMessage(R.string.family_migration_message)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                val familyPw = familyInput.text.toString()
                val loginPw = loginInput.text.toString()
                lifecycleScope.launch {
                    try {
                        SingleFlight.run("migrateFamily") {
                            MedicineRepository.migrateFamilyToLogin(familyPw, loginPw)
                            MedicineRepository.syncAll()
                            SyncScheduler.enqueuePeriodicSync()
                        } ?: return@launch
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_SHORT).show()
                        ensureVaultUnlocked()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel) { _, _ -> finish() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBanner()
        // 界面可见后再弹后台运行保护引导，避免 onCreate 弹窗黑屏
        maybeRequestBatteryExemption()
        // 回到前台时拉取最新 vault，反映家属端/其他设备的改动（仅本人模式；家属远程会话结束的短暂窗口内跳过，避免用错密钥）
        lifecycleScope.launch {
            if (SessionState.mode == SessionState.Mode.OWN && !MedicineRepository.vaultLocked) {
                try { MedicineRepository.syncAll() } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    /** 监听网络恢复：断网期间有本地改动（wasOffline）时，联网瞬间立即触发覆盖推送同步 */
    private fun registerReconnectSync() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissionBanner()
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .hide(medicineFragment)
            .hide(recordFragment)
            .hide(meFragment)
            .show(fragment)
            .commit()
    }

    /** 首次启动：弹窗引导用户开启后台运行保护（电池优化 + 自启动/受保护应用） */
    private fun maybeRequestBatteryExemption() {
        if (Prefs.batteryGuideShown) return
        // 引导弹窗已在前台时不重复弹出（onResume 会反复触发）
        if (batteryGuideDialog?.isShowing == true) return
        showNextBatteryGuideStep()
    }

    /** 逐项引导项：needed() 按当前设备与实际授权状态判断是否需要引导；action 为跳转动作（null 表示仅说明） */
    private class BatteryGuideItem(
        val title: String, val message: String, val needed: () -> Boolean, val action: (() -> Unit)?
    )

    private var batteryGuideDialog: androidx.appcompat.app.AlertDialog? = null

    /** 待引导项清单（固定顺序；每项展示前按实际状态判断，已授权/不适用的自动跳过） */
    private fun batteryGuideItems(): List<BatteryGuideItem> {
        val items = ArrayList<BatteryGuideItem>()
        if (DeviceCompat.isHuaweiDevice()) {
            items.add(BatteryGuideItem(
                getString(R.string.popup_item_title), getString(R.string.popup_item_message),
                { true },
                {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    } catch (_: Exception) {
                    }
                }
            ))
        }
        items.add(BatteryGuideItem(
            getString(R.string.overlay_item_title), getString(R.string.overlay_item_message),
            { !Settings.canDrawOverlays(this@MainActivity) },
            {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null)))
                } catch (_: Exception) {
                }
            }
        ))
        items.add(BatteryGuideItem(
            getString(R.string.battery_item_title), getString(R.string.battery_item_message),
            { !(getSystemService(PowerManager::class.java)).isIgnoringBatteryOptimizations(packageName) },
            {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null)))
                } catch (_: Exception) {
                }
            }
        ))
        if (DeviceCompat.isHuaweiDevice()) {
            items.add(BatteryGuideItem(
                getString(R.string.startup_item_title), getString(R.string.startup_item_message),
                { true },
                { openHuaweiStartupManager() }
            ))
        }
        return items
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
            Toast.makeText(this, getString(R.string.startup_item_message), Toast.LENGTH_LONG).show()
        }
    }

    /** 展示下一项待引导的权限（每次一项；Authorize 跳转设置，Cancel 跳过当前项） */
    private fun showNextBatteryGuideStep() {
        val items = batteryGuideItems()
        var step = Prefs.batteryGuideStep
        // 跳过已授权/不适用的项（按系统实际状态判断，不虚构授权状态）
        while (step < items.size && !items[step].needed()) step++
        if (step >= items.size) {
            Prefs.batteryGuideShown = true
            return
        }
        Prefs.batteryGuideStep = step
        val item = items[step]
        try {
            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(item.title)
                .setMessage(item.message)
                .setNegativeButton(R.string.common_cancel) { _, _ ->
                    Prefs.batteryGuideStep = step + 1
                    showNextBatteryGuideStep()
                }
            if (item.action != null) {
                builder.setPositiveButton(R.string.battery_allow) { _, _ ->
                    Prefs.batteryGuideStep = step + 1
                    item.action?.invoke()
                }
            } else {
                builder.setPositiveButton(R.string.battery_got_it) { _, _ ->
                    Prefs.batteryGuideStep = step + 1
                    showNextBatteryGuideStep()
                }
            }
            batteryGuideDialog = builder.create()
            batteryGuideDialog?.show()
        } catch (_: Exception) {
            // 弹窗失败不影响主流程
        }
    }

    /** 申请通知权限（未登录也需要，否则闹钟无法弹通知）；仅首次启动自动申请一次，
     *  用户拒绝后不再每次启动重复弹窗，改由顶部红条/权限管理入口手动重试 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (Prefs.notificationPermissionRequested) return
            Prefs.notificationPermissionRequested = true
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
    }

    /** 刷新顶部红条：优先提示缺失的全屏通知权限，其次华为/鸿蒙悬浮窗权限，再其次通知权限 */
    private fun refreshPermissionBanner() {
        val fullScreenMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !(getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false)
        val overlayMissing = DeviceCompat.isHuaweiDevice() && !Settings.canDrawOverlays(this)
        val notificationMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

        when {
            fullScreenMissing -> {
                permissionBannerText.text = getString(R.string.permission_fullscreen_warning)
                permissionBanner.visibility = View.VISIBLE
            }
            overlayMissing -> {
                permissionBannerText.text = getString(R.string.permission_overlay_warning)
                permissionBanner.visibility = View.VISIBLE
            }
            notificationMissing -> {
                permissionBannerText.text = getString(R.string.permission_warning)
                permissionBanner.visibility = View.VISIBLE
            }
            else -> permissionBanner.visibility = View.GONE
        }
    }

    /** 点击红条：按优先级申请缺失的权限 */
    private fun requestAlarmPermissions() {
        // 1) 通知权限（Android 13+，运行时请求）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
            return
        }
        // 2) 全屏通知（Android 14+，跳系统设置开启）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && !nm.canUseFullScreenIntent()) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                } catch (_: Exception) {
                }
                return
            }
        }
        // 3) 华为/鸿蒙悬浮窗权限（锁屏全屏弹窗依赖；跳系统设置开启）
        if (DeviceCompat.isHuaweiDevice() && !Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG_MEDICINES = "medicines"
        private const val TAG_RECORDS = "records"
        private const val TAG_ME = "me"
    }
}
