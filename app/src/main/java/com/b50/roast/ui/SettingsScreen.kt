package com.b50.roast.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b50.roast.AppState
import com.b50.roast.BuildConfig
import com.b50.roast.data.LlmPresets
import com.b50.roast.data.Res
import com.b50.roast.data.ResourceInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：服务地址 + 服务启停 + 版本信息。
 * 启停走 su，执行 /data/local/tmp/b50_webui.sh {start|stop|status}。
 */
@Composable
fun SettingsScreen(
    state: AppState,
    scope: CoroutineScope,
) {
    val cs = MiuixTheme.colorScheme
    // 四个字段的编辑副本：key 里带上供应商 id，切供应商时自动重置成新预设的值
    var llmUrl by remember(state.llmProvider) { mutableStateOf(state.llmUrl) }
    var llmPath by remember(state.llmProvider) { mutableStateOf(state.llmPath) }
    var llmKey by remember(state.llmProvider) { mutableStateOf(state.llmKey) }
    var llmModel by remember(state.llmProvider) { mutableStateOf(state.llmModel) }

    var llmStatus by remember { mutableStateOf("") }
    var llmOk by remember { mutableStateOf(false) }
    var llmBusy by remember { mutableStateOf(false) }     // 拉模型 / 测连接进行中

    // 本地资源包状态（首启解压的结果）
    val appCtx = LocalContext.current.applicationContext
    val installer = remember { ResourceInstaller(appCtx) }
    val res = remember { Res(installer) }
    var assetInfo by remember { mutableStateOf("读取中…") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val v = installer.installedVersion().ifBlank { "未安装" }
            val covers = java.io.File(installer.root(), "cover").listFiles()?.size ?: 0
            val used = installer.usedBytes() / 1048576
            val miss = res.sanityCheck()
            assetInfo = "资源包 cn:" + v + " · 曲绘 " + covers + " 张 · 占用 " + used + " MB" +
                if (miss.isEmpty()) " · 完整" else " · 缺 " + miss.size + " 项（" + miss.joinToString("、") + "）"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        // 标题行不再放「返回」按钮（用户 2026-10-28 要求）：
        // 返回走系统手势/返回键 —— MainActivity 的 PredictiveBackHandler 已经接管，
        // 叠层页会跟着手指右移缩回，比点按钮更符合"新标签页"的形态。
        Text(
            "设置",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
        )

        // ---------- 本地资源包 ----------
        SectionCard("本地资源包", "首次启动会把内置素材解压到本机，之后出图完全离线。cn: 后面是机台版本代号（上游素材版本）。") {
            Column(Modifier.padding(12.dp)) {
                Text(assetInfo, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        installer.clear()
                        assetInfo = "已清空，重启 App 会重新解压"
                    }) { Text("清空资源") }
                }
            }
        }

        // ---------- 供应商 ----------
        val cur = LlmPresets.byId(state.llmProvider)
        SectionCard("AI 供应商", "点一下自动填好基础地址、路径和常用模型，你只需要填 Key。") {
            Column(Modifier.padding(12.dp)) {
                // 5 家预置横向可滚；「自定义」固定钉在右边。
                // 以前它排在滚动行最末，被推到屏幕外 —— 一旦回落到 custom，
                // 界面上看起来像「一个都没选中」，用户会以为坏了。
                val pick: (String) -> Unit = { id ->
                    state.switchLlmProvider(id)
                    llmStatus = ""
                    llmOk = false
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LlmPresets.ALL.filter { it.id != LlmPresets.CUSTOM.id }.forEach { p ->
                            Chip(p.label, state.llmProvider == p.id, onClick = { pick(p.id) })
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Chip(
                        LlmPresets.CUSTOM.label,
                        state.llmProvider == LlmPresets.CUSTOM.id,
                        onClick = { pick(LlmPresets.CUSTOM.id) },
                    )
                }
                if (cur.note.isNotBlank()) {
                    Text(
                        cur.note,
                        fontSize = 12.sp,
                        color = cs.primary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        // ---------- 接口参数 ----------
        SectionCard("接口参数", "四项都能手改；换供应商时会重新灌一遍预设。") {
            Column(Modifier.padding(12.dp)) {

                LabeledField(
                    label = "API 密钥",
                    value = llmKey,
                    onValueChange = { llmKey = it },
                    placeholder = cur.keyHint,
                )
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    label = "API 基础地址",
                    value = llmUrl,
                    onValueChange = { llmUrl = it },
                    placeholder = "https://api.example.com/v1",
                )
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    label = "API 路径",
                    value = llmPath,
                    onValueChange = { llmPath = it },
                    placeholder = "/chat/completions",
                )
                Spacer(Modifier.height(10.dp))

                // 模型 + 「获取」按钮
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        LabeledField(
                            label = "模型",
                            value = llmModel,
                            onValueChange = { llmModel = it },
                            placeholder = "deepseek-chat",
                        )
                    }
                    Button(
                        enabled = !llmBusy,
                        onClick = {
                            scope.launch {
                                state.saveLlm(llmUrl, llmPath, llmKey, llmModel)
                                llmBusy = true
                                llmStatus = "正在拉取模型列表…"
                                llmOk = false
                                runCatching { state.fetchLlmModels() }
                                    .onSuccess { n ->
                                        llmStatus = "拉到 " + n + " 个模型，点下方标签即可填入"
                                        llmOk = true
                                        if (llmModel.isBlank()) llmModel = state.llmModelPool.firstOrNull() ?: ""
                                    }
                                    .onFailure { e ->
                                        llmStatus = "拉取失败：" + (e.message ?: e.javaClass.simpleName)
                                        llmOk = false
                                    }
                                llmBusy = false
                            }
                        },
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) { Text(if (llmBusy) "…" else "获取") }
                }

                // 模型池：拉到了就显示全量，没拉过就显示内置兜底
                val pool = state.llmModelPool.ifEmpty { cur.models }
                if (pool.isNotEmpty()) {
                    Text(
                        if (state.llmModelPool.isNotEmpty())
                            "该 Key 可用模型 " + pool.size + " 个（左右滑动）"
                        else
                            "常用模型（内置兜底，点「获取」可拉取该家全量）",
                        fontSize = 12.sp,
                        color = cs.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 2.dp),
                    ) {
                        items(pool) { id ->
                            Chip(shortModel(id), llmModel == id, onClick = { llmModel = id })
                        }
                    }
                }
                if (llmModel.isNotBlank()) {
                    Text(
                        "当前模型：" + llmModel,
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                // 保存 / 测试连接
                Row(
                    Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(enabled = !llmBusy, onClick = {
                        state.saveLlm(llmUrl, llmPath, llmKey, llmModel)
                        llmStatus = if (state.llmReady()) "已保存" else "基础地址和 Key 都要填"
                        llmOk = state.llmReady()
                    }) { Text("保存") }

                    Button(enabled = !llmBusy, onClick = {
                        scope.launch {
                            state.saveLlm(llmUrl, llmPath, llmKey, llmModel)
                            if (!state.llmReady()) {
                                llmStatus = "基础地址和 Key 都要填"
                                llmOk = false
                                return@launch
                            }
                            if (llmModel.isBlank()) {
                                llmStatus = "先填模型名，或点「获取」拉一个"
                                llmOk = false
                                return@launch
                            }
                            llmBusy = true
                            llmStatus = "正在测试…"
                            llmOk = false
                            runCatching { state.testLlm() }
                                .onSuccess { llmStatus = it; llmOk = true }
                                .onFailure { e ->
                                    llmStatus = "测试失败：" + (e.message ?: e.javaClass.simpleName)
                                    llmOk = false
                                }
                            llmBusy = false
                        }
                    }) { Text("测试连接") }
                }

                if (llmStatus.isNotBlank()) {
                    Text(
                        llmStatus,
                        fontSize = 12.sp,
                        color = if (llmOk) cs.primary else cs.error,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        // ---------- 关于 ----------
        SectionCard("关于") {
            Column(Modifier.padding(vertical = 6.dp)) {
                InfoRow("客户端", "B50 锐评机 · miuix UI")
                InfoRow("App 版本", BuildConfig.VERSION_NAME)
                InfoRow("资源包", "cn:" + ResourceInstaller.PACK_VERSION)
                InfoRow("账号", state.accountName.ifBlank { "未绑定" })
                InfoRow("今日剩余", state.quotaText())
                InfoRow("AI 接口", if (state.llmReady()) "已配置" else "未配置")
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}

/**
 * 模型标签只显示最后一段（`deepseek-ai/DeepSeek-V3` → `DeepSeek-V3`），
 * 否则一排标签会长到把屏幕撑爆。点选后填进输入框的仍然是完整 id。
 */
private fun shortModel(id: String): String {
    val tail = id.substringAfterLast('/')
    return if (tail.length <= 20) tail else tail.take(19) + "…"
}
