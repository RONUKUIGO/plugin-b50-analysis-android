package com.b50.roast

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.b50.roast.data.*
import com.b50.roast.render.B50Renderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 单一状态持有者。刻意不用 ViewModel：整页只有一个屏，状态量不大，
 * 用 remember + mutableStateOf 就够，省掉 lifecycle-viewmodel-compose 依赖。
 *
 * 所有网络调用都在 Dispatchers.IO 上跑，调用方在协程里 await。
 */
class AppState(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences("b50_app", Context.MODE_PRIVATE)
    // ---------------- 表单 ----------------
    var qq by mutableStateOf(prefs.getString("qq", "") ?: "")
        private set
    var words by mutableStateOf(prefs.getString("words", "600") ?: "600")
    var tone by mutableStateOf(prefs.getString("tone", "") ?: "")
    var angle by mutableStateOf(prefs.getString("angle", "综合锐评") ?: "综合锐评")
    var width by mutableStateOf(prefs.getString("width", "1200") ?: "1200")
    var format by mutableStateOf(prefs.getString("format", "png") ?: "png")
    var sections by mutableStateOf<Sections2>(
        Sections2(prefs.getString("sections", "all") ?: "all")
    )

    // ---------------- 数据 ----------------
    // ============================================================
    // 水鱼账号（App 内设备码绑定，完全不依赖本地服务）
    // ============================================================
    private val oauthClient = OAuthClient(appContext)
    private val installer = ResourceInstaller(appContext)
    private val res = Res(installer)
    private val musicDb by lazy { MusicDb(res) }

    /** 规整后的 B35/B15（渲染器的输入） */
    var b50 by mutableStateOf(null as RecordsNormalizer.B50?)
        private set

    var bound by mutableStateOf(oauthClient.isBound())
        private set
    var bindState by mutableStateOf("idle")          // idle | waiting | ok | error
        private set
    var bindUserCode by mutableStateOf("")
        private set
    var bindUrl by mutableStateOf("")
        private set
    var bindError by mutableStateOf("")
        private set

    var accountName by mutableStateOf(prefs.getString("acc_name", "") ?: "")
        private set
    var accountRating by mutableStateOf(prefs.getInt("acc_rating", 0))
        private set
    var accountRecords by mutableStateOf(prefs.getInt("acc_records", 0))
        private set
    var accountSyncedAt by mutableStateOf(prefs.getLong("acc_synced", 0L))
        private set

    init {
        // 仅调试：assets/debug_bootstrap.txt 存在时自动导入 refresh_token（验证完会删掉该文件）
        if (!oauthClient.isBound()) {
            runCatching {
                val txt = appContext.assets.open("debug_bootstrap.txt")
                    .bufferedReader().use { it.readText() }.trim()
                if (txt.isNotBlank()) {
                    oauthClient.importRefreshToken(txt)
                    bound = true
                    android.util.Log.w("B50Debug", "已从 assets 导入 refresh_token（调试用）")
                }
            }
        }
    }
    val maiApi = MaimaiApi(appContext, oauthClient)
    val quota = DailyQuota(appContext)
    val shots = ShotStore(appContext)

    // ---------------- 配额镜像（DailyQuota 读的是 SharedPreferences，不是 State） ----------------
    /** 今天还剩几次，Compose 读它才会跟着变 */
    var quotaLeft by mutableStateOf(quota.remaining())
        private set

    /** 被拦下的原因；空串 = 可以出图 */
    var quotaBlock by mutableStateOf(quota.blockReason())
        private set

    /** 历史目录里有没有图。首启自检只在完全空目录时做，避免每次启动都多一张 1.7 MB 的图 */
    suspend fun hasAnyShot(): Boolean = withContext(Dispatchers.IO) { shots.list().isNotEmpty() }

    /** 重新读一次配额（跨日 / 系统时间变化 / 出图后调用） */
    fun refreshQuota() {
        quotaLeft = quota.remaining()
        quotaBlock = quota.blockReason()
    }

    // ---------------- 开发者模式 ----------------
    /**
     * 解锁后不再受每日在线刷新次数限制。
     * 入口藏在「授权」页 QQ 卡片的标题上（连点 9 次），凭据校验在 DevKey。
     */
    var devUnlocked by mutableStateOf(prefs.getBoolean("dev_unlocked", false))
        private set

    /**
     * 校验并保存开发者凭据。
     * @return 是否解锁成功（失败不动现有状态）
     */
    fun unlockDev(input: String): Boolean {
        if (!DevKey.matches(input)) return false
        devUnlocked = true
        prefs.edit().putBoolean("dev_unlocked", true).apply()
        refreshQuota()
        android.util.Log.w("B50Dev", "开发者模式已开启，不再限制在线刷新次数")
        return true
    }

    fun lockDev() {
        devUnlocked = false
        prefs.edit().putBoolean("dev_unlocked", false).apply()
        refreshQuota()
        android.util.Log.w("B50Dev", "开发者模式已关闭")
    }

    /** 一行配额摘要，给 UI 直接显示 */
    fun quotaText(): String = when {
        devUnlocked -> "开发者模式 · 不限次数"
        quotaBlock.isNotBlank() -> quotaBlock
        else -> "在线刷新剩 " + quotaLeft + "/" + quota.limit() + " 次 · " + quota.untilResetText()
    }

    /** 本地有没有可用的成绩缓存（有就能免费出图） */
    fun hasLocalData(): Boolean = maiApi.hasCache()

    /** 缓存年龄（毫秒），没有缓存返回 Long.MAX_VALUE */
    fun dataAgeMs(): Long = maiApi.cacheAge()

    /** "3 小时前" / "刚刚" */
    fun dataAgeText(): String {
        val ms = maiApi.cacheAge()
        if (ms == Long.MAX_VALUE) return "还没有数据"
        val min = ms / 60_000L
        return when {
            min < 1 -> "刚刚"
            min < 60 -> min.toString() + " 分钟前"
            min < 60 * 24 -> (min / 60).toString() + " 小时前"
            else -> (min / 60 / 24).toString() + " 天前"
        }
    }

    /**
     * 本地数据是不是"该刷新了"。
     * 只影响界面提示（弹「要刷新吗」），不会自己去联网 —— 花不花额度由用户决定。
     */
    fun dataIsStale(): Boolean {
        val ms = maiApi.cacheAge()
        return ms != Long.MAX_VALUE && ms > MaimaiApi.FRESH_MS
    }

    // ---------------- LLM（用户自填，必填） ----------------
    var llmUrl by mutableStateOf(prefs.getString("llm_url", "https://api.deepseek.com/v1") ?: "")
        private set
    var llmPath by mutableStateOf(prefs.getString("llm_path", "/chat/completions") ?: "/chat/completions")
        private set
    var llmKey by mutableStateOf(prefs.getString("llm_key", "") ?: "")
        private set
    var llmModel by mutableStateOf(prefs.getString("llm_model", "deepseek-chat") ?: "")
        private set

    /**
     * 当前供应商 id。
     * 老版本只存过 llm_url、没存 llm_provider，所以用「地址反查预设」兜底，
     * 升级上来不会显示成「自定义」。
     *
     * ⚠️ 必须声明在 llmUrl 之后：推断要用已经带默认值的 llmUrl，
     * 直接读 prefs 会因为「老用户没存过 llm_url」而永远落回 custom。
     */
    var llmProvider by mutableStateOf(
        (prefs.getString("llm_provider", "") ?: "").ifBlank {
            LlmPresets.matchByUrl(llmUrl)?.id ?: LlmPresets.CUSTOM.id
        }
    )
        private set

    /** 联网拉到的模型列表；空表示还没拉过，界面回退到供应商内置的兜底列表 */
    var llmModelPool by mutableStateOf(emptyList<String>())
        private set

    /**
     * 切换供应商：把预设灌进四个字段。
     * 同时**按供应商分别记住 Key**，来回切不会互相覆盖。
     */
    fun switchLlmProvider(id: String) {
        val old = llmProvider
        if (old == id) return
        prefs.edit().putString("llm_key_" + old, llmKey).apply()   // 先把上一家的 Key 存起来

        val p = LlmPresets.byId(id)
        llmProvider = p.id
        llmUrl = p.baseUrl
        llmPath = p.path
        llmModel = p.models.firstOrNull() ?: ""
        llmKey = prefs.getString("llm_key_" + p.id, "") ?: ""
        llmModelPool = emptyList()

        persistLlm()
    }

    /** 保存四个字段（界面上点「保存」/「获取模型」/「测试连接」前都会调） */
    fun saveLlm(url: String, path: String, key: String, model: String) {
        llmUrl = url.trim()
        llmPath = path.trim().ifBlank { "/chat/completions" }
        llmKey = key.trim()
        llmModel = model.trim()
        persistLlm()
    }

    private fun persistLlm() {
        prefs.edit()
            .putString("llm_provider", llmProvider)
            .putString("llm_url", llmUrl)
            .putString("llm_path", llmPath)
            .putString("llm_key", llmKey)
            .putString("llm_model", llmModel)
            .putString("llm_key_" + llmProvider, llmKey)
            .apply()
    }

    fun llmReady(): Boolean = llmUrl.isNotBlank() && llmKey.isNotBlank()

    /** 当前供应商需要的额外请求头（OpenRouter 之类要来源标识） */
    fun llmHeaders(): Map<String, String> = LlmPresets.byId(llmProvider).extraHeaders

    /** 联网拉模型列表，写进 llmModelPool，返回条数。失败抛异常，message 可直给用户看。 */
    suspend fun fetchLlmModels(): Int = withContext(Dispatchers.IO) {
        val p = LlmPresets.byId(llmProvider)
        val list = LlmClient.fetchModels(llmUrl, p.modelsPath, llmKey, p.extraHeaders)
        llmModelPool = list
        list.size
    }

    /** 发一条最小 chat 请求验证 Key / 模型 / 路径，返回可直给的文案。失败抛异常。 */
    suspend fun testLlm(): String = withContext(Dispatchers.IO) {
        LlmClient.testChat(llmUrl, llmPath, llmKey, llmModel, llmHeaders())
    }

    /** 发起绑定：设备码 → 轮询 → 拿到 refresh_token → 立刻拉一次全量验证身份 */
    fun startBind(scope: CoroutineScope) {
        if (bindState == "waiting") return
        bindState = "waiting"
        bindError = ""
        bindUserCode = ""
        bindUrl = ""
        scope.launch {
            try {
                val flow = withContext(Dispatchers.IO) { oauthClient.startDeviceFlow() }
                bindUserCode = flow.userCode
                bindUrl = flow.verifyUrl
                withContext(Dispatchers.IO) { oauthClient.awaitDeviceToken(flow) }
                bound = true
                // 刚绑完本地一定没缓存，refreshRecords 会识别成"首次拉取"从而不扣次数
                refreshRecords()
                bindState = "ok"
            } catch (e: Exception) {
                bindState = "error"
                bindError = e.message ?: "绑定失败"
            }
        }
    }

    /**
     * 按「是否允许联网」同步一次成绩。这是唯一的底层出口，对外只暴露两个语义清晰的入口：
     *   · [loadFromCache]  只读本地，绝不联网、绝不扣次数（冷启动 / 切页面用）
     *   · [refreshRecords] 联网刷新，会扣一次额度（首次拉取免费）
     */
    private suspend fun syncAccount(force: Boolean = false, allowNetwork: Boolean = true) = withContext(Dispatchers.IO) {
        android.util.Log.w("B50Sync", "syncAccount force=" + force + " allowNetwork=" + allowNetwork + " bound=" + bound)
        val r = maiApi.fetchRecords(force = force, allowNetwork = allowNetwork)
        val d = r.data
        accountName = d.optString("nickname").ifBlank { d.optString("username") }
        accountRating = d.optInt("rating")
        accountRecords = d.optJSONArray("records")?.length() ?: 0
        accountSyncedAt = r.fetchedAt
        prefs.edit()
            .putString("acc_name", accountName)
            .putInt("acc_rating", accountRating)
            .putInt("acc_records", accountRecords)
            .putLong("acc_synced", accountSyncedAt)
            .apply()

        // 版本校准 + B35/B15 规整（与 Python 版对拍的关键一步）
        runCatching {
            val calib = maiApi.fetchCalibDxIds(qq.trim().takeIf { it.isNotBlank() }, d.optString("username"))
            val norm = RecordsNormalizer.normalize(d, musicDb, calib)
            b50 = norm
            android.util.Log.w("B50Norm", "B35 前5: " + norm.b35.take(5)
                .joinToString(" | ") { it.title + "/" + it.levelLabel + "/" + it.ra })
            android.util.Log.w("B50Norm", "B15 前5: " + norm.b15.take(5)
                .joinToString(" | ") { it.title + "/" + it.levelLabel + "/" + it.ra })
        }.onFailure {
            android.util.Log.e("B50Norm", "规整失败", it)
        }
    }

    /** 刷新成绩时出问题的话在这里，界面直接显示 */
    var refreshError by mutableStateOf("")
        private set

    /**
     * 冷启动 / 切页面用：只用本地缓存规整出 B35/B15，**绝不联网、绝不扣次数**。
     * 没有缓存就静默跳过 —— 数据要不要拉，等用户真的点「刷新」或「出图」时再说。
     */
    suspend fun loadFromCache() {
        if (!bound) return
        runCatching { syncAccount(force = false, allowNetwork = false) }
            .onFailure { android.util.Log.w("B50Sync", "本地缓存不可用：" + it.message) }
    }

    /**
     * 联网刷新成绩 —— **全 App 唯一会消耗水鱼 client_id 额度的入口**。
     *
     * 规则：
     *   · 本地从来没有缓存 → 首次拉取，**免费**，不扣次数
     *   · 已有缓存 → 先查今日额度，够才发请求，成功才扣 1 次（请求失败不扣）
     *
     * @return true 表示刷新成功（或本来就有可用数据）
     */
    suspend fun refreshRecords(): Boolean = withContext(Dispatchers.IO) {
        if (!bound) {
            refreshError = "先绑定水鱼账号"
            return@withContext false
        }
        val firstTime = !maiApi.hasCache()
        val bypassQuota = devUnlocked          // 开发者模式：既不查额度也不扣次数

        if (!firstTime && !bypassQuota) {
            refreshQuota()
            if (quotaBlock.isNotBlank()) {
                refreshError = quotaBlock
                android.util.Log.w("B50Quota", "刷新被拦：" + quotaBlock)
                return@withContext false
            }
        }

        try {
            syncAccount(force = true, allowNetwork = true)
            refreshError = ""
            if (firstTime) {
                android.util.Log.w("B50Quota", "首次拉取，不计次数")
            } else if (bypassQuota) {
                android.util.Log.w("B50Quota", "开发者模式，本次刷新不计次数")
            } else {
                quotaLeft = quota.consume()
                quotaBlock = quota.blockReason()
                android.util.Log.w("B50Quota", "已消耗 1 次在线刷新，剩余 " + quotaLeft + "/" + quota.limit())
            }
            true
        } catch (e: Exception) {
            refreshError = e.message ?: "刷新失败"
            false
        }
    }

    fun unbind() {
        oauthClient.logout()
        maiApi.clearCache()
        bound = false
        bindState = "idle"
        bindUserCode = ""
        bindUrl = ""
        bindError = ""
        accountName = ""
        accountRating = 0
        accountRecords = 0
        accountSyncedAt = 0
        prefs.edit().remove("acc_name").remove("acc_rating")
            .remove("acc_records").remove("acc_synced").apply()
    }



    // ============================================================
    // 内嵌 Python 渲染（原版 render.py，效果与桌面版一致）
    // ============================================================
    private val pyRunner = PythonRunner(appContext)

    var pyReady by mutableStateOf(pyRunner.isInstalled())
        private set
    var pyProgress by mutableStateOf(0)
        private set
    var pyStage by mutableStateOf("")
        private set

    /** 首启安装 Python 运行时（20 MB，约 2~4 秒） */
    suspend fun installPython() = withContext(Dispatchers.IO) {
        if (pyRunner.isInstalled()) {
            pyReady = true
            return@withContext
        }
        runCatching {
            pyRunner.install { p, stage ->
                pyProgress = p
                pyStage = stage
            }
        }.onFailure { android.util.Log.e("B50Py", "Python 安装失败", it) }
        pyReady = pyRunner.isInstalled()
    }

    /** 用 Python 引擎出图（失败时调用方可回退 Kotlin 渲染） */
    suspend fun renderWithPython(analysis: String = "", outWidth: String = "1200"): File? =
        withContext(Dispatchers.IO) {
            var data = b50
            if (data == null && bound) {
                runCatching { syncAccount(force = false, allowNetwork = false) }
                data = b50
            }
            if (data == null) {
                renderError = "还没拿到成绩，先去「授权」页绑定水鱼账号"
                return@withContext null
            }
            if (!pyReady) installPython()

            rendering = true
            renderError = ""
            try {
                val records = File(appContext.filesDir, "cache/records.json")
                val musicJson = res.data("music_data.json")
                if (!musicJson.exists()) {
                    renderError = "资源包缺少 music_data.json，请重装 App"
                    return@withContext null
                }
                val avQq = qq.trim()
                if (avQq.isNotBlank() && avQq.all { it.isDigit() }) {
                    pyRunner.ensureAvatar(res.root(), avQq)
                }
                val out = File(appContext.filesDir, "shots/b50_" + System.currentTimeMillis() + ".png")
                val r = pyRunner.render(
                    recordsJson = records,
                    musicJson = musicJson,
                    assets = res.root(),
                    out = out,
                    width = outWidth.toIntOrNull() ?: 1200,
                    qq = qq,
                    versions = data.currentVersions.joinToString("|"),
                    analysis = analysis,
                )
                if (r.ok && r.image != null) {
                    lastShot = r.image
                    renderInfo = (r.image.length() / 1024).toString() + " KB · Python 引擎"
                    android.util.Log.w("B50Py", "出图成功 → " + r.image.absolutePath)
                    r.image
                } else {
                    renderError = r.output.take(400)
                    android.util.Log.e("B50Py", "出图失败: " + renderError)
                    null
                }
            } catch (e: Exception) {
                renderError = e.message ?: "渲染异常"
                null
            } finally {
                rendering = false
            }
        }

    // ============================================================
    // 锐评链路：Python 出提示词 → Kotlin 调 LLM → Python 出图
    // ============================================================

    /** 锐评进行到哪一步了，界面直接显示 */
    var aiStatus by mutableStateOf("")
        private set

    /**
     * 完整链路：生成提示词 → AI 写锐评 → 渲染出图。
     *
     * 额度规则：**出图本身不消耗任何次数**。
     * 只有 [refreshFirst] = true（用户选了「先刷新再出图」）或本地压根没有数据时，
     * 才会走一次 [refreshRecords]，那一步才可能扣掉一次在线刷新额度。
     *
     * @param refreshFirst 用户明确要求先用最新成绩出图
     */
    suspend fun roastAndRender(outWidth: String = "1200", refreshFirst: Boolean = false): File? {
        if (!bound) {
            renderError = "还没拿到成绩，先去「授权」页绑定水鱼账号"
            return null
        }
        if (!llmReady()) {
            renderError = "先去「设置」页配好 AI 供应商和 Key"
            return null
        }
        if (llmModel.isBlank()) {
            renderError = "还没选模型，去「设置」点一下「获取模型」"
            return null
        }

        // 要不要先联网更新数据？（只有这两种情况会碰网络）
        if (refreshFirst) {
            if (!refreshRecords()) {
                renderError = refreshError.ifBlank { "刷新成绩失败" }
                return null
            }
        } else if (!hasLocalData()) {
            // 本地啥也没有，只能拉一次（首次免费）
            if (!refreshRecords()) {
                renderError = refreshError.ifBlank { "本地没有成绩数据，且今日在线刷新已用完" }
                return null
            }
        } else if (b50 == null) {
            // 有缓存但还没规整过（比如冷启动的 loadFromCache 还没跑完），白捡一次
            runCatching { syncAccount(force = false, allowNetwork = false) }
        }

        val data = b50
        if (data == null) {
            renderError = "成绩数据解析失败，去「授权」页点一次「刷新数据」试试"
            return null
        }
        if (!pyReady) installPython()

        rendering = true
        renderError = ""
        try {
            val records = File(appContext.filesDir, "cache/records.json")
            val musicJson = res.data("music_data.json")
            val avQq = qq.trim()
            if (avQq.isNotBlank() && avQq.all { it.isDigit() }) {
                pyRunner.ensureAvatar(res.root(), avQq)
            }

            // ---- 1. 让 Python 把 7 个分区压成提示词 ----
            aiStatus = "正在整理成绩数据…"
            val promptFile = File(appContext.cacheDir, "roast_prompt.json")
            val promptOk = withContext(Dispatchers.IO) {
                pyRunner.makePrompt(
                    recordsJson = records,
                    musicJson = musicJson,
                    assets = res.root(),
                    outJson = promptFile,
                    width = outWidth.toIntOrNull() ?: 1200,
                    qq = qq,
                    versions = data.currentVersions.joinToString("|"),
                    tone = tone,
                    angle = angle,
                    words = words,
                )
            }
            if (!promptOk) {
                renderError = "提示词生成失败，详见 logcat 标签 B50Py"
                return null
            }
            val pj = JSONObject(promptFile.readText(Charsets.UTF_8))
            val systemPrompt = pj.optString("system")
            val userPrompt = pj.optString("user")
            android.util.Log.w("B50Roast", "提示词 system=" + systemPrompt.length +
                " user=" + userPrompt.length + " 字")

            // ---- 2. 调 LLM ----
            aiStatus = "AI 正在写锐评…"
            val raw = withContext(Dispatchers.IO) {
                LlmClient.chat(
                    baseUrl = llmUrl,
                    path = llmPath,
                    key = llmKey,
                    model = llmModel,
                    system = systemPrompt,
                    user = userPrompt,
                    extraHeaders = llmHeaders(),
                )
            }
            android.util.Log.w("B50Roast", "锐评正文 " + raw.length + " 字符")

            // ---- 3. 渲染 ----
            aiStatus = "正在出图…"
            // 出图不出网、不扣任何额度
            return renderWithPython(raw, outWidth)
        } catch (e: Exception) {
            // AI 这一步塌了：退回纯出图，且不扣次数
            val msg = e.message ?: e.javaClass.simpleName
            android.util.Log.e("B50Roast", "锐评失败：" + msg, e)
            aiStatus = ""
            renderError = ""
            val f = renderWithPython("", outWidth)
            if (f != null) {
                renderError = "锐评失败（" + msg.take(120) + "），本次已退回纯出图"
            } else {
                renderError = "锐评失败：" + msg.take(200)
            }
            return f
        } finally {
            rendering = false
            aiStatus = ""
        }
    }

    // ============================================================
    // 本地出图（原生 Canvas 渲染，完全不依赖后端）
    // ============================================================
    private val renderer by lazy { B50Renderer(appContext, res) }

    var rendering by mutableStateOf(false)
        private set
    var lastShot by mutableStateOf(null as File?)
        private set
    var renderError by mutableStateOf("")
        private set
    var renderInfo by mutableStateOf("")
        private set

    /** 用本地数据渲染一张 B50 图，存到 App 私有目录，返回文件 */
    suspend fun renderLocal(bodyText: String = "", outWidth: String = "1200"): File? =
        withContext(Dispatchers.IO) {
            var data = b50
            if (data == null && bound) {
                runCatching { syncAccount(force = false, allowNetwork = false) }
                data = b50
            }
            if (data == null) {
                renderError = "还没拿到成绩，先去「授权」页绑定水鱼账号"
                return@withContext null
            }
            rendering = true
            renderError = ""
            try {
                val w = outWidth.toIntOrNull() ?: 1200
                val bmp = renderer.render(
                    B50Renderer.Input(
                        b50 = data,
                        qq = qq,
                        nickname = data.nickname.ifBlank { accountName },
                        rating = data.rating,
                        b35Ra = data.b35.sumOf { it.ra },
                        b15Ra = data.b15.sumOf { it.ra },
                        bodyText = bodyText,
                        outWidth = w,
                    )
                )
                val f = renderer.saveToPrivate(bmp)
                lastShot = f
                renderInfo = bmp.width.toString() + "×" + bmp.height + " · " +
                    (f.length() / 1024) + " KB"
                android.util.Log.w("B50Render", "本地出图完成 " + renderInfo + " → " + f.absolutePath)
                f
            } catch (e: Exception) {
                renderError = e.message ?: "渲染失败"
                android.util.Log.e("B50Render", "渲染失败", e)
                null
            } finally {
                rendering = false
            }
        }


    // ============================================================
    // 预设套用
    // ============================================================


    private fun savePrefs() {
        prefs.edit()
            .putString("qq", qq)
            .putString("words", words)
            .putString("tone", tone)
            .putString("angle", angle)
            .putString("width", width)
            .putString("format", format)
            .putString("sections", sections.spec())
            .apply()
    }

    fun saveNow() = savePrefs()

    /**
     * 设置出图用的 QQ 号（在「授权」页填）。
     *
     * 每敲一个字符就落盘：QQ 是出图的必需参数，用户填完关掉 App 再回来不能丢。
     * 顺手过滤非数字 + 限长 12 位（QQ 号目前最长 12 位），省得后面还要校验。
     */
    fun updateQq(value: String) {
        val clean = value.filter { it.isDigit() }.take(12)
        if (clean == qq) return
        qq = clean
        prefs.edit().putString("qq", qq).apply()
    }
}

/**
 * 分区选择：内部保存原始 spec 字符串（all / none / "highlights,push"），
 * 与后端 SECTION_ALIASES 直接兼容，不需要本地维护映射。
 */
data class Sections2(val raw: String) {
    private val set: Set<String> =
        if (raw == "all" || raw.isBlank()) Sections.KEYS.toSet()
        else if (raw == "none") emptySet()
        else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()

    fun isOn(key: String): Boolean = key in set

    fun toggle(key: String): Sections2 {
        val next = set.toMutableSet()
        if (!next.remove(key)) next.add(key)
        val spec = when {
            next.size == Sections.KEYS.size -> "all"
            next.isEmpty() -> "none"
            else -> next.joinToString(",")
        }
        return Sections2(spec)
    }

    fun spec(): String = raw.ifBlank { "all" }

    fun summary(): String = when {
        set.size == Sections.KEYS.size -> "全部 7 个分区"
        set.isEmpty() -> "不画分区"
        else -> set.joinToString("、") { k -> Sections.ALL.firstOrNull { it.first == k }?.second ?: k }
    }
}
