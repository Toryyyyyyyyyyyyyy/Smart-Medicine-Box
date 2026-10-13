/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.repository

import com.smartmedicinebox.App
import com.smartmedicinebox.R
import com.smartmedicinebox.data.api.ApiClient
import com.smartmedicinebox.data.api.ApiResponse
import com.smartmedicinebox.data.api.FamilyAccessInitRequest
import com.smartmedicinebox.data.api.FamilyAccessVerifyRequest
import com.smartmedicinebox.data.api.VaultBlobUpdateRequest
import com.smartmedicinebox.data.db.MedicineEntity
import com.smartmedicinebox.data.db.PendingDoseEntity
import com.smartmedicinebox.data.db.RecordEntity
import com.smartmedicinebox.util.Crypto
import com.smartmedicinebox.util.CryptoSession
import com.smartmedicinebox.util.FamilyHistoryEntry
import com.smartmedicinebox.util.FamilyHistoryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 家属远程会话仓库：凭「目标用户id + 家人控制密码」无登录访问控制对象数据。
 *
 * 数据仅存内存（MutableStateFlow），不写入本机 Room，避免污染家属自己的药箱。
 * 全部读写经端到端加密 vault 通道：本地解密后操作，改完整体重加密推送。
 */
data class FamilyRemoteState(
    val medicines: List<MedicineEntity> = emptyList(),
    val records: List<RecordEntity> = emptyList(),
    val pendingDoses: List<PendingDoseEntity> = emptyList()
)

object FamilyRemoteRepository {

    private val api get() = ApiClient.getService()

    private val _state = MutableStateFlow(FamilyRemoteState())
    val state: StateFlow<FamilyRemoteState> = _state.asStateFlow()

    private val _medicines = MutableStateFlow<List<MedicineEntity>>(emptyList())
    private val _records = MutableStateFlow<List<RecordEntity>>(emptyList())
    private val _pendingDoses = MutableStateFlow<List<PendingDoseEntity>>(emptyList())

    val medicinesFlow: Flow<List<MedicineEntity>> = _medicines.map { list -> list.filter { it.isActive } }
    val trashedMedicinesFlow: Flow<List<MedicineEntity>> =
        _medicines.map { list -> list.filter { !it.isActive && it.deletedAt > 0 }.sortedByDescending { it.deletedAt } }
    /** 记录列表展示流：隐藏「暂不服用」暂存行（pending），0 点转为正式未服用后才出现 */
    val recordsFlow: Flow<List<RecordEntity>> = _records.map { list -> list.filter { it.deletedAt == 0L && it.status != "pending" } }
    /** 全部未删除记录（含 pending），供 DosePlanner / 服用按钮状态使用 */
    val activeRecordsFlow: Flow<List<RecordEntity>> = _records.map { list -> list.filter { it.deletedAt == 0L } }
    /** 全部记录（含软删除墓碑），供服用按钮状态使用：已服集合需计入墓碑，删除历史不改变按钮 */
    val allRecordsFlow: Flow<List<RecordEntity>> = _records
    val pendingDosesFlow: StateFlow<List<PendingDoseEntity>> = _pendingDoses.asStateFlow()

    /** 未服用剂量（补服用）：今天起仍未服用的记录（missed + pending），按应服时间升序，与本人端药品界面一致。
     *  「今天」按目标设备当前时间读数与目标用户本地时区计算。 */
    val missedRecordsFlow: Flow<List<RecordEntity>> = activeRecordsFlow.map { list ->
        val start = DosePlanner.todayStart(seniorNow(), seniorZone())
        list.filter { (it.status == "missed" || it.status == "pending") && it.scheduledTime >= start }
            .sortedBy { it.scheduledTime }
    }

    var seniorId: Long = 0
        private set
    var username: String? = null
        private set
    var viewOnly: Boolean = true
        private set
    var token: String? = null
        private set
    /** 控制对象本人设备屏幕宽高比（宽/高）；远程裁剪视频时按此比例，避免按家属自己的屏比裁错 */
    var seniorScreenAspect: Float? = null
        private set
    /** 控制对象上传到服务器的按时服药阈值（分钟）：家属远程服药时按此判定，保证与本人端一致 */
    var seniorThresholdMinutes: Int = 60
        private set
    /** 控制对象设备时区（IANA 标识）：家属远程控制中所有时间处理与显示都以此时区为准 */
    var seniorTimeZoneId: String? = null
        private set

    /** 远程控制统一使用的时间基准：目标用户本地时区（服务器未上传时退回本机时区） */
    fun seniorZone(): ZoneId = seniorTimeZoneId?.let { id ->
        try {
            ZoneId.of(id)
        } catch (_: Exception) {
            null
        }
    } ?: ZoneId.systemDefault()

