/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.repository

import android.widget.Toast
import com.smartmedicinebox.App
import com.smartmedicinebox.R
import com.smartmedicinebox.alarm.AlarmScheduler
import com.smartmedicinebox.data.api.ApiClient
import com.smartmedicinebox.data.api.ApiResponse
import com.smartmedicinebox.data.api.ChangePasswordRequest
import com.smartmedicinebox.data.api.ClockOffsetRequest
import com.smartmedicinebox.data.api.FamilyControlSetRequest
import com.smartmedicinebox.data.api.LoginChallengeRequest
import com.smartmedicinebox.data.api.LoginData
import com.smartmedicinebox.data.api.LoginRequest
import com.smartmedicinebox.data.api.RegisterRequest
import com.smartmedicinebox.data.api.ScreenAspectRequest
import com.smartmedicinebox.data.api.ThresholdRequest
import com.smartmedicinebox.data.api.TimezoneRequest
import com.smartmedicinebox.data.api.UpdateProfileRequest
import com.smartmedicinebox.data.api.VaultBlobUpdateRequest
import com.smartmedicinebox.data.db.MedicineEntity
import com.smartmedicinebox.data.db.RecordEntity
import com.smartmedicinebox.sync.SyncScheduler
import com.smartmedicinebox.util.Crypto
import com.smartmedicinebox.util.CryptoSession
import com.smartmedicinebox.util.FamilyHistoryStore
import com.smartmedicinebox.util.Prefs
import com.smartmedicinebox.util.SingleFlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * 仓库层：本地 Room 为主，后端同步为辅
 */
object MedicineRepository {

    private val medicineDao get() = App.instance.database.medicineDao()
    private val recordDao get() = App.instance.database.recordDao()
    private val api get() = ApiClient.getService()

    private fun authHeader(): String = "Bearer ${Prefs.token.orEmpty()}"

    // ==================== 注册 / 登录 ====================

    /** 登录证明两步流程：取盐与一次性挑战 → 本地计算 HMAC 证明 → 登录。明文密码永不出设备。 */
    private suspend fun loginWithProof(userCode: String, password: String): Response<ApiResponse<LoginData>> {
        val challengeResp = api.loginChallenge(LoginChallengeRequest(userCode))
        if (!challengeResp.isSuccessful) throw Exception(ApiClient.errorText(challengeResp))
        val challengeData = challengeResp.body()?.data ?: throw Exception(
            App.instance.getString(R.string.error_login_failed)
        )
        val proof = Crypto.loginProof(
            password, Crypto.hexToBytes(challengeData.salt), Crypto.b64d(challengeData.challenge)
        )
        return api.login(LoginRequest(userCode, Crypto.b64e(proof)))
    }

    /** 用户名+密码注册（即注册即登录）：客户端本地派生密码盐与 PBKDF2 哈希，明文密码不上传 */
    suspend fun register(username: String, password: String) {
        val salt = Crypto.randomSalt()
        val hash = Crypto.loginHash(password, salt)
        val resp = api.register(RegisterRequest(username, Crypto.hex(salt), Crypto.hex(hash)))
        if (!resp.isSuccessful) throw Exception(ApiClient.errorText(resp))
        val body = resp.body()
        if (body == null || body.code != 0 || body.data == null) {
            throw Exception(body?.message ?: App.instance.getString(R.string.error_login_failed))
        }
        saveSession(body.data, password)
    }

    /** 用户id+密码登录：明文密码只在本地派生验证材料 */
    suspend fun login(userCode: String, password: String) {
        val resp = loginWithProof(userCode, password)
        if (!resp.isSuccessful) throw Exception(ApiClient.errorText(resp))
        val body = resp.body()
        if (body == null || body.code != 0 || body.data == null) {
            throw Exception(body?.message ?: App.instance.getString(R.string.error_login_failed))
        }
        saveSession(body.data, password)
    }

    /** 把登录/注册响应写入本地登录态，并派生端到端加密密钥 */
    private fun saveSession(data: com.smartmedicinebox.data.api.LoginData, password: String) {
        Prefs.token = data.accessToken
        Prefs.userId = data.user.id
        Prefs.userCode = data.user.userCode
        Prefs.username = data.user.username
        Prefs.familyControlEnabled = data.user.familyControlEnabled ?: false
        Prefs.familyControlViewOnly = data.user.familyControlViewOnly ?: false
        Prefs.encEnabled = data.user.encEnabled ?: false

        // 数据恒用登录密码加密：登录密码派生登录密钥并直接生效（家人控制只包裹密钥，不再锁住本人设备）。
        // 旧账号（家人控制已开启但服务器尚无包裹密钥）需一次性迁移，暂不激活，交给 MainActivity 触发。
        data.user.encSalt?.let { CryptoSession.establishLogin(password, it) }
        if (!(Prefs.familyControlEnabled && data.user.encWrappedFamilyKey.isNullOrBlank())) {
            CryptoSession.activateLogin()
        }

        // 登录/注册成功即注册周期同步任务（仅家人控制开启时才有同步价值）
        if (Prefs.isLoggedIn && Prefs.familyControlEnabled) SyncScheduler.enqueuePeriodicSync()
    }

    // ==================== 账号 ====================

    /** 拉取当前用户资料，回填 Prefs（用户id/用户名 / 家人控制开关 / 加密标志） */
    suspend fun fetchProfile() {
        if (!Prefs.isLoggedIn) return
        val resp = api.getProfile(authHeader())
        if (resp.code != 0 || resp.data == null) return
        Prefs.userCode = resp.data.userCode
        Prefs.username = resp.data.username
        Prefs.familyControlEnabled = resp.data.familyControlEnabled ?: false
        Prefs.familyControlViewOnly = resp.data.familyControlViewOnly ?: false
        Prefs.encEnabled = resp.data.encEnabled ?: false
    }

    /** 自定义用户名 */
    suspend fun updateUsername(username: String) {
        if (!Prefs.isLoggedIn) throw Exception(App.instance.getString(R.string.toast_login_first))
        val resp = api.updateProfile(authHeader(), UpdateProfileRequest(username))
        if (resp.code != 0) throw Exception(resp.message ?: "更新失败")
        Prefs.username = resp.data?.username ?: username
    }

    /** 注销账号：仅删除服务器账号及其全部数据；本地药品/记录等数据保留（回退到离线使用） */
    suspend fun deleteAccount() {
        if (!Prefs.isLoggedIn) return
        val resp = api.deleteAccount(authHeader())
        if (resp.code != 0) throw Exception(resp.message ?: "注销失败")
        // 仅清除登录态与周期任务，本地数据不清除
        Prefs.logout()
        SyncScheduler.cancelPeriodicSync()
    }

    // ==================== 家人控制 ====================

    /** 设置/更新家人控制（开启需密码，关闭仅关开关）；数据恒用登录密码加密，家人密码只包裹登录密钥。
     *  鉴权验证子 authVerifier = PBKDF2(家人密码, salt||"auth") 在本地派生后上传，明文密码永不上传服务器 */
    suspend fun setFamilyControl(password: String?, viewOnly: Boolean?, enabled: Boolean?) {
        if (!Prefs.isLoggedIn) throw Exception(App.instance.getString(R.string.toast_login_first))
        // 开启/设置密码时：本地派生鉴权验证子 + 用家人密码包裹登录派生数据/媒体密钥；数据本身不重加密
        var wrappedFamilyKey: String? = null
        var authVerifier: String? = null
        if (enabled != false && password != null) {
            val salt = CryptoSession.salt ?: throw Exception("密钥未初始化")
            val dataKey = CryptoSession.dataKey ?: throw Exception("密钥未初始化")
            val mediaKey = CryptoSession.mediaKey ?: throw Exception("密钥未初始化")
            wrappedFamilyKey = Crypto.b64e(Crypto.encrypt(dataKey + mediaKey, Crypto.wrapKey(password, salt)))
            authVerifier = Crypto.b64e(Crypto.authVerifier(password, salt))
        }
        val resp = try {
            api.setFamilyControl(authHeader(), FamilyControlSetRequest(authVerifier, viewOnly, enabled, wrappedFamilyKey))
        } catch (e: HttpException) {
            // 把服务器返回的具体原因（如登录已失效）原样抛给界面，而不是吞掉
            throw Exception(ApiClient.errorText(e))
        }
        if (resp.code != 0) throw Exception(resp.message ?: "更新失败")
        val data = resp.data
        Prefs.familyControlEnabled = data?.familyControlEnabled ?: false
        Prefs.familyControlViewOnly = data?.familyControlViewOnly ?: false
        // 开关变化时调整周期同步任务：开启才注册（内部按登录+开关条件自检），关闭/注销立即取消
        if (Prefs.familyControlEnabled) SyncScheduler.enqueuePeriodicSync() else SyncScheduler.cancelPeriodicSync()
        // 数据密钥不变，无需切换/重加密
    }

