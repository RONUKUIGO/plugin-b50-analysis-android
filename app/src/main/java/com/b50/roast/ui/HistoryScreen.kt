package com.b50.roast.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b50.roast.AppState
import com.b50.roast.data.ShotItem
import com.b50.roast.data.ShotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 成绩图原始比例（1200×2104），三列格子按它裁切，保证不拉伸 */
private const val SHOT_ASPECT = 1200f / 2104f

/**
 * 历史记录：直接扫 App 私有目录 filesDir/shots/，三列自适应缩略图。
 *
 * 不再依赖任何后端接口 —— 图本来就躺在本地，扫盘最快也最可靠。
 * 每格缩略图在 IO 线程按 420px 目标宽度采样解码（RGB_565），
 * 解码结果进 LruCache，滚动回滚时不再重复解码。
 *
 * 点格子弹出大图预览，可存相册 / 分享 / 删除。
 */
@Composable
fun HistoryScreen(state: AppState, scope: CoroutineScope) {
    val cs = MiuixTheme.colorScheme
    val store = state.shots

    var list by remember { mutableStateOf(emptyList<ShotItem>()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var preview by remember { mutableStateOf(null as ShotItem?) }

    // 进入页面（或 reload 变化）就扫一次盘。切 tab 时本屏会重建，所以每次进来都是新的
    LaunchedEffect(reload) {
        loading = true
        list = withContext(Dispatchers.IO) { store.list() }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- 顶部统计 ----------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (loading) "扫描中…"
                else "共 " + list.size + " 张 · " + fmtTotal(list.sumOf { it.sizeBytes }),
                fontSize = 13.sp,
                color = cs.onSurfaceVariantSummary,
            )
            Button(onClick = { reload++ }) { Text("刷新") }
        }

        if (!loading && list.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有生成过图", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "去「生成」页点一次出图，成品会出现在这里",
                        fontSize = 12.sp,
                        color = cs.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // ---------- 三列网格 ----------
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.name }) { item ->
                ShotCell(item = item, store = store, onClick = { preview = item })
            }
        }
    }

    // 返回栈第 3 层：预览层 → 关预览（比切 tab 更内层，所以放在这里注册）
    BackHandler(enabled = preview != null) { preview = null }

    // ---------- 大图预览层 ----------
    preview?.let { item ->
        PreviewOverlay(
            item = item,
            store = store,
            scope = scope,
            onClose = { preview = null },
            onDeleted = {
                preview = null
                reload++
            },
        )
    }
}

/** 一个格子：缩略图 + 时间水印 */
@Composable
private fun ShotCell(item: ShotItem, store: ShotStore, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    var bmp by remember(item.name) { mutableStateOf(null as Bitmap?) }

    LaunchedEffect(item.name) {
        bmp = withContext(Dispatchers.IO) { store.thumb(item) }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(SHOT_ASPECT)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surface)
            .clickable(onClick = onClick),
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "…",
                fontSize = 12.sp,
                color = cs.onSurfaceVariantSummary,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // 时间条压在底部，方便一眼认出是哪天的图
        Text(
            item.timeText,
            fontSize = 10.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

/** 大图预览 + 存相册 / 分享 / 删除 */
@Composable
private fun PreviewOverlay(
    item: ShotItem,
    store: ShotStore,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onDeleted: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val ctx = LocalContext.current
    var bmp by remember(item.name) { mutableStateOf(null as Bitmap?) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(item.name) {
        bmp = withContext(Dispatchers.IO) { store.full(item) }
    }

    fun say(s: String) {
        msg = s
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
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
                    Chip("关闭", false, onClick = onClose)
                }

                // ---------- 操作 ----------
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip("存相册", false, enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            val r = store.saveToGallery(item)
                            busy = false
                            say(r)
                        }
                    })
                    Chip("分享", false, enabled = !busy, onClick = {
                        val send = store.shareIntent(item)
                        if (send == null) {
                            say("生成分享链接失败")
                        } else {
                            runCatching { ctx.startActivity(send) }
                                .onFailure { e -> say("打开发送面板失败：" + e.message) }
                        }
                    })
                    Chip(if (confirmDelete) "确认删除" else "删除", confirmDelete, enabled = !busy, onClick = {
                        if (!confirmDelete) {
                            confirmDelete = true
                            msg = "再点一次「确认删除」"
                        } else {
                            val ok = store.delete(item)
                            Toast.makeText(
                                ctx,
                                if (ok) "已删除" else "删除失败",
                                Toast.LENGTH_SHORT,
                            ).show()
                            if (ok) onDeleted() else { confirmDelete = false; msg = "删除失败" }
                        }
                    })
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
}

private fun fmtTotal(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(java.util.Locale.CHINA, "%.2f GB", bytes / 1_073_741_824f)
    bytes >= 1_048_576L -> String.format(java.util.Locale.CHINA, "%.1f MB", bytes / 1_048_576f)
    else -> (bytes / 1024L).toString() + " KB"
}
