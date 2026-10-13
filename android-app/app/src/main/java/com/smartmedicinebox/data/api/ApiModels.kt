/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.api

import com.google.gson.annotations.SerializedName

/** 后端统一响应包装 */
data class ApiResponse<T>(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: T?
)

// ==================== 局域网配对 ====================

/** 配对证明请求：proof = HMAC-SHA256(配对码派生密钥, 服务器 SPKI 指纹) 的 Base64 */
data class PairVerifyRequest(
    @SerializedName("proof") val proof: String,
    @SerializedName("fingerprint") val fingerprint: String
)

data class PairVerifyData(
    @SerializedName("paired") val paired: Boolean,
    @SerializedName("fingerprint") val fingerprint: String? = null
)

// ==================== 注册 / 登录（明文密码永不上传：客户端本地派生验证材料） ====================

/** 注册请求：客户端本地派生密码盐（hex，16 字节）与 PBKDF2-SHA256 哈希（hex，32 字节） */
data class RegisterRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password_salt") val passwordSalt: String,
    @SerializedName("password_hash") val passwordHash: String
)

/** 登录挑战请求：换取密码盐与一次性挑战 */
data class LoginChallengeRequest(
    @SerializedName("user_code") val userCode: String
)

/** 登录挑战响应：challenge 为 Base64 随机数，salt 为密码盐（hex） */
data class LoginChallengeData(
    @SerializedName("challenge") val challenge: String,
    @SerializedName("salt") val salt: String
)

/** 登录请求：proof = HMAC-SHA256(密码哈希, challenge)，Base64 */
data class LoginRequest(
    @SerializedName("user_code") val userCode: String,
    @SerializedName("proof") val proof: String
)

/** 更改登录密码请求：旧密码以证明提交，新密码以客户端本地派生的盐+哈希提交 */
data class ChangePasswordRequest(
    @SerializedName("old_proof") val oldProof: String,
    @SerializedName("new_password_salt") val newPasswordSalt: String,
    @SerializedName("new_password_hash") val newPasswordHash: String
)

data class LoginData(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("user") val user: UserDto
)

data class UserDto(
    @SerializedName("id") val id: Long,
    @SerializedName("user_code") val userCode: String?,
    @SerializedName("username") val username: String?,
    @SerializedName("avatar_url") val avatarUrl: String?,
    @SerializedName("family_control_enabled") val familyControlEnabled: Boolean?,
    @SerializedName("family_control_view_only") val familyControlViewOnly: Boolean?,
    @SerializedName("enc_enabled") val encEnabled: Boolean?,
    @SerializedName("enc_salt") val encSalt: String?,
    @SerializedName("enc_wrapped_family_key") val encWrappedFamilyKey: String? = null,
    @SerializedName("screen_aspect") val screenAspect: Float? = null
)

data class ScreenAspectRequest(
    @SerializedName("aspect") val aspect: Float
)

data class ThresholdRequest(
    @SerializedName("threshold_minutes") val thresholdMinutes: Int
)

data class TimezoneRequest(
    @SerializedName("timezone") val timezone: String
)

data class ServerTimeData(
    @SerializedName("server_time") val serverTime: Long
)

data class ClockOffsetRequest(
    @SerializedName("clock_offset_ms") val clockOffsetMs: Long
)

data class UpdateProfileRequest(
    @SerializedName("username") val username: String
)

// ==================== 家人控制 ====================

/** 开启/更新家人控制设置（需登录）；验证子由客户端本地派生上传，明文家人密码永不上传服务器 */
data class FamilyControlSetRequest(
    @SerializedName("auth_verifier") val authVerifier: String?,
    @SerializedName("view_only") val viewOnly: Boolean?,
    @SerializedName("enabled") val enabled: Boolean?,
    @SerializedName("wrapped_family_key") val wrappedFamilyKey: String? = null
)

/** 家人控制开关标志（响应 data）；salt 仅在设置密码时返回，供本人派生 dataKey/mediaKey */
data class FamilyControlFlagsData(
    @SerializedName("family_control_enabled") val familyControlEnabled: Boolean?,
    @SerializedName("family_control_view_only") val familyControlViewOnly: Boolean?,
    @SerializedName("salt") val salt: String?,
    @SerializedName("wrapped_family_key") val wrappedFamilyKey: String? = null
)

/** 无登录访问第一步：取盐与 challenge */
data class FamilyAccessInitRequest(
    @SerializedName("user_code") val userCode: String
)

data class FamilyAccessInitData(
    @SerializedName("senior_id") val seniorId: Long,
    @SerializedName("username") val username: String?,
    @SerializedName("salt") val salt: String,
    @SerializedName("challenge") val challenge: String,
    @SerializedName("wrapped_family_key") val wrappedFamilyKey: String? = null,
    @SerializedName("screen_aspect") val screenAspect: Float? = null
)

/** 无登录访问第二步：提交 HMAC 证明换取令牌 */
data class FamilyAccessVerifyRequest(
    @SerializedName("user_code") val userCode: String,
    @SerializedName("proof") val proof: String
)

data class FamilyAccessVerifyData(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("senior_id") val seniorId: Long,
    @SerializedName("username") val username: String?,
    @SerializedName("view_only") val viewOnly: Boolean?
)

/** 家属拉取控制对象设置（按时服药阈值、时区、时钟偏移等） */
data class FamilySettingsData(
    @SerializedName("threshold_minutes") val thresholdMinutes: Int?,
    @SerializedName("screen_aspect") val screenAspect: Float?,
    @SerializedName("timezone") val timezone: String?,
    @SerializedName("server_time") val serverTime: Long?,
    @SerializedName("clock_offset_ms") val clockOffsetMs: Long?
)

// ==================== 端到端加密 vault ====================

/** 单个加密 blob（ciphertext 为 base64(nonce||ct||tag)，null 表示尚未上传） */
data class VaultBlobData(
    @SerializedName("ciphertext") val ciphertext: String?,
    @SerializedName("version") val version: Int,
    @SerializedName("updated_at") val updatedAt: String?
)

/** 上传加密 blob（乐观锁） */
data class VaultBlobUpdateRequest(
    @SerializedName("ciphertext") val ciphertext: String,
    @SerializedName("expected_version") val expectedVersion: Int
)

/** 加密媒体元数据 */
data class VaultMediaMeta(
    @SerializedName("media_id") val mediaId: String,
    @SerializedName("size") val size: Int,
    @SerializedName("content_type") val contentType: String?,
    @SerializedName("filename") val filename: String?,
    @SerializedName("updated_at") val updatedAt: String?
)

/** GET /api/vault 响应 data */
data class VaultData(
    @SerializedName("medicines") val medicines: VaultBlobData,
    @SerializedName("records") val records: VaultBlobData,
    @SerializedName("family_history") val familyHistory: VaultBlobData? = null,
    @SerializedName("media") val media: List<VaultMediaMeta>
)

/** 媒体上传响应 */
data class VaultMediaUploadData(
    @SerializedName("media_id") val mediaId: String,
    @SerializedName("size") val size: Int
)
