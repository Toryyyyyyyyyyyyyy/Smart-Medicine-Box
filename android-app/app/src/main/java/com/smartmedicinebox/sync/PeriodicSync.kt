/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smartmedicinebox.App
import com.smartmedicinebox.data.repository.MedicineRepository
import com.smartmedicinebox.util.Prefs
import java.util.concurrent.TimeUnit

/**
 * 周期同步任务：仅本人已登录且开启家人控制时执行（注册与否也以同样条件为准）。
 * 15 分钟为 WorkManager 允许的最小周期，实际执行时间由系统调度，不保证精确。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 任务被取消前可能已排队：执行时再校验一次登录与家人控制开关
        if (!Prefs.isLoggedIn || !Prefs.familyControlEnabled) return Result.success()
        return try {
            MedicineRepository.syncPeriodic()
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }
}

object SyncScheduler {

    private const val UNIQUE_NAME = "periodic_vault_sync"
    private const val INTERVAL_MINUTES = 15L

    /** 注册周期同步（幂等：任务已存在则保留，不重置调度）。登录且开启家人控制时调用。 */
    fun enqueuePeriodicSync() {
        if (!Prefs.isLoggedIn || !Prefs.familyControlEnabled) return
        val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(App.instance).enqueueUniquePeriodicWork(
            UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    /** 取消周期同步：退出登录 / 关闭家人控制 / 注销账号时调用，避免为不需要同步的用户空跑任务 */
    fun cancelPeriodicSync() {
        WorkManager.getInstance(App.instance).cancelUniqueWork(UNIQUE_NAME)
    }
}
