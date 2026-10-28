package com.b50.roast.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 水鱼全量成绩接口（GET /player/records，Bearer）。
 *
 * 服务端铁律：这个端点不接受 qq / username 参数，返回的永远是「令牌持有人本人」。
 * 所以 App 的分发形态就是"每台手机各自绑定各自出图"，不提供查别人。
 *
 * 缓存：落到 App 私有目录 filesDir/cache/records.json，默认 10 分钟内不重拉。
 *  · 每用户 200 次/日配额，缓存能省一大截
 *  · 断网时也能用上次的记录出图（带"数据截止时间"标记）
 */
class MaimaiApi(private val ctx: Context, private val oauth: OAuthClient) {

    companion object {
        private const val BASE = "https://www.diving-fish.com/api/maimaidxprober"

        /**
         * 缓存"新鲜"的时长。超过它就认为数据该刷新了 —— 但这个判断**只用于界面提示**，
         * 不会再自动联网：要不要花一次额度刷新由用户拍板。
         */
        const val FRESH_MS = 6 * 60 * 60_000L
    }

    class ApiException(message: String) : Exception(message)

    private val cacheDir = File(ctx.filesDir, "cache").apply { mkdirs() }
    private val cacheFile = File(cacheDir, "records.json")

    data class Result(val data: JSONObject, val fromCache: Boolean, val fetchedAt: Long)

    /** 缓存年龄（毫秒），没有缓存返回 Long.MAX_VALUE */
    fun cacheAge(): Long =
        if (cacheFile.exists()) System.currentTimeMillis() - cacheFile.lastModified() else Long.MAX_VALUE

    fun cacheTime(): Long = if (cacheFile.exists()) cacheFile.lastModified() else 0L

    /** 本地有没有可用的成绩缓存 */
    fun hasCache(): Boolean = cacheFile.exists() && cacheFile.length() > 0

    /**
     * 拉全量记录。必须在 IO 线程调用。
     *
     * @param force        true  = 忽略缓存，强制联网拉（会消耗一次额度，调用方负责扣）
     * @param allowNetwork false = 只读本地缓存，绝不发请求；没有缓存就抛异常
     *
     * 注意：非 force 时**只要本地有缓存就直接用，不看新旧**。
     * 数据要不要更新由用户在界面上决定，不在这里偷偷联网浪费额度。
     */
    fun fetchRecords(force: Boolean = false, allowNetwork: Boolean = true): Result {
        if (!force && hasCache()) {
            runCatching { return Result(JSONObject(cacheFile.readText()), true, cacheFile.lastModified()) }
                .onFailure { Log.w("B50Api", "缓存解析失败", it) }
        }

        if (!allowNetwork) {
            throw ApiException("本地还没有成绩缓存，需要联网拉取一次")
        }

        var token = oauth.accessToken()
        var resp = get("$BASE/player/records", token)
        if (resp.first == 401) {
            Log.w("B50Api", "令牌被服务端判失效，强刷一次")
            token = oauth.accessToken(force = true)
            resp = get("$BASE/player/records", token)
        }

        val (code, body) = resp
        if (code != 200) {
            val msg = when (code) {
                403 -> "这个账号还没同意水鱼用户协议，去水鱼官网登录点一次同意再试。"
                404 -> "水鱼查不到这个账号的成绩，确认账号里导入过成绩。"
                410 -> "接口已废弃（410），请更新到新版 App。"
                429 -> "今日全量查询配额用尽（每用户 200 次/日），明天再来。"
                else -> "拉取成绩失败 HTTP $code：${body.take(160)}"
            }
            throw ApiException(msg)
        }

        val json = JSONObject(body)
        val n = json.optJSONArray("records")?.length() ?: 0
        if (n == 0) throw ApiException("这个账号还没有任何成绩记录，先去水鱼导入成绩。")

        runCatching {
            cacheFile.writeText(body)
            Log.w("B50Api", "全量记录已缓存：$n 条 → ${cacheFile.absolutePath}")
        }
        return Result(json, false, System.currentTimeMillis())
    }

    /**
     * 只用公开接口拿 B15 的 song_id 列表，专用于「当前版本」校准。
     * 这不是 quick 出图路径 —— 只取 15 个曲目 id 投票，成绩数据仍全部来自 OAuth 全量记录。
     */
    fun fetchCalibDxIds(qq: String?, username: String?): List<String> {
        val body = JSONObject()
        when {
            !qq.isNullOrBlank() -> {
                val n = qq.trim().toLongOrNull() ?: return emptyList()
                body.put("qq", n)
            }
            !username.isNullOrBlank() -> body.put("username", username.trim())
            else -> return emptyList()
        }
        val (code, text) = postJson("$BASE/query/player", body.toString())
        if (code != 200) {
            Log.w("B50Api", "版本校准失败 HTTP $code")
            return emptyList()
        }
        val dx = runCatching {
            JSONObject(text).optJSONObject("charts")?.optJSONArray("dx")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(dx.length())
        for (i in 0 until dx.length()) {
            val o = dx.optJSONObject(i) ?: continue
            val id = o.optString("song_id").ifBlank { o.optString("music_id") }
            if (id.isNotBlank()) out.add(id)
        }
        return out
    }

    private fun postJson(url: String, body: String): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
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

    /** 只读缓存（绝不出网）。没有缓存返回 null。 */
    fun cachedRecords(): JSONObject? =
        if (cacheFile.exists()) runCatching { JSONObject(cacheFile.readText()) }.getOrNull() else null

    fun clearCache() {
        cacheFile.delete()
    }

    private fun get(url: String, token: String): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }
}
