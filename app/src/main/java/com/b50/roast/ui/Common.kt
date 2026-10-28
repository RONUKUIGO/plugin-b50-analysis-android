package com.b50.roast.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b50.roast.data.RoastPreset
import com.b50.roast.data.RoastPresets
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * miuix 风格的小组件集合。
 * 所有选项型控件统一用 Chip 表达，避开 Dropdown/Popup 的复杂 API，
 * 视觉上贴近 MIUI/澎湃设置页的"分段选择"。
 */

// ---------------------------------------------------------------------------
// 顶栏
// ---------------------------------------------------------------------------

/**
 * 应用顶栏（大标题 + 副标题）。
 *
 * 为什么不用 miuix 的 `TopAppBar`：
 * 它的"大标题"布局把 `TopAppBarDefaults.CollapsedHeight`（52dp）**硬编码**成
 * 大标题块的上内边距（源码 `box(largeTitle).padding(top = CollapsedHeight)`），
 * 参数里只暴露 titlePadding（横向）/ 图标内边距，纵向那 52dp 改不掉。
 * 实测本机（状态栏 104px、密度 420）：状态栏底 → 标题第一笔之间空 **159px（60dp）**，
 * 用户反馈"标题顶部留空太多"。自己拼一个 Column 就能把这段空精确调到 8dp。
 *
 * 状态栏高度不写死，用 WindowInsets.systemBars 的 top 边 —— MIUI 的状态栏比 AOSP 高。
 * 这个 Composable 放在 Scaffold 的 topBar 槽里，Scaffold 会用它的实测高度去算 contentPadding，
 * 所以下面内容不会被状态栏或标题压住。
 */
@Composable
fun AppHeader(title: String, subtitle: String = "") {
    val cs = MiuixTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(cs.surface)                        // 与页面同色 #F7F7F7（和原 TopAppBar 一致）
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .padding(start = 26.dp, end = 26.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title1,   // 字号跟原来 TopAppBar 的大标题同源，只删空白不动观感
            color = cs.onSurface,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.body2,
                color = cs.onSurfaceVariantSummary,
            )
        }
    }
}

/** 卡片内/顶栏底的细分隔线（与原顶栏那条 1px 线同色：onSurface 8%） */
@Composable
fun RowDivider(startIndent: Dp = 16.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    )
}

// ---------------------------------------------------------------------------
// 卡片 / 标题
// ---------------------------------------------------------------------------

