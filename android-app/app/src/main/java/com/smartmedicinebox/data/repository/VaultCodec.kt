/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.smartmedicinebox.util.FamilyHistoryEntry
import java.util.UUID

/**
 * 端到端加密 vault 的明文 JSON 编解码（加密/解密由 MedicineRepository + Crypto 负责）。
 *
 * 这里只做「完整 entity 全集」的序列化，字段是 MedicineEntity/RecordEntity 的超集，
 * 去掉跨设备不稳定/无意义字段（本地 id、serverId），新增稳定主键与媒体引用：
 *   - clientUuid：跨设备稳定主键（合并/去重依据）
 *   - medicineUuid（记录）：关联药品的 clientUuid，替代本地 medicineId 跨设备链接
 *   - videoMediaId / ringtoneMediaId：自定义媒体的服务端 media_id 引用
 */
data class VaultMedicineDto(
    val clientUuid: String = "",
    val name: String = "",
    val dosage: String = "",
    val frequency: String = "daily",
    val timeSlots: String = "",
    val stock: Int = 0,
    val notes: String = "",
    val isActive: Boolean = true,
    val deletedAt: Long = 0,
    val hasBeenTrashed: Boolean = false,
    val stateResetAt: Long = 0,
    val alarmType: String = "sound,vibration",
    val repeatDays: String = "",
    val repeatMode: String = "weekday",
    val intervalDays: Int = 1,
    val anchorDay: Long = 0,
    val videoCrop: String? = null,
    val videoMediaId: String? = null,
    val ringtoneMediaId: String? = null,
    val createdAt: Long = 0
)

data class VaultRecordDto(
    val clientUuid: String = "",
    val medicineUuid: String = "",
    val medicineName: String = "",
    val scheduledTime: Long = 0,
    val takenTime: Long? = null,
    val status: String = "pending",
    val deletedAt: Long = 0,     // 0=未删除；>0=软删除时间戳（跨设备双向同步删除标记）
    val undoable: Boolean = true // 撤销资格：药品闹钟时间变更后冻结旧记录
)

object VaultCodec {

    private val gson = Gson()
    private val medicineType = object : TypeToken<List<VaultMedicineDto>>() {}.type
    private val recordType = object : TypeToken<List<VaultRecordDto>>() {}.type

    fun newUuid(): String = UUID.randomUUID().toString()

    fun serializeMedicines(dtos: List<VaultMedicineDto>): String = gson.toJson(dtos)

    fun parseMedicines(json: String): List<VaultMedicineDto> =
        try { gson.fromJson<List<VaultMedicineDto>>(json, medicineType) ?: emptyList() } catch (_: Exception) { emptyList() }

    fun serializeRecords(dtos: List<VaultRecordDto>): String = gson.toJson(dtos)

    fun parseRecords(json: String): List<VaultRecordDto> =
        try { gson.fromJson<List<VaultRecordDto>>(json, recordType) ?: emptyList() } catch (_: Exception) { emptyList() }

    // ---- 家属历史（账户级 vault blob：密码随账号同步，端到端加密，含删除墓碑） ----

    private val historyType = object : TypeToken<List<FamilyHistoryEntry>>() {}.type

    fun serializeHistory(entries: List<FamilyHistoryEntry>): String = gson.toJson(entries)

    fun parseHistory(json: String): List<FamilyHistoryEntry> =
        try { gson.fromJson<List<FamilyHistoryEntry>>(json, historyType) ?: emptyList() } catch (_: Exception) { emptyList() }
}
