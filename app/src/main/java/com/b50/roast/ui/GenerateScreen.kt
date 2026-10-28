package com.b50.roast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b50.roast.AppState
import com.b50.roast.data.Sections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主界面：一块滚动表单 + 结果卡片。
 * 布局顺序刻意与网页端一致（身份 → 内容 → 范围 → 分区 → 出图 → 结果），
 * 老用户肌肉记忆不用重建。
 */
@Composable
fun GenerateScreen(
    state: AppState,
    scope: CoroutineScope,
    onOpenSettings: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val scroll = rememberScrollState()
    var stalePrompt by remember { mutableStateOf(false) }

    // 进入页面先同步一次配额；之后每 30 秒刷一次，跨日 0 点会自动解锁按钮
    LaunchedEffect(Unit) {
        while (true) {
            state.refreshQuota()
            kotlinx.coroutines.delay(30_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp),
    ) {
        // ---------- 账号状态（不再显示服务地址/版本，这些对使用者无意义） ----------
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(14.dp)) {
                if (state.bound) {
                    Text(
                        state.accountName.ifBlank { "已绑定水鱼账号" } +
                            (if (state.accountRating > 0) "   ·   " + state.accountRating else ""),
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                    )
                    Text(
                        if (state.accountRecords > 0)
                            "全量 " + state.accountRecords + " 条成绩   ·   数据 " + state.dataAgeText()
                        else "正在同步成绩…",
                        fontSize = 12.sp,
                        color = cs.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    // QQ 号在「授权」页填，这里只做只读提示
                    Text(
                        if (state.qq.isNotBlank()) "出图 QQ：" + state.qq
                        else "还没填 QQ 号 → 去「授权」页填",
                        fontSize = 12.sp,
                        color = if (state.qq.isNotBlank()) cs.onSurface.copy(alpha = 0.6f) else cs.error,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                } else {
                    Text("还没绑定水鱼账号", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(
                        "去底部「授权」页绑定一次，之后随时出图。",
                        fontSize = 12.sp,
                        color = cs.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // ---------- 内容 ----------
        SectionCard("写什么") {
            LabeledField(
                label = "字数",
                value = state.words,
                onValueChange = { state.words = it },
                placeholder = "600",
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            LabeledField(
                label = "语气 / 人设",
                value = state.tone,
                onValueChange = { state.tone = it },
                placeholder = "留空=默认；例：雌小鬼、温柔猫娘",
                singleLine = false,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            LabeledField(
                label = "角度",
                value = state.angle,
                onValueChange = { state.angle = it },
                placeholder = "综合锐评 / 推分建议 / 配置诊断",
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        // ---------- 分区 ----------
        SectionCard("画哪些分区", state.sections.summary()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                ChipGroup(
                    items = Sections.ALL,
                    isSelected = { k -> state.sections.isOn(k) },
                    onClick = { k -> state.sections = state.sections.toggle(k) },
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("全选", state.sections.spec() == "all", onClick = { state.sections = com.b50.roast.Sections2("all") })
                    Chip("清空", state.sections.spec() == "none", onClick = { state.sections = com.b50.roast.Sections2("none") })
                }
            }
        }

        // ---------- 出图设置 ----------
        SectionCard("出图设置") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("图片宽度", fontSize = 12.sp, color = cs.onSurfaceVariantSummary)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("900", "1200", "1500").forEach { w ->
                        Chip(w, state.width == w, onClick = { state.width = w })
                    }
                }
                Text("格式", fontSize = 12.sp, color = cs.onSurfaceVariantSummary, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("png", "jpg", "webp").forEach { f ->
                        Chip(f.uppercase(), state.format == f, onClick = { state.format = f })
                    }
                }
            }
        }

        // ---------- 出图按钮 ----------
        // 注意：出图**不消耗任何额度**。额度只跟「联网刷新成绩」有关，
        // 所以这里不受 quotaBlock 限制 —— 本地有数据就能出。
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            val aiReady = state.llmReady()
            val hasData = state.hasLocalData()
            val canGo = state.bound && aiReady && !state.rendering
            Button(
                onClick = {
                    // 数据旧了先问一句，别偷偷联网花掉用户额度
                    if (hasData && state.dataIsStale()) stalePrompt = true
                    else scope.launch { state.roastAndRender(state.width) }
                },
                enabled = canGo,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.rendering -> state.aiStatus.ifBlank { "出图中…" }
                        !state.bound -> "先去「授权」页绑定水鱼账号"
                        !aiReady -> "先去「设置」配好 AI 接口"
                        else -> "出图 + AI 锐评"
                    },
                    fontWeight = FontWeight.Medium,
                )
            }

            // 锐评进度（整理数据 → AI 写作 → 出图）
            if (state.aiStatus.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(
                        state.aiStatus,
                        fontSize = 13.sp,
                        color = cs.primary,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }

            // 数据新鲜度 + 在线刷新额度
            Text(
                "成绩数据：" + state.dataAgeText() + "（出图不消耗次数）",
                fontSize = 12.sp,
                color = cs.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                state.quotaText(),
                fontSize = 12.sp,
                color = if (state.quotaBlock.isNotBlank()) cs.error else cs.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (state.renderInfo.isNotBlank()) {
                Text(
                    "已生成 " + state.renderInfo + "（「历史」页可保存到相册 / 分享）",
                    fontSize = 12.sp,
                    color = cs.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.renderError.isNotBlank()) {
                Text(
                    state.renderError,
                    fontSize = 12.sp,
                    color = cs.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text(
                "一次大约 20~60 秒（AI 写锐评占大头，出图本身只要 2~4 秒），生成中不要重复点。",
                fontSize = 12.sp,
                color = cs.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    // 数据过期确认层：用户自己决定花不花这一次在线刷新额度
    if (stalePrompt) {
        StaleDataPrompt(
            ageText = state.dataAgeText(),
            quotaText = state.quotaText(),
            canRefresh = state.quotaBlock.isBlank(),
            onUseOld = {
                stalePrompt = false
                scope.launch { state.roastAndRender(state.width) }
            },
            onRefresh = {
                stalePrompt = false
                scope.launch { state.roastAndRender(state.width, refreshFirst = true) }
            },
            onDismiss = { stalePrompt = false },
        )
    }
}

/**
 * 「数据是 X 小时前的，要刷新吗」确认层。
 *
 * 用自制覆盖层而不是 Dialog：项目里没有 material 依赖，miuix 的弹窗 API 也没接，
 * 而 HistoryScreen 的预览层已经验证过这种写法在真机上没问题。
 */
@Composable
private fun StaleDataPrompt(
    ageText: String,
    quotaText: String,
    canRefresh: Boolean,
    onUseOld: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    BackHandler { onDismiss() }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Card(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("成绩数据是" + ageText, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Text(
                    "出图可以直接用这份旧数据，不会消耗次数；" +
                        "也可以先拉一份最新的，那会消耗 1 次在线刷新额度。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = cs.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    quotaText,
                    fontSize = 12.sp,
                    color = if (canRefresh) cs.primary else cs.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = onUseOld, modifier = Modifier.weight(1f)) {
                        Text("用旧数据出图")
                    }
                    Button(
                        onClick = onRefresh,
                        enabled = canRefresh,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (canRefresh) "先刷新再出图" else "额度已用完")
                    }
                }
            }
        }
    }
}