    /** 更改登录密码（仅旧密码方式）：数据恒用登录密码加密，需旧登录密钥解密 → 新登录密钥重加密；
     *  家人控制开启时还需用家人密码重新包裹登录密钥（否则家属无法再解包）。 */
    suspend fun changeLoginPassword(oldPassword: String, newPassword: String, familyPassword: String? = null) {
        if (!Prefs.isLoggedIn) throw Exception(App.instance.getString(R.string.toast_login_first))
        val salt = CryptoSession.salt ?: throw Exception("密钥未初始化")
        val newDataKey = Crypto.dataKey(newPassword, salt)
        val newMediaKey = Crypto.mediaKey(newPassword, salt)
        // 数据恒用登录密码加密：改登录密码需重加密全部数据
        reencryptVaultWithKeys(
            Crypto.dataKey(oldPassword, salt), Crypto.mediaKey(oldPassword, salt),
            newDataKey, newMediaKey
        )
        // 家人控制开启时，用家人密码重新包裹新登录密钥（服务器只存包裹后的密文）
        if (Prefs.familyControlEnabled) {
            val fp = familyPassword ?: throw Exception("请提供家人控制密码")
            val wrappedFamilyKey = Crypto.b64e(Crypto.encrypt(newDataKey + newMediaKey, Crypto.wrapKey(fp, salt)))
            val wrapResp = api.setFamilyControl(authHeader(), FamilyControlSetRequest(null, null, null, wrappedFamilyKey))
            if (wrapResp.code != 0) throw Exception(wrapResp.message ?: "家人密钥更新失败")
        }
        // 旧密码以证明校验，新密码以本地派生的盐+哈希提交（明文密码永不出设备）
        val challengeResp = api.loginChallenge(LoginChallengeRequest(Prefs.userCode.orEmpty()))
        if (!challengeResp.isSuccessful) throw Exception(ApiClient.errorText(challengeResp))
        val challengeData = challengeResp.body()?.data ?: throw Exception("无法获取登录挑战")
        val oldProof = Crypto.loginProof(
            oldPassword, Crypto.hexToBytes(challengeData.salt), Crypto.b64d(challengeData.challenge)
        )
        val newSalt = Crypto.randomSalt()
        val newHash = Crypto.loginHash(newPassword, newSalt)
        val resp = api.changePassword(
            authHeader(), ChangePasswordRequest(Crypto.b64e(oldProof), Crypto.hex(newSalt), Crypto.hex(newHash))
        )
        if (resp.code != 0) throw Exception(resp.message ?: "更改失败")
        // 刷新内存中的登录密钥（盐不变），并直接重新生效
        CryptoSession.establishLogin(newPassword, Crypto.b64e(salt))
        CryptoSession.activateLogin()
    }

    // ==================== 端到端加密 vault ====================

    /** 数据同步入口：数据恒加密，始终走 vault */
    suspend fun syncAll() {
        if (!Prefs.isLoggedIn) return
        syncIfReconnected()
        syncVault()
        heartbeat()
        syncScreenAspect()
        syncThreshold()
        syncTimezone()
        syncClockOffset()
    }

    /**
     * 断网期间有本地改动（wasOffline）时：恢复联网后立即推送。
     * 按同步基线三方合并：断网期间家属端在心跳窗口内改动过的行（远端与基线不同），
     * 冲突以家属端数据为准（覆盖服务器对应数据）；家属端未改动的行仍以本地为准；
     * 双方各自新增的行都保留。无基线（升级后首次重连）时保持旧行为（本地整体覆盖）。
     * 推送成功后清除标记，恢复正常双向合并。
     */
    private suspend fun syncIfReconnected() {
        if (!Prefs.wasOffline) return
        pushReconnect("medicines")
        pushReconnect("records")
        Prefs.wasOffline = false
    }

    /** 重连合并推送单个 blob（与 vaultMutex 互斥；失败按既有规则标记离线/会话失效） */
    private suspend fun pushReconnect(kind: String) {
        if (!Prefs.isLoggedIn) return
        vaultMutex.withLock {
            when (kind) {
                "medicines" -> pushMedicinesReconnectLocked()
                "records" -> pushRecordsReconnectLocked()
            }
        }
    }

    /** 重连三方合并（base=上次同步点本地快照，local=本人离线改动，remote=服务器现状）：
     *  家属端改动过的行（remote 与 base 内容不同，含家属删除）以家属端为准；
     *  家属端未改动的行以本人本地为准（含本人删除）；双方新增各自保留。 */
    private fun <T> mergeReconnect(
        base: List<T>, local: List<T>, remote: List<T>,
        keyOf: (T) -> String, contentEquals: (T, T) -> Boolean
    ): List<T> {
        val baseMap = base.associateBy(keyOf)
        val localMap = local.associateBy(keyOf)
        val remoteMap = remote.associateBy(keyOf)
        val result = LinkedHashMap<String, T>()
        for (uuid in (baseMap.keys + localMap.keys + remoteMap.keys)) {
            val b = baseMap[uuid]
            val l = localMap[uuid]
            val r = remoteMap[uuid]
            when {
                b == null -> (r ?: l)?.let { result[uuid] = it }
                r == null || !contentEquals(b, r) -> r?.let { result[uuid] = it }
                else -> l?.let { result[uuid] = it }
            }
        }
        return result.values.toList()
    }

    /** 行内容比较（忽略媒体 id：媒体随整行输赢，不单独参与冲突判定） */
    private fun medicineContentEquals(a: VaultMedicineDto, b: VaultMedicineDto): Boolean =
        a.clientUuid == b.clientUuid &&
            a.name == b.name && a.dosage == b.dosage && a.frequency == b.frequency &&
            a.timeSlots == b.timeSlots && a.stock == b.stock && a.notes == b.notes &&
            a.isActive == b.isActive && a.deletedAt == b.deletedAt &&
            a.hasBeenTrashed == b.hasBeenTrashed && a.stateResetAt == b.stateResetAt &&
            a.alarmType == b.alarmType && a.repeatDays == b.repeatDays &&
            a.repeatMode == b.repeatMode && a.intervalDays == b.intervalDays &&
            a.anchorDay == b.anchorDay && a.videoCrop == b.videoCrop && a.createdAt == b.createdAt

