/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.util

/**
 * 端到端加密密钥的内存态缓存（不落盘）。
 *
 * 数据恒用「登录密码」派生密钥加密（dataKey/mediaKey 由登录密码派生，始终不变）。
 * 家人控制密码只用于「包裹」登录派生密钥（wrappedFamilyKey），供家属远程解包到同一 dataKey。
 * 服务器只存 salt + enc_verifier + wrappedFamilyKey，dataKey/mediaKey 永不出本设备。
 * 退出登录 / 家属会话结束 / 进程死亡时清空。
 */
object CryptoSession {

    var salt: ByteArray? = null
        private set

    var authVerifier: ByteArray? = null
        private set

    /** 当前生效的数据密钥（恒为登录密码派生密钥；家属远程时经解包得到同一密钥） */
    var dataKey: ByteArray? = null
        private set

    /** 当前生效的媒体密钥 */
    var mediaKey: ByteArray? = null
        private set

    /** 登录密码派生的数据密钥（保留用于切换回默认态） */
    var loginDataKey: ByteArray? = null
        private set

    /** 登录密码派生的媒体密钥 */
    var loginMediaKey: ByteArray? = null
        private set

    /** 用登录密码派生登录密钥（不激活，由调用方决定是否激活为默认态） */
    fun establishLogin(password: String, saltB64: String) {
        val s = Crypto.b64d(saltB64)
        salt = s
        loginDataKey = Crypto.dataKey(password, s)
        loginMediaKey = Crypto.mediaKey(password, s)
        persistLoginKeys()
    }

    /** 登录派生密钥落盘：盐明文存，dataKey/mediaKey 用 Android Keystore 包裹后存（更新 APK 不丢，卸载重装才失效） */
    private fun persistLoginKeys() {
        Prefs.encSalt = salt?.let { Crypto.b64e(it) }
        val dk = loginDataKey ?: return
        val mk = loginMediaKey ?: return
        Prefs.encWrappedLoginKey = Crypto.b64e(KeyStoreCrypto.wrap(dk + mk))
    }

    /** 从本地持久化恢复登录密钥（无密码）；成功返回 true，无/坏数据或 Keystore 失效返回 false */
    fun restoreLoginKeys(): Boolean {
        val saltB64 = Prefs.encSalt ?: return false
        val wrappedB64 = Prefs.encWrappedLoginKey ?: return false
        return try {
            val combined = KeyStoreCrypto.unwrap(Crypto.b64d(wrappedB64))
            require(combined.size == 64) { "包裹的登录密钥长度非法" }
            salt = Crypto.b64d(saltB64)
            loginDataKey = combined.copyOfRange(0, 32)
            loginMediaKey = combined.copyOfRange(32, 64)
            activateLogin()
            true
        } catch (_: Exception) {
            clear()
            false
        }
    }

    /** 激活登录密钥为当前生效密钥（默认态） */
    fun activateLogin() {
        dataKey = loginDataKey
        mediaKey = loginMediaKey
        authVerifier = null
    }

    /**
     * 家属凭家人控制密码解包登录派生密钥：
     * wrappedFamilyB64 = encrypt(wrapKey(家人密码), dataKey(32) || mediaKey(32))。
     * 解包后得到与登录态完全相同的 dataKey/mediaKey（数据恒用登录密码加密）。
     * 兼容旧数据：wrappedFamilyB64 为空时，数据仍按家人密码直接派生（历史行为回退）。
     */
    fun establishFamily(wrappedFamilyB64: String?, familyPassword: String, saltB64: String) {
        val s = Crypto.b64d(saltB64)
        salt = s
        authVerifier = Crypto.authVerifier(familyPassword, s)
        val wrapped = wrappedFamilyB64?.takeIf { it.isNotBlank() }
        if (wrapped != null) {
            val combined = Crypto.decrypt(Crypto.b64d(wrapped), Crypto.wrapKey(familyPassword, s))
            require(combined.size == 64) { "包裹的家属密钥长度非法" }
            dataKey = combined.copyOfRange(0, 32)
            mediaKey = combined.copyOfRange(32, 64)
        } else {
            dataKey = Crypto.dataKey(familyPassword, s)
            mediaKey = Crypto.mediaKey(familyPassword, s)
        }
        loginDataKey = null
        loginMediaKey = null
    }

    fun clear() {
        salt = null
        authVerifier = null
        dataKey = null
        mediaKey = null
        loginDataKey = null
        loginMediaKey = null
    }
}
