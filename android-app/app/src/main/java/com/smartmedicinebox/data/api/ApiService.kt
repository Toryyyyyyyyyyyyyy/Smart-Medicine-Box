/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.api

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 后端 API 接口定义（对接 FastAPI）
 */
interface ApiService {

    // ==================== 认证（明文密码永不上传：客户端本地派生验证材料） ====================

    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<ApiResponse<LoginData>>

    @POST("api/auth/login-challenge")
    suspend fun loginChallenge(@Body body: LoginChallengeRequest): Response<ApiResponse<LoginChallengeData>>

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<ApiResponse<LoginData>>

    @GET("api/auth/profile")
    suspend fun getProfile(
        @Header("Authorization") token: String
    ): ApiResponse<UserDto>

    @PUT("api/auth/profile")
    suspend fun updateProfile(
        @Header("Authorization") token: String,
        @Body body: UpdateProfileRequest
    ): ApiResponse<UserDto>

    @DELETE("api/auth/account")
    suspend fun deleteAccount(
        @Header("Authorization") token: String
    ): ApiResponse<Any>

    @PUT("api/auth/password")
    suspend fun changePassword(
        @Header("Authorization") token: String,
        @Body body: ChangePasswordRequest
    ): ApiResponse<Any>

    @PUT("api/auth/screen-aspect")
    suspend fun updateScreenAspect(
        @Header("Authorization") token: String,
        @Body body: ScreenAspectRequest
    ): ApiResponse<Any>

    @POST("api/auth/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") token: String
    ): ApiResponse<Any>

    @PUT("api/auth/threshold")
    suspend fun updateThreshold(
        @Header("Authorization") token: String,
        @Body body: ThresholdRequest
    ): ApiResponse<Any>

    @PUT("api/auth/timezone")
    suspend fun updateTimezone(
        @Header("Authorization") token: String,
        @Body body: TimezoneRequest
    ): ApiResponse<Any>

    @GET("api/auth/time")
    suspend fun getServerTime(
        @Header("Authorization") token: String
    ): ApiResponse<ServerTimeData>

    @PUT("api/auth/clock-offset")
    suspend fun updateClockOffset(
        @Header("Authorization") token: String,
        @Body body: ClockOffsetRequest
    ): ApiResponse<Any>

    // ==================== 家属绑定 ====================

    @POST("api/family/bind")
    suspend fun bindSenior(
        @Header("Authorization") token: String,
        @Body body: FamilyBindRequest
    ): Response<ApiResponse<Any>>

    @GET("api/family/bindings")
    suspend fun getBindings(
        @Header("Authorization") token: String
    ): ApiResponse<Any>

    // ==================== 家人控制（需登录） ====================

    @GET("api/auth/family-control")
    suspend fun getFamilyControl(
        @Header("Authorization") token: String
    ): ApiResponse<FamilyControlFlagsData>

    @PUT("api/auth/family-control")
    suspend fun setFamilyControl(
        @Header("Authorization") token: String,
        @Body body: FamilyControlSetRequest
    ): ApiResponse<FamilyControlFlagsData>

    // ==================== 家人控制（无登录访问） ====================

    @POST("api/family/access/init")
    suspend fun familyAccessInit(
        @Body body: FamilyAccessInitRequest
    ): ApiResponse<FamilyAccessInitData>

    @POST("api/family/access/verify")
    suspend fun familyAccessVerify(
        @Body body: FamilyAccessVerifyRequest
    ): ApiResponse<FamilyAccessVerifyData>

    // ==================== 家属拉取控制对象设置（数据读写统一走 vault） ====================

    @GET("api/family/control/settings")
    suspend fun familyGetSettings(
        @Header("Authorization") token: String
    ): ApiResponse<FamilySettingsData>

    // ==================== 端到端加密 vault ====================

    @GET("api/vault")
    suspend fun getVault(
        @Header("Authorization") token: String
    ): ApiResponse<VaultData>

    @PUT("api/vault/medicines")
    suspend fun putMedicinesVault(
        @Header("Authorization") token: String,
        @Body body: VaultBlobUpdateRequest
    ): ApiResponse<VaultBlobData>

    @PUT("api/vault/records")
    suspend fun putRecordsVault(
        @Header("Authorization") token: String,
        @Body body: VaultBlobUpdateRequest
    ): ApiResponse<VaultBlobData>

    @PUT("api/vault/family-history")
    suspend fun putFamilyHistoryVault(
        @Header("Authorization") token: String,
        @Body body: VaultBlobUpdateRequest
    ): ApiResponse<VaultBlobData>

    @Multipart
    @POST("api/vault/media")
    suspend fun uploadVaultMedia(
        @Header("Authorization") token: String,
        @Part("media_id") mediaId: RequestBody,
        @Part("content_type") contentType: RequestBody,
        @Part("filename") filename: RequestBody,
        @Part file: MultipartBody.Part
    ): ApiResponse<VaultMediaUploadData>

    @GET("api/vault/media/{media_id}")
    suspend fun getVaultMedia(
        @Header("Authorization") token: String,
        @Path("media_id") mediaId: String
    ): Response<ResponseBody>

    @DELETE("api/vault/media/{media_id}")
    suspend fun deleteVaultMedia(
        @Header("Authorization") token: String,
        @Path("media_id") mediaId: String
    ): ApiResponse<Any>

    // ==================== 局域网配对 ====================

    @POST("api/pair/verify")
    suspend fun pairVerify(@Body body: PairVerifyRequest): Response<ApiResponse<PairVerifyData>>

    // ==================== 健康检查（匿名探针，用于保存服务器地址后的连接/证书探测） ====================

    @GET("api/health")
    suspend fun health(): Response<ResponseBody>
}

data class FamilyBindRequest(
    @SerializedName("senior_user_code") val seniorUserCode: String
)
