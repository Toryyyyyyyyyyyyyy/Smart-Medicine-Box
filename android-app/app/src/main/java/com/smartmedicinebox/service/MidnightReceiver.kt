/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smartmedicinebox.alarm.AlarmScheduler
import com.smartmedicinebox.data.repository.MedicineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 每日 0 点接收器：把过期仍未补服的剂量转成正式「未服用」记录，并重新注册下一次
 */
class MidnightReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 重新注册下一次 0 点
        AlarmScheduler.scheduleMidnightCleanup(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MedicineRepository.flushMissedDoses()
            } catch (_: Exception) {
            }
            pendingResult.finish()
        }
    }
}
