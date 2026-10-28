package com.b50.roast.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b50.roast.data.ShotItem
import com.b50.roast.data.ShotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全局浮层（弹窗）体系。
 *
 * 为什么必须放在 Activity 根部（Scaffold 之外）：
 *   以前两个弹窗是 `Box(fillMaxSize().background(scrim))` 写在各个屏幕里，
 *   而屏幕内容被 Scaffold 塞进 content 区 → 遮罩只能盖住中间那块，
 *   TopAppBar 和 NavigationBar 依旧全亮（真机实测：遮罩从 y=406 才开始，
 *   顶栏 0~406 像素完全没压暗）。放在根部才是最上层的、真正全屏的遮罩。
 *
 * 为什么用 miuix `Surface(shadowElevation = ...)` 而不是 `Card`：
 *   miuix 0.9.4 的 `Card` 是平面卡（签名里没有 elevation 参数），
 *   只有 `Surface` 会把 shadowElevation 交给 `graphicsLayer` 画原生阴影。
 *   浮层用它才有"浮起来"的层级；列表卡片继续用平面 Card 才符合规范。
 */

/** 浮层请求。由屏幕发起、由根部的 [AppOverlayHost] 渲染。 */
sealed interface AppOverlay {

    /** 生成页：成绩数据过期，问一句要不要先联网刷新。 */
    class StaleData(
        val ageText: String,
        val quotaText: String,
        val canRefresh: Boolean,
        val onUseOld: () -> Unit,
        val onRefresh: () -> Unit,
    ) : AppOverlay

    /**
     * 出图完成提示（用户 2026-10-28 要求）。
     * 只在**界面可见**时弹：人已经切走去干别的了，就走系统通知，不弹这个。
     */
    class Done(
        val info: String,
        val warn: String,
        val onOpenHistory: () -> Unit,
    ) : AppOverlay

    /** 历史页：大图预览 + 存相册 / 分享 / 删除。 */
    class Preview(
        val item: ShotItem,
        val store: ShotStore,
        val scope: CoroutineScope,
        val onDeleted: () -> Unit,
    ) : AppOverlay
}

/**
 * 浮层宿主：放在 MainActivity 的根 Box 里（Scaffold 之后），保证层级最高、覆盖全屏。
 * 传 null 表示没有浮层。
 */
@Composable
fun AppOverlayHost(overlay: AppOverlay?, onDismiss: () -> Unit) {
    if (overlay == null) return
    BackHandler { onDismiss() }

    // 遮罩：全屏 → 压暗所有层（含顶栏/底栏）；自身吃掉点击 → 不会穿透到下层
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        when (overlay) {
            is AppOverlay.StaleData -> StaleDataDialog(overlay, onDismiss)
            is AppOverlay.Done -> DoneDialog(overlay, onDismiss)
            is AppOverlay.Preview -> PreviewDialog(overlay, onDismiss)
        }
    }
}

/**
 * 浮层卡片容器：miuix `Surface` + shadowElevation，圆角与阴影形状一致（不会出现方形影子）。
 * 卡片自身再挂一层点击拦截，点卡片内部不会误关弹窗。
 */
@Composable
private fun FloatCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .pointerInput(Unit) { detectTapGestures { /* 吃掉点击，防止穿透到遮罩 */ } },
        shape = shape,
        color = cs.surface,
        shadowElevation = 12.dp,
    ) {
        Column(content = content)
    }
}

/** 「数据是 X 小时前的，要刷新吗」确认层。 */
@Composable
private fun StaleDataDialog(o: AppOverlay.StaleData, onDismiss: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    FloatCard {
        Column(Modifier.padding(20.dp)) {
            Text("成绩数据：" + o.ageText, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text(
                "出图可以直接用这份旧数据，不会消耗次数；" +
                    "也可以先拉一份最新的，那会消耗 1 次在线刷新额度。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = cs.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                o.quotaText,
                fontSize = 12.sp,
                color = if (o.canRefresh) cs.primary else cs.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 点完先关弹窗再开跑：出图要 20~60 秒，弹窗留着会把
                // "正在整理成绩数据…/AI 写作中…" 的进度提示全挡住（2026-10-28 实测发现）
                Button(onClick = { onDismiss(); o.onUseOld() }, modifier = Modifier.weight(1f)) {
                    Text("用旧数据出图")
                }
                Button(
                    onClick = { onDismiss(); o.onRefresh() },
                    enabled = o.canRefresh,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (o.canRefresh) "先刷新再出图" else "额度已用完")
                }
            }
            // 「先不弄了」：显式给一个不花钱的出口，和点遮罩等效
            Text(
                "点空白处取消",
                fontSize = 12.sp,
                color = cs.onSurfaceVariantSummary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 14.dp)
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
        }
    }
}

/**
 * 「出图完成」提示。
 * 为什么还要这个弹窗：出图 20~60 秒，用户很可能还盯着这一页；
 * 只发系统通知的话，人在 App 里根本注意不到横幅（尤其 MIUI 默认把通知折叠）。
 */
@Composable
private fun DoneDialog(o: AppOverlay.Done, onDismiss: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    FloatCard {
        Column(Modifier.padding(20.dp)) {
            Text("出图完成", fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text(
                if (o.info.isBlank()) "已保存到「历史」页" else "已保存到「历史」页 · " + o.info,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = cs.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (o.warn.isNotBlank()) {
                Text(
                    o.warn,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = cs.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        onDismiss()
                        o.onOpenHistory()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("去历史看看")
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("知道了")
                }
            }
        }
    }
}

/** 大图预览 + 存相册 / 分享 / 删除 */
@Composable
private fun PreviewDialog(o: AppOverlay.Preview, onDismiss: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    val ctx = LocalContext.current
    val item = o.item
    var bmp by remember(item.name) { mutableStateOf(null as Bitmap?) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(item.name) {
        bmp = withContext(Dispatchers.IO) { o.store.full(item) }
    }

    fun say(s: String) {
        msg = s
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    }

    FloatCard {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            // 标题行 + 关闭。操作区一律放在最上面，
            // 因为图片本身比可视区高，按钮压在最底下会被挤出屏幕。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        item.timeText + "  ·  " + item.sizeText,
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Chip("关闭", false, onClick = onDismiss)
            }

            // ---------- 操作 ----------
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("存相册", false, enabled = !busy, onClick = {
                    o.scope.launch {
                        busy = true
                        val r = o.store.saveToGallery(item)
                        busy = false
                        say(r)
                    }
                })
                Chip("分享", false, enabled = !busy, onClick = {
                    val send = o.store.shareIntent(item)
                    if (send == null) {
                        say("生成分享链接失败")
                    } else {
                        runCatching { ctx.startActivity(send) }
                            .onFailure { e -> say("打开发送面板失败：" + e.message) }
                    }
                })
                Chip(
                    if (confirmDelete) "确认删除" else "删除",
                    confirmDelete,
                    enabled = !busy,
                    onClick = {
                        if (!confirmDelete) {
                            confirmDelete = true
                            msg = "再点一次「确认删除」"
                        } else {
                            val ok = o.store.delete(item)
                            Toast.makeText(
                                ctx,
                                if (ok) "已删除" else "删除失败",
                                Toast.LENGTH_SHORT,
                            ).show()
                            if (ok) o.onDeleted() else { confirmDelete = false; msg = "删除失败" }
                        }
                    },
                )
            }

            if (msg.isNotBlank()) {
                Text(
                    msg,
                    fontSize = 12.sp,
                    color = cs.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // 图片放最后：比可见区高，靠滚动看全貌，操作按钮始终在上方
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = item.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Text(
                    "加载中…",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}