    private suspend fun pushMedicinesReconnectLocked() {
        val key = requireDataKey()
        try {
            for (attempt in 0 until 2) {
                val vaultResp = api.getVault(authHeader())
                if (vaultResp.code != 0) throw Exception(vaultResp.message ?: "拉取失败")
                val vault = vaultResp.data ?: return
                val remoteDtos = vault.medicines.ciphertext?.let {
                    VaultCodec.parseMedicines(String(Crypto.decrypt(Crypto.b64d(it), key), Charsets.UTF_8))
                } ?: emptyList()
                val baseDtos = Prefs.medicinesSyncBase?.let { VaultCodec.parseMedicines(it) }
                val localList = medicineDao.getAllMedicines().first()
                val localDtos = localList.map { medicineToDto(it) }
                // 无基线（升级后首次重连）或服务器尚无 blob：保持旧行为，本地整体覆盖服务器
                val merged = if (baseDtos == null || vault.medicines.ciphertext == null) localDtos
                else mergeReconnect(baseDtos, localDtos, remoteDtos, { it.clientUuid }, ::medicineContentEquals)
                val ct = Crypto.b64e(Crypto.encrypt(VaultCodec.serializeMedicines(merged).toByteArray(Charsets.UTF_8), key))
                try {
                    val putResp = api.putMedicinesVault(authHeader(), VaultBlobUpdateRequest(ct, vault.medicines.version))
                    if (putResp.code != 0) throw Exception(putResp.message ?: "更新失败")
                    lastPushedMedicinesVersion = vault.medicines.version + 1
                    // 把合并结果应用到本地：与推送前快照比对——快照里没有的行（家属端新增）和内容不同的行
                    // （家属端改动）入库；快照里有但合并结果没有的行（家属端永久删除）从本地移除并取消闹钟。
                    // 本人端获胜的行跳过重写（避免覆盖本地媒体引用）；推送期间用户并发新增/改动的行不受影响
                    val mergedUuids = merged.map { it.clientUuid }.toSet()
                    val snapshotUuids = localDtos.map { it.clientUuid }.toSet()
                    val snapshotByUuid = localDtos.associateBy { it.clientUuid }
                    for (dto in merged) {
                        val snapshotDto = snapshotByUuid[dto.clientUuid]
                        if (snapshotDto == null || !medicineContentEquals(dto, snapshotDto)) {
                            medicineDao.upsertByClientUuid(dtoToMedicine(dto))
                        }
                    }
                    val freshLocal = medicineDao.getAllMedicines().first()
                    for (m in freshLocal) {
                        if (m.clientUuid in snapshotUuids && m.clientUuid !in mergedUuids) {
                            // 家属端在窗口内永久删除：取消该药已注册的闹钟与延迟提醒，避免幽灵闹钟
                            AlarmScheduler.cancelMedicine(App.instance, m)
                            AlarmScheduler.cancelSnooze(App.instance, m.id)
                            medicineDao.delete(m)
                        }
                    }
                    Prefs.medicinesSyncBase = VaultCodec.serializeMedicines(merged)
                    return
                } catch (e: HttpException) {
                    if (e.code() == 409 && attempt == 0) continue
                    throw e
                }
            }
        } catch (e: IOException) {
            // 网络不可用：标记离线，恢复联网后立即以本地为最新数据源覆盖推送服务器
            Prefs.wasOffline = true
            throw e
        } catch (e: HttpException) {
            // 401 = 账号已在其他设备登录：清除登录态并停止一切自动备份（重新登录才恢复）
            onSessionExpired(e)
            throw e
        }
    }

    private suspend fun pushRecordsReconnectLocked() {
        val key = requireDataKey()
        try {
            for (attempt in 0 until 2) {
                val vaultResp = api.getVault(authHeader())
                if (vaultResp.code != 0) throw Exception(vaultResp.message ?: "拉取失败")
                val vault = vaultResp.data ?: return
                val remoteDtos = vault.records.ciphertext?.let {
                    VaultCodec.parseRecords(String(Crypto.decrypt(Crypto.b64d(it), key), Charsets.UTF_8))
                } ?: emptyList()
                val baseDtos = Prefs.recordsSyncBase?.let { VaultCodec.parseRecords(it) }
                val medicines = medicineDao.getAllMedicines().first()
                val idToUuid = HashMap<Long, String>()
                for (m in medicines) idToUuid[m.id] = ensureMedicineUuid(m)
                val allRecords = recordDao.getAllRecordsRaw()
                val localDtos = allRecords.map { r ->
                    VaultRecordDto(
                        clientUuid = ensureRecordUuid(r),
                        medicineUuid = idToUuid[r.medicineId] ?: "",
                        medicineName = r.medicineName,
                        scheduledTime = r.scheduledTime,
                        takenTime = r.takenTime,
                        status = r.status,
                        deletedAt = r.deletedAt,
                        undoable = r.undoable
                    )
                }
                // 无基线（升级后首次重连）或服务器尚无 blob：保持旧行为，本地整体覆盖服务器
                val merged = if (baseDtos == null || vault.records.ciphertext == null) localDtos
                else mergeReconnect(baseDtos, localDtos, remoteDtos, { it.clientUuid }, { a, b -> a == b })
                val ct = Crypto.b64e(Crypto.encrypt(VaultCodec.serializeRecords(merged).toByteArray(Charsets.UTF_8), key))
                try {
                    val putResp = api.putRecordsVault(authHeader(), VaultBlobUpdateRequest(ct, vault.records.version))
                    if (putResp.code != 0) throw Exception(putResp.message ?: "更新失败")
                    lastPushedRecordsVersion = vault.records.version + 1
                    // 把合并结果应用到本地：家属端在窗口内改动/新增的行入库（本人端获胜的行内容未变，跳过重写）；
                    // 家属端永久删除的行从本地移除。删除判定用 ensure 之后的最新行（ensureRecordUuid 会给空 uuid 行补发）
                    val mergedUuids = merged.map { it.clientUuid }.toSet()
                    val snapshotUuids = localDtos.map { it.clientUuid }.toSet()
                    val uuidToMedicineId = medicineDao.getAllMedicines().first().associate { it.clientUuid to it.id }
                    val localByUuid = localDtos.associateBy { it.clientUuid }
                    for (dto in merged) {
                        val localDto = localByUuid[dto.clientUuid]
                        if (localDto == null || localDto != dto) {
                            recordDao.upsertByClientUuid(dtoToRecord(dto, uuidToMedicineId[dto.medicineUuid] ?: 0L))
                        }
                    }
                    // 仅删除推送前快照里存在、合并结果里没有的行（家属端永久删除）；推送期间并发新增的记录不受影响
                    val removeIds = recordDao.getAllRecordsRaw()
                        .filter { it.clientUuid in snapshotUuids && it.clientUuid !in mergedUuids }.map { it.id }
                    if (removeIds.isNotEmpty()) recordDao.deleteByIds(removeIds)
                    Prefs.recordsSyncBase = VaultCodec.serializeRecords(merged)
                    return
                } catch (e: HttpException) {
                    if (e.code() == 409 && attempt == 0) continue
                    throw e
                }
            }
        } catch (e: IOException) {
            Prefs.wasOffline = true
            throw e
        } catch (e: HttpException) {
            onSessionExpired(e)
            throw e
        }
    }

    /** 记录基线 DTO（快照存储用；本地记录全量，含墓碑） */
    private suspend fun recordsBaseDtos(medicines: List<MedicineEntity>): List<VaultRecordDto> {
        val idToUuid = medicines.associate { it.id to it.clientUuid }
        return recordDao.getAllRecordsRaw().map { r ->
            VaultRecordDto(
                clientUuid = r.clientUuid,
                medicineUuid = idToUuid[r.medicineId] ?: "",
                medicineName = r.medicineName,
                scheduledTime = r.scheduledTime,
                takenTime = r.takenTime,
                status = r.status,
                deletedAt = r.deletedAt,
                undoable = r.undoable
            )
        }
    }

    /**
     * 登录/注册后的双向合并同步：先拉云端按 clientUuid 合并到本地，再把本地独有的推上去。
     * 不覆盖、不丢数据（云端独有下载、本地独有上传、冲突按 clientUuid 合并）。
     */
    suspend fun syncMerge() {
        if (!Prefs.isLoggedIn) return
        syncIfReconnected()
        syncVault()
        pushVault("medicines")
        pushVault("records")
        syncScreenAspect()
        syncThreshold()
        syncTimezone()
        syncClockOffset()
    }

    /** 本人设备心跳：告知服务器设备当前在线（家属控制据此判断目标设备是否连接服务器；失败静默） */
    private suspend fun heartbeat() {
        if (!Prefs.isLoggedIn) return
        try {
            api.heartbeat(authHeader())
        } catch (_: Exception) {
        }
    }

