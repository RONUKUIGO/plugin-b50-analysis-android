package com.b50.roast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun SectionCard(
    title: String,
    desc: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        SmallTitle(title)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)) {
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

/** 单个可点选 chip */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val cs = MiuixTheme.colorScheme
    val bg = if (selected) cs.primary else cs.surface
    val fg = if (selected) cs.onPrimary else cs.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(
                if (selected) Modifier
                else Modifier.border(1.dp, cs.secondary, RoundedCornerShape(50))
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (enabled) fg else fg.copy(alpha = 0.4f),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** 自动换行的 chip 组（用 FlowRow 太依赖实验 API，这里用简易分行） */
@Composable
fun ChipGroup(
    items: List<Pair<String, String>>,   // key -> label
    isSelected: (String) -> Boolean,
    onClick: (String) -> Unit,
    perRow: Int = 4,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (key, label) ->
                    Chip(label = label, selected = isSelected(key), onClick = { onClick(key) })
                }
            }
        }
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
