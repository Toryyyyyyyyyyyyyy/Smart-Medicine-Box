/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.repository

import com.smartmedicinebox.App
import com.smartmedicinebox.data.db.MedicineEntity
import com.smartmedicinebox.data.db.RecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

/**
 * 数据源路由：MedicineFragment / RecordFragment 通过它读写数据，
 * OWN 模式走本地 Room + MedicineRepository，FAMILY_REMOTE 模式走家属远程仓库。
 */
object SessionState {

    enum class Mode { OWN, FAMILY_REMOTE }

    var mode: Mode = Mode.OWN

    val isFamilyRemote: Boolean get() = mode == Mode.FAMILY_REMOTE

    /** 家属会话是否为「仅允许查看」（服务器为准，UI 仅辅助隐藏） */
    val viewOnly: Boolean get() = isFamilyRemote && FamilyRemoteRepository.viewOnly

    // ==================== 数据流 ====================

    /** 时间基准时区：家属远程模式按目标用户本地时区，本人模式按本机时区（家属设备自身功能不受影响） */
    fun doseZone(): ZoneId =
        if (isFamilyRemote) FamilyRemoteRepository.seniorZone() else ZoneId.systemDefault()

    fun activeMedicines(): Flow<List<MedicineEntity>> =
        if (isFamilyRemote) FamilyRemoteRepository.medicinesFlow
        else App.instance.database.medicineDao().getActiveMedicines()

    fun pendingDoses(): Flow<List<RecordEntity>> =
        if (isFamilyRemote) FamilyRemoteRepository.missedRecordsFlow
        else App.instance.database.recordDao().getMissedRecords().map { list ->
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            list.filter { it.scheduledTime >= start }
        }

    fun records(): Flow<List<RecordEntity>> =
        if (isFamilyRemote) FamilyRemoteRepository.recordsFlow
        else App.instance.database.recordDao().getAllRecords()

    /** 全部记录（含软删除墓碑），供服用按钮状态/目标计算使用：已服集合需计入墓碑，删除历史不改变按钮 */
    fun allRecords(): Flow<List<RecordEntity>> =
        if (isFamilyRemote) FamilyRemoteRepository.allRecordsFlow
        else App.instance.database.recordDao().getAllRecordsIncludingTrash()

    // ==================== 写操作 ====================

    suspend fun takeNextDose(medicine: MedicineEntity) {
        if (isFamilyRemote) FamilyRemoteRepository.takeNextDose(medicine)
        else MedicineRepository.takeNextDose(medicine)
    }

    suspend fun takePendingDose(dose: RecordEntity) {
        if (isFamilyRemote) FamilyRemoteRepository.takePendingDose(dose)
        else MedicineRepository.takePendingDose(dose)
    }

    suspend fun undoTaken(record: RecordEntity) {
        if (isFamilyRemote) FamilyRemoteRepository.undoTaken(record)
        else MedicineRepository.undoTaken(record)
    }

    suspend fun deleteRecords(ids: List<Long>) {
        if (isFamilyRemote) FamilyRemoteRepository.deleteRecords(ids)
        else MedicineRepository.deleteRecords(ids)
    }
}
