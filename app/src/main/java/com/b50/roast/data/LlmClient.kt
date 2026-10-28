package com.b50.roast.data

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容接口的最小客户端。只做两件事：
 *
 *   1. [fetchModels]  GET {baseUrl}{modelsPath}      → 拿到该供应商的模型 id 列表
 *   2. [testChat]     POST {baseUrl}{path}           → 发一条最小 chat，验证 Key / 模型能不能真跑
 *
 * 故意不用 OkHttp：全项目只有这里和 MaimaiApi 两处要联网，
 * 用 HttpURLConnection 少一个 3 MB 的依赖，APK 已经 58 MB 了。
 *
 * 约定：所有方法都是**阻塞**的，必须在 Dispatchers.IO 上调用。
 */
object LlmClient {

    private const val TAG = "B50Llm"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 40_000

    /** 把 baseUrl 和 path 拼成一个合法 URL，容忍两边多写/少写斜杠 */
    fun joinUrl(baseUrl: String, path: String): String {
        val b = baseUrl.trim().trimEnd('/')
        if (path.isBlank()) return b
        // path 里可能已经带了 ?query（Azure 那类），原样保留
        val p = if (path.startsWith("/")) path else "/$path"
        return b + p
    }

    // ---------------------------------------------------------------- 模型列表

    /**
     * 拉模型列表。成功返回 id 列表（已去空、去重），失败抛异常，异常 message 直接可以给用户看。
     *
     * 兼容两种返回：
     *   · OpenAI 标准 `{"data":[{"id":"gpt-4o"},…]}`
     *   · 少数网关直接给数组或者 `{"data":["gpt-4o",…]}`
     */
    fun fetchModels(
        baseUrl: String,
        modelsPath: String,
        key: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): List<String> {
        val url = joinUrl(baseUrl, modelsPath.ifBlank { "/models" })
        Log.w(TAG, "拉模型列表：$url")
        val (code, body) = request("GET", url, key, null, extraHeaders)

        if (code == 401 || code == 403) {
            throw IllegalStateException("Key 无效或没有模型列表权限（HTTP $code）")
        }
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code：" + body.take(200))
        }