    // 目标设备当前时间读数（毫秒）基准：最近一次拉取设置时的「服务器时间 + 目标端时钟偏移」，
    // 之后用单调时钟外推——远程操作的时间戳与日期判定统一以此为准，不受家属设备时钟影响
    private var seniorNowBaseEpoch = 0L
    private var seniorNowBaseElapsed = 0L

    /** 目标设备当前的时间读数（毫秒）：家属远程操作的实际服药时间、日期与状态判定统一以它为准 */
    fun seniorNow(): Long {
        if (seniorNowBaseEpoch == 0L) return System.currentTimeMillis()
        return seniorNowBaseEpoch + (android.os.SystemClock.elapsedRealtime() - seniorNowBaseElapsed)
    }

    // 最近一次 fetchRemote 的内存快照：推送时按差异识别本次操作改动的行（这些行用本地值覆盖远端），
    // 其余行采用推送时刚拉取的最新远端数据——避免旧内存快照整体覆盖丢掉本人端并发写入的数据
    private var _medicinesSnapshot: List<MedicineEntity> = emptyList()
    private var _recordsSnapshot: List<RecordEntity> = emptyList()

    // 写操作互斥：写操作（拉取→改动→推送）整体持锁；刷新（fetchRemote）在写操作进行中直接跳过。
    // 防止刷新与写操作在网络挂起点交错，覆盖内存态/快照导致改动静默丢失
    private val opsMutex = Mutex()

    val isConnected: Boolean get() = !token.isNullOrEmpty()

    private fun authHeader(): String = "Bearer ${token.orEmpty()}"

    /** 服务器地址切换时作废内存中的家属令牌：旧凭据绝不发给未验证身份的新地址 */
    fun invalidateToken() {
        token = null
    }

    /** 包装 API 调用：将非 2xx 抛出的 HttpException 转成后端 detail 的可读消息 */
    private suspend fun <T> safeCall(block: suspend () -> ApiResponse<T>): ApiResponse<T> =
        try {
            block()
        } catch (e: retrofit2.HttpException) {
            throw Exception(ApiClient.errorText(e))
        }

    /** 无登录访问：输入控制对象用户id → init → 派生验证子 → proof → verify → 建立会话 */
    suspend fun access(userCode: String, password: String) {
        val initResp = safeCall { api.familyAccessInit(FamilyAccessInitRequest(userCode)) }
        val initData = initResp.data
        if (initResp.code != 0 || initData == null) {
            throw Exception(initResp.message ?: App.instance.getString(R.string.toast_access_failed))
        }

        val salt = Crypto.b64d(initData.salt)
        val verifier = Crypto.authVerifier(password, salt)
        val challenge = Crypto.b64d(initData.challenge)
        val proof = Crypto.hmacSha256(verifier, challenge)

        val verifyResp = safeCall {
            api.familyAccessVerify(FamilyAccessVerifyRequest(userCode, Crypto.b64e(proof)))
        }
        val verifyData = verifyResp.data
        if (verifyResp.code != 0 || verifyData == null) {
            throw Exception(verifyResp.message ?: App.instance.getString(R.string.toast_access_wrong_password))
        }

        token = verifyData.accessToken
        seniorId = verifyData.seniorId
        username = verifyData.username
        viewOnly = verifyData.viewOnly ?: true
        seniorScreenAspect = initData.screenAspect
        CryptoSession.establishFamily(initData.wrappedFamilyKey, password, initData.salt)

        // 认证成功：写入历史记录（同 userCode 覆盖并置顶）
        FamilyHistoryStore.save(
            FamilyHistoryEntry(
                username = verifyData.username ?: "",
                userCode = userCode,
                password = password
            )
        )

        fetchRemote()
    }

    /** 拉取控制对象药品与记录：端到端加密 vault 通道，本地解密后渲染。
     *  写操作进行中时直接跳过（下次 onResume 再拉），避免与写操作交错覆盖其改动。 */
    suspend fun fetchRemote() {
        if (!opsMutex.tryLock()) return
        try {
            fetchRemoteLocked()
        } finally {
            opsMutex.unlock()
        }
    }

    /** 手动下拉刷新专用：等待正在进行的写操作完成后真正拉取（不跳过）；
     *  失败会抛出异常，调用方据此区分「已刷新」与「刷新失败」 */
    suspend fun fetchRemoteForRefresh() {
        opsMutex.withLock { fetchRemoteLocked() }
    }

