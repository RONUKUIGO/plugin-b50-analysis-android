package com.b50.roast.data

/**
 * 一个供应商预设。界面上点一下就把 baseUrl / path / 模型灌进去，用户只需要填 Key。
 *
 * @param id          稳定标识，用来存 prefs（**换版本不能改名**，否则用户的 Key 会丢）
 * @param label       界面显示名
 * @param baseUrl     API 基础地址，到 /v1（或各家等价层）为止
 * @param path        chat 补全的路径，拼在 baseUrl 后面
 * @param modelsPath  拉模型列表的路径；空串表示该家不支持，界面上隐藏「获取」按钮
 * @param models      内置兜底模型名，联网拉取失败时至少能选（**只作兜底**，权威列表按「获取」）
 * @param keyHint     Key 输入框的占位提示
 * @param extraHeaders 少数网关需要的额外请求头
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
 * 供应商预设表（用户 2026-10-28 定稿：只留 5 家）。
 *
 * 取舍原因：这 5 家是「有官方 OpenAI 兼容层 + 模型够用」的组合，
 * 其余（硅基流动 / 智谱 GLM / 通义百炼 / 火山方舟 / 腾讯混元 / OpenRouter）
 * 要么是聚合站、要么要求填接入点 ID，配置成本高于收益，已按用户表格删除。
 *
 * ⚠️ 三条硬约束：
 *  1. 改这张表只会影响「切换供应商时灌进去的默认值」，用户在输入框手改过的内容以输入框为准。
 *  2. **删掉某家的预设后，用户的 Key 不会丢** —— `byId()` 找不到就回落 `CUSTOM`，
 *     而 url/path/key/model 是另外四个独立 prefs 字段，值原样还在输入框里。
 *  3. 新增供应商必须实测 `GET {baseUrl}{modelsPath}` 有响应（哪怕是 401），
 *     否则「获取」按钮会直接报错。Gemini / Claude 两家已实测：
 *     Gemini → 400 "Please pass a valid API key"；Claude → 401 "Invalid Anthropic API Key"。
 */
object LlmPresets {

    /** 自定义：什么都不预填，全手填。**必须留在最后**，也是所有未知 id 的兜底。 */
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
            id = "gemini",
            label = "Gemini",
            // Google 官方的 OpenAI 兼容层；baseUrl 到 /openai 为止，后面照常接 /chat/completions
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            path = "/chat/completions",
            modelsPath = "/models",
            models = listOf("gemini-2.5-flash", "gemini-2.5-pro"),
            keyHint = "AIza…（aistudio.google.com 拿 Key）",
            note = "走 Google 官方 OpenAI 兼容层。模型名可以点「获取」拉全量；" +
                "内置的只是兜底，名字对不上就以拉到的为准。",
        ),
        LlmProvider(
            id = "claude",
            label = "Claude",
            // Anthropic 官方的 OpenAI SDK 兼容层：baseUrl 到 /v1，path 仍是 /chat/completions
            baseUrl = "https://api.anthropic.com/v1",
            path = "/chat/completions",
            modelsPath = "/models",
            models = listOf("claude-sonnet-4-5", "claude-haiku-4-5"),
            keyHint = "sk-ant-…（console.anthropic.com 申请）",
            note = "走 Anthropic 官方 OpenAI 兼容层，Key 直接填 sk-ant- 开头那种。" +
                "模型名用别名即可，别带日期后缀；点「获取」可拉当前可用列表。",
        ),
        LlmProvider(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            models = listOf("gpt-4o-mini", "gpt-4o"),
            keyHint = "sk-…（platform.openai.com 申请）",
            note = "内置两个便宜模型作兜底；新模型点「获取」拉全量再选。",
        ),
        LlmProvider(
            id = "moonshot",
            label = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            models = listOf("moonshot-v1-8k", "moonshot-v1-32k"),
            keyHint = "sk-…（platform.moonshot.cn 申请）",
        ),
        CUSTOM,
    )

    /** id → 预设。删掉的老 id（siliconflow / zhipu / dashscope / volces / hunyuan / openrouter）也走这里回落 CUSTOM。 */
    fun byId(id: String): LlmProvider = ALL.firstOrNull { it.id == id } ?: CUSTOM

    /** 老版本只存了 llm_url，没有供应商 id。升级上来时用它把地址反查回某个预设。 */
    fun matchByUrl(url: String): LlmProvider? {
        val u = url.trim().trimEnd('/')
        if (u.isBlank()) return null
        return ALL.firstOrNull { it.id != CUSTOM.id && it.baseUrl.trimEnd('/') == u }
    }

    /**
     * 预置的 id 是否还在这张表里。
     * 用户从旧版本升上来时，prefs 里可能还留着被删供应商的 id —— 用它判断要不要回落 CUSTOM，
     * 否则界面上会出现「一个 chip 都没选中」的怪状态。
     */
    fun isKnown(id: String): Boolean = ALL.any { it.id == id }
}