    /**
     * 账号已在其他设备登录（服务器 token_version 已递增，旧会话 401）：清除本地登录态、
     * 取消周期同步并提示用户。之后所有自动备份机制（onResume 同步、周期同步、重连覆盖推送、
     * 本地改动上报）都因 isLoggedIn=false 而停止，只有重新登录该账号才恢复备份。
     */
    private fun onSessionExpired(e: HttpException) {
        if (e.code() != 401) return
        Prefs.logout()
        SyncScheduler.cancelPeriodicSync()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(
                App.instance,
                App.instance.getString(R.string.toast_session_expired),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 周期同步（WorkManager 周期任务调用，约每 15 分钟一次）：
     *  仅本人已登录且开启家人控制时执行；拉取合并后再尽力推送本地改动 */
    suspend fun syncPeriodic() {
        if (!Prefs.isLoggedIn || !Prefs.familyControlEnabled) return
        try {
            syncAll()
        } catch (_: Exception) {
        }
        try {
            pushVault("medicines")
        } catch (_: Exception) {
        }
        try {
            pushVault("records")
        } catch (_: Exception) {
        }
    }

    /** 上传本人设备屏幕宽高比（宽/高），供家属远程裁剪视频时按此比例（非关键，失败静默） */
    suspend fun syncScreenAspect() {
        if (!Prefs.isLoggedIn) return
        val dm = App.instance.resources.displayMetrics
        val aspect = dm.widthPixels.toFloat() / dm.heightPixels.toFloat()
        try {
            api.updateScreenAspect(authHeader(), ScreenAspectRequest(aspect))
        } catch (_: Exception) {
            // 屏比上传失败不影响主流程
        }
    }

    /** 上传本人设备的按时服药阈值（分钟），供家属端远程服药时按同一标准判定（失败静默，下次同步自动重试当前值） */
    suspend fun syncThreshold() {
        if (!Prefs.isLoggedIn) return
        try {
            api.updateThreshold(authHeader(), ThresholdRequest(Prefs.thresholdMinutes))
        } catch (_: Exception) {
        }
    }

    /** 上传本人设备时区（IANA 标识），供家属端远程控制时按目标用户本地时区显示和处理时间（失败静默，下次同步重试） */
    suspend fun syncTimezone() {
        if (!Prefs.isLoggedIn) return
        try {
            api.updateTimezone(authHeader(), TimezoneRequest(TimeZone.getDefault().id))
        } catch (_: Exception) {
        }
    }

    /** 上传本人设备时钟相对服务器时间的偏移（毫秒），供家属端还原目标设备当前时间读数
     *  （远程操作的实际服药时间以目标端为准；失败静默，下次同步重试） */
    suspend fun syncClockOffset() {
        if (!Prefs.isLoggedIn) return
        try {
            val timeResp = api.getServerTime(authHeader())
            val serverTime = timeResp.data?.serverTime ?: return
            val offset = System.currentTimeMillis() - serverTime
            api.updateClockOffset(authHeader(), ClockOffsetRequest(offset))
        } catch (_: Exception) {
        }
    }

    /** 修改按时服药阈值：立即同步到服务器（失败抛出给界面提示；之后每次 syncAll 都会重试当前值） */
    suspend fun updateThreshold(minutes: Int) {
        if (!Prefs.isLoggedIn) return
        val resp = try {
            api.updateThreshold(authHeader(), ThresholdRequest(minutes))
        } catch (e: HttpException) {
            throw Exception(ApiClient.errorText(e))
        }
        if (resp.code != 0) throw Exception(resp.message ?: "更新失败")
    }

    /** 是否需要解锁：已登录但本进程尚未建立 dataKey（重启/重登后需输入对应密码） */
    val vaultLocked: Boolean
        get() = Prefs.isLoggedIn && CryptoSession.dataKey == null

    /** 解锁 vault：取盐 → 登录密码派生密钥 → 用密文验证密码（数据恒用登录密码加密）。
     *  单设备登录会使旧 token 失效（token_version 递增，如迁移时登录校验后）：401 时用本地用户id+密码静默重登一次 */
    suspend fun unlockVault(password: String) {
        if (!Prefs.isLoggedIn) throw Exception(App.instance.getString(R.string.toast_login_first))
        var resp = try {
            api.getFamilyControl(authHeader())
        } catch (e: HttpException) {
            if (e.code() != 401) throw Exception(ApiClient.errorText(e))
            val loginResp = loginWithProof(Prefs.userCode.orEmpty(), password)
            if (!loginResp.isSuccessful || loginResp.body()?.data == null) {
                throw Exception(ApiClient.errorText(loginResp))
            }
            Prefs.token = loginResp.body()?.data?.accessToken
            api.getFamilyControl(authHeader())
        }
        if (resp.code != 0) throw Exception(resp.message ?: "无法获取密钥")
        val salt = resp.data?.salt ?: throw Exception("密钥未初始化")
        CryptoSession.establishLogin(password, salt)
        CryptoSession.activateLogin()
        val key = CryptoSession.dataKey!!
        val vault = api.getVault(authHeader()).data
        val ct = vault?.medicines?.ciphertext ?: vault?.records?.ciphertext
        if (ct != null) {
            try {
                Crypto.decrypt(Crypto.b64d(ct), key)
            } catch (_: Exception) {
                CryptoSession.clear()
                throw Exception(App.instance.getString(R.string.toast_access_wrong_password))
            }
        }
    }

    /** 是否需要一次性迁移：家人控制已开启但服务器尚无包裹密钥 → 旧数据按家人密码加密 */
    suspend fun needsFamilyMigration(): Boolean {
        if (!Prefs.isLoggedIn) return false
        if (!Prefs.familyControlEnabled) return false
        val resp = api.getFamilyControl(authHeader())
        if (resp.code != 0) return false
        return resp.data?.wrappedFamilyKey.isNullOrBlank()
    }

    /** 一次性迁移：旧数据按家人密码加密 → 用登录密码重加密，并用家人密码包裹新登录密钥上传 */
    suspend fun migrateFamilyToLogin(familyPassword: String, loginPassword: String) {
        if (!Prefs.isLoggedIn) throw Exception(App.instance.getString(R.string.toast_login_first))
        // 先校验登录密码（避免用错误密码派生新密钥导致数据永久不可达）；明文密码只在本地派生证明
        val loginCheck = loginWithProof(Prefs.userCode.orEmpty(), loginPassword)
        if (!loginCheck.isSuccessful || loginCheck.body()?.code != 0) {
            throw Exception(App.instance.getString(R.string.toast_access_wrong_password))
        }
        // 登录成功会使服务器 token_version 递增（单设备登录），旧 token 立即失效；
        // 必须用新 token 替换本地会话，否则后续所有请求 401（家人控制将永远无法开启）
        loginCheck.body()?.data?.let { Prefs.token = it.accessToken }
        val fcResp = api.getFamilyControl(authHeader())
        if (fcResp.code != 0) throw Exception(fcResp.message ?: "无法获取密钥")
        val saltB64 = fcResp.data?.salt ?: throw Exception("密钥未初始化")
        val salt = Crypto.b64d(saltB64)
        val oldDataKey = Crypto.dataKey(familyPassword, salt)
        val oldMediaKey = Crypto.mediaKey(familyPassword, salt)
        // 校验家人密码：用旧家人密钥解密任一密文
        val vault = api.getVault(authHeader()).data
        val ct = vault?.medicines?.ciphertext ?: vault?.records?.ciphertext
        if (ct != null) {
            try {
                Crypto.decrypt(Crypto.b64d(ct), oldDataKey)
            } catch (_: Exception) {
                throw Exception(App.instance.getString(R.string.toast_access_wrong_password))
            }
        }
        val newDataKey = Crypto.dataKey(loginPassword, salt)
        val newMediaKey = Crypto.mediaKey(loginPassword, salt)
        // 旧家人密钥解密 → 新登录密钥重加密
        reencryptVaultWithKeys(oldDataKey, oldMediaKey, newDataKey, newMediaKey)
        // 用家人密码包裹新登录密钥上传（服务器只存包裹后的密文）
        val wrappedFamilyKey = Crypto.b64e(Crypto.encrypt(newDataKey + newMediaKey, Crypto.wrapKey(familyPassword, salt)))
        val resp = api.setFamilyControl(authHeader(), FamilyControlSetRequest(null, null, null, wrappedFamilyKey))
        if (resp.code != 0) throw Exception(resp.message ?: "迁移失败")
        // 建立登录会话
        CryptoSession.establishLogin(loginPassword, saltB64)
        CryptoSession.activateLogin()
    }

    private fun requireDataKey(): ByteArray =
        CryptoSession.dataKey ?: throw Exception(App.instance.getString(R.string.toast_login_first))

    // 最近一次成功推送后服务器 blob 的版本号：低于此版本的拉取响应是推送前的旧快照，
    // 跳过应用，避免并发拉取把旧数据覆盖到本地新改动上（如回收站恢复/永久删除被旧墓碑覆盖）
    @Volatile private var lastPushedMedicinesVersion = 0
    @Volatile private var lastPushedRecordsVersion = 0

    // vault 拉取应用与推送互斥：拉取必须是「取回+应用」原子操作，否则旧快照会在用户操作
    // （回收站恢复/永久删除）的本地写入与推送读取之间覆盖本地新状态，导致首次操作失效
    private val vaultMutex = Mutex()

    private val videosDir: File get() = File(App.instance.filesDir, "videos").apply { mkdirs() }
    private val ringtonesDir: File get() = File(App.instance.filesDir, "ringtones").apply { mkdirs() }

    /** 拉取并解密两 blob，按 clientUuid 合并到本地；缺失的自定义媒体下载解密到本地文件（与推送互斥） */
    suspend fun syncVault() {
        if (!Prefs.isLoggedIn) return
        vaultMutex.withLock { syncVaultLocked() }
    }

    private suspend fun syncVaultLocked() {
        if (!Prefs.isLoggedIn) return
        val key = requireDataKey()
        val resp = try {
            api.getVault(authHeader())
        } catch (e: IOException) {
            // 网络不可用：标记离线，恢复联网后立即以本地为最新数据源覆盖推送服务器
            Prefs.wasOffline = true
            throw e
        } catch (e: HttpException) {
            // 401 = 账号已在其他设备登录：清除登录态并停止一切自动备份（重新登录才恢复）
            onSessionExpired(e)
            throw e
        }
        if (resp.code != 0 || resp.data == null) return
        val vault = resp.data

        val medicineUuidToLocalId = HashMap<String, Long>()
        // 版本低于最近一次推送版本：本次响应是推送前发出的旧快照，跳过应用避免旧数据覆盖本地新改动
        if (vault.medicines.version >= lastPushedMedicinesVersion) {
            vault.medicines.ciphertext?.let { ct ->
                val plain = String(Crypto.decrypt(Crypto.b64d(ct), key), Charsets.UTF_8)
                val dtos = VaultCodec.parseMedicines(plain)
                for (dto in dtos) {
                    medicineDao.upsertByClientUuid(dtoToMedicine(dto))
                    medicineDao.getByClientUuid(dto.clientUuid)?.let { medicineUuidToLocalId[dto.clientUuid] = it.id }
                }
                // 基线推进：仅当本地与旧基线一致（无未推送的本地改动）时推进为本地快照，
                // 保证重连三方合并的共同祖先正确；有未推送改动时保留旧基线
                if (localMedicinesMatchBase()) {
                    Prefs.medicinesSyncBase = VaultCodec.serializeMedicines(
                        medicineDao.getAllMedicines().first().map { medicineToBaseDto(it) }
                    )
                }
            }
        } else {
            // 药品 blob 为旧快照被跳过：记录关联映射改从本地库构建，避免记录行 medicineId 丢失
            for (m in medicineDao.getAllMedicines().first()) {
                if (m.clientUuid.isNotBlank()) medicineUuidToLocalId[m.clientUuid] = m.id
            }
        }

        if (vault.records.version >= lastPushedRecordsVersion) {
            vault.records.ciphertext?.let { ct ->
                val dtos = VaultCodec.parseRecords(String(Crypto.decrypt(Crypto.b64d(ct), key), Charsets.UTF_8))
                for (dto in dtos) {
                    val localMedicineId = medicineUuidToLocalId[dto.medicineUuid] ?: 0L
                    recordDao.upsertByClientUuid(dtoToRecord(dto, localMedicineId))
                }
                // 基线推进（同药品：仅当本地与旧基线一致时推进）
                if (localRecordsMatchBase()) {
                    Prefs.recordsSyncBase = VaultCodec.serializeRecords(recordsBaseDtos(medicineDao.getAllMedicines().first()))
                }
            }
        }

        // 家属历史（账户级 vault blob：密码端到端加密随账号同步；服务器只见密文）
        vault.familyHistory?.ciphertext?.let { ct ->
            val entries = VaultCodec.parseHistory(String(Crypto.decrypt(Crypto.b64d(ct), key), Charsets.UTF_8))
            FamilyHistoryStore.merge(entries)
        }
        if (FamilyHistoryStore.isDirty()) {
            try {
                val historyCt = Crypto.b64e(Crypto.encrypt(
                    VaultCodec.serializeHistory(FamilyHistoryStore.rawAll()).toByteArray(Charsets.UTF_8), key
                ))
                val putResp = api.putFamilyHistoryVault(
                    authHeader(), VaultBlobUpdateRequest(historyCt, vault.familyHistory?.version ?: 0)
                )
                if (putResp.code == 0) FamilyHistoryStore.clearDirty()
            } catch (_: Exception) {
                // 推送失败保留 dirty，下次同步重试
            }
        }

        rescheduleAllAlarms()
    }

    /** 上传某个 blob 的对外入口 */
    suspend fun pushVault(kind: String, overwrite: Boolean = false) {
        if (!Prefs.isLoggedIn) return
        when (kind) {
            "medicines" -> pushMedicinesVault(overwrite)
            "records" -> pushRecordsVault(overwrite)
        }
    }

    /** 合并并上传药品 blob；版本冲突(409)时重新拉取合并重试一次。
     *  overwrite=true 时不合并远端，直接整体覆盖（回收站 GC 硬删后防止远端墓碑复活；恢复联网后本地覆盖服务器旧数据）。 */
    private suspend fun pushMedicinesVault(overwrite: Boolean = false) {
        if (!Prefs.isLoggedIn) return
        vaultMutex.withLock { pushMedicinesVaultLocked(overwrite) }
    }

    private suspend fun pushMedicinesVaultLocked(overwrite: Boolean = false) {
        if (!Prefs.isLoggedIn) return
        val key = requireDataKey()
        try {
            for (attempt in 0 until 2) {
                val vaultResp = api.getVault(authHeader())
                if (vaultResp.code != 0 || vaultResp.data == null) return
                val vault = vaultResp.data
                val remoteDtos = if (overwrite) emptyList() else
                    vault.medicines.ciphertext?.let {
                        VaultCodec.parseMedicines(String(Crypto.decrypt(Crypto.b64d(it), key), Charsets.UTF_8))
                    } ?: emptyList()
                val localList = medicineDao.getAllMedicines().first()
                val localDtos = ArrayList<VaultMedicineDto>(localList.size)
                for (m in localList) localDtos.add(medicineToDto(m))
                val merged = mergeMedicines(remoteDtos, localDtos)
                val ct = Crypto.b64e(Crypto.encrypt(VaultCodec.serializeMedicines(merged).toByteArray(Charsets.UTF_8), key))
                try {
                    val putResp = api.putMedicinesVault(authHeader(), VaultBlobUpdateRequest(ct, vault.medicines.version))
                    if (putResp.code != 0) return
                    lastPushedMedicinesVersion = vault.medicines.version + 1
                    // 推送成功：本地与服务器一致，同步基线推进为本地快照
                    Prefs.medicinesSyncBase = VaultCodec.serializeMedicines(localList.map { medicineToBaseDto(it) })
                    return
                } catch (e: HttpException) {
                    if (e.code() == 409 && attempt == 0) continue
                    throw e
                }
            }
        } catch (e: IOException) {
            // 网络不可用：标记离线，恢复联网后立即以本地为最新数据源覆盖推送服务器
            Prefs.wasOffline = true
            throw e
        } catch (e: HttpException) {
            // 401 = 账号已在其他设备登录：清除登录态并停止一切自动备份（重新登录才恢复）
            onSessionExpired(e)
            throw e
        }
    }

    /** 合并并上传记录 blob；版本冲突(409)时重新拉取合并重试一次。
     *  overwrite=true 时不合并远端，直接整体覆盖（GC 硬删后防止远端墓碑复活；恢复联网后本地覆盖服务器旧数据）。 */
    private suspend fun pushRecordsVault(overwrite: Boolean = false) {
        if (!Prefs.isLoggedIn) return
        vaultMutex.withLock { pushRecordsVaultLocked(overwrite) }
    }

    private suspend fun pushRecordsVaultLocked(overwrite: Boolean = false) {
        if (!Prefs.isLoggedIn) return
        val key = requireDataKey()
        try {
            for (attempt in 0 until 2) {
                val vaultResp = api.getVault(authHeader())
                if (vaultResp.code != 0 || vaultResp.data == null) return
                val vault = vaultResp.data
                val remoteDtos = if (overwrite) emptyList() else
                    vault.records.ciphertext?.let {
                        VaultCodec.parseRecords(String(Crypto.decrypt(Crypto.b64d(it), key), Charsets.UTF_8))
                    } ?: emptyList()
                val medicines = medicineDao.getAllMedicines().first()
                val idToUuid = HashMap<Long, String>()
                for (m in medicines) idToUuid[m.id] = ensureMedicineUuid(m)
                val allRecords = recordDao.getAllRecordsRaw()
                val localDtos = ArrayList<VaultRecordDto>(allRecords.size)
                for (r in allRecords) {
                    localDtos.add(VaultRecordDto(
                        clientUuid = ensureRecordUuid(r),
                        medicineUuid = idToUuid[r.medicineId] ?: "",
                        medicineName = r.medicineName,
                        scheduledTime = r.scheduledTime,
                        takenTime = r.takenTime,
                        status = r.status,
                        deletedAt = r.deletedAt,
                        undoable = r.undoable
                    ))
                }
                val merged = mergeRecords(remoteDtos, localDtos)
                val ct = Crypto.b64e(Crypto.encrypt(VaultCodec.serializeRecords(merged).toByteArray(Charsets.UTF_8), key))
                try {
                    val putResp = api.putRecordsVault(authHeader(), VaultBlobUpdateRequest(ct, vault.records.version))
                    if (putResp.code != 0) return
                    lastPushedRecordsVersion = vault.records.version + 1
                    // 推送成功：本地与服务器一致，同步基线推进为本地快照
                    Prefs.recordsSyncBase = VaultCodec.serializeRecords(recordsBaseDtos(medicines))
                    return
                } catch (e: HttpException) {
                    if (e.code() == 409 && attempt == 0) continue
                    throw e
                }
            }
        } catch (e: IOException) {
            // 网络不可用：标记离线，恢复联网后立即以本地为最新数据源覆盖推送服务器
            Prefs.wasOffline = true
            throw e
        } catch (e: HttpException) {
            // 401 = 账号已在其他设备登录：清除登录态并停止一切自动备份（重新登录才恢复）
            onSessionExpired(e)
            throw e
        }
    }

    /** 密钥重加密：旧密钥解密远端 → 新密钥重加密 → 重传（登录↔家人 切换 / 改密共用） */
    private suspend fun reencryptVaultWithKeys(
        oldDataKey: ByteArray, oldMediaKey: ByteArray,
        newDataKey: ByteArray, newMediaKey: ByteArray
    ) {
        if (!Prefs.isLoggedIn) return
        val vault = api.getVault(authHeader()).data ?: return
        vault.medicines.ciphertext?.let { ct ->
            val plain = Crypto.decrypt(Crypto.b64d(ct), oldDataKey)
            api.putMedicinesVault(authHeader(), VaultBlobUpdateRequest(
                Crypto.b64e(Crypto.encrypt(plain, newDataKey)), vault.medicines.version
            ))
        }
        vault.records.ciphertext?.let { ct ->
            val plain = Crypto.decrypt(Crypto.b64d(ct), oldDataKey)
            api.putRecordsVault(authHeader(), VaultBlobUpdateRequest(
                Crypto.b64e(Crypto.encrypt(plain, newDataKey)), vault.records.version
            ))
        }
        for (meta in vault.media) {
            val resp = api.getVaultMedia(authHeader(), meta.mediaId)
            if (resp.isSuccessful) {
                val plain = Crypto.decrypt(resp.body()?.bytes() ?: continue, oldMediaKey)
                val enc = Crypto.encrypt(plain, newMediaKey)
                val part = MultipartBody.Part.createFormData(
                    "file", meta.filename ?: "",
                    enc.toRequestBody((meta.contentType ?: "application/octet-stream").toMediaTypeOrNull())
                )
                api.uploadVaultMedia(
                    authHeader(),
                    meta.mediaId.toRequestBody("text/plain".toMediaTypeOrNull()),
                    (meta.contentType ?: "").toRequestBody("text/plain".toMediaTypeOrNull()),
                    (meta.filename ?: "").toRequestBody("text/plain".toMediaTypeOrNull()),
                    part
                )
            }
        }
    }

    /** 本地变更后尽力推送两个 blob */
    private suspend fun pushLocalChanges() {
        if (!Prefs.isLoggedIn) return
        try {
            pushMedicinesVault()
            pushRecordsVault()
        } catch (_: Exception) {
        }
    }

    // ---- 实体 ↔ DTO 映射 ----

    private suspend fun ensureMedicineUuid(m: MedicineEntity): String {
        if (m.clientUuid.isNotBlank()) return m.clientUuid
        val uuid = VaultCodec.newUuid()
        medicineDao.update(m.copy(clientUuid = uuid))
        return uuid
    }

    private suspend fun ensureRecordUuid(r: RecordEntity): String {
        if (r.clientUuid.isNotBlank()) return r.clientUuid
        val uuid = VaultCodec.newUuid()
        recordDao.update(r.copy(clientUuid = uuid))
        return uuid
    }

    private suspend fun medicineToDto(m: MedicineEntity): VaultMedicineDto = VaultMedicineDto(
        clientUuid = ensureMedicineUuid(m),
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
        videoMediaId = uploadLocalMedia(m.videoPath, "video"),
        ringtoneMediaId = uploadLocalMedia(m.ringtoneUri, "ringtone"),
        createdAt = m.createdAt
    )

    private suspend fun dtoToMedicine(dto: VaultMedicineDto): MedicineEntity = MedicineEntity(
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
        videoPath = dto.videoMediaId?.let { ensureLocalMedia(it, "video") },
        videoCrop = dto.videoCrop,
        ringtoneUri = dto.ringtoneMediaId?.let { ensureLocalMedia(it, "ringtone")?.let { p -> "file://$p" } },
        createdAt = dto.createdAt
    )

    private fun dtoToRecord(dto: VaultRecordDto, localMedicineId: Long): RecordEntity = RecordEntity(
        clientUuid = dto.clientUuid,
        medicineId = localMedicineId,
        medicineName = dto.medicineName,
        scheduledTime = dto.scheduledTime,
        takenTime = dto.takenTime,
        status = dto.status,
        deletedAt = dto.deletedAt,
        undoable = dto.undoable
    )

    private fun mergeMedicines(remote: List<VaultMedicineDto>, local: List<VaultMedicineDto>): List<VaultMedicineDto> {
        val map = LinkedHashMap<String, VaultMedicineDto>()
        remote.forEach { map[it.clientUuid] = it }
        local.forEach { map[it.clientUuid] = it }  // 本地覆盖远端
        return map.values.toList()
    }

    private fun mergeRecords(remote: List<VaultRecordDto>, local: List<VaultRecordDto>): List<VaultRecordDto> {
        val map = LinkedHashMap<String, VaultRecordDto>()
        remote.forEach { map[it.clientUuid] = it }
        local.forEach { map[it.clientUuid] = it }
        return map.values.toList()
    }

    /** 基线快照用 DTO：忽略媒体字段（媒体随整行输赢，不单独参与冲突判定）、不上传媒体 */
    private fun medicineToBaseDto(m: MedicineEntity): VaultMedicineDto = VaultMedicineDto(
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
        createdAt = m.createdAt
    )

    /** 本地药品是否与同步基线一致（无未推送的本地改动）；无基线视为一致（首次建立） */
    private suspend fun localMedicinesMatchBase(): Boolean {
        val baseJson = Prefs.medicinesSyncBase ?: return true
        val baseMap = VaultCodec.parseMedicines(baseJson).associateBy { it.clientUuid }
        val localDtos = medicineDao.getAllMedicines().first().map { medicineToBaseDto(it) }
        if (baseMap.size != localDtos.size) return false
        for (l in localDtos) {
            val b = baseMap[l.clientUuid] ?: return false
            if (!medicineContentEquals(b, l)) return false
        }
        return true
    }

    /** 本地记录是否与同步基线一致（无未推送的本地改动）；无基线视为一致（首次建立） */
    private suspend fun localRecordsMatchBase(): Boolean {
        val baseJson = Prefs.recordsSyncBase ?: return true
        val baseMap = VaultCodec.parseRecords(baseJson).associateBy { it.clientUuid }
        val medicines = medicineDao.getAllMedicines().first()
        val localDtos = recordsBaseDtos(medicines)
        if (baseMap.size != localDtos.size) return false
        for (l in localDtos) {
            if (baseMap[l.clientUuid] != l) return false
        }
        return true
    }

    // ---- 媒体加密上传 / 下载 ----

    /** 上传本地自定义媒体（加密后存服务端），返回 media_id；系统铃声 content:// 不跨设备同步 */
    private suspend fun uploadLocalMedia(path: String?, kind: String): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("content://")) return null
        val realPath = if (path.startsWith("file://")) path.removePrefix("file://") else path
        val file = File(realPath)
        if (!file.exists()) return null

        // 以内容哈希作为 media_id，重复上传幂等（服务器按 media_id upsert，不产生重复 blob）
        val mediaId = contentMediaId(file)
        uploadEncryptedMedia(mediaId, file, kind)
        return mediaId
    }

