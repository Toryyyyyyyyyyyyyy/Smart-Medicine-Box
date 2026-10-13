/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.repository

import com.smartmedicinebox.alarm.AlarmScheduler
import com.smartmedicinebox.data.db.MedicineEntity
import com.smartmedicinebox.data.db.RecordEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 纯逻辑：今日应服时间点 + 主「服用」按钮目标选择（本人端 / 家属远程共用，保证两端行为一致）。
 *
 * 状态机（R5）：
 *  - 有「HH:MM 未服用」按钮（pending/missed）→ 主按钮补服最早一条（R3），不受闹钟时间限制；
 *  - 无按钮 → 目标为今天最早未处理的剂量：
 *      * 首个时间点可直接提前服用；
 *      * 提前服用后按钮禁用，直到该剂量原定时间到达，之后解锁并指向下一个剂量（循环）；
 *      * 非首个时间点需等待前一时间点的原定时间到达（门控）；
 *  - 今天全部处理完 → null（按钮禁用，绝不跨天）。
 * 「按时服药阈值」只影响记录状态判定（determineStatus），绝不参与按钮解锁。
 */
object DosePlanner {

    data class Slot(val index: Int, val time: String, val millis: Long)

    data class Target(
        val scheduledTime: Long,
        val slotIndex: Int = -1,
        val time: String = "",
        val makeup: RecordEntity? = null
    )

    private fun zone(): ZoneId = ZoneId.systemDefault()

    /** 今天 0 点（默认本机时区；家属远程控制时传入目标用户本地时区） */
    fun todayStart(now: Long, zone: ZoneId = zone()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** 今天适用的应服时间点（按时间升序），语义与 AlarmScheduler.calculateNextTrigger 对齐；
     *  家属远程控制时传入目标用户本地时区 */
    fun todaySlots(medicine: MedicineEntity, now: Long, zone: ZoneId = zone()): List<Slot> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val applicable = if (medicine.repeatMode == AlarmScheduler.REPEAT_MODE_INTERVAL) {
            // interval：从 anchorDay 起每隔 N 天；anchor<=0 时以当天为锚 → 每天适用（与 calculateNextTrigger 一致）
            val n = medicine.intervalDays.coerceAtLeast(1).toLong()
            val anchor = medicine.anchorDay
            if (anchor <= 0L) true
            else (today.toEpochDay() - anchor).coerceAtLeast(0L) % n == 0L
        } else {
            // weekday：repeatDays 空 = 每天；否则 1=周一…7=周日（java.time DayOfWeek 同编码）
            val days = medicine.repeatDays.split(",").filter { it.isNotBlank() }
                .mapNotNull { it.toIntOrNull() }.toSet()
            days.isEmpty() || today.dayOfWeek.value in days
        }
        if (!applicable) return emptyList()
        return medicine.getTimeSlotList().mapIndexedNotNull { index, time ->
            val parts = time.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val s = parts.getOrNull(2)?.toIntOrNull() ?: 0
            Slot(index, time, today.atTime(h, m, s).atZone(zone).toInstant().toEpochMilli())
        }.sortedBy { it.millis }
    }

    /** 主「服用」按钮的目标；null = 无目标（按钮禁用）。records 为全部记录（含墓碑、可含其他药品，内部过滤）。
     *  家属远程控制时传入目标用户本地时区。 */
    fun nextTarget(medicine: MedicineEntity, records: List<RecordEntity>, now: Long, zone: ZoneId = zone()): Target? {
        val start = todayStart(now, zone)
        val mine = records.filter { it.medicineId == medicine.id }
        val active = mine.filter { it.deletedAt == 0L }

        // (a) 补服：今天起仍未服用的按钮（pending/missed），取最早一条（R3）
        val makeup = active
            .filter { (it.status == "missed" || it.status == "pending") && it.scheduledTime >= start }
            .minByOrNull { it.scheduledTime }
        if (makeup != null) return Target(makeup.scheduledTime, makeup = makeup)

        // (b) 今天尚未处理的剂量：最早一条
        val slots = todaySlots(medicine, now, zone)
        if (slots.isEmpty()) return null
        // 已服集合：软删除（墓碑）的 taken 记录仍算已服，删除历史不应让剂量回到未处理（R17）
        // 从回收站恢复（stateResetAt）后视为全新药品：恢复前的服用进度不计入，不继承提前服用的按钮禁用状态
        val resetAt = medicine.stateResetAt
        val takenTimes = mine.filter { it.status.startsWith("taken") && (it.takenTime ?: 0L) >= resetAt }
            .map { it.scheduledTime }.toSet()
        val target = slots.firstOrNull { it.millis !in takenTimes } ?: return null

        // 门控：首个时间点允许提前服用；其余需等前一时间点的原定时间已到（R5 循环）
        val position = slots.indexOf(target)
        if (position > 0 && now < slots[position - 1].millis) return null
        return Target(target.millis, slotIndex = target.index, time = target.time)
    }
}
