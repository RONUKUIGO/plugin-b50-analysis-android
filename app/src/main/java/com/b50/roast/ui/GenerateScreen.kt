package com.b50.roast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b50.roast.AppState
import com.b50.roast.data.RoastPresets
import com.b50.roast.data.Sections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
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
    // 弹窗一律交给根部的 AppOverlayHost 渲染：只有它在 Scaffold 之外，
    // 遮罩才能盖住 TopAppBar / NavigationBar（详见 ui/Overlay.kt 顶部注释）
    onOverlay: (AppOverlay) -> Unit,
    onGoHistory: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val scroll = rememberScrollState()

    // 「写什么」卡里两个预设菜单的互斥开关：KEY_TONE / KEY_ANGLE / null（都收起）。
    // 放在卡片外面（这个 Composable 顶层）而不是 ExpandablePicker 内部 ——
    // 这样开一个会自动收起另一个，不会出现两个菜单同时展开。
    var openPicker by remember { mutableStateOf(null as String?) }

    // ── 参数落盘 ────────────────────────────────────────────────────────────
    // 以前 words / tone / angle / width / format / sections 六个值只在 AppState 里
    // 有 savePrefs()，但全工程**一次都没调用过**（saveNow() 也没有调用点）→
    // 用户选完语气/角度，重启 App 就退回默认值。
    // 现在这六个值一变就写一次（SharedPreferences#apply 是异步且去重的，代价可忽略）。
    LaunchedEffect(state.words, state.tone, state.angle, state.width, state.format, state.sections) {
        state.saveNow()
    }

    // 出图完成（状态在 AppState 里，界面可见时才填）→ 弹窗提示，并清掉事件避免重复弹
    LaunchedEffect(state.doneEvent) {
        val e = state.doneEvent ?: return@LaunchedEffect
        state.consumeDone()
        onOverlay(AppOverlay.Done(info = e.info, warn = e.warn, onOpenHistory = onGoHistory))
    }

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

        // ---------- 内容 + 出图（原「出图设置」卡已并入这里） ----------
        // 用户 2026-10-28 要求：
        //   · 图片设置合并进「写什么」卡片 → 相关参数集中在一张卡里，少滚一屏；
        //   · 语气/人设、角度改成按钮，点开就地展开**竖向**预设菜单（ExpandablePicker）；
        //   · 字数只要一个小方框 → 行内右侧 92dp 的窄输入框，只收数字。
        SectionCard("写什么") {
            // 字数：一个小框就够（4 位数字上限；非数字直接过滤，避免喂给 Python 后 parse_words 回落默认）
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("字数", fontSize = 15.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                // 紧凑输入框（40dp）：miuix TextField 强压高度会把文字裁掉，见 Common.kt 里 CompactTextField 的注释
                CompactTextField(
                    value = state.words,
                    onValueChange = { v -> state.words = v.filter { it.isDigit() } },
                    modifier = Modifier.width(92.dp),
                    maxLength = 4,
                )
                Text(
                    "字",
                    fontSize = 13.sp,
                    color = cs.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            RowDivider()

            // 语气 / 人设 → 按钮 + 竖向预设菜单
            ExpandablePicker(
                label = "语气 / 人设",
                value = state.tone,
                presets = RoastPresets.TONES,
                onSelect = { state.tone = it },
                expanded = openPicker == KEY_TONE,
                onToggle = { openPicker = if (openPicker == KEY_TONE) null else KEY_TONE },
            )
            RowDivider()

            // 角度 → 同上（预设第一条就是原来的默认值「综合锐评」）
            ExpandablePicker(
                label = "角度",
                value = state.angle,
                presets = RoastPresets.ANGLES,
                onSelect = { state.angle = it },
                expanded = openPicker == KEY_ANGLE,
                onToggle = { openPicker = if (openPicker == KEY_ANGLE) null else KEY_ANGLE },
            )
            RowDivider()

            // 出图参数：宽度 / 格式（原来单独一张卡，现在并进来）
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("图片宽度", fontSize = 12.sp, color = cs.onSurfaceVariantSummary)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("900", "1200", "1500").forEach { w ->
                        Chip(w, state.width == w, onClick = { state.width = w })
                    }
                }
                Text(
                    "格式",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("png", "jpg", "webp").forEach { f ->
                        Chip(f.uppercase(), state.format == f, onClick = { state.format = f })
                    }
                }
            }
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

        // ---------- 出图按钮 ----------
        // 注意：出图**不消耗任何额度**。额度只跟「联网刷新成绩」有关，
        // 所以这里不受 quotaBlock 限制 —— 本地有数据就能出。
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            val aiReady = state.llmReady()
            val hasData = state.hasLocalData()
            // 注意：这里**不**把 rendering 算进 enabled —— 出图期间按钮保持可读的底色，
            // 只在 onClick 里忽略重复点击（禁用会让底色/文字变淡，光效也会被一起压暗）。
            val canGo = state.bound && aiReady
            // 出图按钮 = 三层：底色 / 剧场光效 / 透明底按钮（文字在最上层）。
            // 为什么拆层：光效要画在"底色之上、文字之下"，而 miuix Button 的底色是不透明的，
            // 所以让 Button 自己透明、由外层 Box 提供底色 + 圆角裁剪，光扫过时文字不会被冲淡。
            val btnColors = ButtonDefaults.buttonColors()
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ButtonDefaults.CornerRadius))
                    .background(if (canGo) btnColors.color else btnColors.disabledColor),
            ) {
                FlowLight(active = state.rendering, modifier = Modifier.matchParentSize())
                Button(
                    onClick = {
                        if (state.rendering) return@Button   // 出图中：按钮照常显示进度，但不重复触发
                        // 开发者模式：额度不限（quotaText 显示"不限次数"），所以没必要问
                        // 「要刷新吗」—— 直接联网拉最新成绩再出图（refreshRecords 内部走 bypassQuota，
                        // 既不查额度也不扣次数）。用户 2026-10-28 要求：开发者模式应该能直接在线生图。
                        if (state.devUnlocked) {
                            scope.launch { state.roastAndRender(state.width, refreshFirst = true) }
                        } else if (hasData && state.dataIsStale()) {
                            // 普通用户：数据旧了先问一句，别偷偷联网花掉额度
                            onOverlay(
                                AppOverlay.StaleData(
                                    ageText = state.dataAgeText(),
                                    quotaText = state.quotaText(),
                                    canRefresh = state.quotaBlock.isBlank(),
                                    onUseOld = {
                                        scope.launch { state.roastAndRender(state.width) }
                                    },
                                    onRefresh = {
                                        scope.launch {
                                            state.roastAndRender(state.width, refreshFirst = true)
                                        }
                                    },
                                )
                            )
                        } else {
                            scope.launch { state.roastAndRender(state.width) }
                        }
                    },
                    enabled = canGo,
                    // 底色由外层 Box 画，这里只保留点击 / 水波纹 / 文字
                    colors = ButtonDefaults.buttonColors(
                        color = Color.Transparent,
                        disabledColor = Color.Transparent,
                    ),
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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

}

/** 预设菜单的 key（互斥展开用） */
private const val KEY_TONE = "tone"
private const val KEY_ANGLE = "angle"