    /** 加密并上传媒体到 vault */
    suspend fun uploadEncryptedMedia(mediaId: String, file: File, kind: String) {
        val key = CryptoSession.mediaKey ?: throw Exception("媒体密钥未初始化")
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
        if (resp.code != 0) throw Exception(resp.message ?: "上传失败")
    }

    /** 确保 media_id 对应本地文件存在（缺失则下载解密），返回本地绝对路径 */
    private suspend fun ensureLocalMedia(mediaId: String, kind: String): String? {
        val ext = if (kind == "video") "mp4" else "mp3"
        val dir = if (kind == "video") videosDir else ringtonesDir
        val target = File(dir, "vault_${mediaId}.$ext")
        if (target.exists()) return target.absolutePath
        val key = CryptoSession.mediaKey ?: return null
        val resp = api.getVaultMedia(authHeader(), mediaId)
        if (!resp.isSuccessful) return null
        val plain = Crypto.decrypt(resp.body()?.bytes() ?: return null, key)
        target.writeBytes(plain)
        return target.absolutePath
    }

    /** 用文件内容 SHA-256（截断 36 位）作为稳定 media_id */
    private fun contentMediaId(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hash = md.digest(file.readBytes())
        return hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(36)
    }

    // ==================== 药品 ====================

    /** 添加药品：本地插入 + 调度闹钟 + 尝试同步 */
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
    ): Long {
        val entity = MedicineEntity(
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
            anchorDay = anchorDay
        )
        val localId = medicineDao.insert(entity)

        // 调度闹钟
        AlarmScheduler.scheduleMedicine(App.instance, entity.copy(id = localId))

        // 尝试同步到服务器（失败不阻塞）：数据恒加密，走 vault
        try {
            if (Prefs.isLoggedIn) {
                pushMedicinesVault()
            }
        } catch (_: Exception) {
        }

        return localId
    }

    /** 重新调度所有闹钟 */
    suspend fun rescheduleAllAlarms() {
        val medicines = medicineDao.getAllMedicines().first()
        medicines.filter { it.isActive }.forEach { medicine ->
            AlarmScheduler.scheduleMedicine(App.instance, medicine)
        }
    }

    // ==================== 服药记录 ====================

    /** 根据原定应服时间与实际服用时间判定服药状态（阈值可在设置中调节）。
     *  thresholdMinutes 默认取本机设置；家属端传入控制对象上传到服务器的阈值，保证两端判定一致。 */
    fun determineStatus(scheduledTime: Long, takenTime: Long, thresholdMinutes: Int = Prefs.thresholdMinutes): String {
        val threshold = thresholdMinutes * 60 * 1000L
        val diff = takenTime - scheduledTime
        return when {
            diff < -threshold -> "taken_early"   // 提前超过阈值
            diff > threshold -> "taken_late"     // 推迟超过阈值
            else -> "taken_on_time"              // 阈值范围内
        }
    }

    /** 记录一次服药：状态按「实际时间 vs 原定应服时间」判定。已服过的剂量不再重复记录（撤销后重新服用除外）。
     *  闹钟弹窗点击后界面立即销毁会取消调用方协程，导致已写入的记录来不及上传服务器；
     *  用 NonCancellable 保证已启动的写入与推送完整执行。 */
    suspend fun markTaken(medicineId: Long, scheduledTime: Long): Boolean = withContext(NonCancellable) {
        // 测试弹窗（medicineId <= 0）不产生记录
        if (medicineId <= 0) return@withContext false

        val takenTime = System.currentTimeMillis()
        val med = medicineDao.getById(medicineId)
        val record = RecordEntity(
            clientUuid = VaultCodec.newUuid(),
            medicineId = medicineId,
            medicineName = med?.name ?: "",
            scheduledTime = scheduledTime,
            takenTime = takenTime,
            status = determineStatus(scheduledTime, takenTime)
            // undoable 用默认 true：新记录按正常规则可撤销；冻结只由改闹钟时间/进回收站时批量执行（resetDoseState/moveToTrash）
        )
        // 防重复：双弹窗/重复点击时，同一剂量（药品 + 应服时间）只记一次；恢复前（stateResetAt 之前）的已服记录不参与去重
        val inserted = recordDao.insertTakenIfAbsent(record, med?.stateResetAt ?: 0L)
        if (!inserted) return@withContext false

        // 扣减剩余数量（不扣到负数）
        medicineDao.decrementStock(medicineId)

        // 尝试上报服务器（失败不阻塞）：数据恒加密，走 vault（记录 + 库存两 blob）
        pushLocalChanges()
        return@withContext true
    }

    /** 撤销某条已服用记录：墓碑化记录 + 恢复剩余量；闹钟未到则恢复闹钟，已过则转为「未服用」 */
    suspend fun undoTaken(record: RecordEntity) {
        if (record.id <= 0) return

        // 1. 撤销墓碑（status 改为 undone，区别于删除墓碑：剂量回到未处理，按钮/闹钟可恢复），并恢复剩余数量
        recordDao.markUndone(record.id, System.currentTimeMillis())
        medicineDao.incrementStock(record.medicineId)

        // 2. 闹钟时间未到 → 恢复闹钟；已过 → 转为「未服用」按钮（pending，0 点才正式记为未服用，不立即进记录）
        if (record.scheduledTime > System.currentTimeMillis()) {
            val medicine = medicineDao.getById(record.medicineId)
            if (medicine != null && medicine.isActive) {
                AlarmScheduler.scheduleMedicine(App.instance, medicine)
            }
        } else {
            // 恢复前（stateResetAt 之前）的已服记录不参与去重：恢复后视为全新药品，撤销后正常生成「未服用」按钮
            val resetAt = medicineDao.getById(record.medicineId)?.stateResetAt ?: 0L
            recordDao.insertMissedIfAbsent(
                RecordEntity(
                    clientUuid = VaultCodec.newUuid(),
                    medicineId = record.medicineId,
                    medicineName = record.medicineName,
                    scheduledTime = record.scheduledTime,
                    takenTime = null,
                    status = "pending"
                ),
                resetAt
            )
        }
        pushLocalChanges()
    }

    /** 删除多条服药记录（软删除）：标记 deletedAt 并推送，让删除跨设备双向同步 */
    suspend fun deleteRecords(ids: List<Long>) {
        if (ids.isEmpty()) return
        recordDao.markDeleted(ids, System.currentTimeMillis())
        pushLocalChanges()
    }

    // ==================== 服用 / 补服 / 暂不服用 ====================

    /** 点击「服用」：有未服用/暂不服用按钮则补最早一条，否则按 R5 状态机服用下一个目标剂量并推进闹钟 */
    suspend fun takeNextDose(medicine: MedicineEntity) {
        if (medicine.id <= 0) return
        val records = recordDao.getAllForMedicine(medicine.id)
        val target = DosePlanner.nextTarget(medicine, records, System.currentTimeMillis()) ?: return
        if (target.makeup != null) {
            // 补服：把最早一条未服用/暂不服用记录直接转为已服用
            takePendingDose(target.makeup)
        } else {
            // 正常服用：目标剂量（提前服用亦记录实际点击时间），并取消稍后提醒、推进该闹钟到下一周期
            if (markTaken(medicine.id, target.scheduledTime)) {
                AlarmScheduler.cancelSnooze(App.instance, medicine.id)
                AlarmScheduler.advanceSlot(App.instance, medicine, target.slotIndex, target.time, target.scheduledTime)
            }
        }
    }

    /** 直接补服某一条未服用/暂不服用记录，不影响其他未服用记录 */
    suspend fun takePendingDose(record: RecordEntity) {
        if (record.id <= 0) return
        val takenTime = System.currentTimeMillis()
        val status = determineStatus(record.scheduledTime, takenTime)
        // 状态守卫：0 行受影响说明已被补服/删除，不再重复扣库存
        if (recordDao.markMissedAsTaken(record.id, status, takenTime) <= 0) return
        medicineDao.decrementStock(record.medicineId)
        pushLocalChanges()
    }

    /** 「暂不服用」：暂存为 pending（不产生正式记录，仅显示一条「HH:MM 未服用」按钮），0 点转为正式未服用。
     *  闹钟弹窗点击后界面立即销毁会取消调用方协程，导致已写入的按钮状态来不及上传服务器；
     *  用 NonCancellable 保证已启动的写入与推送完整执行（按钮生成逻辑本身不变）。 */
    suspend fun skipDose(medicineId: Long, scheduledTime: Long) = withContext(NonCancellable) {
        if (medicineId <= 0) return@withContext
        val med = medicineDao.getById(medicineId)
        recordDao.insertMissedIfAbsent(
            RecordEntity(
                clientUuid = VaultCodec.newUuid(),
                medicineId = medicineId,
                medicineName = med?.name ?: "",
                scheduledTime = scheduledTime,
                takenTime = null,
                status = "pending"
                // undoable 用默认 true：补服/转为已服用后按正常规则可撤销
            ),
            med?.stateResetAt ?: 0L
        )
        pushLocalChanges()
    }

    /** 每日 0 点（或打开 App 兜底）：把今天之前仍未处理的「暂不服用」（pending）剂量转成正式「未服用」（missed）记录；
     *  今天的临时按钮不在此结算（当天只显示按钮，不立即生成正式记录） */
    suspend fun flushMissedDoses() {
        SingleFlight.run("flushMissedDoses") {
            val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            // 无条件执行 UPDATE：Room 表级失效触发药品界面流重新发射，0 点按钮自动消失
            val changed = recordDao.markPendingAsMissed(todayStart)
            if (changed > 0) pushLocalChanges()
        }
    }

    /** 编辑药品且时间点变化后：清除今天起的未服用/暂不服用按钮（软删除），冻结修改前产生的全部记录不可撤销；新时间点由 planner 重新初始化 */
    suspend fun resetDoseState(medicineId: Long) {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val changed1 = recordDao.softDeletePendingMissedSince(medicineId, start, now)
        val changed2 = recordDao.markUndoableDisabled(medicineId)
        if (changed1 > 0 || changed2 > 0) pushLocalChanges()
    }

    // ==================== 回收站 ====================

    /** 移入回收站：取消闹钟与延迟提醒，软删除（保留数据以便恢复）；删除闹钟不删除服药记录，但进入回收站即冻结该药全部现存记录的撤销资格 */
    suspend fun moveToTrash(medicine: MedicineEntity) {
        AlarmScheduler.cancelMedicine(App.instance, medicine)
        AlarmScheduler.cancelSnooze(App.instance, medicine.id)
        val now = System.currentTimeMillis()
        medicineDao.update(medicine.copy(isActive = false, deletedAt = now, hasBeenTrashed = true))
        // 删除闹钟：历史记录保留且仍可删除，但撤销资格永久冻结；恢复后新产生的记录按正常规则处理（undoable 默认 true）
        recordDao.markUndoableDisabled(medicine.id)
        pushLocalChanges()
    }

    /** 从回收站恢复：重新调度闹钟并清除之前临时服药状态（未服用按钮等），历史记录保留且仍不可撤销 */
    suspend fun restoreMedicine(id: Long) {
        // 本地写入与推送整体持锁：并发的拉取不会在中间把旧墓碑覆盖到刚恢复的行上
        vaultMutex.withLock {
            val now = System.currentTimeMillis()
            // 恢复 = 视为全新药品：stateResetAt 之前的服药进度不计入按钮/闹钟（不继承提前服用的禁用与闹钟取消状态）
            medicineDao.restoreMedicine(id, now)
            val m = medicineDao.getById(id)
            if (m != null && m.isActive) {
                AlarmScheduler.scheduleMedicine(App.instance, m)
            }
            // 清除今天起的未服用/暂不服用按钮（临时状态）；历史记录保留，撤销资格不恢复，配置数据不变
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            recordDao.softDeletePendingMissedSince(id, start, now)
            // 同步失败时抛给界面（TrashActivity 显示失败提示而不是假成功）；本地恢复保留，
            // 断网场景由 wasOffline + 重连覆盖推送兜底，不会丢失
            pushMedicinesVaultLocked()
            pushRecordsVaultLocked()
        }
    }

    /** 永久删除回收站中的药品：先本地硬删除（无服务器/未登录也可用），再尽力拉最新+覆盖推送同步删除结果；服药记录保留。
     *  同步失败会抛出给界面（显示失败提示而不是假成功）；本地删除保留，断网由重连覆盖推送兜底。 */
    suspend fun hardDeleteMedicine(id: Long) {
        val uuid = medicineDao.getById(id)?.clientUuid
        // 1. 本地立即删除：回收站即刻消失，不依赖服务器
        medicineDao.getById(id)?.let { medicineDao.delete(it) }
        // 同步与推送整体持锁：并发的拉取不会在删除之后把旧墓碑重新插回本地
        vaultMutex.withLock {
            // 2. 同步（断网期间有本地改动时跳过拉取：拉取会把服务器旧数据合并回本地，直接覆盖推送本地快照）
            if (!Prefs.wasOffline) syncVaultLocked()
            // 3. 拉最新可能把远端副本合并回本地：按 clientUuid 再次删除
            if (uuid != null && uuid.isNotBlank()) {
                medicineDao.getByClientUuid(uuid)?.let { medicineDao.delete(it) }
            }
            // 4. 覆盖推送本地删除结果（与 purgeExpiredTrash 同策略，防远端副本复活）
            pushMedicinesVaultLocked(overwrite = true)
        }
    }

    /** 清除回收站中超过 7 天未恢复的条目：先拉最新再硬删再覆盖推送（防远端墓碑复活）。每天至多一次。 */
    suspend fun purgeExpiredTrash() {
        if (!Prefs.isLoggedIn) return
        val today = (System.currentTimeMillis() / (24 * 60 * 60 * 1000L)).toInt()
        if (Prefs.lastTrashGcDay == today) return
        SingleFlight.run("purgeExpiredTrash") {
            // 离线时跳过拉取（拉取会抛网络异常），仅等下次在线同步
            if (!Prefs.wasOffline) syncVault()
            val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            val deleted = medicineDao.deleteExpiredTrash(cutoff)
            if (deleted > 0) pushMedicinesVault(overwrite = true)
            Prefs.lastTrashGcDay = today
        }
    }

    /** 记录墓碑 GC：先拉最新合并（保证云端墓碑都落入本地），硬删超过 7 天的墓碑，再覆盖推送（防复活）。每天至多一次。 */
    suspend fun purgeExpiredRecordTrash() {
        if (!Prefs.isLoggedIn) return
        val today = (System.currentTimeMillis() / (24 * 60 * 60 * 1000L)).toInt()
        if (Prefs.lastRecordGcDay == today) return
        SingleFlight.run("purgeExpiredRecordTrash") {
            // 离线时跳过拉取（拉取会抛网络异常），仅等下次在线同步
            if (!Prefs.wasOffline) syncVault()
            val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            val deleted = recordDao.deleteExpiredTrash(cutoff)
            if (deleted > 0) pushRecordsVault(overwrite = true)
            Prefs.lastRecordGcDay = today
        }
    }

    /** 回收站列表 */
    fun getTrashedMedicines() = medicineDao.getTrashedMedicines()
}