    private suspend fun fetchRemoteLocked() {
        val key = dataKey()
        // 先取控制对象设置（按时服药阈值等）：目标离线/会话失效时在此失败，避免用旧阈值判定服药类型
        val settingsResp = safeCall { api.familyGetSettings(authHeader()) }
        if (settingsResp.code != 0) {
            throw Exception(settingsResp.message ?: App.instance.getString(R.string.toast_operation_failed))
        }
        seniorThresholdMinutes = settingsResp.data?.thresholdMinutes ?: 60
        seniorTimeZoneId = settingsResp.data?.timezone
        // 重建目标设备当前时间读数基准：服务器时间 + 目标端时钟偏移
        val serverTime = settingsResp.data?.serverTime
        if (serverTime != null && serverTime > 0) {
            seniorNowBaseEpoch = serverTime + (settingsResp.data?.clockOffsetMs ?: 0L)
            seniorNowBaseElapsed = android.os.SystemClock.elapsedRealtime()
        }
        val resp = safeCall { api.getVault(authHeader()) }
        val vault = resp.data ?: return

        val medUuidToId = HashMap<String, Long>()
        val medicines = ArrayList<MedicineEntity>()
        vault.medicines.ciphertext?.let { ct ->
            val dtos = VaultCodec.parseMedicines(String(Crypto.decrypt(Crypto.b64d(ct), key), Charsets.UTF_8))
            var syntheticId = 1L
            for (dto in dtos) {
                val entity = MedicineEntity(
                    id = syntheticId,
                    clientUuid = dto.clientUuid,
                    name = dto.name,
                    dosage = dto.dosage,
                    frequency = dto.frequency,
                    timeSlots = dto.timeSlots,
                    stock = dto.stock,
                    notes = dto.notes,
                    isActive = dto.isActive,
                    deletedAt = dto.deletedAt,
                    hasBeenTrashed = dto.hasBeenTrashed,
                    stateResetAt = dto.stateResetAt,
                    alarmType = dto.alarmType,
                    repeatDays = dto.repeatDays,
                    repeatMode = dto.repeatMode,
                    intervalDays = dto.intervalDays,
                    anchorDay = dto.anchorDay,
                    videoPath = dto.videoMediaId?.let { ensureMedia(it, "video") },
                    videoCrop = dto.videoCrop,
                    ringtoneUri = dto.ringtoneMediaId?.let { ensureMedia(it, "ringtone")?.let { p -> "file://$p" } },
                    createdAt = dto.createdAt
                )
                medicines.add(entity)
                medUuidToId[dto.clientUuid] = entity.id
                syntheticId++
            }
        }

        val records = ArrayList<RecordEntity>()
        vault.records.ciphertext?.let { ct ->
            val dtos = VaultCodec.parseRecords(String(Crypto.decrypt(Crypto.b64d(ct), key), Charsets.UTF_8))
            var syntheticId = 1L
            for (dto in dtos) {
                records.add(RecordEntity(
                    id = syntheticId++,
                    clientUuid = dto.clientUuid,
                    medicineId = medUuidToId[dto.medicineUuid] ?: 0L,
                    medicineName = dto.medicineName,
                    scheduledTime = dto.scheduledTime,
                    takenTime = dto.takenTime,
                    status = dto.status,
                    deletedAt = dto.deletedAt,
                    undoable = dto.undoable
                ))
            }
        }
        records.sortByDescending { it.takenTime ?: it.scheduledTime }

        _state.value = FamilyRemoteState(medicines.filter { it.isActive }, records.filter { it.deletedAt == 0L }, emptyList())
        _medicines.value = medicines
        _records.value = records
        _pendingDoses.value = emptyList()
        // 记录本次拉取的快照：推送合并时据此区分「本次操作改动的行」与「本人端并发改动的行」
        _medicinesSnapshot = medicines
        _recordsSnapshot = records
    }

    // ==================== 写操作（view_only 时拒绝） ====================

    private fun ensureWritable() {
        if (viewOnly) throw Exception(App.instance.getString(R.string.toast_view_only))
    }

    private fun dataKey(): ByteArray =
        CryptoSession.dataKey ?: throw Exception(App.instance.getString(R.string.toast_operation_failed))

