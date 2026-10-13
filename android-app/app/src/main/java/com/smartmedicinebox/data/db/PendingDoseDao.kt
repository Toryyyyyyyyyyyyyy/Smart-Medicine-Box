/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingDoseDao {

    @Query("SELECT * FROM pending_doses ORDER BY scheduledTime ASC")
    fun getAllFlow(): Flow<List<PendingDoseEntity>>

    @Query("SELECT * FROM pending_doses WHERE medicineId = :medicineId ORDER BY scheduledTime ASC LIMIT 1")
    suspend fun getEarliestForMedicine(medicineId: Long): PendingDoseEntity?

    @Query("SELECT * FROM pending_doses WHERE scheduledTime < :cutoff ORDER BY scheduledTime ASC")
    suspend fun getBefore(cutoff: Long): List<PendingDoseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dose: PendingDoseEntity): Long

    @Delete
    suspend fun delete(dose: PendingDoseEntity)

    @Query("DELETE FROM pending_doses WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
