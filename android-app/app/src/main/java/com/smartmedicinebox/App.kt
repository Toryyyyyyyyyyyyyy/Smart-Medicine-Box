/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.smartmedicinebox.data.db.AppDatabase
import com.smartmedicinebox.util.CryptoSession
import com.smartmedicinebox.util.LocaleHelper

/**
 * 应用入口：初始化通知渠道和数据库单例
 */
class App : Application() {

    companion object {
        const val CHANNEL_ALARM = "alarm_channel"
        const val CHANNEL_SERVICE = "service_channel"

        lateinit var instance: App
            private set
    }

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    override fun attachBaseContext(base: Context) {
        // 全局应用语言，保证 Service / Receiver 的通知文案也跟随语言
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        // 恢复本地持久化的登录密钥，重启/更新 APK 后免输密码即可同步（无则忽略）
        CryptoSession.restoreLoginKeys()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM,
                getString(R.string.channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_alarm_desc)
            }

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_service_desc)
            }

            manager.createNotificationChannel(alarmChannel)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
