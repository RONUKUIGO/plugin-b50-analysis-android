package com.b50.roast

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b50.roast.data.ResourceInstaller
import com.b50.roast.ui.GenerateScreen
import com.b50.roast.ui.HistoryScreen
import com.b50.roast.ui.OAuthScreen
import com.b50.roast.ui.SettingsScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 入口：miuix 浅色主题 + 3 个底部 tab（生成 / 历史 / 授权）+ 设置页。
 * 用户要求的"浅色 + 底部导航"在这里落地。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme(colors = lightColorScheme()) {
                // Compose 没有「点输入框外面自动收起键盘」的内置行为，得在根部自己挂一个 tap 监听：
                //   · 命中空白处 → clearFocus()，EditText 失焦后系统会自动收起软键盘
                //   · 被子组件（Button / Chip / 列表项）消费掉的点击不会传到这里，不影响原有交互
                //   · 滑动（有位移）不算 tap，所以滚页面不会误收起键盘
                val focusManager = LocalFocusManager.current
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { focusManager.clearFocus() })
                        }
                ) {
                    AppRoot()
                }
            }
        }
    }
}

private enum class Tab(val label: String) {
    GENERATE("生成"),
    HISTORY("历史"),
    OAUTH("授权"),
}

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    val state = remember { AppState(ctx.applicationContext) }

    // ── 首启：把 assets/pack.zip（21 MB / 曲绘 1569 张）解压到 filesDir/res ──
    val installer = remember { ResourceInstaller(ctx.applicationContext) }
    var assetsReady by remember { mutableStateOf(installer.isInstalled()) }
    var progress by remember { mutableStateOf(ResourceInstaller.Progress(0, 0)) }
    var installError by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf(0) }

    LaunchedEffect(retry) {
        if (!assetsReady) {
            installError = ""
            runCatching { installer.ensureInstalled { progress = it } }
                .onSuccess {
                    assetsReady = true
                    Log.i("B50Assets", "首启资源检查通过 version=" + installer.installedVersion() +
                        " 曲绘=" + java.io.File(installer.root(), "cover").listFiles()?.size +
                        " 占用=" + installer.usedBytes() / 1048576 + "MB")
                }
                .onFailure {
                    installError = it.message ?: "资源解压失败"
                    Log.e("B50Assets", "资源解压失败", it)
                }
        }
    }
    if (!assetsReady) {
        InstallScreen(progress, installError) { retry++ }
        return
    }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.GENERATE) }
    var showSettings by remember { mutableStateOf(false) }

    // 已绑定但还没规整过 → 同步一次（拿 B35/B15）
    LaunchedEffect(state.bound) {
        Log.w("B50Sync", "触发同步：bound=" + state.bound + " b50=" + (state.b50 != null))
        if (state.bound && state.b50 == null) {
            runCatching { state.loadFromCache() }
                .onSuccess { Log.w("B50Sync", "同步完成，b50=" + (state.b50 != null)) }
                .onFailure { Log.e("B50Sync", "同步失败：" + it.javaClass.simpleName + " / " + it.message, it) }
        }
    }

    // 资源就绪后安装 Python 运行时（首启约 2~4 秒）
    LaunchedEffect(assetsReady) {
        if (assetsReady && !state.pyReady) {
            runCatching { state.installPython() }
                .onFailure { Log.e("B50Py", "installPython 异常", it) }
        }
    }

    // 数据就绪后自动渲染一张（本地出图自检）
    LaunchedEffect(state.b50) {
        // 只有"数据就绪 + 本次会话没出过图 + 历史目录是空的"才自检一张，
        // 否则每次冷启动都会白渲染一张 1.7 MB 的图
        if (state.b50 != null && state.lastShot == null && !state.hasAnyShot()) {
            // consume = false：首启自检不算用户额度
            runCatching { state.renderWithPython("", state.width) }
                .onFailure { Log.e("B50Render", "自动出图失败", it) }
        }
    }

    // 返回栈第 1 层：设置页 → 关设置（而不是直接回桌面）
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(state = state, scope = scope, onBack = { showSettings = false })
        return
    }

    // 返回栈第 2 层：不在「生成」tab 时，返回先回「生成」，再按才是退出
    BackHandler(enabled = tab != Tab.GENERATE) { tab = Tab.GENERATE }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "B50 锐评机",
                subtitle = when (tab) {
                    Tab.GENERATE -> "填 QQ 号，出图 + 锐评"
                    Tab.HISTORY -> "最近生成的成绩图"
                    Tab.OAUTH -> "水鱼账号授权"
                },
                actions = {
                    Button(onClick = { showSettings = true }) { Text("设置") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.GENERATE,
                    onClick = { tab = Tab.GENERATE },
                    icon = MiuixIcons.Basic.Search,
                    label = Tab.GENERATE.label,
                )
                NavigationBarItem(
                    selected = tab == Tab.HISTORY,
                    onClick = { tab = Tab.HISTORY },
                    icon = MiuixIcons.Basic.ArrowRight,
                    label = Tab.HISTORY.label,
                )
                NavigationBarItem(
                    selected = tab == Tab.OAUTH,
                    onClick = { tab = Tab.OAUTH },
                    icon = MiuixIcons.Basic.Check,
                    label = Tab.OAUTH.label,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.GENERATE -> GenerateScreen(
                    state = state,
                    scope = scope,
                    onOpenSettings = { showSettings = true },
                )
                Tab.HISTORY -> HistoryScreen(state = state, scope = scope)
                Tab.OAUTH -> OAuthScreen(state = state, scope = scope)
            }
        }
    }
}

/**
 * 首启解压界面。只在这一步显示，解压完成后永久跳过（除非 PACK_VERSION 变了）。
 */
@Composable
private fun InstallScreen(
    progress: ResourceInstaller.Progress,
    error: String,
    onRetry: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("B50 锐评机", fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Text(
            "本地资源包 cn:" + ResourceInstaller.PACK_VERSION,
            fontSize = 13.sp,
            color = cs.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(32.dp))

        if (error.isBlank()) {
            val frac = (progress.percent / 100f).coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxWidth().height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(cs.onSurface.copy(alpha = 0.12f))
            ) {
                Box(
                    Modifier.fillMaxWidth(if (frac <= 0f) 0.001f else frac).height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(cs.primary)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "${progress.percent}%   ${progress.done}/${progress.total} 个文件",
                fontSize = 12.sp,
                color = cs.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "首次启动约 5~10 秒，之后出图完全离线",
                fontSize = 11.sp,
                color = cs.onSurface.copy(alpha = 0.45f),
            )
        } else {
            Text(error, fontSize = 13.sp, color = cs.error)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}
