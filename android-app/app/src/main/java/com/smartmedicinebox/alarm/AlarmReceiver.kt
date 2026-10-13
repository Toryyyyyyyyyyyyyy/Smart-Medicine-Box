/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.alarm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.smartmedicinebox.AlarmActivity
import com.smartmedicinebox.App
import com.smartmedicinebox.R

/**
 * 闹钟触发接收器：到点后弹全屏界面 + 发通知 + 重调度下一天
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val medicineId = intent.getLongExtra(AlarmScheduler.EXTRA_MEDICINE_ID, 0L)
        val medicineName = intent.getStringExtra(AlarmScheduler.EXTRA_MEDICINE_NAME) ?: context.getString(R.string.default_medicine_name)
        val dosage = intent.getStringExtra(AlarmScheduler.EXTRA_DOSAGE) ?: ""
        val time = intent.getStringExtra(AlarmScheduler.EXTRA_TIME) ?: "08:00"
        val alarmType = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_TYPE) ?: "sound"
        val videoPath = intent.getStringExtra(AlarmScheduler.EXTRA_VIDEO_PATH)
        val videoCrop = intent.getStringExtra(AlarmScheduler.EXTRA_VIDEO_CROP)
        val ringtoneUri = intent.getStringExtra(AlarmScheduler.EXTRA_RINGTONE_URI)
        val repeatDays = intent.getStringExtra(AlarmScheduler.EXTRA_REPEAT_DAYS) ?: ""
        val repeatMode = intent.getStringExtra(AlarmScheduler.EXTRA_REPEAT_MODE) ?: AlarmScheduler.REPEAT_MODE_WEEKDAY
        val intervalDays = intent.getIntExtra(AlarmScheduler.EXTRA_INTERVAL_DAYS, 1)
        val anchorDay = intent.getLongExtra(AlarmScheduler.EXTRA_ANCHOR_DAY, 0L)
        val slotIndex = intent.getIntExtra(AlarmScheduler.EXTRA_SLOT_INDEX, 0)
        val scheduledTime = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_TIME, 0L)

        // 1. 重新调度下一个匹配日期的同一时间（保证持续提醒）；测试闹钟(medicineId<=0)不重排
        if (medicineId > 0) {
            AlarmScheduler.scheduleOne(
                context, medicineId, medicineName, dosage, time, alarmType, videoPath, videoCrop, ringtoneUri,
                repeatDays, repeatMode, intervalDays, anchorDay, slotIndex, scheduledTime
            )
        }

        // 2. 构建全屏通知（Android 10+ 后台启动 Activity 的合规方式）
        val fullScreenIntent = buildFullScreenIntent(context, medicineId, medicineName, dosage, time, alarmType, videoPath, videoCrop, ringtoneUri, repeatDays, repeatMode, intervalDays, anchorDay, slotIndex, scheduledTime)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(context, App.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.alarm_take_medicine, medicineName))
            .setContentText(if (dosage.isNotEmpty()) "$dosage · ${time.take(5)}" else time.take(5))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(fullScreenIntent)   // 点击通知也能打开全屏界面
            .setFullScreenIntent(fullScreenIntent, true)

        // Android 14+ 若未授予「全屏通知」，全屏会被降级为普通通知；此时补上铃声/震动，避免无声
        val fullScreenOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent()
        if (!fullScreenOk) {
            val types = alarmType.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if ("sound" in types) {
                builder.setSound(
                    ringtoneUri?.let { Uri.parse(it) }
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                )
            }
            if ("vibration" in types) {
                builder.setVibrate(longArrayOf(0, 600, 400, 600, 400))
            }
        }

        val notification = builder.build()
        try {
            manager.notify(medicineId.toInt(), notification)
        } catch (e: SecurityException) {
            // 缺少 POST_NOTIFICATIONS 权限时，仍尝试直接启动全屏界面
        }

        // 兼容：前台 / 低版本等允许后台启动 Activity 的场景，直接弹出全屏界面
        try {
            context.startActivity(buildAlarmIntent(context, medicineId, medicineName, dosage, time, alarmType, videoPath, videoCrop, ringtoneUri, repeatDays, repeatMode, intervalDays, anchorDay, slotIndex, scheduledTime))
        } catch (_: Exception) {
        }
    }

    private fun buildAlarmIntent(
        context: Context, medicineId: Long, medicineName: String, dosage: String,
        time: String, alarmType: String, videoPath: String?, videoCrop: String?, ringtoneUri: String?,
        repeatDays: String, repeatMode: String, intervalDays: Int, anchorDay: Long,
        slotIndex: Int, scheduledTime: Long
    ): Intent = Intent(context, AlarmActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(AlarmScheduler.EXTRA_MEDICINE_ID, medicineId)
        putExtra(AlarmScheduler.EXTRA_MEDICINE_NAME, medicineName)
        putExtra(AlarmScheduler.EXTRA_DOSAGE, dosage)
        putExtra(AlarmScheduler.EXTRA_TIME, time)
        putExtra(AlarmScheduler.EXTRA_ALARM_TYPE, alarmType)
        putExtra(AlarmScheduler.EXTRA_VIDEO_PATH, videoPath)
        putExtra(AlarmScheduler.EXTRA_VIDEO_CROP, videoCrop)
        putExtra(AlarmScheduler.EXTRA_RINGTONE_URI, ringtoneUri)
        putExtra(AlarmScheduler.EXTRA_REPEAT_DAYS, repeatDays)
        putExtra(AlarmScheduler.EXTRA_REPEAT_MODE, repeatMode)
        putExtra(AlarmScheduler.EXTRA_INTERVAL_DAYS, intervalDays)
        putExtra(AlarmScheduler.EXTRA_ANCHOR_DAY, anchorDay)
        putExtra(AlarmScheduler.EXTRA_SLOT_INDEX, slotIndex)
        putExtra(AlarmScheduler.EXTRA_SCHEDULED_TIME, scheduledTime)
    }

    private fun buildFullScreenIntent(
        context: Context, medicineId: Long, medicineName: String, dosage: String,
        time: String, alarmType: String, videoPath: String?, videoCrop: String?, ringtoneUri: String?,
        repeatDays: String, repeatMode: String, intervalDays: Int, anchorDay: Long,
        slotIndex: Int, scheduledTime: Long
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(
            context,
            medicineId.toInt(),
            buildAlarmIntent(context, medicineId, medicineName, dosage, time, alarmType, videoPath, videoCrop, ringtoneUri, repeatDays, repeatMode, intervalDays, anchorDay, slotIndex, scheduledTime),
            flags
        )
    }
}
