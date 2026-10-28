package com.b50.roast

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.b50.roast.data.ResourceInstaller
import com.b50.roast.ui.AppHeader
import com.b50.roast.ui.AppOverlay
import com.b50.roast.ui.AppOverlayHost
import com.b50.roast.ui.GenerateScreen
import com.b50.roast.ui.HistoryScreen
import com.b50.roast.ui.OAuthScreen
import com.b50.roast.ui.RowDivider
import com.b50.roast.ui.SettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
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

    // 界面可见性：出图结束时 AppState 靠它决定"弹窗"还是"发通知"
    override fun onResume() {
        super.onResume()
        AppForeground.visible = true
    }

    override fun onPause() {
        super.onPause()
        AppForeground.visible = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 浅色主题 → 状态栏/导航栏图标必须是深色，否则浅色图标压在 #F7F7F7 上看不清。
        // 之前全工程一处都没设，全靠 Theme.Material.Light 的默认值（MIUI 上实测最右那颗
        // 只有 L≈120~180，明显发虚）。这两行不改变布局/insets，只切图标明暗。
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
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

    // ── Android 13+ 通知权限 ──
    // 出图 20~60 秒，进度/完成都靠通知提醒；没有这个权限系统会静默丢弃（不报错，最难查）。
    // 只在这里要一次：用户拒了也不影响出图，只是没有通知。
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> Log.w("B50Notify", "通知权限：$granted") }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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

    // ── 浮层状态：弹窗必须在 Scaffold 之外渲染 ──
    // Scaffold 会把内容塞进 content 区，写在那里的遮罩永远盖不住 TopAppBar / NavigationBar。
    // 旧写法实测：遮罩只从 y=406 开始，顶栏 0~406 全亮；放在根 Box 才是真正的最上层。
    var overlay by remember { mutableStateOf(null as AppOverlay?) }
    val dismissOverlay: () -> Unit = { overlay = null }

    // ── 页面层级 ────────────────────────────────────────────────────────────
    // 用户要求：点进一个界面要像"开了新标签页"，且预测性返回的动效要对。
    // 结构 = 底层主界面（3 个 tab，切换带方向感）+ 上层设置页（从右滑入）。
    // manifest 早就开了 enableOnBackInvokedCallback，但以前设置页是 if/else 直接换内容，
    // 系统找不到"可以缩回去的那一页"，于是返回手势什么都没有 —— 这就是观感差的根因。
    var backProgress by remember { mutableFloatStateOf(0f) }

    PredictiveBackHandler(enabled = showSettings) { progress ->
        try {
            progress.collect { backProgress = it.progress }   // 手势中：跟手右移（0→1）
            showSettings = false                              // 手势完成：真的关掉
        } catch (_: CancellationException) {
            // 手势取消 → 下一帧回到原位
        } finally {
            backProgress = 0f
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ---------- 底层：主界面 ----------
        // 不在「生成」tab 时，返回先回「生成」，再按才是退出
        BackHandler(enabled = !showSettings && tab != Tab.GENERATE) { tab = Tab.GENERATE }

        Scaffold(
            topBar = {
                // 顶栏 = 自己拼的 AppHeader（见 ui/Common.kt）。
                // 换掉 miuix TopAppBar 的原因：它把"大标题上方 52dp"硬编码成大标题块的上内边距，
                // 实测本机状态栏底(104px) 到标题第一笔之间有 159px 空白 —— 用户 2026-10-28
                // 反馈"标题顶部留空太多"。AppHeader 把这段压到 8dp，整块顶栏从 408px 降到约 240px。
                // 下面那条 1px 分割线保留：顶栏与内容同为 #F7F7F7，没有分界时滚动会糊在一起。
                Column {
                    AppHeader(
                        title = "B50 锐评机",
                        subtitle = when (tab) {
                            Tab.GENERATE -> "填 QQ 号，出图 + 锐评"
                            Tab.HISTORY -> "最近生成的成绩图"
                            Tab.OAUTH -> "水鱼账号授权"
                        },
                        // 顶栏不再放「设置」小按钮：入口挪到授权页（用户 2026-10-28 要求）
                    )
                    RowDivider(startIndent = 0.dp)
                }
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
                // tab 切换：短距离横向滑入 + 淡入，做出「换了一张标签页」的感觉
                // （方向按 tab 序号决定，往右切就从右边进，往左切就从左边进）
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                        (slideInHorizontally(tween(240)) { it * dir / 8 } + fadeIn(tween(180)))
                            .togetherWith(
                                slideOutHorizontally(tween(240)) { -it * dir / 8 } + fadeOut(tween(140))
                            )
                    },
                    label = "tab-switch",
                ) { t ->
                    when (t) {
                        Tab.GENERATE -> GenerateScreen(
                            state = state,
                            scope = scope,
                            onOverlay = { overlay = it },
                            onGoHistory = { tab = Tab.HISTORY },
                        )
                        Tab.HISTORY -> HistoryScreen(
                            state = state,
                            scope = scope,
                            onOverlay = { overlay = it },
                        )
                        Tab.OAUTH -> OAuthScreen(
                            state = state,
                            scope = scope,
                            onOpenSettings = { showSettings = true },
                        )
                    }
                }
            }
        }

        // ---------- 上层：设置页（"新标签页"从右侧滑入 + 预测性返回跟手） ----------
        val enter by animateFloatAsState(
            targetValue = if (showSettings) 1f else 0f,
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            label = "settings-enter",
        )
        if (showSettings || enter > 0.01f) {
            // 设置页也放进 Scaffold：底色统一（#F7F7F7，别再吃窗口的 #FAFAFA）、
            // 状态栏 inset 正确（原来标题压在状态栏图标上）。
            // 注意别传 containerColor = cs.background —— 那是 #FFFFFF，会和白卡片糊成一片。
            Scaffold(
                modifier = Modifier.graphicsLayer {
                    // ① 进入：从右侧 30% 处滑到 0；② 预测性返回：跟着手指右移（最大 35% 宽），
                    // 同时轻微缩小 + 变淡 —— 让用户清楚看到"这一页正在被推回去"，
                    // 而不是像以前那样松手后突兀地啪一下消失。
                    translationX = (1f - enter) * 0.30f * size.width +
                        backProgress * 0.35f * size.width
                    val sc = 1f - backProgress * 0.08f
                    scaleX = sc
                    scaleY = sc
                    alpha = enter * (1f - backProgress * 0.15f)
                    shadowElevation = 18.dp.toPx() * enter  // 让它真的"浮"在主界面之上
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    SettingsScreen(state = state, scope = scope)
                }
            }
        }

        // 最上层：全屏遮罩 + miuix Surface 浮层卡片（见 ui/Overlay.kt）
        AppOverlayHost(overlay = overlay, onDismiss = dismissOverlay)
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