    /** 家属点击「服用」：写前拉最新重建，用与本人端一致的 DosePlanner 计算目标（补服/门控/去重），再覆盖推送 */
    suspend fun takeNextDose(medicine: MedicineEntity) {
        ensureWritable()
        opsMutex.withLock {
            val medUuid = medicine.clientUuid
            fetchRemoteLocked()
            val now = seniorNow()
            if (medUuid.isBlank()) return
            val fresh = _medicines.value.firstOrNull { it.clientUuid == medUuid } ?: return
            val target = DosePlanner.nextTarget(fresh, _records.value, now, seniorZone()) ?: return
            if (target.makeup != null) {
                // 补服最早一条未服用/暂不服用：内部重新 fetch + 状态校验 + 扣库存 + 推送
                takePendingDoseLocked(target.makeup)
                return
            }
            // 正常服用：同剂量已有生效的已服用记录则跳过（防重复记录/重复扣库存）；恢复前（stateResetAt 之前）的记录不参与去重
            val exists = _records.value.any {
                it.deletedAt == 0L && it.medicineId == fresh.id &&
                    it.scheduledTime == target.scheduledTime && it.status.startsWith("taken") &&
                    (it.takenTime ?: 0L) >= fresh.stateResetAt
            }
            if (exists) return
            val nextId = (_records.value.maxOfOrNull { it.id } ?: 0L) + 1
            _records.value = _records.value + RecordEntity(
                id = nextId,
                clientUuid = VaultCodec.newUuid(),
                medicineId = fresh.id,
                medicineName = fresh.name,
                scheduledTime = target.scheduledTime,
                takenTime = now,
                // 用控制对象上传到服务器的阈值判定，保证与本人端对同一操作得出相同的服药类型
                status = MedicineRepository.determineStatus(target.scheduledTime, now, seniorThresholdMinutes)
                // undoable 用默认 true：新记录按正常规则可撤销；冻结只由改闹钟时间/进回收站时批量执行
            )
            val meds = _medicines.value.toMutableList()
            val idx = meds.indexOfFirst { it.id == fresh.id }
            if (idx >= 0) meds[idx] = fresh.copy(stock = (fresh.stock - 1).coerceAtLeast(0))
            _medicines.value = meds
            pushAll()
        }
    }

    /** 补服某一条未服用/暂不服用记录：写前拉最新重建，按 clientUuid 定位并转为已服用 */
    suspend fun takePendingDose(record: RecordEntity) {
        ensureWritable()
        opsMutex.withLock { takePendingDoseLocked(record) }
    }

    private suspend fun takePendingDoseLocked(record: RecordEntity) {
        val uuid = record.clientUuid
        fetchRemoteLocked()
        val now = seniorNow()
        val fresh = if (uuid.isNotBlank()) _records.value.firstOrNull { it.clientUuid == uuid && it.deletedAt == 0L } else null
        // 状态守卫：非未服用/暂不服用（已被补服/撤销）不再处理，避免重复扣库存
        if (fresh == null || (fresh.status != "missed" && fresh.status != "pending")) return
        // 用控制对象上传到服务器的阈值判定，保证与本人端一致
        val status = MedicineRepository.determineStatus(fresh.scheduledTime, now, seniorThresholdMinutes)
        _records.value = _records.value.map {
            if (it.clientUuid == uuid) it.copy(status = status, takenTime = now) else it
        }
        val meds = _medicines.value.toMutableList()
        val idx = meds.indexOfFirst { it.id == fresh.medicineId }
        if (idx >= 0) meds[idx] = meds[idx].copy(stock = (meds[idx].stock - 1).coerceAtLeast(0))
        _medicines.value = meds
        pushAll()
    }

    /** 撤销一条已服用记录：写前拉最新重建，按 clientUuid 软删除记录 + 恢复库存，再覆盖推送 */
    suspend fun undoTaken(record: RecordEntity) {
        ensureWritable()
        opsMutex.withLock {
            val uuid = record.clientUuid
            fetchRemoteLocked()
            val now = seniorNow()
        // 重建后按稳定 clientUuid 定位；若记录已被控制对象端删除/GC 则无需撤销
        val fresh = if (uuid.isNotBlank()) _records.value.firstOrNull { it.clientUuid == uuid } else null
        if (fresh == null) return
        // 撤销墓碑：status 改为 undone，区别于删除墓碑（剂量回到未处理，按钮/闹钟可恢复）
        _records.value = _records.value.map {
            if (it.clientUuid == uuid) it.copy(deletedAt = now, status = "undone") else it
        }
        val meds = _medicines.value.toMutableList()
        val idx = meds.indexOfFirst { it.id == fresh.medicineId }
        if (idx >= 0) meds[idx] = meds[idx].copy(stock = meds[idx].stock + 1)
        _medicines.value = meds
        // 已过应服时间 → 转为「未服用」按钮（pending，等同点了暂不服用；0 点才正式记为未服用，不立即进记录），跨设备同步。
        // 对齐本人端 undo 的 insertMissedIfAbsent：同剂量（药品 + 应服时间）已有未服用/暂不服用/已服用记录则不再新增，避免重复；
        // 恢复前（stateResetAt 之前）的已服记录不参与去重（恢复后视为全新药品）
        if (fresh.scheduledTime <= now) {
            val resetAt = _medicines.value.firstOrNull { it.id == fresh.medicineId }?.stateResetAt ?: 0L
            val alreadyExists = _records.value.any {
                it.deletedAt == 0L && it.medicineId == fresh.medicineId &&
                    it.scheduledTime == fresh.scheduledTime &&
                    (it.status == "missed" || it.status == "pending" ||
                        (it.status.startsWith("taken") && (it.takenTime ?: 0L) >= resetAt))
            }
            if (!alreadyExists) {
                val nextId = (_records.value.maxOfOrNull { it.id } ?: 0L) + 1
                _records.value = _records.value + RecordEntity(
                    id = nextId,
                    clientUuid = VaultCodec.newUuid(),
                    medicineId = fresh.medicineId,
                    medicineName = fresh.medicineName,
                    scheduledTime = fresh.scheduledTime,
                    takenTime = null,
                    status = "pending"
                )
            }
        }
        pushAll()
        }
    }

