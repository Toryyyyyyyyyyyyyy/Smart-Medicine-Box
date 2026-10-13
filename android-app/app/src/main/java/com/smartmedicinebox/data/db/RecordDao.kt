/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {

    /** 记录列表展示：隐藏「暂不服用」暂存行（pending），0 点翻转为正式未服用后才出现 */
    @Query("SELECT * FROM records WHERE deletedAt = 0 AND status != 'pending' ORDER BY COALESCE(takenTime, scheduledTime) DESC")
    fun getAllRecords(): Flow<List<RecordEntity>>

    /** 全部记录（含软删除墓碑），供服用按钮状态计算：已服集合需计入墓碑，删除历史不改变按钮 */
    @Query("SELECT * FROM records")
    fun getAllRecordsIncludingTrash(): Flow<List<RecordEntity>>

    /** 某药品的全部记录（含软删除墓碑，服用/补服目标计算用），按应服时间升序 */
    @Query("SELECT * FROM records WHERE medicineId = :medicineId ORDER BY scheduledTime ASC")
    suspend fun getAllForMedicine(medicineId: Long): List<RecordEntity>

    /** 某药品某应服时间是否已有生效的已服用记录（闹钟推进跳过已服剂量用）；resetAt>0 时忽略恢复前（takenTime < resetAt）的记录 */
    @Query("SELECT EXISTS(SELECT 1 FROM records WHERE deletedAt = 0 AND medicineId = :medicineId AND scheduledTime = :scheduledTime AND status LIKE 'taken%' AND COALESCE(takenTime, 0) >= :resetAt)")
    suspend fun hasActiveTakenAt(medicineId: Long, scheduledTime: Long, resetAt: Long): Boolean

    /** 全部记录（含软删除墓碑），供推送到 vault 用——墓碑必须一并上传才能让删除双向传播 */
    @Query("SELECT * FROM records ORDER BY COALESCE(takenTime, scheduledTime) DESC")
    suspend fun getAllRecordsRaw(): List<RecordEntity>

    @Query("SELECT * FROM records WHERE deletedAt = 0 AND scheduledTime >= :start AND scheduledTime < :end")
    fun getRecordsBetween(start: Long, end: Long): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE deletedAt = 0 AND medicineId = :medicineId AND scheduledTime = :scheduledTime LIMIT 1")
    suspend fun getByMedicineAndTime(medicineId: Long, scheduledTime: Long): RecordEntity?

    /** 同 getByMedicineAndTime，但忽略恢复前（takenTime < resetAt）的已服用记录：药品从回收站恢复后按全新药品处理 */
    @Query("SELECT * FROM records WHERE deletedAt = 0 AND medicineId = :medicineId AND scheduledTime = :scheduledTime AND (status NOT LIKE 'taken%' OR COALESCE(takenTime, 0) >= :resetAt) LIMIT 1")
    suspend fun getByMedicineAndTimeSince(medicineId: Long, scheduledTime: Long, resetAt: Long): RecordEntity?

    @Query("SELECT * FROM records WHERE clientUuid = :clientUuid AND clientUuid != '' LIMIT 1")
    suspend fun getByClientUuid(clientUuid: String): RecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: RecordEntity): Long

    /**
     * 防重复记录：同一剂量（药品 + 应服时间）当天若已存在「已服用」记录则跳过并返回 false，
     * 否则插入并返回 true。放在事务里保证并发点击/双弹窗时只记一次。
     * resetAt>0（药品从回收站恢复过）时，恢复前的已服用记录不参与去重（视为全新药品）。
     */
    @androidx.room.Transaction
    suspend fun insertTakenIfAbsent(record: RecordEntity, resetAt: Long): Boolean {
        val existing = getByMedicineAndTimeSince(record.medicineId, record.scheduledTime, resetAt)
        if (existing != null) {
            if (existing.status.startsWith("taken")) return false
            // 同剂量已存在「未服用/暂不服用」记录：直接转为已服用，避免同一剂量出现重复记录
            return markMissedAsTaken(existing.id, record.status, record.takenTime ?: System.currentTimeMillis()) > 0
        }
        insert(record)
        return true
    }

    @Update
    suspend fun update(record: RecordEntity)

    @Query("DELETE FROM records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 软删除：标记 deletedAt 而非物理删除，墓碑随 vault 推送让删除跨设备双向同步 */
    @Query("UPDATE records SET deletedAt = :deletedAt WHERE id IN (:ids)")
    suspend fun markDeleted(ids: List<Long>, deletedAt: Long)

    /** 撤销：软删除并把状态改为 undone——与删除墓碑区分，撤销后剂量回到未处理（不计入已服集合），删除墓碑仍计入已服 */
    @Query("UPDATE records SET deletedAt = :deletedAt, status = 'undone' WHERE id = :id")
    suspend fun markUndone(id: Long, deletedAt: Long)

    /** 未服用剂量（补服用）：暂不服用暂存（pending）+ 正式未服用（missed），按应服时间升序 */
    @Query("SELECT * FROM records WHERE deletedAt = 0 AND status IN ('missed','pending') ORDER BY scheduledTime ASC")
    fun getMissedRecords(): Flow<List<RecordEntity>>

    /** 把某条未服用/暂不服用记录直接转为已服用（补服）；返回受影响行数（0 = 状态已变，防重复补服） */
    @Query("UPDATE records SET status = :status, takenTime = :takenTime WHERE id = :id AND deletedAt = 0 AND status IN ('missed','pending','skipped')")
    suspend fun markMissedAsTaken(id: Long, status: String, takenTime: Long): Int

    /** 插入一条未服用记录（同剂量已存在未服用/暂不服用/已服用则跳过，避免重复）。resetAt>0 时忽略恢复前的已服用记录 */
    @androidx.room.Transaction
    suspend fun insertMissedIfAbsent(record: RecordEntity, resetAt: Long): Boolean {
        val existing = getByMedicineAndTimeSince(record.medicineId, record.scheduledTime, resetAt)
        if (existing != null && (existing.status == "missed" || existing.status == "pending" ||
                existing.status == "skipped" || existing.status.startsWith("taken"))) return false
        insert(record)
        return true
    }

    /** 把今天之前（scheduledTime < 今天 0 点）仍未处理的「暂不服用」暂存（pending）转成正式「未服用」（missed）；
     *  今天的临时按钮保持 pending 到次日 0 点才结算（当天只显示按钮，不生成正式记录）；返回受影响行数 */
    @Query("UPDATE records SET status = 'missed' WHERE deletedAt = 0 AND status = 'pending' AND scheduledTime < :todayStart")
    suspend fun markPendingAsMissed(todayStart: Long): Int

    /** 编辑药品时间点后：清掉今天起的未服用/暂不服用按钮（软删除）；返回受影响行数 */
    @Query("UPDATE records SET deletedAt = :now WHERE deletedAt = 0 AND medicineId = :medicineId AND scheduledTime >= :start AND status IN ('pending','missed')")
    suspend fun softDeletePendingMissedSince(medicineId: Long, start: Long, now: Long): Int

    /** 冻结该药全部现存记录的撤销资格（闹钟时间修改/进回收站时批量执行）；之后新产生的记录不受影响；返回受影响行数 */
    @Query("UPDATE records SET undoable = 0 WHERE deletedAt = 0 AND medicineId = :medicineId")
    suspend fun markUndoableDisabled(medicineId: Long): Int

    /** 硬删除超过保留期的墓碑（deletedAt>0 且早于 cutoff），GC 用，释放存储；返回删除条数 */
    @Query("DELETE FROM records WHERE deletedAt > 0 AND deletedAt < :cutoff")
    suspend fun deleteExpiredTrash(cutoff: Long): Int

    /** 按 clientUuid 合并写入（存在则沿用原 id 更新，否则插入）。clientUuid 为空时直接插入。 */
    @androidx.room.Transaction
    suspend fun upsertByClientUuid(record: RecordEntity) {
        if (record.clientUuid.isBlank()) {
            insert(record)
            return
        }
        val existing = getByClientUuid(record.clientUuid)
        if (existing == null) {
            insert(record)
        } else {
            update(record.copy(id = existing.id))
        }
    }
}
