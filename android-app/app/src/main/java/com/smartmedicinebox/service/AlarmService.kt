/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.smartmedicinebox.App
import com.smartmedicinebox.MainActivity
import com.smartmedicinebox.R
import com.smartmedicinebox.alarm.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 前台服务：保持进程常驻，重新调度所有闹钟
 */
class AlarmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 重新调度所有闹钟（开机自启 / 服务重启时调用）
        rescheduleAll()
        // 注册每日 0 点清理闹钟
        AlarmScheduler.scheduleMidnightCleanup(this)
        return START_STICKY
    }

    /** 用户从最近任务划掉时：尽力通过系统闹钟重启前台服务，保持常驻 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleRestart()
    }

    /** 注册一个 2 秒后的重启广播（用 AlarmManager，进程被杀后系统仍会触发） */
    private fun scheduleRestart() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, RestartReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, RESTART_REQUEST_CODE, intent, flags)
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 2000,
                pi
            )
        } catch (_: Exception) {
        }
    }

    /** 启动前台服务（显示常驻通知） */
    private fun startAsForeground() {
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun buildServiceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 从数据库读取所有启用的药品，重新注册闹钟 */
    private fun rescheduleAll() {
        scope.launch {
            val medicines = App.instance.database.medicineDao()
                .getAllMedicines()
                .first()
                .filter { it.isActive }
            medicines.forEach { medicine ->
                AlarmScheduler.scheduleMedicine(this@AlarmService, medicine)
            }
        }
    }

    companion object {
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val RESTART_REQUEST_CODE = 4243

        fun start(context: Context) {
            val intent = Intent(context, AlarmService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Android 12+ 后台启动前台服务受限：忽略，闹钟本身由 AlarmManager 兜底
            }
        }
    }
}
