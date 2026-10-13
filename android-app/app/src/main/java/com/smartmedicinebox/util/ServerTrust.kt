/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.util

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 服务器身份信任：SPKI 证书锁定 + 一次性配对码建立信任。
 *
 * - 已配对：仅信任与本机保存的 SPKI 指纹一致的证书（TrustManager 精确比对 + 有效期校验），
 *   HostnameVerifier 绑定配对时的 host（非无条件放行，也不依赖自签证书的 SAN）。
 * - 未配对：走系统信任链（公网 HTTPS / Let's Encrypt 开箱即用）；
 *   证书不受信任是进入配对流程的触发条件。
 * - 配对模式：一次性 throwaway 客户端（接受任意证书仅用于捕获握手证书），
 *   配对码证明通过后才落盘 pin；正常模式绝不接受任意证书。
 */
object ServerTrust {

    /** 服务器身份不匹配的标记字符串：错误分类按 cause 链识别它 */
    const val PIN_MISMATCH_MARKER = "server identity mismatch"

    /** 服务器身份失败类型：UI 据此给出不同引导 */
    enum class TrustFailure { PIN_MISMATCH, UNTRUSTED, CONNECT, OTHER }

    /** 配对 TLS 材料：SSLContext + 同源 X509TrustManager（OkHttp 要求两者一致） */
    class PairTls(val sslContext: SSLContext, val trustManager: X509TrustManager)

    /** SPKI 指纹：SHA-256(证书公钥 DER) 的小写 hex，与后端 server_identity.get_fingerprint() 逐字节一致 */
    fun spkiSha256Hex(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 已配对服务器的 pin 存取（按 host:port） */
    fun getPin(host: String, port: Int): String? = Prefs.pinnedServersMap["$host:$port"]

    fun savePin(host: String, port: Int, pinHex: String) {
        val map = Prefs.pinnedServersMap.toMutableMap()
        map["$host:$port"] = pinHex
        Prefs.pinnedServersMap = map
    }

    fun removePin(host: String, port: Int) {
        val map = Prefs.pinnedServersMap.toMutableMap()
        map.remove("$host:$port")
        Prefs.pinnedServersMap = map
    }

    /** 已配对模式：只信任与 pin 一致的证书（指纹不符即抛带标记的 CertificateException） */
    fun pinnedTls(pinHex: String): PairTls {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("empty chain")
                if (!MessageDigest.isEqual(
                        spkiSha256Hex(leaf).toByteArray(Charsets.US_ASCII),
                        pinHex.toByteArray(Charsets.US_ASCII)
                    )
                ) {
                    throw CertificateException(PIN_MISMATCH_MARKER)
                }
                leaf.checkValidity() // 过期证书同样拒绝
            }
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        return PairTls(ctx, tm)
    }

    /** 配对模式：接受任意证书，仅把叶子证书交给 capture 用于计算指纹（只用于一次性配对客户端） */
    fun pairingTls(capture: (X509Certificate) -> Unit): PairTls {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                chain.firstOrNull()?.let(capture)
            }
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        return PairTls(ctx, tm)
    }

    /** 已配对模式主机名校验：必须与配对时保存的主机一致（非无条件放行） */
    fun pinnedHostnameVerifier(expectedHost: String): HostnameVerifier =
        HostnameVerifier { hostname, _ -> hostname.equals(expectedHost, ignoreCase = true) }

    /** 配对模式主机名校验（仅用于一次性配对客户端；信任由配对码证明建立，而非主机名） */
    fun pairingHostnameVerifier(): HostnameVerifier = HostnameVerifier { _, _ -> true }

    /** 错误分类：走 cause 链识别身份/证书/网络错误，避免把安全失败误报成「网络不好」 */
    fun classify(e: Throwable): TrustFailure {
        val chain = generateSequence(e as Throwable?) { it.cause }.toList()
        if (chain.any { it.message.orEmpty().contains(PIN_MISMATCH_MARKER) }) return TrustFailure.PIN_MISMATCH
        if (chain.any {
                it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException
            }
        ) return TrustFailure.UNTRUSTED
        if (chain.any { it is SSLHandshakeException }) return TrustFailure.UNTRUSTED
        if (chain.any { it is ConnectException || it is UnknownHostException || it is SocketTimeoutException }) {
            return TrustFailure.CONNECT
        }
        if (e is java.io.IOException) return TrustFailure.CONNECT
        return TrustFailure.OTHER
    }
}
