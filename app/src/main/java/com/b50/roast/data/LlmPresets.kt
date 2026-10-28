package com.b50.roast.data

/**
 * 一个供应商预设。界面上点一下就把 baseUrl / path / 模型灌进去，用户只需要填 Key。
 *
 * @param id          稳定标识，用来存 prefs（换版本不能改名，否则用户的 Key 会丢）
 * @param label       界面显示名
 * @param baseUrl     API 基础地址，到 /v1 这一层为止
 * @param path        chat 补全的路径，拼在 baseUrl 后面
 * @param modelsPath  拉模型列表的路径；空串表示该家不支持，界面上隐藏「获取」按钮
 * @param models      内置兜底模型名，联网拉取失败时至少能选
 * @param keyHint     Key 输入框的占位提示
 * @param extraHeaders 少数网关需要的额外请求头（如 OpenRouter 的来源标识）
 * @param note        给用户看的特殊说明，空串则不显示
 */
data class LlmProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val path: String = "/chat/completions",
    val modelsPath: String = "/models",
    val models: List<String> = emptyList(),
    val keyHint: String = "sk-…",
    val extraHeaders: Map<String, String> = emptyMap(),
    val note: String = "",
)

/**
 * 供应商预设表。
 *
 * ⚠️ 改这张表只会影响「切换供应商时灌进去的默认值」，
 * 用户在输入框里手改过的内容以输入框为准，不会被覆盖回去。
 *
 * 各家 /models 端点都实测过存在（无 Key 返回 401，说明路径对）。
 */
object LlmPresets {

    /** 自定义：什么都不预填，全手填 */
    val CUSTOM = LlmProvider(
        id = "custom",
        label = "自定义",
        baseUrl = "",
        path = "/chat/completions",
        modelsPath = "/models",
        models = emptyList(),
        keyHint = "sk-…（你自己的网关）",
        note = "任何 OpenAI 兼容接口都能接：基础地址填到 /v1 那一层，路径一般是 /chat/completions。",
    )

    val ALL: List<LlmProvider> = listOf(
        LlmProvider(
            id = "deepseek",
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
            keyHint = "sk-…（platform.deepseek.com 申请）",
        ),
        LlmProvider(
            id = "siliconflow",
            label = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            models = listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct"),
            keyHint = "sk-…（cloud.siliconflow.cn 申请）",
            note = "模型名要带「厂商/」前缀，比如 deepseek-ai/DeepSeek-V3。",
        ),
        LlmProvider(
            id = "moonshot",
            label = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            models = listOf("moonshot-v1-8k", "moonshot-v1-32k"),
            keyHint = "sk-…（platform.moonshot.cn 申请）",
        ),
        LlmProvider(
            id = "zhipu",
            label = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            models = listOf("glm-4-flash", "glm-4-plus", "glm-4-air"),
            keyHint = "「id.secret」形式的 API Key（bigmodel.cn 申请）",
        ),
        LlmProvider(
            id = "dashscope",
            label = "通义百炼",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            models = listOf("qwen-plus", "qwen-max", "qwen-turbo"),
            keyHint = "sk-…（bailian.console.aliyun.com 申请）",
        ),
        LlmProvider(
            id = "volces",
            label = "火山方舟",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            models = emptyList(),
            keyHint = "方舟 API Key",
            note = "火山要求模型填「推理接入点 ID」（形如 ep-2024xxxx），不是模型原名，建好接入点后把 ID 填进模型框。",
        ),
        LlmProvider(
            id = "hunyuan",
            label = "腾讯混元",
            baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
            models = listOf("hunyuan-turbos-latest", "hunyuan-lite"),
            keyHint = "混元 API Key",
        ),
        LlmProvider(
            id = "openrouter",
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            models = listOf("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet"),
            keyHint = "sk-or-v1-…（openrouter.ai/keys 申请）",
            extraHeaders = mapOf(
                "HTTP-Referer" to "https://github.com/b50-roast",
                "X-Title" to "B50 Roast",
            ),
            note = "模型名带厂商前缀，如 openai/gpt-4o-mini。",
        ),
        LlmProvider(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            models = listOf("gpt-4o-mini", "gpt-4o"),
            keyHint = "sk-…（platform.openai.com 申请）",
        ),
        CUSTOM,
    )

    fun byId(id: String): LlmProvider = ALL.firstOrNull { it.id == id } ?: CUSTOM

    /**
     * 老版本只存了 llm_url，没有供应商 id。
     * 升级上来时用它把地址反查回某个预设，界面上就不会显示成「自定义」。
     */
    fun matchByUrl(url: String): LlmProvider? {
        val u = url.trim().trimEnd('/')
        if (u.isBlank()) return null
        return ALL.firstOrNull { it.id != CUSTOM.id && it.baseUrl.trimEnd('/') == u }
    }
}
