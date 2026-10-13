/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.api

import com.smartmedicinebox.App
import com.smartmedicinebox.R
import com.smartmedicinebox.util.Prefs
import com.smartmedicinebox.util.ServerTrust
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Retrofit 单例：动态 baseUrl，统一超时与安全传输配置。
 *
 * 全部流量必须 HTTPS：明文 HTTP 直接拒绝（防御性，UI 校验外兜底）。
 * 已配对服务器（存在 pin）用锁定证书的 SSLContext + 绑定主机的 HostnameVerifier；
 * 未配对走系统信任链（公网 HTTPS / Let's Encrypt 开箱即用）。
 */
object ApiClient {

    private var retrofit: Retrofit? = null
    private var cachedKey: String = ""

    fun getService(): ApiService {
        val baseUrl = Prefs.serverBaseUrl
        val key = buildKey(baseUrl)
        if (retrofit == null || cachedKey != key) {
            cachedKey = key
            retrofit = buildRetrofit(baseUrl)
        }
        return retrofit!!.create(ApiService::class.java)
    }

    /** 缓存键：地址 + pin 状态（配对成功/换地址后重建客户端） */
    private fun buildKey(baseUrl: String): String {
        val parsed = baseUrl.toHttpUrlOrNull()
        val pin = if (parsed != null) ServerTrust.getPin(parsed.host, parsed.port) else null
        return "$baseUrl|${pin ?: "nopin"}"
    }

    /** 丢弃缓存的 Retrofit（配对成功、服务器地址变化后调用） */
    fun invalidate() {
        retrofit = null
        cachedKey = ""
    }

    private fun buildRetrofit(baseUrl: String): Retrofit {
        val parsed = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("非法服务器地址: $baseUrl")
        require(parsed.scheme == "https") { "服务器地址必须为 https" }
        // 确保 baseUrl 以 / 结尾
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val logging = HttpLoggingInterceptor().apply {
            // 不记录请求/响应正文：避免任何敏感内容（含历史明文密码、密文数据）进入日志
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val builder = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

        // 已配对服务器：只信 pin 对应的证书；未配对：系统信任链
        val pin = ServerTrust.getPin(parsed.host, parsed.port)
        if (pin != null) {
            val tls = ServerTrust.pinnedTls(pin)
            builder.sslSocketFactory(tls.sslContext.socketFactory, tls.trustManager)
                .hostnameVerifier(ServerTrust.pinnedHostnameVerifier(parsed.host))
        }

        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(builder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * 从响应中提取可读错误信息：HTTP 状态码 + 后端返回的 detail。
     */
    fun errorText(response: Response<*>): String {
        val code = response.code()
        val detail = try {
            response.errorBody()?.string()
                ?.let { JSONObject(it).optString("detail") }
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        return when {
            detail != null -> "HTTP $code: $detail"
            response.message().isNotBlank() -> "HTTP $code ${response.message()}"
            else -> "HTTP $code"
        }
    }

    /**
     * 从 Retrofit 的 HttpException 中提取后端 detail（如「密码错误」），
     * 用于 suspend 非 Response 返回类型在非 2xx 时抛出的异常。
     */
    fun errorText(e: retrofit2.HttpException): String {
        val detail = try {
            e.response()?.errorBody()?.string()
                ?.let { JSONObject(it).optString("detail") }
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        return detail ?: "HTTP ${e.code()}"
    }

    /** 把底层异常转成用户可读信息：区分服务器身份变化/证书不受信任/网络故障/服务器原因 */
    fun friendlyMessage(e: Throwable): String = when (ServerTrust.classify(e)) {
        ServerTrust.TrustFailure.PIN_MISMATCH -> App.instance.getString(R.string.err_server_identity_changed)
        ServerTrust.TrustFailure.UNTRUSTED -> App.instance.getString(R.string.err_server_untrusted)
        ServerTrust.TrustFailure.CONNECT -> App.instance.getString(R.string.err_network)
        ServerTrust.TrustFailure.OTHER -> e.message ?: App.instance.getString(R.string.refresh_failed)
    }
}

/**
 * 一次性局域网配对客户端。
 *
 * 用「接受任意证书」的 throwaway OkHttpClient 仅完成一次 TLS 握手以捕获服务器证书，
 * 提交配对码证明（HMAC 绑定服务器 SPKI 指纹），服务器校验通过、且回显指纹与捕获
 * 证书一致后，才把 pin 落盘。用完立即断开连接并释放线程，绝不污染正常客户端。
 */
object PairingClient {

    const val DOMAIN_SEP = "smart-medicine-box-pairing-v1"

    /** 配对码派生密钥：SHA-256(域分离标签 || 码)，与后端 pairing_service.derive_code_key 逐字节一致 */
    fun deriveCodeKey(code: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(DOMAIN_SEP.toByteArray(Charsets.UTF_8))
        md.update(code.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    /** proof = Base64(HMAC-SHA256(派生密钥, 指纹 hex 的 ASCII))，与后端 verify_pairing 一致 */
    fun computePairProof(code: String, fingerprintHex: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(deriveCodeKey(code), "HmacSHA256"))
        return android.util.Base64.encodeToString(
            mac.doFinal(fingerprintHex.toByteArray(Charsets.US_ASCII)),
            android.util.Base64.NO_WRAP
        )
    }

    sealed class Result {
        data class Success(val fingerprint: String) : Result()
        data class Failed(val message: String) : Result()
    }

    /** 执行配对：成功即把 pin 落盘并作废旧 Retrofit 缓存 */
    suspend fun verify(baseUrl: String, code: String): Result {
        val parsed = baseUrl.toHttpUrlOrNull() ?: return Result.Failed("非法服务器地址")
        require(parsed.scheme == "https") { "服务器地址必须为 https" }
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        var captured: X509Certificate? = null
        val tls = ServerTrust.pairingTls { captured = it }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(tls.sslContext.socketFactory, tls.trustManager)
            .hostnameVerifier(ServerTrust.pairingHostnameVerifier())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        try {
            val service = retrofit.create(ApiService::class.java)
            // 证书来自 TLS 握手本身：必须先发起一次请求触发握手，capture 回调才会拿到证书
            service.health()
            val cert = captured ?: return Result.Failed("未能获取服务器证书，请确认地址正确")
            val fingerprint = ServerTrust.spkiSha256Hex(cert)
            val proof = computePairProof(code, fingerprint)
            val resp = service.pairVerify(PairVerifyRequest(proof, fingerprint))
            if (!resp.isSuccessful) {
                return Result.Failed(ApiClient.errorText(resp))
            }
            // 服务器回显指纹必须与本次捕获的证书一致，否则视为中间人，拒绝配对
            val echoed = resp.body()?.data?.fingerprint
            if (echoed == null || !MessageDigest.isEqual(
                    echoed.toByteArray(Charsets.US_ASCII),
                    fingerprint.toByteArray(Charsets.US_ASCII)
                )
            ) {
                return Result.Failed("服务器身份校验失败")
            }
            ServerTrust.savePin(parsed.host, parsed.port, fingerprint)
            ApiClient.invalidate()
            return Result.Success(fingerprint)
        } catch (e: Exception) {
            return Result.Failed(ApiClient.friendlyMessage(e))
        } finally {
            // 一次性客户端：断开所有连接并释放线程，绝不让「接受任意证书」的客户端存活。
            // 必须在 IO 线程执行——TLS 关闭握手包含网络写，主线程会抛 NetworkOnMainThreadException
            withContext(Dispatchers.IO) {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