    /** 删除多条记录（软删除）：写前拉最新重建，按 clientUuid 标记 deletedAt，再覆盖推送 */
    suspend fun deleteRecords(ids: List<Long>) {
        ensureWritable()
        opsMutex.withLock {
            val uuids = _records.value.filter { it.id in ids }.map { it.clientUuid }.toSet()
            fetchRemoteLocked()
            val now = seniorNow()
        _records.value = _records.value.map {
            if (it.clientUuid in uuids) it.copy(deletedAt = now) else it
        }
        pushAll()
        }
    }

    /** 家属添加药品（端到端加密：写入本地态并整块推送，含本机媒体加密上传） */
    suspend fun addMedicine(
        name: String,
        dosage: String,
        frequency: String,
        timeSlots: List<String>,
        stock: Int,
        notes: String,
        alarmType: String,
        videoPath: String?,
        videoCrop: String?,
        ringtoneUri: String?,
        repeatDays: String,
        repeatMode: String,
        intervalDays: Int,
        anchorDay: Long
    ) {
        ensureWritable()
        opsMutex.withLock {
            fetchRemoteLocked()
        val nextId = (_medicines.value.maxOfOrNull { it.id } ?: 0L) + 1
        val entity = MedicineEntity(
            id = nextId,
            clientUuid = VaultCodec.newUuid(),
            name = name,
            dosage = dosage,
            frequency = frequency,
            timeSlots = timeSlots.joinToString(","),
            stock = stock,
            notes = notes,
            alarmType = alarmType,
            videoPath = videoPath,
            videoCrop = videoCrop,
            ringtoneUri = ringtoneUri,
            repeatDays = repeatDays,
            repeatMode = repeatMode,
            intervalDays = intervalDays,
            anchorDay = anchorDay,
            isActive = true
        )
        _medicines.value = listOf(entity) + _medicines.value
        pushAll()
        }
    }

    /** 家属编辑药品：更新本地态（保留 clientUuid 与频率），再整块推送 */
    suspend fun updateMedicine(
        id: Long,
        name: String,
        dosage: String,
        timeSlots: List<String>,
        stock: Int,
        notes: String,
        alarmType: String,
        videoPath: String?,
        videoCrop: String?,
        ringtoneUri: String?,
        repeatDays: String,
        repeatMode: String,
        intervalDays: Int,
        anchorDay: Long
    ) {
        ensureWritable()
        opsMutex.withLock {
            val uuid = _medicines.value.firstOrNull { it.id == id }?.clientUuid ?: ""
            fetchRemoteLocked()
            val meds = _medicines.value.toMutableList()
            val idx = meds.indexOfFirst { it.clientUuid == uuid && uuid.isNotBlank() }
            if (idx < 0) return
            val newTimeSlots = timeSlots.joinToString(",")
            val timeSlotsChanged = meds[idx].timeSlots != newTimeSlots
            meds[idx] = meds[idx].copy(
                name = name,
                dosage = dosage,
                timeSlots = newTimeSlots,
                stock = stock,
                notes = notes,
                alarmType = alarmType,
                videoPath = videoPath,
                videoCrop = videoCrop,
                ringtoneUri = ringtoneUri,
                repeatDays = repeatDays,
                repeatMode = repeatMode,
                intervalDays = intervalDays,
                anchorDay = anchorDay
            )
            _medicines.value = meds
            // 与本人端一致：仅当时间点实际变化时，清除该药今天起的未服用/暂不服用按钮（软删除），并冻结修改前产生的全部记录不可撤销
            if (timeSlotsChanged) {
                val start = DosePlanner.todayStart(System.currentTimeMillis(), seniorZone())
                val now = seniorNow()
                val medicineId = meds[idx].id
                _records.value = _records.value.map {
                    if (it.deletedAt == 0L && it.medicineId == medicineId) {
                        if ((it.status == "missed" || it.status == "pending") && it.scheduledTime >= start) {
                            it.copy(deletedAt = now)  // 与本人端 softDeletePendingMissedSince 一致：软删除的墓碑不冻结 undoable
                        } else {
                            it.copy(undoable = false)  // 时间点变化：修改前产生的记录全部冻结（不分过去/未来）
                        }
                    } else {
                        it
                    }
                }
            }
            pushAll()
        }
    }

