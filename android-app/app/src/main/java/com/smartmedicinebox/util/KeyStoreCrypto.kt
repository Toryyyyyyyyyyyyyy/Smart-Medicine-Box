/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 包裹/解包敏感密钥：登录派生密钥落盘前先用本机 Keystore 密钥 AES-GCM 加密。
 *
 * Keystore 密钥绑定应用签名 + UID：更新 APK 不丢，卸载重装（或换签名）后失效。
 * 解包失败（密钥丢失/被清除）时抛异常，由调用方回退到密码解锁。
 */
object KeyStoreCrypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "login_key_wrapper"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_LEN_BITS = 128

    /** 加密，返回 iv(12) || ciphertext+tag */
    fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    /** 解密；Keystore 密钥缺失/损坏时抛异常 */
    fun unwrap(payload: ByteArray): ByteArray {
        require(payload.size > IV_LEN) { "wrapped key too short" }
        val iv = payload.copyOfRange(0, IV_LEN)
        val ct = payload.copyOfRange(IV_LEN, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LEN_BITS, iv))
        return cipher.doFinal(ct)
    }

    /** 取或创建 Keystore AES-256-GCM 密钥（应用私有，卸载重装/换签名后自动失效） */
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }
}
