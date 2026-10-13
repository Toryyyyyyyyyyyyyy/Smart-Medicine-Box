/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.smartmedicinebox.App
import com.smartmedicinebox.data.db.MedicineEntity
import com.smartmedicinebox.service.MidnightReceiver
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * 闹钟调度器：注册/取消 AlarmManager 精确闹钟
 */
object AlarmScheduler {

    const val EXTRA_MEDICINE_ID = "medicine_id"
    const val EXTRA_MEDICINE_NAME = "medicine_name"
    const val EXTRA_DOSAGE = "dosage"
    const val EXTRA_TIME = "time"
    const val EXTRA_ALARM_TYPE = "alarm_type"
    const val EXTRA_VIDEO_PATH = "video_path"
    const val EXTRA_VIDEO_CROP = "video_crop"
    const val EXTRA_RINGTONE_URI = "ringtone_uri"
    const val EXTRA_REPEAT_DAYS = "repeat_days"
    const val EXTRA_REPEAT_MODE = "repeat_mode"
    const val EXTRA_INTERVAL_DAYS = "interval_days"
    const val EXTRA_ANCHOR_DAY = "anchor_day"
    const val EXTRA_SLOT_INDEX = "slot_index"
    const val EXTRA_SCHEDULED_TIME = "scheduled_time"

    // 重复模式
    const val REPEAT_MODE_WEEKDAY = "weekday"   // 按周几
    const val REPEAT_MODE_INTERVAL = "interval" // 每隔几天

    // 每日 0 点清理闹钟的 requestCode（取负值避免与药品闹钟冲突）
    private const val MIDNIGHT_REQUEST_CODE = -4242

    /** 某药品「下一次」应服剂量 */
    data class NextDose(
        val scheduledTime: Long,
        val slotIndex: Int,
        val time: String
    )

    /**
     * 为单个药品的所有时间点注册闹钟。
     * 记录感知：今天已服用（含提前服用/远程代为服用）的剂量跳过，直接推进到下一周期，
     * 避免应用重开/同步时把已处理剂量的闹钟重新注册（R5「不再触发」）。
     */
    suspend fun scheduleMedicine(context: Context, medicine: MedicineEntity) {
        val recordDao = App.instance.database.recordDao()
        medicine.getTimeSlotList().forEachIndexed { index, time ->
            var after = System.currentTimeMillis()
            var guard = 0
            while (guard < 16) {
                val trigger = calculateNextTrigger(
                    time, medicine.repeatMode, medicine.repeatDays,
                    medicine.intervalDays, medicine.anchorDay, after
                )
                if (!recordDao.hasActiveTakenAt(medicine.id, trigger, medicine.stateResetAt)) break
                after = trigger
                guard++
            }
            scheduleOne(
                context,
                medicine.id,
                medicine.name,
                medicine.dosage,
                time,
                medicine.alarmType,
                medicine.videoPath,
                medicine.videoCrop,
                medicine.ringtoneUri,
                medicine.repeatDays,
                medicine.repeatMode,
                medicine.intervalDays,
                medicine.anchorDay,
                index,
                after
            )
        }
    }

    /** 计算该药品「下一次」应服时间（所有时间点中最早的下一次触发） */
    fun findNextDose(medicine: MedicineEntity): NextDose? {
        val slots = medicine.getTimeSlotList()
        if (slots.isEmpty()) return null
        var best: NextDose? = null
        slots.forEachIndexed { index, time ->
            val trigger = calculateNextTrigger(
                time, medicine.repeatMode, medicine.repeatDays,
                medicine.intervalDays, medicine.anchorDay
            )
            val current = best
            if (current == null || trigger < current.scheduledTime) {
                best = NextDose(trigger, index, time)
            }
        }
        return best
    }