    /** 按本地合成 id 取药品（供编辑页回显） */
    fun getMedicine(id: Long): MedicineEntity? = _medicines.value.firstOrNull { it.id == id }

    /** 家属删除药品：写前拉最新重建，按 clientUuid 软删除（移入回收站），冻结该药全部现存记录的撤销资格（历史记录保留），再覆盖推送 */
    suspend fun deleteMedicine(medicine: MedicineEntity) {
        ensureWritable()
        opsMutex.withLock {
            val uuid = medicine.clientUuid
            fetchRemoteLocked()
            val meds = _medicines.value.toMutableList()
            val idx = meds.indexOfFirst { it.clientUuid == uuid && uuid.isNotBlank() }
            if (idx < 0) return
            val medicineId = meds[idx].id
            val now = seniorNow()
            meds[idx] = meds[idx].copy(isActive = false, deletedAt = now, hasBeenTrashed = true)
            _medicines.value = meds
            // 删除闹钟：历史记录保留且仍可删除，但进入回收站即冻结该药全部现存记录的撤销资格；恢复后新记录按正常规则处理
            _records.value = _records.value.map {
                if (it.deletedAt == 0L && it.medicineId == medicineId) it.copy(undoable = false) else it
            }
            pushAll()
        }
    }

    /** 家属恢复回收站中的药品：写前拉最新重建，按 clientUuid 置回 active，清除临时状态（未服用按钮等），历史记录保留且仍不可撤销 */
    suspend fun restoreMedicine(medicine: MedicineEntity) {
        ensureWritable()
        opsMutex.withLock {
            val uuid = medicine.clientUuid
            fetchRemoteLocked()
            val meds = _medicines.value.toMutableList()
            val idx = meds.indexOfFirst { it.clientUuid == uuid && uuid.isNotBlank() }
            if (idx < 0) return
            val medicineId = meds[idx].id
            val now = seniorNow()
            // 恢复 = 视为全新药品：stateResetAt 之前的服药进度不计入按钮状态；清除今天起的未服用/暂不服用按钮
            meds[idx] = meds[idx].copy(isActive = true, deletedAt = 0, stateResetAt = now)
            _medicines.value = meds
            // 恢复 = 重新初始化：清除今天起的未服用/暂不服用按钮；历史记录保留（不可撤销状态不回恢复）
            val start = DosePlanner.todayStart(System.currentTimeMillis(), seniorZone())
            _records.value = _records.value.map {
                if (it.deletedAt == 0L && it.medicineId == medicineId &&
                    (it.status == "missed" || it.status == "pending") && it.scheduledTime >= start) {
                    it.copy(deletedAt = now)
                } else it
            }
            pushAll()
        }
    }

    /** 家属永久删除回收站中的药品：先拉最新，再本地移除并推送同步删除结果。
     *  拉取失败在改动前抛出（目标离线时药品不会从界面消失）；推送失败 pushAll 回滚使药品重新出现。 */
    suspend fun hardDeleteMedicine(medicine: MedicineEntity) {
        ensureWritable()
        opsMutex.withLock {
            val uuid = medicine.clientUuid
            if (uuid.isBlank()) return
            // 1. 先拉最新（失败抛给界面，此时尚未改动本地态，不假装删除成功）
            fetchRemoteLocked()
            // 2. 本地移除（推送失败时 pushAll 回滚到快照，药品会重新出现在回收站）
            _medicines.value = _medicines.value.filterNot { it.clientUuid == uuid }
            // 3. 推送本地删除结果
            pushAll()
        }
    }

    // ---- vault 推送（合并：写前已 fetchRemote 重建为最新，推送时再以刚拉取的远端为底，
    //      仅覆盖本次操作改动的行，避免旧快照整体覆盖丢掉本人端并发写入的数据） ----

    /** 推送两个 blob；失败时回滚内存态到本次操作前并抛出——界面不显示未生效的改动，不假装成功 */
    private suspend fun pushAll() {
        try {
            pushMedicines()
            pushRecords()
        } catch (e: Exception) {
            _medicines.value = _medicinesSnapshot
            _records.value = _recordsSnapshot
            throw e
        }
    }