        val ids = ArrayList<String>()
        val root = runCatching { JSONObject(body) }.getOrNull()
        if (root != null) {
            val arr = root.optJSONArray("data") ?: root.optJSONArray("models")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    val id = item?.optString("id")?.takeIf { it.isNotBlank() }
                        ?: arr.optString(i).takeIf { it.isNotBlank() }
                        ?: continue
                    if (id.isNotBlank()) ids.add(id)
                }
            }
        } else {
            // 直接是数组的情况
            val arr = runCatching { org.json.JSONArray(body) }.getOrNull()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("id")
                        ?: arr.optString(i)
                    if (id.isNotBlank()) ids.add(id)
                }
            }
        }

        if (ids.isEmpty()) throw IllegalStateException("接口通了，但没解析出模型列表，可能不是 OpenAI 兼容接口")
        return ids.distinct().sorted()
    }

    // ---------------------------------------------------------------- 测试连接

    /**
     * 发一条最小 chat 请求。成功返回 "OK · 用时 xxx ms · 回复：…"，失败抛异常。
     *
     * max_tokens 设 8：只要对方不报错就说明 Key、模型名、路径全对，
     * 同时花费可以忽略不计（一次不到 0.0001 元）。
     */
    fun testChat(
        baseUrl: String,
        path: String,
        key: String,
        model: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val url = joinUrl(baseUrl, path.ifBlank { "/chat/completions" })
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", org.json.JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "ping")
            }))
            put("max_tokens", 8)
            put("stream", false)
        }.toString()

        Log.w(TAG, "测试连接：$url  model=$model")
        val t0 = System.currentTimeMillis()
        val (code, body) = request("POST", url, key, payload, extraHeaders)
        val ms = System.currentTimeMillis() - t0

        if (code !in 200..299) {
            // 尽量把服务端的 error.message 抠出来，比 http code 有用得多
            val detail = runCatching {
                JSONObject(body).optJSONObject("error")?.optString("message")
            }.getOrNull()?.takeIf { it.isNotBlank() }
            throw IllegalStateException("HTTP $code：" + (detail ?: body.take(200)))
        }

        val reply = runCatching {
            JSONObject(body)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "(空回复)"

        return "连接正常 · " + ms + " ms · 模型回：" + reply.take(20)
    }

    // ---------------------------------------------------------------- 生成锐评

    /**
     * 真正生成一次锐评正文（system + user → 模型 → 原始文本）。
     *
     * 这里刻意做了三轮容错，都是真机上会遇到的：
     *   1. 先带 `response_format=json_object` 和 `reasoning.effort=low`；
     *      服务商不认就 400 → 去掉该参数重试（硅基流动、部分网关不支持 json 模式）。
     *   2. `deepseek-flash` 这类推理模型会把 max_tokens 全花在推理上，
     *      content 返回空串 → 把预算翻倍重试。
     *   3. 最多 4 次，仍失败就把最后一次的错误抛出去。
     *
     * @return 模型返回的**原始文本**（通常是 JSON 字符串）。解析交给 Python 侧的
     *         render.py `_parse_analysis_result`，它会兜底把非 JSON 也当正文用。
     */
    fun chat(
        baseUrl: String,
        path: String,
        key: String,
        model: String,
        system: String,
        user: String,
        extraHeaders: Map<String, String> = emptyMap(),
        maxTokens: Int = 20000,
        temperature: Double = 1.0,
        effort: String? = "low",
        jsonMode: Boolean = true,
        readTimeoutMs: Int = 240_000,
    ): String {
        val url = joinUrl(baseUrl, path.ifBlank { "/chat/completions" })

        fun payload(useJson: Boolean, useEffort: Boolean, tokens: Int): String =
            JSONObject().apply {
                put("model", model)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", system) })
                    put(JSONObject().apply { put("role", "user"); put("content", user) })
                })
                put("temperature", temperature)
                put("max_tokens", tokens)
                put("stream", false)
                if (useJson) put("response_format", JSONObject().put("type", "json_object"))
                if (useEffort && effort != null) put("reasoning", JSONObject().put("effort", effort))
            }.toString()

        var useJson = jsonMode
        var useEffort = effort != null
        var tokens = maxTokens
        var lastErr = ""

        for (attempt in 0 until 4) {
            val (code, body) = request("POST", url, key, payload(useJson, useEffort, tokens), extraHeaders, readTimeoutMs)
            Log.w(TAG, "chat attempt=$attempt code=$code tokens=$tokens json=$useJson effort=$useEffort")

            if (code in 200..299) {
                val content = runCatching {
                    JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")
                }.getOrNull().orEmpty()

                if (content.isNotBlank()) return content

                // 正文为空 = 推理把 token 吃光了
                lastErr = "模型只输出了推理、没有正文（max_tokens=$tokens 不够）"
                tokens = minOf(tokens * 2, 60000)
                continue
            }

            lastErr = "HTTP $code：" + errorDetail(body)
            if (code == 400 && useEffort) { useEffort = false; continue }   // 不认 reasoning
            if (code == 400 && useJson)   { useJson = false;   continue }   // 不认 json 模式
            throw IllegalStateException(lastErr)
        }
        throw IllegalStateException("锐评生成失败：" + lastErr)
    }

    /** 从错误响应里抠出服务端的 error.message，比裸 HTTP 码有用得多 */
    private fun errorDetail(body: String): String =
        runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)

    // ---------------------------------------------------------------- 底层

    private fun request(
        method: String,
        url: String,
        key: String,
        body: String?,
        extraHeaders: Map<String, String>,
        readTimeoutMs: Int = READ_TIMEOUT,
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = readTimeoutMs
                setRequestProperty("Authorization", "Bearer " + key.trim())
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "B50Roast/1.0 (Android)")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn?.disconnect()
        }
    }
}