@Composable
fun SectionCard(
    title: String,
    desc: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        SmallTitle(title)
        Card(Modifier.fillMaxWidth()) {
            // 不再额外加 2dp：miuix Card 自带 insideMargin（CardDefaults.InsideMargin），
            // 再叠一层会让卡片内边距与卡外留白不一致，观感像"脏边"
            Column(Modifier.fillMaxWidth()) {
                content()
            }
        }
        if (desc.isNotBlank()) {
            Text(
                text = desc,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Chip
// ---------------------------------------------------------------------------

/** 单个可点选 chip。modifier 用于让外层按 weight 等宽拉伸（见 ChipGroup）。 */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val cs = MiuixTheme.colorScheme
    val bg = if (selected) cs.primary else cs.surface
    val fg = if (selected) cs.onPrimary else cs.onSurface
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(
                if (selected) Modifier
                else Modifier.border(1.dp, cs.secondary, RoundedCornerShape(50))
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            maxLines = 1,
            softWrap = false,
            color = if (enabled) fg else fg.copy(alpha = 0.4f),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/**
 * 分区 chip 组：**短标签靠前 + 每行等宽铺满**（用户 2026-10-28 反馈的两点）。
 *
 * 以前按「每行固定 4 个 + 各自自然宽度」排：
 *   · 标签 2~5 字不等（亮点 / 最高RA），行尾留一大片洞，最后一行只剩一两个；
 *   · 观感上像表格错位。
 * 现在：
 *   1. `sortedBy { 字数 }` 短标签先排（同字数保持原顺序，sortedBy 稳定）；
 *   2. 按 [perRow] 切块后，每块内的 chip 用 `Modifier.weight(1f)` **等宽撑满整行** ——
 *      行尾不再有洞，最后一行少几个也不显空（3 个各占 1/3）。
 *
 * 注：等宽后每个 chip 至少 ~230px（1080 宽屏），最长的「最高RA」实测约 190px 文字宽，
 * 放得下；Chip 内部已设 maxLines=1 + softWrap=false 兜底。
 */
@Composable
fun ChipGroup(
    items: List<Pair<String, String>>,   // key -> label
    isSelected: (String) -> Boolean,
    onClick: (String) -> Unit,
    perRow: Int = 4,
) {
    val ordered = remember(items) { items.sortedBy { (_, label) -> label.length } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ordered.chunked(perRow).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { (key, label) ->
                    Chip(
                        label = label,
                        selected = isSelected(key),
                        onClick = { onClick(key) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 可展开的预设选择器（本次改造的核心控件）
// ---------------------------------------------------------------------------

/**
 * 一行"按钮 + 就地展开的竖向菜单"。
 *
 * 形态（用户 2026-10-28 要求：语气/人设、角度都改成按钮，点了展开竖向排布的预设菜单）：
 * ```
 *  语气 / 人设                     毒舌锐评  ›        ← 收起：一行，右值 + 箭头
 *  ┌───────────────────────────────────────┐
 *  │  不指定（默认）                ✓      │        ← 展开：浅灰内嵌块里的竖向列表
 *  │  毒舌锐评                             │
 *  │  …                                    │
 *  │  自定义…                              │
 *  └───────────────────────────────────────┘
 * ```
 *
 * 为什么不弹窗：这里只是"从几条预设里挑一个"，弹窗会盖住卡片、还得处理遮罩层级
 * （浮动层体系 AppOverlayHost 是给危险操作/确认用的）。就地展开更像系统设置页的
 * "展开选项"，也不会把下面的卡片顶走太多。
 *
 * 状态说明：
 *   · [expanded] 由外层持有 —— 这样同一张卡里两个选择器能互斥（开一个自动收另一个）
 *   · 自定义编辑框的草稿 draft 用 `remember(expanded)`，**每次展开都从当前值重来**，
 *     避免上次没提交的输入残留
 */
@Composable
fun ExpandablePicker(
    label: String,
    value: String,
    presets: List<RoastPreset>,
    onSelect: (String) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    allowCustom: Boolean = true,
    customPlaceholder: String = "直接描述你想要的风格 / 角度",
) {
    val cs = MiuixTheme.colorScheme
    val matched = RoastPresets.match(presets, value)
    val isCustom = matched == null
    val display = matched?.label ?: if (value.isBlank()) "默认" else value

    // 收起/展开时箭头转 90°（'›' 转成 '⌄'），给一个明确的"我来回开关"的反馈
    val arrowAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "picker-arrow",
    )

    var draft by remember(expanded) { mutableStateOf(value) }
    var editing by remember(expanded) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        // ---- 收起态那一行本身就是按钮 ----
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 15.sp,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = display,
                fontSize = 14.sp,
                // 自定义值用主色，和"这条不是预设"区分开
                color = if (isCustom) cs.primary else cs.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1.3f),
            )
            Text(
                text = "›",
                fontSize = 18.sp,
                color = cs.onSurfaceVariantSummary,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .rotate(arrowAngle),
            )
        }

        // ---- 展开态：浅灰内嵌块 + 竖向列表 ----
        // 限高 5 行 + 内部滚动（用户 2026-10-28 要求）：语气有 7 条预设、加「自定义…」共 8 行，
        // 全展开会把下面两张卡片顶下去 320dp。现在最多露 5 行，多出来的在块内滚，
        // 整块高度恒定 200dp，展开/收起不会让页面大幅跳动。
        val innerScroll = rememberScrollState()
        val rowPx = with(LocalDensity.current) { PickerRowHeight.toPx() }
        LaunchedEffect(expanded) {
            if (expanded) {
                // 展开时自动把当前选中项滚进可视区（否则选中的那条可能在 5 行之外，用户以为没选中）
                val idx = presets.indexOfFirst { it.value == value.trim() }
                if (idx > 0) innerScroll.animateScrollTo((idx * rowPx).toInt())
            } else {
                innerScroll.scrollTo(0)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(100)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surface)     // #F7F7F7，白卡片里的一块"内嵌面板"
                    .heightIn(max = PickerRowHeight * MaxVisibleRows)
                    .verticalScroll(innerScroll)
                    .padding(vertical = 4.dp),
            ) {
                presets.forEach { p ->
                    PickerRow(
                        label = p.label,
                        selected = p.value == value.trim(),
                        onClick = {
                            onSelect(p.value)
                            editing = false
                            onToggle()               // 选完就收起，少一次点击
                        },
                    )
                }

                if (allowCustom) {
                    PickerRow(
                        label = "自定义…",
                        selected = isCustom,
                        onClick = { editing = !editing },
                    )
                    if (editing) {
                        // 就地输入：草稿不会实时写回 state，必须点「用这个」才生效
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            TextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = customPlaceholder,
                                singleLine = false,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Button(
                                    onClick = {
                                        onSelect(draft.trim())
                                        editing = false
                                        onToggle()
                                    },
                                ) {
                                    Text("用这个", fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 菜单里的一行：选中项用主色 + 右侧 ✓ */
/** 菜单行固定高度：内部滚动要靠它算偏移量（改这个值，滚动定位会自动跟着走） */
private val PickerRowHeight = 40.dp

/** 菜单最多露几行，多出来的内部滚动 */
private const val MaxVisibleRows = 5

@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(PickerRowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = if (selected) cs.primary else cs.onSurface,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) Text("✓", fontSize = 14.sp, color = cs.primary)
    }
}

// ---------------------------------------------------------------------------
// 「剧场光效」（出图中的按钮进度表现）
// ---------------------------------------------------------------------------

/**
 * 剧场光效：一条柔光带斜着反复扫过整块区域，像舞台追光从台口划过去。
 *
 * 为什么不用转圈（用户 2026-10-28 定的）：
 *   · miuix 的 `CircularProgressIndicator` 尺寸要单独传 `size` 参数，`Modifier.size()` 只压画布
 *     （绘图半径仍按默认 30dp 算 → 圆环被裁、看着不垂直居中）；
 *   · 圆环和文字并排放还会互相挤占空间。
 *   光效是**纯背景层**：不占布局、不参与排版，文字永远在正中间。
 *
 * 实现要点：
 *   · `active = false` 时直接不组合（不跑无限动画，静止时零开销）；
 *   · 用 `keyframes` 做"停 → 扫 → 停"，比匀速循环更像舞台灯（periodMs 里 18% 停、67% 扫、15% 收）；
 *   · 光带是斜的：`linearGradient` 的 start/end 在 y 上错开 skew，形成斜切；
 *   · 自身**不裁剪**，靠外层的 `clip(RoundedCornerShape(...))` 收边（所以能盖住圆角）。
 *
 * 调参入口：[bandWidthRatio] 带宽（占宽比例）、[alpha] 亮度峰值、[periodMs] 周期。
 */
@Composable
fun FlowLight(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    bandWidthRatio: Float = 0.24f,   // 窄一点更像"一道光"，宽了会变成整块发白
    alpha: Float = 0.95f,            // 白光是打在 #F0F0F0 底上，峰值低了根本看不出来
    periodMs: Int = 2200,
) {
    if (!active) return

    val transition = rememberInfiniteTransition(label = "flow-light")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = periodMs
                0f at 0
                0f at (periodMs * 0.18f).toInt()      // 起手停一下，别一直闪
                1f at (periodMs * 0.85f).toInt()      // 扫过整块
                1f at periodMs
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "flow-light-progress",
    )

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val bandW = w * bandWidthRatio
        val travel = w + bandW * 2f                    // 从左侧画外 → 右侧画外
        val cx = -bandW + travel * progress
        val skew = h * 0.9f                            // 斜切量

        val brush = Brush.linearGradient(
            0.00f to Color.Transparent,
            0.35f to color.copy(alpha = alpha * 0.06f),   // 长尾，扫过去才有"拖影"
            0.50f to color.copy(alpha = alpha),           // 亮芯
            0.65f to color.copy(alpha = alpha * 0.06f),
            1.00f to Color.Transparent,
            start = Offset(cx - bandW / 2f, 0f),
            end = Offset(cx + bandW / 2f, skew),
        )
        drawRect(brush)
    }
}

// ---------------------------------------------------------------------------
// 输入框 / 只读行
// ---------------------------------------------------------------------------

/**
 * 紧凑输入框（40dp 高，圆角填充底）。
 *
 * **为什么不用 miuix `TextField`**：它内部是按 56dp 排版的（label 区 + 输入行），
 * 拿 `Modifier.height(40.dp)` 硬压会**把文字裁成一条细缝** ——
 * 实测「600」只剩 y714..722 共 9px 高，肉眼就是一条黑线（用户 2026-10-28 报"文本被挤压显示不全"）。
 * 所以这里用 `BasicTextField` 自己排版：高度、字号、对齐、光标色全部可控。
 *
 * @param maxLength 最多输入几位（字数框 4 位，够 9999）
 */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Number,
    maxLength: Int = 4,
    textAlign: TextAlign = TextAlign.Center,
) {
    val cs = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            // 6% onSurface 压在白色卡片上 ≈ #F0F0F0，和原来 miuix TextField 的填充底一致
            .background(cs.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.take(maxLength)) },
            textStyle = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onSurface,
                textAlign = textAlign,
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 带标签的输入框 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    numeric: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = placeholder,
            enabled = enabled,
            singleLine = singleLine,
        )
    }
}

/** 一行 label + 值（只读展示） */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(
            value,
            fontSize = 14.sp,
            color = if (valueColor == Color.Unspecified) MiuixTheme.colorScheme.onSurface else valueColor,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