    private suspend fun pushMedicines() {
        val key = dataKey()
        for (attempt in 0 until 2) {
            val vaultResp = try {
                api.getVault(authHeader())
            } catch (e: HttpException) {
                throw Exception(ApiClient.errorText(e))
            }
            val vault = vaultResp.data ?: return
            val remoteDtos = vault.medicines.ciphertext?.let {
                VaultCodec.parseMedicines(String(Crypto.decrypt(Crypto.b64d(it), key), Charsets.UTF_8))
            } ?: emptyList()
            // 本次操作改动的行（含新增）；本次永久删除的行从远端移除
            val touched = _medicines.value.filter { m ->
                _medicinesSnapshot.firstOrNull { it.clientUuid == m.clientUuid } != m
            }.map { it.clientUuid }.toSet()
            val removed = _medicinesSnapshot.map { it.clientUuid }.toSet() -
                _medicines.value.map { it.clientUuid }.toSet()
            val snapshotUuids = _medicinesSnapshot.map { it.clientUuid }.toSet()
            val localByUuid = _medicines.value.associateBy { it.clientUuid }
            val remoteUuids = remoteDtos.map { it.clientUuid }.toSet()
            val merged = remoteDtos.mapNotNull { dto ->
                when {
                    dto.clientUuid in removed -> null
                    dto.clientUuid in touched -> localByUuid[dto.clientUuid]?.let { medicineDto(it) }
                    else -> dto
                }
            }.toMutableList()
            for (m in _medicines.value) {
                // 只补本次操作新增的行（快照中不存在的）；快照里有但远端没有的行是本人端已永久删除的，删除优先，不复活
                if (m.clientUuid !in remoteUuids && m.clientUuid !in snapshotUuids) merged.add(medicineDto(m))
            }
            val ct = Crypto.b64e(Crypto.encrypt(VaultCodec.serializeMedicines(merged).toByteArray(Charsets.UTF_8), key))
            try {
                api.putMedicinesVault(authHeader(), VaultBlobUpdateRequest(ct, vault.medicines.version))
                return
            } catch (e: HttpException) {
                if (e.code() == 409 && attempt == 0) continue
                throw Exception(ApiClient.errorText(e))
            }
        }
    }

    private suspend fun medicineDto(m: MedicineEntity): VaultMedicineDto = VaultMedicineDto(
        clientUuid = m.clientUuid,
        name = m.name,
        dosage = m.dosage,
        frequency = m.frequency,
        timeSlots = m.timeSlots,
        stock = m.stock,
        notes = m.notes,
        isActive = m.isActive,
        deletedAt = m.deletedAt,
        hasBeenTrashed = m.hasBeenTrashed,
        stateResetAt = m.stateResetAt,
        alarmType = m.alarmType,
        repeatDays = m.repeatDays,
        repeatMode = m.repeatMode,
        intervalDays = m.intervalDays,
        anchorDay = m.anchorDay,
        videoCrop = m.videoCrop,
        videoMediaId = uploadOrResolveMedia(m.videoPath, "video"),
        ringtoneMediaId = uploadOrResolveMedia(m.ringtoneUri, "ringtone"),
        createdAt = m.createdAt
    )

    private suspend fun pushRecords() {
        val key = dataKey()
        for (attempt in 0 until 2) {
            val vaultResp = try {
                api.getVault(authHeader())
            } catch (e: HttpException) {
                throw Exception(ApiClient.errorText(e))
            }
            val vault = vaultResp.data ?: return
            val remoteDtos = vault.records.ciphertext?.let {
                VaultCodec.parseRecords(String(Crypto.decrypt(Crypto.b64d(it), key), Charsets.UTF_8))
            } ?: emptyList()
            // 本次操作改动的行（含新增）用本地值覆盖远端；其余行（含本人端刚推上来的「未服用」记录）保留远端值
            val touched = _records.value.filter { r ->
                _recordsSnapshot.firstOrNull { it.clientUuid == r.clientUuid } != r
            }.map { it.clientUuid }.toSet()
            val snapshotUuids = _recordsSnapshot.map { it.clientUuid }.toSet()
            val idToUuid = _medicines.value.associate { it.id to it.clientUuid }
            val localByUuid = _records.value.associateBy { it.clientUuid }
            val remoteUuids = remoteDtos.map { it.clientUuid }.toSet()
            val merged = remoteDtos.map { dto ->
                if (dto.clientUuid in touched) localByUuid[dto.clientUuid]?.let { recordDto(it, idToUuid) } ?: dto else dto
            }.toMutableList()
            for (r in _records.value) {
                // 只补本次操作新增的行（快照中不存在的）；快照里有但远端没有的行是本人端已永久删除/GC 的，删除优先，不复活
                if (r.clientUuid !in remoteUuids && r.clientUuid !in snapshotUuids) merged.add(recordDto(r, idToUuid))
            }
            val ct = Crypto.b64e(Crypto.encrypt(VaultCodec.serializeRecords(merged).toByteArray(Charsets.UTF_8), key))
            try {
                api.putRecordsVault(authHeader(), VaultBlobUpdateRequest(ct, vault.records.version))
                return
            } catch (e: HttpException) {
                if (e.code() == 409 && attempt == 0) continue
                throw Exception(ApiClient.errorText(e))
            }
        }
    }

