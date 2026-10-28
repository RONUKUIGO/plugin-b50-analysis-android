package com.b50.roast.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b50.roast.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账号页：只显示「使用人自己」的东西。
 *
 * 不再暴露任何服务端信息（应用 id、端口、token 路径一概不显示）：
 *   · 未绑定 → 一句话说明 + 「绑定水鱼账号」
 *   · 绑定中 → 用户码大字 + 自动打开浏览器 + 状态提示
 *   · 已绑定 → 昵称 / Rating / 成绩条数 / 数据时间 + 刷新 + 解绑
 *
 * 整个流程跑在 App 内（OAuthClient 设备码 + refresh_token 轮换），
 * 不经过任何本地 HTTP 服务。
 */
@Composable
fun OAuthScreen(
    state: AppState,
    scope: CoroutineScope,
    // 设置入口：顶栏的小按钮已按用户要求去掉，统一从这一页进
    onOpenSettings: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val ctx = LocalContext.current

    // 只要本地缓存（不联网、不扣次数）；没有缓存就等用户自己点「刷新数据」
    LaunchedEffect(Unit) {
        if (state.bound) state.loadFromCache()
    }

    var refreshMsg by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }

    // 开发者入口：QQ 卡片标题连点 9 次（两次之间超过 1.5 秒就重新计数，防误触）
    var devTaps by remember { mutableStateOf(0) }
    var devLastTap by remember { mutableStateOf(0L) }
    var showDevPanel by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        // ---------- 出图用的 QQ 号 ----------
        // 生成的成绩图里要显示头像，头像按 QQ 号去水鱼下载，所以这是出图的必需参数。
        // 放在这里 + 敲一个字符存一次盘，生成页就不用每次再填了。
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "出图用的 QQ 号",
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures {
                                val now = System.currentTimeMillis()
                                if (now - devLastTap > 1500L) devTaps = 0
                                devLastTap = now
                                devTaps++
                                if (devTaps >= 9) {
                                    devTaps = 0
                                    showDevPanel = true
                                }
                            }
                        },
                )
                Text(
                    "出图时按它下载水鱼头像，填一次长期保存，生成页不用再填。",
                    fontSize = 12.sp,
                    color = cs.onSurface.copy(alpha = 0.62f),
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextField(
                    value = state.qq,
                    onValueChange = { state.updateQq(it) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = "QQ 号（纯数字）",
                    singleLine = true,
                )
                if (state.qq.isNotBlank()) {
                    Text(
                        "已保存：" + state.qq,
                        fontSize = 12.sp,
                        color = cs.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (state.bound) "我的水鱼账号" else "还没绑定",
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = if (state.bound) cs.primary else cs.onSurface,
                )

                if (state.bound) {
                    Spacer(Modifier.height(12.dp))
                    AccountLine("昵称", state.accountName.ifBlank { "—" })
                    AccountLine("Rating", if (state.accountRating > 0) state.accountRating.toString() else "—")
                    AccountLine(
                        "成绩记录",
                        if (state.accountRecords > 0) state.accountRecords.toString() + " 条" else "—",
                    )
                    AccountLine("数据时间", fmtTime(state.accountSyncedAt))
                } else {
                    Text(
                        "绑定你自己的水鱼（diving-fish）账号，App 才能读到你的全量成绩并出图。",
                        fontSize = 13.sp,
                        color = cs.onSurface.copy(alpha = 0.62f),
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "授权只需要一次：点下面的按钮 → 浏览器里点「同意」→ 回到 App 自动完成。",
                        fontSize = 12.sp,
                        color = cs.onSurface.copy(alpha = 0.5f),
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                // ---------------- 绑定中 ----------------
                if (state.bindState == "waiting") {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        state.bindUserCode,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = cs.primary,
                    )
                    Text(
                        "在浏览器里输入上面的用户码并点「同意」",
                        fontSize = 12.sp,
                        color = cs.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (state.bindUrl.isNotBlank()) {
                        Button(
                            onClick = {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(state.bindUrl))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text("打开浏览器授权") }
                    }
                    Text(
                        "等待中…（用户码 10 分钟内有效）",
                        fontSize = 12.sp,
                        color = cs.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                // ---------------- 错误 ----------------
                if (state.bindState == "error" && state.bindError.isNotBlank()) {
                    Text(
                        state.bindError,
                        fontSize = 12.sp,
                        color = cs.error,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                // ---------------- 操作 ----------------
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.bound) {
                        Button(
                            onClick = {
                                scope.launch {
                                    refreshing = true
                                    refreshMsg = ""
                                    val ok = state.refreshRecords()
                                    refreshMsg = if (ok) "已更新到最新成绩（数据 " + state.dataAgeText() + "）"
                                                 else state.refreshError.ifBlank { "刷新失败" }
                                    refreshing = false
                                }
                            },
                            enabled = !refreshing,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (refreshing) "刷新中…" else "刷新数据") }
                        Button(
                            onClick = { state.unbind() },
                            modifier = Modifier.weight(1f),
                        ) { Text("解除绑定") }
                    } else {
                        Button(
                            onClick = { state.startBind(scope) },
                            enabled = state.bindState != "waiting",
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.bindState == "waiting") "等待授权…" else "绑定水鱼账号")
                        }
                    }
                }

                // 「刷新数据」是唯一会消耗水鱼 client_id 额度的操作
                Text(
                    "刷新会消耗 1 次在线额度：" + state.quotaText(),
                    fontSize = 12.sp,
                    color = if (state.quotaBlock.isNotBlank()) cs.error else cs.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (refreshMsg.isNotBlank()) {
                    Text(
                        refreshMsg,
                        fontSize = 12.sp,
                        color = if (refreshMsg.startsWith("已更新")) cs.primary else cs.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // ---------- 设置入口 ----------
        // 用户要求：不要顶栏那个小按钮，入口放这里，做成列表行（整行可点，右侧箭头）
        SectionCard("应用设置", "AI 供应商、接口参数、本地资源包都在里面") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("打开设置", fontSize = 15.sp, color = cs.onSurface)
                Text("›", fontSize = 18.sp, color = cs.onSurfaceVariantSummary)
            }
        }

        Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("关于授权", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "· 授权范围只有「读取成绩」，不能改你的任何数据\n" +
                        "· 凭据只存在这台手机里，不会上传到任何服务器\n" +
                        "· 每台设备各自绑定自己的账号，互不影响\n" +
                        "· 随时可以在这里解除绑定",
                    fontSize = 12.sp,
                    color = cs.onSurface.copy(alpha = 0.6f),
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(30.dp))
    }

    // 开发者入口：QQ 卡片标题连点 9 次触发
    if (showDevPanel) {
        DevPanel(state = state, onDismiss = { showDevPanel = false })
    }
}

/**
 * 开发者模式面板。入口藏在 QQ 卡片标题的 9 连点上，不在界面上留任何可见入口。
 *
 * 填对凭据后 `state.devUnlocked = true`：在线刷新既不查额度也不扣次数。
 * 凭据本身在 DevKey 里做 XOR 混淆，dex 里搜不到明文。
 */
@Composable
private fun DevPanel(state: AppState, onDismiss: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    var input by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var msgOk by remember { mutableStateOf(false) }

    BackHandler { onDismiss() }

    Box(
        // imePadding：键盘弹起来时把整个弹层顶上去，否则输入框下方的按钮会被键盘挡住点不到
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("开发者模式", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Text(
                    if (state.devUnlocked) "当前已开启：本机不再限制在线刷新次数。"
                    else "填入开发者凭据后，本机不再限制在线刷新次数。",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = cs.onSurface.copy(alpha = 0.62f),
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = "开发者凭据",
                    singleLine = true,
                )
                if (msg.isNotBlank()) {
                    Text(
                        msg,
                        fontSize = 12.sp,
                        color = if (msgOk) cs.primary else cs.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            android.util.Log.w("B50Dev", "取消 clicked")
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("取消") }
                    Button(
                        onClick = {
                            android.util.Log.w("B50Dev", "确定 clicked, input=" + input.length + " 位")
                            if (state.unlockDev(input)) {
                                msgOk = true
                                msg = "已开启：不再限制在线刷新次数"
                                input = ""
                            } else {
                                msgOk = false
                                msg = "凭据不正确"
                            }
                        },
                        enabled = input.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("确定") }
                }
                if (state.devUnlocked) {
                    Button(
                        onClick = {
                            state.lockDev()
                            msgOk = true
                            msg = "已关闭开发者模式"
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) { Text("关闭开发者模式") }
                }
            }
        }
    }
}

@Composable
private fun AccountLine(label: String, value: String) {
    val cs = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f))
        Text(value, fontSize = 13.sp, color = cs.onSurface)
    }
}

private fun fmtTime(ts: Long): String {
    if (ts <= 0) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ts))
}
