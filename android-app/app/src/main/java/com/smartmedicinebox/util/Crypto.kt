/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.util

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端加密工具：PBKDF2 派生 + AES-256-GCM 加解密 + HMAC-SHA256。
 * 明文密码永不上传服务器，所有派生/加解密仅在本端发生（验证子本地派生后上传）：
 *   - PBKDF2WithHmacSHA256，150_000 迭代，256 位密钥
 *   - 域分离通过「盐拼接标签」实现：salt || "data" / "auth" / "media"
 *   - AES/GCM/NoPadding，12 字节 nonce，128 位 tag，每次加密用全新 nonce
 *
 * 密文格式：nonce(12) || ciphertext || tag(16)。
 */
object Crypto {

    const val PBKDF2_ITERATIONS = 150_000
    const val KEY_LEN_BITS = 256
    const val NONCE_LEN = 12
    const val TAG_LEN_BITS = 128

    private const val INFO_DATA = "data"
    private const val INFO_AUTH = "auth"
    private const val INFO_MEDIA = "media"
    private const val INFO_WRAP = "wrap"

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun randomNonce(): ByteArray = randomBytes(NONCE_LEN)

    fun randomSalt(): ByteArray = randomBytes(16)

    /** PBKDF2 密钥派生，info 为域分离标签 */
    fun deriveKey(password: String, salt: ByteArray, info: String): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt + info.toByteArray(Charsets.UTF_8),
            PBKDF2_ITERATIONS,
            KEY_LEN_BITS
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    /** 数据加密密钥（仅存在于客户端，服务器不持有） */
    fun dataKey(password: String, salt: ByteArray): ByteArray = deriveKey(password, salt, INFO_DATA)

    /** 家属鉴权验证子（存于服务器，用于无登录挑战应答） */
    fun authVerifier(password: String, salt: ByteArray): ByteArray = deriveKey(password, salt, INFO_AUTH)

    /** 媒体加密密钥 */
    fun mediaKey(password: String, salt: ByteArray): ByteArray = deriveKey(password, salt, INFO_MEDIA)

    /** 密钥包裹密钥：家人密码只包裹登录派生数据/媒体密钥，不直接加密数据 */
    fun wrapKey(password: String, salt: ByteArray): ByteArray = deriveKey(password, salt, INFO_WRAP)

    // ---- 登录密码验证材料（明文密码永不上传服务器） ----

    /** 登录密码哈希的 PBKDF2 迭代次数：与后端 PASSWORD_HASH_ITERATIONS 一致 */
    const val LOGIN_PBKDF2_ITERATIONS = 200_000

    /** 登录密码哈希（注册时本地派生后提交 hex；与后端存储格式一致，32 字节） */
    fun loginHash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, LOGIN_PBKDF2_ITERATIONS, KEY_LEN_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    /** 登录证明：proof = HMAC-SHA256(登录密码哈希, challenge)；服务器只做证明校验，收不到明文密码 */
    fun loginProof(password: String, salt: ByteArray, challenge: ByteArray): ByteArray =
        hmacSha256(loginHash(password, salt), challenge)

    /** AES-256-GCM 加密，返回 nonce || ciphertext+tag */
    fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val nonce = randomNonce()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN_BITS, nonce))
        val ct = cipher.doFinal(plain)
        return nonce + ct
    }

    /** AES-256-GCM 解密；密码错误/数据损坏时抛 AEADBadTagException */
    fun decrypt(payload: ByteArray, key: ByteArray): ByteArray {
        require(payload.size > NONCE_LEN) { "ciphertext too short" }
        val nonce = payload.copyOfRange(0, NONCE_LEN)
        val ct = payload.copyOfRange(NONCE_LEN, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN_BITS, nonce))
        return cipher.doFinal(ct)
    }

    /** HMAC-SHA256，用于家属无登录证明 proof = HMAC(authVerifier, challenge) */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun b64e(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** 字节数组 → hex 字符串（密码盐/哈希的传输格式） */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** hex 字符串 → 字节数组 */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "invalid hex length" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
