/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 药品实体（本地主存储）
 */
@Entity(tableName = "medicines", indices = [Index(value = ["clientUuid"])])
data class MedicineEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val serverId: Long? = null,       // 后端同步 ID
    @ColumnInfo(defaultValue = "''") val clientUuid: String = "",      // 客户端 UUID（E2E 合并/去重的稳定主键，跨设备一致）
    val name: String,
    val dosage: String = "",
    val frequency: String = "daily",  // daily / weekly / custom
    val timeSlots: String = "",       // 逗号分隔 "08:00:00,12:00:00,18:00:00"
    val stock: Int = 0,
    val notes: String = "",
    val isActive: Boolean = true,
    val deletedAt: Long = 0,              // 0=未删除；>0=移入回收站的时间戳（毫秒）
    @ColumnInfo(defaultValue = "0") val hasBeenTrashed: Boolean = false,  // 是否进入过回收站（vault 同步字段，置 true 后不再复位）；撤销冻结由各记录 undoable 快照承担
    val stateResetAt: Long = 0,  // 最近一次从回收站恢复的时间戳：恢复后视为全新药品，此前的服药进度（takenTime < 该值）不计入按钮/闹钟状态；0=未恢复过
    val alarmType: String = "sound,vibration",  // 逗号分隔，任意组合：sound / vibration / video
    val repeatDays: String = "",      // 重复星期：逗号分隔 1~7（1=周一…7=周日），空=每天
    val repeatMode: String = "weekday",  // weekday（按周几，默认）| interval（每隔几天）
    val intervalDays: Int = 1,        // interval 模式：每 N 天一次
    val anchorDay: Long = 0,          // interval 模式起始日（LocalDate.toEpochDay()），0=未设置
    val videoPath: String? = null,    // 自定义提示视频路径
    val videoCrop: String? = null,    // 自定义视频裁剪区域（归一化 "left,top,right,bottom"，0..1）
    val ringtoneUri: String? = null,  // 自定义提醒铃声（content:// 或 file://，null=系统默认）
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 解析服药时间点 */
    fun getTimeSlotList(): List<String> =
        timeSlots.split(",").filter { it.isNotBlank() }
}
