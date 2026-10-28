package com.b50.roast.data

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.min

/**
 * 水鱼 OAuth 客户端（设备码流程 + refresh_token 轮换），完全跑在 App 内。
 *
 * 对应 Python 版 oauth.py，但没有任何本地服务依赖：
 *   ① startDeviceFlow()   拿 user_code，让用户去浏览器点「同意」
 *   ② awaitDeviceToken()  每 interval 秒问一次，成功后把 refresh_token 落盘
 *   ③ accessToken()       5 分钟令牌；内存缓存 + 自动续票（并把轮换后的新 refresh_token 写回）
 *
 * 三个不能"优化"掉的服务端约束：
 *   · refresh_token 每次换票都会变，旧值随即失效 —— 必须回写
 *   · 换票限流 60 次/小时，所以 access_token 必须缓存（5 分钟，提前 30 秒换）
 *   · 本应用是「公开客户端」：只有 client_id、没有 secret，人人可授权
 *
 * client_id 以「分段 + XOR」形式存放，静态扫描 dex 字符串搜不到明文；
 * 再配合 R8 混淆，普通逆向拿不到原值。（公开客户端 id 本身不算机密，
 * 这里只是防止被人随手抄去用。）
 */
class OAuthClient(private val ctx: Context) {

    companion object {
        private const val AUTH_BASE = "https://auth.diving-fish.com"
        private const val SCOPE = "prober.records.read"

        /** client_id 的 XOR 编码（key = 0x5B），运行时还原，避免明文出现在 dex */
        private val CID_ENC = intArrayOf(
            0x38, 0x38, 0x6C, 0x6E, 0x38, 0x69, 0x38, 0x6F,
            0x6F, 0x6B, 0x3A, 0x68, 0x6F, 0x6F, 0x6D, 0x3E,
            0x39, 0x3D, 0x3F, 0x6C, 0x68, 0x6C, 0x3A, 0x39,
            0x3F, 0x6A, 0x38, 0x6F, 0x3E, 0x3A, 0x6A, 0x6E,
        )
        private const val CID_KEY = 0x5B

        private const val PREFS = "b50_oauth"
        private const val K_RT = "refresh_token"
        private const val K_SAVED_AT = "saved_at"

        private const val TOKEN_TTL_MS = 5 * 60_000L
        private const val EARLY_MS = 30_000L
    }

    class OAuthException(message: String) : Exception(message)

    data class DeviceFlow(
        val deviceCode: String,
        val userCode: String,
        val verifyUrl: String,
        val interval: Int,
        val expiresIn: Int,
    )

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var cachedToken: String = ""
    @Volatile private var cachedExpireAt: Long = 0L
    private val tokenLock = Any()

    // ---------------------------------------------------------------- 身份

    /** 运行时还原内置 client_id */
    fun builtinClientId(): String {
        val bytes = ByteArray(CID_ENC.size) { (CID_ENC[it] xor CID_KEY).toByte() }
        return String(bytes, Charsets.UTF_8)
    }

    fun isBound(): Boolean = !prefs.getString(K_RT, null).isNullOrBlank()

    fun boundAt(): Long = prefs.getLong(K_SAVED_AT, 0L)

    /** 解绑：只删本机凭据，不影响水鱼服务端的授权记录 */
    fun logout() {
        prefs.edit().clear().apply()
        cachedToken = ""
        cachedExpireAt = 0
    }

    /** 调试用：直接从外部导入一份 refresh_token（验证流程用，正式版不会调用） */
    fun importRefreshToken(rt: String) {
        if (rt.isNotBlank()) saveRefreshToken(rt.trim())
    }

    private fun saveRefreshToken(rt: String) {
        if (rt.isBlank()) return
        prefs.edit().putString(K_RT, rt).putLong(K_SAVED_AT, System.currentTimeMillis()).apply()
    }

    // ---------------------------------------------------------------- ① 发起绑定