    /** 服用后推进该时间点到下一周期：先取消该时间点原定待触发的闹钟（提前服用场景），再调度下一次 */
    fun advanceSlot(context: Context, medicine: MedicineEntity, slotIndex: Int, time: String, after: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // PendingIntent 的 extras 含 scheduled_time，必须按原 scheduledTime 构建才能匹配取消已注册的闹钟
        alarmManager.cancel(
            buildPendingIntent(
                context, medicine.id, medicine.name, medicine.dosage, time,
                medicine.alarmType, medicine.videoPath, medicine.videoCrop, medicine.ringtoneUri,
                medicine.repeatDays, medicine.repeatMode, medicine.intervalDays, medicine.anchorDay,
                slotIndex, after
            )
        )
        scheduleOne(
            context, medicine.id, medicine.name, medicine.dosage, time,
            medicine.alarmType, medicine.videoPath, medicine.videoCrop, medicine.ringtoneUri,
            medicine.repeatDays, medicine.repeatMode, medicine.intervalDays, medicine.anchorDay,
            slotIndex, after
        )
    }

    /**
     * 注册单个时间点的闹钟
     */
    fun scheduleOne(
        context: Context,
        medicineId: Long,
        medicineName: String,
        dosage: String,
        time: String,
        alarmType: String,
        videoPath: String?,
        videoCrop: String?,
        ringtoneUri: String?,
        repeatDays: String,
        repeatMode: String,
        intervalDays: Int,
        anchorDay: Long,
        slotIndex: Int,
        after: Long = System.currentTimeMillis()
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerTime = calculateNextTrigger(time, repeatMode, repeatDays, intervalDays, anchorDay, after)
        val pendingIntent = buildPendingIntent(
            context, medicineId, medicineName, dosage, time, alarmType, videoPath, videoCrop, ringtoneUri,
            repeatDays, repeatMode, intervalDays, anchorDay, slotIndex, triggerTime
        )

        // 用 setAlarmClock 注册：精确 + 免休眠，且作为「闹钟」在系统 UI 显示
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerTime, null),
            pendingIntent
        )
    }

    /**
     * 取消某个药品的所有闹钟
     */
    fun cancelMedicine(context: Context, medicine: MedicineEntity) {
        medicine.getTimeSlotList().forEachIndexed { index, time ->
            val pi = buildPendingIntent(
                context, medicine.id, medicine.name, medicine.dosage,
                time, medicine.alarmType, medicine.videoPath,
                medicine.videoCrop, medicine.ringtoneUri, medicine.repeatDays, medicine.repeatMode,
                medicine.intervalDays, medicine.anchorDay, index
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pi)
        }
    }

    /**
     * 取消某个药品的「5 分钟后提醒」延迟闹钟（与 scheduleSnooze 使用相同的 requestCode）
     */
    fun cancelSnooze(context: Context, medicineId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, medicineId.toInt(), intent, flags)
        alarmManager.cancel(pi)
    }

    /**
     * 注册「稍后提醒」延迟闹钟：5 分钟后再次全屏弹出。
     * 直接以全屏 Activity 作为闹钟目标（配合 setAlarmClock），后台/亮屏时也能全屏弹出。
     */
    fun scheduleSnooze(
        context: Context,
        medicineId: Long,
        medicineName: String,
        dosage: String,
        time: String,
        alarmType: String,
        videoPath: String?,
        videoCrop: String?,
        ringtoneUri: String?,
        repeatDays: String,
        repeatMode: String,
        intervalDays: Int,
        anchorDay: Long,
        slotIndex: Int,
        scheduledTime: Long
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_MEDICINE_ID, medicineId)
            putExtra(EXTRA_MEDICINE_NAME, medicineName)
            putExtra(EXTRA_DOSAGE, dosage)
            putExtra(EXTRA_TIME, time)
            putExtra(EXTRA_ALARM_TYPE, alarmType)
            putExtra(EXTRA_VIDEO_PATH, videoPath)
            putExtra(EXTRA_VIDEO_CROP, videoCrop)
            putExtra(EXTRA_RINGTONE_URI, ringtoneUri)
            putExtra(EXTRA_REPEAT_DAYS, repeatDays)
            putExtra(EXTRA_REPEAT_MODE, repeatMode)
            putExtra(EXTRA_INTERVAL_DAYS, intervalDays)
            putExtra(EXTRA_ANCHOR_DAY, anchorDay)
            putExtra(EXTRA_SLOT_INDEX, slotIndex)
            putExtra(EXTRA_SCHEDULED_TIME, scheduledTime)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(context, medicineId.toInt(), intent, flags)
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 5 * 60 * 1000L, null),
            pendingIntent
        )
    }

    /** 注册每日 0 点的未服用清理闹钟 */
    fun scheduleMidnightCleanup(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val trigger = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val intent = Intent(context, MidnightReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, MIDNIGHT_REQUEST_CODE, intent, flags)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
    }

    /**
     * 构建带参数的 PendingIntent
     */
    private fun buildPendingIntent(
        context: Context,
        medicineId: Long,
        medicineName: String,
        dosage: String,
        time: String,
        alarmType: String,
        videoPath: String?,
        videoCrop: String?,
        ringtoneUri: String?,
        repeatDays: String,
        repeatMode: String,
        intervalDays: Int,
        anchorDay: Long,
        slotIndex: Int,
        scheduledTime: Long = 0L
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_MEDICINE_ID, medicineId)
            putExtra(EXTRA_MEDICINE_NAME, medicineName)
            putExtra(EXTRA_DOSAGE, dosage)
            putExtra(EXTRA_TIME, time)
            putExtra(EXTRA_ALARM_TYPE, alarmType)
            putExtra(EXTRA_VIDEO_PATH, videoPath)
            putExtra(EXTRA_VIDEO_CROP, videoCrop)
            putExtra(EXTRA_RINGTONE_URI, ringtoneUri)
            putExtra(EXTRA_REPEAT_DAYS, repeatDays)
            putExtra(EXTRA_REPEAT_MODE, repeatMode)
            putExtra(EXTRA_INTERVAL_DAYS, intervalDays)
            putExtra(EXTRA_ANCHOR_DAY, anchorDay)
            putExtra(EXTRA_SLOT_INDEX, slotIndex)
            putExtra(EXTRA_SCHEDULED_TIME, scheduledTime)
        }
        val requestCode = (medicineId.toInt() * 100 + slotIndex)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /**
     * 计算下一次触发时间（"HH:mm:ss" → 毫秒时间戳）
     * repeatMode = weekday：repeatDays 为空 = 每天；否则只在指定星期触发（1=周一…7=周日）
     * repeatMode = interval：从 anchorDay 起每隔 intervalDays 天触发
     */
    fun calculateNextTrigger(
        time: String,
        repeatMode: String,
        repeatDays: String,
        intervalDays: Int,
        anchorDay: Long,
        after: Long = System.currentTimeMillis()
    ): Long {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val second = parts.getOrNull(2)?.toIntOrNull() ?: 0

        // 模式二：每隔 N 天
        if (repeatMode == REPEAT_MODE_INTERVAL) {
            val n = intervalDays.coerceAtLeast(1).toLong()
            val afterDay = Instant.ofEpochMilli(after).atZone(ZoneId.systemDefault())
                .toLocalDate().toEpochDay()
            val anchor = if (anchorDay > 0L) anchorDay else afterDay
            val diff = (afterDay - anchor).coerceAtLeast(0L)
            var candidateDay = afterDay + (n - diff % n) % n
            var millis = epochDayToMillis(candidateDay, hour, minute, second)
            if (millis <= after) {
                candidateDay += n
                millis = epochDayToMillis(candidateDay, hour, minute, second)
            }
            return millis
        }

        // 模式一：按周几
        val days = repeatDays.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
            .toSet()

        for (offset in 0..7) {
            val target = Calendar.getInstance().apply {
                timeInMillis = after
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, second)
                set(Calendar.MILLISECOND, 0)
            }
            // Calendar.DAY_OF_WEEK：1=周日…7=周六 → 转为 1=周一…7=周日
            val dow = target.get(Calendar.DAY_OF_WEEK)
            val weekday = if (dow == Calendar.SUNDAY) 7 else dow - 1
            if (days.isEmpty() || weekday in days) {
                if (target.timeInMillis > after) {
                    return target.timeInMillis
                }
            }
        }
        // 兜底：基于 after 的下一天同一时间
        return Calendar.getInstance().apply {
            timeInMillis = after
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** 将 epoch-day + 时分秒转为本地时区毫秒时间戳 */
    private fun epochDayToMillis(epochDay: Long, hour: Int, minute: Int, second: Int): Long =
        LocalDate.ofEpochDay(epochDay)
            .atTime(hour, minute, second)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
