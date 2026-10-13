/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicineDao {

    @Query("SELECT * FROM medicines WHERE isActive = 1 ORDER BY createdAt DESC")
    fun getActiveMedicines(): Flow<List<MedicineEntity>>

    @Query("SELECT * FROM medicines ORDER BY createdAt DESC")
    fun getAllMedicines(): Flow<List<MedicineEntity>>

    @Query("SELECT * FROM medicines WHERE id = :id")
    suspend fun getById(id: Long): MedicineEntity?

    @Query("SELECT * FROM medicines WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: Long): MedicineEntity?

    @Query("SELECT * FROM medicines WHERE clientUuid = :clientUuid AND clientUuid != '' LIMIT 1")
    suspend fun getByClientUuid(clientUuid: String): MedicineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(medicine: MedicineEntity): Long

    @Update
    suspend fun update(medicine: MedicineEntity)

    @Delete
    suspend fun delete(medicine: MedicineEntity)

    @Query("UPDATE medicines SET stock = stock - 1 WHERE id = :id AND stock > 0")
    suspend fun decrementStock(id: Long)

    @Query("UPDATE medicines SET stock = stock + 1 WHERE id = :id")
    suspend fun incrementStock(id: Long)

    @Query("SELECT * FROM medicines WHERE isActive = 0 AND deletedAt > 0 ORDER BY deletedAt DESC")
    fun getTrashedMedicines(): Flow<List<MedicineEntity>>

    @Query("UPDATE medicines SET isActive = 1, deletedAt = 0, stateResetAt = :resetAt WHERE id = :id")
    suspend fun restoreMedicine(id: Long, resetAt: Long)

    @Query("DELETE FROM medicines WHERE isActive = 0 AND deletedAt > 0 AND deletedAt < :cutoff")
    suspend fun deleteExpiredTrash(cutoff: Long): Int

    /** 按 clientUuid 合并写入（存在则沿用原 id 更新，否则插入）。clientUuid 为空时直接插入。 */
    @androidx.room.Transaction
    suspend fun upsertByClientUuid(medicine: MedicineEntity) {
        if (medicine.clientUuid.isBlank()) {
            insert(medicine)
            return
        }
        val existing = getByClientUuid(medicine.clientUuid)
        if (existing == null) {
            insert(medicine)
        } else {
            update(medicine.copy(id = existing.id))
        }
    }
}