    fun startDeviceFlow(): DeviceFlow {
        val body = form(mapOf("client_id" to builtinClientId(), "scope" to SCOPE))
        val json = postForm("$AUTH_BASE/oauth/device_authorization", body)
        val deviceCode = json.optString("device_code")
        if (deviceCode.isBlank()) {
            throw OAuthException("授权服务器没返回 device_code：${json.toString().take(160)}")
        }
        val verify = json.optString("verification_uri_complete").ifBlank {
            json.optString("verification_uri")
        }
        return DeviceFlow(
            deviceCode = deviceCode,
            userCode = json.optString("user_code"),
            verifyUrl = verify,
            interval = json.optInt("interval", 5).coerceAtLeast(1),
            expiresIn = json.optInt("expires_in", 600),
        )
    }

    // ---------------------------------------------------------------- ② 轮询拿票

    /** 阻塞轮询直到用户点「同意」。必须在 IO 线程调用。返回已落盘的 refresh_token。 */
    fun awaitDeviceToken(flow: DeviceFlow): String {
        val deadline = System.currentTimeMillis() + flow.expiresIn * 1000L
        var interval = flow.interval
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted) throw OAuthException("已取消绑定")
            Thread.sleep(interval * 1000L)
            val body = form(
                mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to flow.deviceCode,
                    "client_id" to builtinClientId(),
                )
            )
            val (code, text) = postFormRaw("$AUTH_BASE/oauth/token", body)
            if (code == 200) {
                val json = JSONObject(text)
                val rt = json.optString("refresh_token")
                if (rt.isBlank()) throw OAuthException("授权成功但服务器没给 refresh_token（旧格式客户端？）")
                saveRefreshToken(rt)
                synchronized(tokenLock) {
                    cachedToken = json.optString("access_token")
                    cachedExpireAt = System.currentTimeMillis() + TOKEN_TTL_MS
                }
                return rt
            }
            val err = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
            when (err) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = min(interval + 2, 15)
                "access_denied", "expired_token" ->
                    throw OAuthException("授权没完成（被拒绝或用户码已过期）。重新点一次绑定即可。")
                else -> throw OAuthException("换票失败 HTTP $code：${text.take(200)}")
            }
        }
        throw OAuthException("等待授权超时（用户码 ${flow.expiresIn / 60} 分钟有效）。重新绑定即可。")
    }

    // ---------------------------------------------------------------- ③ 取令牌

    /** 5 分钟令牌：内存缓存，过期前 30 秒自动换。必须在 IO 线程调用。 */
    fun accessToken(force: Boolean = false): String {
        synchronized(tokenLock) {
            val now = System.currentTimeMillis()
            if (!force && cachedToken.isNotBlank() && now < cachedExpireAt - EARLY_MS) return cachedToken

            val rt = prefs.getString(K_RT, null)?.takeIf { it.isNotBlank() }
                ?: throw OAuthException("还没绑定水鱼账号，无法读取全量成绩。")

            val body = form(
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to rt,
                    "client_id" to builtinClientId(),
                )
            )
            val (code, text) = postFormRaw("$AUTH_BASE/oauth/token", body)
            if (code != 200) {
                val hint = when {
                    text.contains("invalid_grant") -> "授权已失效，请重新绑定一次。"
                    text.contains("invalid_client") -> "应用配置异常，请更新到新版 App。"
                    code == 429 -> "换票太频繁（限 60 次/小时），过一会儿再试。"
                    else -> "换票失败 HTTP $code：${text.take(160)}"
                }
                throw OAuthException(hint)
            }
            val json = JSONObject(text)
            val tok = json.optString("access_token")
            if (tok.isBlank()) throw OAuthException("换票没返回 access_token：${text.take(160)}")
            // ★ 轮换：新 refresh_token 立刻覆盖旧的，否则下次换不出来
            json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { saveRefreshToken(it) }
            cachedToken = tok
            cachedExpireAt = System.currentTimeMillis() + TOKEN_TTL_MS
            return tok
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun form(m: Map<String, String>): String =
        m.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }

    private fun postForm(url: String, body: String): JSONObject {
        val (code, text) = postFormRaw(url, body)
        if (code != 200) throw OAuthException("HTTP $code：${text.take(200)}")
        return JSONObject(text)
    }

    private fun postFormRaw(url: String, body: String): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        }
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }
}
