/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 服药记录实体
 */
@Entity(tableName = "records", indices = [Index(value = ["clientUuid"])])
data class RecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(defaultValue = "''") val clientUuid: String = "",      // 客户端 UUID（E2E 合并/去重的稳定主键，跨设备一致）
    val medicineId: Long,
    val medicineName: String = "",    // 药品名称快照（创建记录时保存，删除/改名后仍保留）
    val scheduledTime: Long,          // 计划服用时间（毫秒时间戳）
    val takenTime: Long? = null,      // 实际服用时间
    val status: String = "pending",   // pending / taken / missed / skipped
    val deletedAt: Long = 0,          // 0=未删除；>0=软删除时间戳（跨设备双向同步删除标记）
    @ColumnInfo(defaultValue = "1") val undoable: Boolean = true  // 撤销资格快照：闹钟时间修改/进回收站时对当时全部现存记录批量置 false；之后新记录保持 true
)
