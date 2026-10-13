/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.smartmedicinebox.App

/**
 * SharedPreferences 封装：登录态与全局设置
 */
object Prefs {

    const val PREFS_NAME = "medicine_box_prefs"
    const val KEY_LANGUAGE = "language"
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"
    const val LANG_SYSTEM = "system"
    const val KEY_FONT_SIZE = "font_size"
    const val FONT_NORMAL = "normal"
    const val FONT_LARGE = "large"
    const val FONT_XLARGE = "xlarge"
    const val KEY_THRESHOLD_MINUTES = "threshold_minutes"

    private val sp: SharedPreferences
        get() = App.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ============ 登录态 ============
    var token: String?
        get() = sp.getString("token", null)
        set(value) = sp.edit().putString("token", value).apply()

    var userId: Long
        get() = sp.getLong("user_id", 0L)
        set(value) = sp.edit().putLong("user_id", value).apply()

    // ============ 账号 / 家人控制 / 端到端加密 ============
    var userCode: String?
        get() = sp.getString("user_code", null)
        set(value) = sp.edit().putString("user_code", value).apply()

    var username: String?
        get() = sp.getString("username", null)
        set(value) = sp.edit().putString("username", value).apply()

    var familyControlEnabled: Boolean
        get() = sp.getBoolean("family_control_enabled", false)
        set(value) = sp.edit().putBoolean("family_control_enabled", value).apply()

    var familyControlViewOnly: Boolean
        get() = sp.getBoolean("family_control_view_only", false)
        set(value) = sp.edit().putBoolean("family_control_view_only", value).apply()

    var encEnabled: Boolean
        get() = sp.getBoolean("enc_enabled", false)
        set(value) = sp.edit().putBoolean("enc_enabled", value).apply()

    // ============ 登录派生密钥落盘：盐明文 + 密钥经 Android Keystore 包裹（更新 APK 不丢，卸载重装才失效） ============
    var encSalt: String?
        get() = sp.getString("enc_salt", null)
        set(value) = sp.edit().putString("enc_salt", value).apply()

    /** dataKey||mediaKey 经 Android Keystore 加密后的 Base64；卸载重装/换签名后失效，需重新输密码 */
    var encWrappedLoginKey: String?
        get() = sp.getString("enc_wrapped_login_key", null)
        set(value) = sp.edit().putString("enc_wrapped_login_key", value).apply()

    var isLoggedIn: Boolean
        get() = !token.isNullOrEmpty()
        set(value) {
            if (!value) {
                sp.edit().clear().apply()
            }
        }

    // ============ 服务器地址 ============
    var serverBaseUrl: String
        get() = sp.getString("server_base_url", "") ?: ""
        set(value) = sp.edit().putString("server_base_url", value).apply()

    /** 已配对服务器身份（局域网配对 pin）：host:port -> SPKI 指纹 hex 的 JSON map */
    var pinnedServersMap: Map<String, String>
        get() = try {
            val json = sp.getString("pinned_servers", null)
            if (json.isNullOrBlank()) emptyMap()
            else Gson().fromJson(json, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        set(value) = sp.edit().putString("pinned_servers", Gson().toJson(value)).apply()

    /** 界面语言：zh=中文，en=英文，system=跟随系统（默认） */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
        set(value) = sp.edit().putString(KEY_LANGUAGE, value).apply()

    /** 字体大小：normal=默认，large=大，xlarge=特大 */
    var fontSize: String
        get() = sp.getString(KEY_FONT_SIZE, FONT_NORMAL) ?: FONT_NORMAL
        set(value) = sp.edit().putString(KEY_FONT_SIZE, value).apply()

    /** 按时服药阈值（分钟）：偏离超过该值即视为提前/推迟，默认 60 */
    var thresholdMinutes: Int
        get() = sp.getInt(KEY_THRESHOLD_MINUTES, 60)
        set(value) = sp.edit().putInt(KEY_THRESHOLD_MINUTES, value).apply()

    /** 是否已展示过后台运行保护引导（仅首次启动提示一次） */
    var batteryGuideShown: Boolean
        get() = sp.getBoolean("battery_guide_shown", false)
        set(value) = sp.edit().putBoolean("battery_guide_shown", value).apply()

    /** 后台运行保护逐项引导的进度（已处理到第几项；逐项弹窗跨设置页往返时保存进度） */
    var batteryGuideStep: Int
        get() = sp.getInt("battery_guide_step", 0)
        set(value) = sp.edit().putInt("battery_guide_step", value).apply()

    /** 是否已自动发起过通知权限申请（仅首次启动自动申请一次；之后由红条/权限管理手动重试） */
    var notificationPermissionRequested: Boolean
        get() = sp.getBoolean("notification_permission_requested", false)
        set(value) = sp.edit().putBoolean("notification_permission_requested", value).apply()

    /** 上次记录墓碑 GC 的日期（epoch day），每天至多一次 */
    var lastRecordGcDay: Int
        get() = sp.getInt("last_record_gc_day", 0)
        set(value) = sp.edit().putInt("last_record_gc_day", value).apply()

    /** 上次药品回收站 GC 的日期（epoch day），每天至多一次 */
    var lastTrashGcDay: Int
        get() = sp.getInt("last_trash_gc_day", 0)
        set(value) = sp.edit().putInt("last_trash_gc_day", value).apply()

    /** 是否处于离线状态（有本地改动未能同步到服务器）；恢复联网后需以本地为最新数据源覆盖服务器 */
    var wasOffline: Boolean
        get() = sp.getBoolean("was_offline", false)
        set(value) = sp.edit().putBoolean("was_offline", value).apply()

    /** 药品同步基线（上次本地与服务器一致时的本地数据快照 JSON）：重连合并时据此区分
     *  家属端在心跳窗口内的改动（以家属为准）与本人离线改动（以本人为准） */
    var medicinesSyncBase: String?
        get() = sp.getString("sync_base_medicines", null)
        set(value) = if (value == null) sp.edit().remove("sync_base_medicines").apply()
        else sp.edit().putString("sync_base_medicines", value).apply()

    /** 记录同步基线（同上） */
    var recordsSyncBase: String?
        get() = sp.getString("sync_base_records", null)
        set(value) = if (value == null) sp.edit().remove("sync_base_records").apply()
        else sp.edit().putString("sync_base_records", value).apply()

    /** 退出登录：仅清除登录态，保留服务器地址等本地设置 */
    fun logout() {
        sp.edit()
            .remove("token")
            .remove("user_id")
            .remove("user_code")
            .remove("username")
            .remove("family_control_enabled")
            .remove("family_control_view_only")
            .remove("enc_enabled")
            .remove("enc_salt")
            .remove("enc_wrapped_login_key")
            .remove("was_offline")
            .remove("sync_base_medicines")
            .remove("sync_base_records")
            .apply()
    }

    /** 清除所有数据 */
    fun clear() {
        sp.edit().clear().apply()
    }
}
