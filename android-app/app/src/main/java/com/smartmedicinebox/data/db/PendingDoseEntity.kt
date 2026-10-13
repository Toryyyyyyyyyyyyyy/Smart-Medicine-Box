/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 待补服的「未服用」剂量（点「暂不服用」产生）
 * 可按原定应服时间单独补服；每日 0 点仍未补服的转成正式「未服用」记录
 */
@Entity(tableName = "pending_doses")
data class PendingDoseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val medicineId: Long,
    val medicineName: String = "",   // 药品名称快照
    val scheduledTime: Long          // 原定应服时间（毫秒时间戳）
)