    private fun recordDto(r: RecordEntity, idToUuid: Map<Long, String>): VaultRecordDto = VaultRecordDto(
        clientUuid = r.clientUuid,
        medicineUuid = idToUuid[r.medicineId] ?: "",
        medicineName = r.medicineName,
        scheduledTime = r.scheduledTime,
        takenTime = r.takenTime,
        status = r.status,
        deletedAt = r.deletedAt,
        undoable = r.undoable
    )

    /** 下载并解密 media 到本地，返回绝对路径（文件名 vault_<mediaId>.ext 以便回推时还原 media_id） */
    private suspend fun ensureMedia(mediaId: String, kind: String): String? {
        val ext = if (kind == "video") "mp4" else "mp3"
        val dir = File(App.instance.filesDir, if (kind == "video") "videos" else "ringtones").apply { mkdirs() }
        val target = File(dir, "vault_${mediaId}.$ext")
        if (target.exists()) return target.absolutePath
        val key = CryptoSession.mediaKey ?: return null
        val resp = api.getVaultMedia(authHeader(), mediaId)
        if (!resp.isSuccessful) return null
        val plain = Crypto.decrypt(resp.body()?.bytes() ?: return null, key)
        target.writeBytes(plain)
        return target.absolutePath
    }

    /** 媒体引用：已下载的 vault 文件复用其 media_id，新选的本地文件加密上传后返回内容哈希 id */
    private suspend fun uploadOrResolveMedia(path: String?, kind: String): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("content://")) return null  // 系统铃声不跨设备
        val realPath = if (path.startsWith("file://")) path.removePrefix("file://") else path
        val file = File(realPath)
        if (!file.exists()) return null
        val name = file.name
        if (name.startsWith("vault_")) {
            val existing = name.removePrefix("vault_").substringBefore('.').takeIf { it.isNotBlank() }
            if (existing != null) return existing
        }
        val mediaId = contentMediaId(file)
        uploadEncryptedMedia(mediaId, file, kind)
        return mediaId
    }

    /** 加密并上传媒体到控制对象 vault（家属用相同 mediaKey） */
    private suspend fun uploadEncryptedMedia(mediaId: String, file: File, kind: String) {
        val key = CryptoSession.mediaKey ?: return
        val enc = Crypto.encrypt(file.readBytes(), key)
        val contentType = if (kind == "video") "video/mp4" else "audio/mpeg"
        val part = MultipartBody.Part.createFormData(
            "file", file.name, enc.toRequestBody(contentType.toMediaTypeOrNull())
        )
        val resp = api.uploadVaultMedia(
            authHeader(),
            mediaId.toRequestBody("text/plain".toMediaTypeOrNull()),
            contentType.toRequestBody("text/plain".toMediaTypeOrNull()),
            file.name.toRequestBody("text/plain".toMediaTypeOrNull()),
            part
        )
        if (resp.code != 0) throw Exception(resp.message ?: App.instance.getString(R.string.toast_operation_failed))
    }

    /** 内容 SHA-256 截断 36 位作为稳定 media_id（与控制对象端一致，幂等） */
    private fun contentMediaId(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hash = md.digest(file.readBytes())
        return hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(36)
    }

    /** 结束家属会话，清空内存态与密钥 */
    fun clear() {
        token = null
        seniorId = 0
        username = null
        viewOnly = true
        seniorScreenAspect = null
        seniorThresholdMinutes = 60
        seniorTimeZoneId = null
        seniorNowBaseEpoch = 0L
        seniorNowBaseElapsed = 0L
        CryptoSession.clear()
        _state.value = FamilyRemoteState()
        _medicines.value = emptyList()
        _records.value = emptyList()
        _pendingDoses.value = emptyList()
        _medicinesSnapshot = emptyList()
        _recordsSnapshot = emptyList()
    }
}
