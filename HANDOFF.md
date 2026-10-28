# HANDOFF · b50 安卓客户端（App 内嵌 Python 渲染）

> 更新时间：2026-10-28 16:50 (CST)　**当前版本 v1.4（versionCode 4）**　素材 `cn:34274` / `PY_VERSION 7`
> **项目范围：只做安卓版**（`com.b50.roast`）。桌面版（CLI / 本地 Web / QQ 插件）已于 2026-10-28 归档到
> `/sdcard/Tools/LSPilot/archive/b50-desktop-v1.3`（只读快照，见其 `ARCHIVED.md`），不再开发、不再运行。
> 状态：**可编译、可安装、可运行、App 内独立出图（不依赖任何后端）**
> **接手第一件事**：`python3 tools/status.py`（23 个关键文件 / 版本号 / py/ 一致性 / 已安装包比对，全绿再动手）
> 本轮已完成：每日 5 次配额接 UI、历史页三列缩略图（改扫本地）、保存相册 / 分享 / 删除、
> API 供应商选择 + 模型联网获取 + 测试连接 + **LLM 锐评（全链路已通）**
> 本轮收尾：预测性返回（三层返回栈）、QQ 号移到授权页并持久化、删掉整个 8765 后端链路
> **配额语义重构：从「出图次数」改成「在线刷新水鱼数据次数」（3 次/天，出图永久免费）**
> **本轮收尾 2（12:20）**：`pycode.zip` 从黑盒变产物 —— Python 源码回填 `py/`（含此前只存在于 zip 里的
> `render_worker.py`），新增 `tools/pack_pycode.py`（确定性打包 + 与桌面版对账），并修正本文档里
> `PY_VERSION` 的滞后值（文档写 4，实际早已是 5）。
> **v1.4（12:45）**：版本号 `1.0 / versionCode 1` → **`1.4 / versionCode 4`**；设置页「关于」新增
> 「App 版本」一行（为此在 `build.gradle.kts` 打开 `buildConfig = true`）；桌面版归档（两处 443 MB 副本
> 合成一份只读快照 + 17 MB 私有数据），`py/` 对账基线、`pack_assets.py` 素材源路径全部改指归档目录。
> **素材/依赖整合（13:10）**：素材版本代号 **cn:34274** 定为 `PACK_VERSION`（App 里就显示这个）；
> 新增 `tools/fetch_assets.py`（素材四层退路，上游证书过期也能下）、`tools/check_upstream.py`
> （两个上游的三态比对 + 基线快照）；`pack_assets.py` 加「前置检查 / 产物校验 / 版本一致性」三道闸；
> 修掉 `Res.sanityCheck()` 的「缺 2 项」假警报（路径写错）与「缺 1 项」误判（字体阈值一刀切）。
> **UI 打磨专场（16:50，第 7 节第 1/2/3/6/7/8 条全部完成）**：
> ① 浮层/阴影体系重建（`ui/Overlay.kt`，弹窗改根级 + miuix `Surface` 阴影）；
> ② AI 供应商按用户定稿改为 5 家 + 自定义（新增 Gemini/Claude，删 6 家）；
> ③ 启动图标 18 个文件全部由 `tools/make_icons.py` 重绘（含安全区/对比度/遮罩自检）；
> ④ 状态栏图标改深色；⑤ 设置入口从顶栏挪到授权页；⑥ 导航改成「新标签页」叠层 + 预测性返回跟手；
> ⑦ 出图格式 png/jpg/webp 真正接通（`PY_VERSION` 6 → **7**）；⑧ 分区 chip 短标签靠前 + 等宽铺满。
> 全部有真机像素级取证，明细见各条「本轮已完成」小节。
> **新会话接续**：工作区里还有两份跨会话笔记 ——
> `/data/local/tmp/ws/projects/b50/notes/b50-note-continue-20261028.md`（开工三步 / 环境事实 / 三条红线）
> 与 `.../b50-note-ui-polish-20261028.md`（上一轮六项改动的取证与踩坑）。
> 本文档写给下个会话：读完这一页即可继续开发，不用重新摸索。

---

## 0. 一句话现状

App 在手机内独立完成整条链路：
**绑定水鱼账号 → 拉全量 831 条 → 规整 B35/B15 → 生成 7 个分析分区上下文 → 用原版 `render.py` 渲染出图**。
渲染引擎是**内嵌的 Python 3.14 + Pillow 13**（不是 Kotlin 重写），所以出图效果与桌面版**像素级一致**（1200×2104）。
**功能已全部完成**，桌面版的核心链路在 App 内跑齐了：
绑定 → 拉全量 → 规整 B50 → 7 分区上下文 → AI 写锐评 → 渲染出图 → 配额扣减。

**当前阶段：打磨期（发布往后放）** —— 用户 2026-10-28 定：先把 App 打磨好再谈发布。
第 7 节的 1/2/3 条（UI 阴影、供应商表、图标）、追加的 6/7/8 条（状态栏、设置入口、导航动效）
以及第 9/10 条（顶栏留白、卡片重构）**已完成**；
第 4/5 条（App 1.5 首发 + 上游仓库发布）**按用户要求挂起**，等打磨完再动。
下一步建议：让用户实际用一轮，按手感微调（转场时长、滑入幅度），以及继续收集「用着别扭」的点。

## 1. 已验证的事实（真机 logcat 实测，不是推测）

| 能力 | 证据 |
|---|---|
| 资源包解压 | `B50Assets: 解压完成：1618 个文件，耗时 1062 ms → res (24 MB)` |
| Python 运行时就绪 | `B50Py: 1370 ms, 75 MB, 可执行=/data/app/…/lib/arm64/libpy_home.so` |
| 全量取数 | `B50Api: 全量记录已缓存：831 条` |
| 规整与桌面版一致 | `B50Norm: 831 条 \| 旧池 736 / 新池 95 \| 自算 15274（接口 15274）\| 版本 PRiSM PLUS（来源 b50）` |
| 上下文分区 | `INFO evidence={"highlights":4,"ordinaries":2,"highest_song_rating":1,"overlap_extremes":2,"push_recommendations":15,"same_rating_average_entry_points":10,"selected_evidence":9,"config_specialized":2}` |
| Python 渲染出图 | `Python 退出码=0 耗时=1892ms → OK …/shots/b50_*.png 1200x2104` |
| 出图内容完整 | 背景 1085 色 / 曲绘 12638 色 / 整图 22992 色，文件 1.75 MB |
| client_id 已隐藏 | `ApkProbe dex_grep("cc75c2c4…") → hits=0` |

## 2. 架构

```
Kotlin（UI + 取数）                         内嵌 Python（算法 + 渲染）
──────────────────────────────────────      ──────────────────────────────────
OAuthClient   设备码绑定 / refresh 轮换
MaimaiApi     GET /player/records → cache/records.json
                        │
                        ▼  ProcessBuilder #1（阻塞，IO 线程）
                render_worker.py  + B50_PROMPT_OUT=cache/roast_prompt.json
                  normalize_records      ← fetch.py（原版）
                  build_context          ← context_builder.py（7 个分区）
                  build_system/user_prompt ← roast.py（桌面版前 236 行）
                  → 提示词 JSON 落盘后退出（不发 HTTP、不渲染）
                        │
                        ▼  LlmClient.chat()（Kotlin 侧 HttpURLConnection）
              baseUrl/chat/completions → 模型返回 JSON 文本（1006 字符）
                        │
                        ▼  ProcessBuilder #2
                render_worker.py <…> <analysis=模型原文>
                  render_image(context, analysis)
                        │
                        ▼
              filesDir/shots/b50_*.png（带锐评正文 1200×2277，不带 1200×2104）
```

**为什么拆成两次 Python**：HTTP 放 Kotlin 侧 —— 系统 CA、超时、代理、错误信息都更可控
（实测 App 内嵌 Python 没打包 CA bundle，走 urllib 大概率 CERTIFICATE_VERIFY_FAILED）。
Python 只负责「拼提示词」和「画图」这两件它擅长的事。代价是多一次进程启动（约 1.3 秒）。

Python 主程序走 **jniLibs**（`libpy_home.so`），因为 Android 10+ 禁止 exec 私有目录；其余 .so 和标准库解压到 `filesDir/py/`。

## 3. 关键技术约束（改代码前必读，都是踩过的坑）

1. **exec 限制**：`filesDir` 下的文件不能 exec（`avc: denied { execute }`）。
   Python 主程序必须放 `app/src/main/jniLibs/arm64-v8a/libpy_home.so`（系统解压到 `/data/app/<pkg>/lib/arm64/`，可执行），
   并且 `build.gradle.kts` 里要 `jniLibs.keepDebugSymbols += "**/libpy_home.so"` 防止被 strip。
2. **rpath**：Termux 二进制写死 `/data/data/com.termux/files/usr/lib`，必须 `patchelf --set-rpath '$ORIGIN'`（见 tools/build_python_runtime.sh）。
3. **资源目录结构必须与桌面版完全一致**（`render.py` 里是硬编码的）：
   ```
   res/ui/icons/<name>.png      ← 背景 bj / 难度 Master / 数字 UI_NUM_Drating_* / 评级 UI_TTR_Rank_*
   res/ui/default_cover.png
   res/ui/fonts/ResourceHanRoundedCN.otf   ← 文件名关键字 han/cn 才会被 _font_path() 选中
   res/ui/fonts/Torus SemiBold.otf         ← 关键字 torus/bold
   res/cover/<music_id>.png     ← 曲绘，1569 张
   res/avatars/<qq>.png         ← 头像，渲染前由 Kotlin 下载
   res/music_data.json  res/chart_summary.json  res/peer_stats.json  res/kb/
   ```
   文件扩展名可以写 `.png` 而内容是 WebP（PIL 按文件头识别），这样体积只有 21 MB。
4. **Pillow 必须有 WebP 支持**（`_webp.so` + `libwebp.so` + `libsharpyuv.so`），否则 `Image.open()` 报
   `image file could not be identified because WEBP support not installed`。曾为省体积删掉过，别重蹈覆辙。
5. **refresh_token 会轮换**：每次换票都要回写，否则下次失效（`OAuthClient.accessToken()` 已处理）。
6. **增量构建会让 APK 膨胀一倍**（27MB → 52MB），`build_android.sh` 已加 `rm -rf app/build/outputs`。
7. **版本号**：素材变了改 `ResourceInstaller.PACK_VERSION`（当前 **34274** ← 素材版本代号，规则见第 8 节）；Python 运行时变了改 `PythonRunner.PY_VERSION`（当前 **7**）。任一变化，App 升级后自动重新解压。
8. **miuix `Card` 天生没有 elevation，要阴影只能用 `Surface`**。0.9.4 的签名是
   `Card(modifier, cornerRadius, insideMargin, colors, content)` —— 平面卡；
   `Surface(modifier, shape, color, contentColor, border, shadowElevation, content)` 才会把
   shadowElevation 交给 `graphicsLayer` 画原生阴影（形状跟随 shape，不会出现方形影子）。
   所以层级划分是「列表卡片平面 / 浮层加阴影」，别给 `Card` 硬塞阴影，也别用 `Modifier.shadow`
   再叠一层（会双影）。
9. **弹窗必须渲染在 `Scaffold` 之外**。写在屏幕里的 `Box(Modifier.fillMaxSize())` 只覆盖 Scaffold
   的 content 区，`TopAppBar` / `NavigationBar` 会全亮（实测：旧遮罩从 y=406 才开始）。
   统一入口是 `MainActivity` 根部 Box 里的 `ui/Overlay.kt → AppOverlayHost`。
10. **Gradle / Kotlin 守护进程会常驻吃 1~3 GB**（2026-10-28 实测：Gradle daemon RSS 涨到 **2.3 GB**、
    Kotlin daemon **0.8 GB**，构建完都不退出 → 23 GB 的机器被压到只剩 500 MB 可用，用户直接报"没内存了"）。
    已做三件事：`gradle.properties` 里 Gradle 堆 3072m→**2048m**、`kotlin.compiler.execution.strategy=in-process`
    （不再单独起第二个 JVM）、`org.gradle.daemon.idletimeout=300000`（空闲 5 分钟自退）；
    `build_android.sh` 构建成功后**自动 `gradlew --stop` + pkill 两个 daemon**（连续构建想省时间加 `--keep-daemon`）。
    临时救急用 `sh tools/reclaim_mem.sh`（停 daemon + `am kill-all` + 打印前后对比）。

## 3.5 配额模型（重要，别改回去）

**额度限制的对象是「水鱼 client_id 的调用量」，不是「出图次数」。**

| 动作 | 联网？ | 扣次数？ |
|---|---|---|
| 冷启动 / 切页面规整数据（`loadFromCache`） | ❌ 只读本地缓存 | ❌ |
| 出图 + AI 锐评（`roastAndRender`） | ❌ | **❌ 永久免费** |
| 本地数据过期时出图 → 用户选「用旧数据」 | ❌ | ❌ |
| 「授权」页点「刷新数据」（`refreshRecords`） | ✅ | ✅ 1 次 |
| 本地数据过期时出图 → 用户选「先刷新再出图」 | ✅ | ✅ 1 次 |
| 首次拉取（本地从没有过缓存） | ✅ | ❌ **免费** |

- 额度 = **每天 3 次**，次日 0 点重置（`DailyQuota(limit = 3)`）。
- 唯一扣费点：`AppState.refreshRecords()` 里 `quota.consume()`，**请求成功后才扣**。
- `MaimaiApi.fetchRecords(force, allowNetwork)` 是唯一的网络出口：
  非 force 时**只要有缓存就直接用，不看新旧**——数据更不更新由用户拍板，绝不偷偷联网。
- 数据「新鲜期」`MaimaiApi.FRESH_MS = 6 小时`：超过会在出图前弹一次确认层，问要不要花一次额度刷新。

## 4. 文件索引

```
com/b50/roast/
├── MainActivity.kt              入口：首启解压门 → 账号卡片 → 3 tab
├── AppState.kt                  状态中枢：绑定 / 同步 / 规整 / 渲染 / LLM / 配额
├── data/
│   ├── OAuthClient.kt           设备码 + token 轮换（client_id 为 XOR 编码，dex 里搜不到明文）
│   ├── MaimaiApi.kt             /player/records + 缓存 + 版本校准请求
│   ├── MusicDb.kt               music_data.json → 1326/1404 首索引
│   ├── RecordsNormalizer.kt     831 条 → B35/B15（与 Python 逐字一致，**现为兜底**）
│   ├── DailyQuota.kt            每设备 3 次/天「在线刷新」配额（出图不计；blockReason/untilResetText 给界面用）
│   ├── ShotStore.kt             本地出图仓库：扫描 shots/、缩略图 LruCache、存相册、分享 Uri、删除
│   ├── RoastNotify.kt           出图通知：进度（LOW 常驻）/ 完成（DEFAULT 会响）/ 失败 + 点开回 App
│   ├── LlmPresets.kt            供应商预设表（baseUrl / path / 内置模型 / 额外 header / 提示语）
│   ├── RoastPresets.kt          「写什么」卡两个按钮的预设表（语气/人设、角度；label→value 两段式）
│   ├── LlmClient.kt             OpenAI 兼容最小客户端：GET /models、POST /chat/completions 测活
│   ├── ResourceInstaller.kt     assets/pack.zip → filesDir/res（版本化 + 原子替换）
│   ├── PythonRunner.kt          assets/py_runtime.zip → filesDir/py + ProcessBuilder 调用
│   ├── Res.kt                   资源路径入口
│   └── Models.kt                只剩 Sections（v0 的 B50Api.kt 已于 2026-10-28 删除）
├── service/RenderKeepAlive.kt   出图期间的前台服务（dataSync）：只保活，不干业务
├── render/B50Renderer.kt        Kotlin Canvas 渲染器（**兜底**，Python 起不来时用）
└── ui/{GenerateScreen,HistoryScreen,OAuthScreen,SettingsScreen,Common,Overlay}.kt
   Overlay.kt  = 根级浮层体系：AppOverlay 请求 + 全屏遮罩（拦截点击/点空白关闭）
                 + 浮层卡（miuix Surface + shadowElevation 12dp）

app/src/main/assets/
├── pack.zip                     21 MB   资源（曲绘 1569 + 图标 + 字体 + JSON + kb）
├── py_runtime.zip               29 MB   Python 3.14 + Pillow 13 + 63 MB 解压后
└── pycode.zip                   32 KB   **产物**（真源在 py/，用 tools/pack_pycode.py 生成，别手工改）

app/src/main/jniLibs/arm64-v8a/
├── libpy_home.so                4.6 KB  python3 主程序（改名后放 lib 目录才能 exec）
├── libpython3.14.so             5.9 MB
├── libandroid-support.so / libc++_shared.so

LICENSE / THIRD_PARTY_NOTICES.md   发布用协议（MIT + 第三方声明）
README.md                     对外介绍；内部开发文档见 docs/DEVELOPMENT.md
docs/DEVELOPMENT.md           构建环境 / aapt2 / 编译期坑 / 源码地图（原 README）

tools/
├── fetch_assets.py              素材获取：本地 → 归档 zip → 上游下载（上游证书过期，默认跳过校验）
├── pack_assets.py               423 MB 素材 → pack.zip 21 MB（前置检查 + 产物校验 + 版本号一致性）
├── check_upstream.py            上游同步：代码 4 文件三态比对 + miuix 版本（带基线快照）
├── pack_pycode.py               py/ → pycode.zip（确定性打包；--check 校验 / --check-desktop 与桌面版对账）
├── status.py                    工程自检：文件齐否 / 版本号 / py 一致性 / 已安装包比对（新会话先跑它）
├── ascii_view.py                截图 → ASCII 灰度图（不看图也能判断布局/重叠/留白）
├── shadow_probe.py              像素探针：colors/vprof/hprof/edge/matrix（量底色、边缘剖面、阴影带、脏边）
├── make_icons.py                生成 18 个启动图标 + 校验安全区/对比度/遮罩（--verify 可单独跑）
└── build_python_runtime.sh      Termux 包 → py_runtime.zip + jniLibs（含 patchelf / 裁剪）

py/                             **App 内嵌 Python 的唯一真源**（zip 是产物，不是源）
├── fetch.py / profile.py       与归档基线（桌面版 v1.3）逐字节相同
├── roast.py                    桌面版裁剪版（只留提示词构建；HTTP 归 Kotlin LlmClient）
├── render_worker.py            **App 专有**：ProcessBuilder 入口（提示词模式 / 渲染模式）
├── vendor/mai/{context_builder,render,paths,utils}.py   与归档基线逐字节相同
└── README.md                   来源说明 / 同步流程 / 改了之后要做什么
```

## 5. 构建与安装

```bash
# 一键构建（会同步源码到 /opt/work/b50-android 再编译，产物拷回 outputs/apk/）
sh /sdcard/Tools/LSPilot/scripts/build_android.sh
#   默认构建成功后自动停 Gradle/Kotlin 守护进程（省 1~3 GB 内存，见 §3 第 10 条）
#   连续多次构建想省启动时间：加 --keep-daemon（内存会一直占着，记得手动 reclaim）
#   内存吃紧时随时回收：sh tools/reclaim_mem.sh [--dry-run]

# 安装（/sdcard 不能被 system_server 读，必须先拷到 /data/local/tmp）
cp /sdcard/Tools/LSPilot/outputs/apk/b50-roast-debug.apk /data/local/tmp/b50.apk
pm install -r -t /data/local/tmp/b50.apk
```

环境（已装好）：JDK 21 `/usr/lib/jvm/java-21-openjdk-arm64`、Gradle 9.8.2 `/opt/gradle9/`、
SDK `/opt/android-sdk`（android-37）、arm64 aapt2 `/opt/termux-aapt2/bin/aapt2`。

**版本约束**：compileSdk 37 / AGP 9.4.1 / Gradle 9.x / 不声明 kotlin-android 插件（AGP 9 内置）/ 必须声明 `org.jetbrains.kotlin.plugin.compose`。

## 6. 调试方法

```bash
logcat -d | grep -E "B50Assets|B50Py|B50Norm|B50Sync|B50Quota"   # 全链路日志（含配额扣减）
# 出图产物在 App 私有目录，用进程命名空间读：
P=$(pidof com.b50.roast); ls /proc/$P/root/data/data/com.b50.roast/files/shots/
```

**UI 自动化取证（三个坑，实测出来的）**

1. `screencap` 出来是 **1080×2400**，不是 1440×3200。`input tap/swipe` 必须用 1080×2400 坐标，
   用 1440 系坐标会静默失效（不报错，什么都不发生）。
2. `uiautomator dump` 有副作用：**dump 完界面会滚回顶部**。想验证"滚动后"的界面，
   顺序必须是「先滚动 → 立即截图 → 用 PIL 分析」，不能靠 dump。
3. 判断界面有没有滚动，别肉眼编号：把两张截图裁到内容区、缩成 40×260 灰度做行签名，
   互相关找最佳垂直偏移。偏移 0 = 没动。
4. `input swipe` 的起点**不能落在 EditText 上**（会被文本框吃掉，页面纹丝不动）。
   从卡片右侧留白滑，或者先 dump 一次拿到 `ScrollView` 的 bounds 再挑起点。
5. ~~沙箱里这个包是空壳~~ **已解决（2026-10-28 实测）**：`/data/data/com.b50.roast`
   在**默认 su 命名空间**里确实看不到（被 storage-isolation 模块遮蔽），但切到
   **init 挂载命名空间**就完全可见：

   ```bash
   nsenter --mount=/proc/1/ns/mnt -- /system/bin/sh -c 'ls /data/data/com.b50.roast/files/'
   # 或  su -M -c '...'
   ```

   已封装成 MCP 工具参数，不必再手写 nsenter：
   - `SysRoot.sh(mount_master=true)`
   - `SysRoot.read_file(path=..., mount_master=true)`

   实测可读：`files/shots/*.png`（App 出的图）、`files/py/`（内嵌运行时）、
   `shared_prefs/{b50_app,b50_oauth,b50_quota}.xml`（配额/绑定状态）。
   **验证 App 私有数据不再只能靠 UI。**

## 7. 待办

> **下一轮的 5 件事（用户 2026-10-28 定稿，按此顺序做）。** 每条下面写了「已知事实/缺口」，
> 都是这轮体检时实测的，省得下次重新摸。

1. ✅ **UI 优化 · 阴影问题** —— 2026-10-28 夜场已完成，实测数据见「本轮已完成（2026-10-28 夜场）」。
   根因不是"阴影画错了"，而是**整个 App 一个阴影都没有**（`Card` 无 elevation 参数），
   外加**遮罩被 Scaffold content 区裁掉**（只盖住中间，顶栏底栏全亮）。四处已按 miuix 规范重建层级。
2. ✅ **API 预设删减** —— 2026-10-28 夜场已完成（用户给了最终表格：DeepSeek / Gemini / Claude / OpenAI / Kimi）。
   改前实际是 **10 项**（`custom` + DeepSeek + 硅基流动 + Kimi + 智谱 GLM + 通义百炼 + 火山方舟 + 腾讯混元 + OpenRouter + OpenAI，
   本文档之前写的"6 项"是过期信息）。改后 **5 家 + 自定义**，删掉 6 家聚合/接入点型的。
   新增 Gemini / Claude 都走各家官方 OpenAI 兼容层，端点已实测（见「本轮已完成（2026-10-28 深夜场）」）。
   兜底逻辑：`LlmPresets.isKnown(id)` 为假 → 回落 `CUSTOM`，四个字段值不动，设置页不会崩。
3. ✅ **图标重绘（miuix 风格）** —— 2026-10-28 深夜场已完成，见「本轮已完成（2026-10-28 图标重绘）」。
   18 个文件全部由 `tools/make_icons.py` 重新生成（可复现），深蓝渐变底 + 白色 B50 字标（Torus SemiBold）。
   验收三个量化门槛全过：安全区（最远角 28.8dp ≤ 33dp）、对比度（5.58:1 ≥ 4.5）、圆/方遮罩不切角。
4. ✅ **App 1.5 首个发布版（签名 + 构建已完成，只剩对外文案）** —— 2026-10-28 已完成构建链路：
   `versionCode 4 → 5`、`versionName 1.4 → 1.5`；`signingConfigs` 早就在 `app/build.gradle.kts` 里
   （优先级：环境变量 > `keystore.properties` > debug 回落），本次生成了正式 keystore 并跑通 `assembleRelease`。
   细节见「发布准备」小节。~~原记录如下~~
   **缺口（实测）**：`app/build.gradle.kts` 只有 `buildTypes`，**没有 `signingConfigs`** ——
   release 包目前是未签名/沿用 debug key，发布前必须加签名（新 keystore + `assembleRelease`）。
   另外确认 `PACK_VERSION`(34274) / `PY_VERSION`(7) 是否需要动（素材/py 没变就不动）。
5. ✅ **已公开发布（2026-10-28）** —— 仓库 https://github.com/RONUKUIGO/plugin-b50-analysis-android
   （public，默认分支 `main`，85 个文件，topics: maimai / maimai-dx / android / jetpack-compose / kotlin）
   Release **v1.5**：https://github.com/RONUKUIGO/plugin-b50-analysis-android/releases/tag/v1.5
   附件 `b50-roast-release.apk` 57,139,194 B，GitHub 记录的 digest = sha256 `0a06b1a4…0365e2`（与本地产物一致），公网下载实测 HTTP 200 / Content-Length 57139194。
   推送方式：设备内装 `gh`（2.45.0）走 **OAuth device flow** 登录（账号 RONUKUIGO），全程没有用到 SSH key 或手贴 token。
   **缺口（实测）**：`b50-android/` 里**没有 LICENSE、没有 THIRD_PARTY_NOTICES.md**（这两份只存在于归档的桌面版）。
   发布三件套：
   - **协议**：上游 MIT（`Copyright (c) 2026 HanYaaaaaaa`），我们 vendored 了 `py/vendor/mai/` 的 4 个文件，
     必须带 MIT 声明 + 说明本地改动；本工程自身许可还要定（建议 MIT/AGPL 二选一，问用户）。
   - **介绍**：README 面向外部（现在这份是内部开发文档，含机器路径与调试命令，需要另写一份）。
   - **仓库结构**：`app/`（Kotlin）+ `py/`（内嵌 Python 真源）+ `tools/`（4 个脚本）+ `docs`（HANDOFF 保留或精简）。
   **待确认**：是「上游作者给权限后直接在主仓库开分支」还是「fork 到自己账号发布」——
   这两种在协议落款、README 措辞、issue 归属上都不一样，下次先问清楚。

6. ✅ **状态栏图标应为深色** —— 已完成（`WindowCompat` 两行，见「本轮已完成（打磨专场）」）。
   ~~原记录如下~~
   **实测现状**：`grep` 全工程 → **一处都没设**状态栏/导航栏外观
   （没有 `enableEdgeToEdge` / `isAppearanceLightStatusBars` / `windowLightStatusBar`），
   全靠 `android:theme="@android:style/Theme.Material.Light.NoActionBar"` 继承。
   截图实测状态栏里的图标是 **MIUI 自己的彩色图标**（#1DCD3A / #E91E1E / #F9BB00），
   最右侧那颗只有 L≈120~180 的浅色字形压在 `#F7F7F7` 上 → 看着发虚。
   修法（两行，改 `MainActivity.onCreate`）：
   ```kotlin
   WindowCompat.getInsetsController(window, window.decorView).apply {
       isAppearanceLightStatusBars = true        // 深色图标
       isAppearanceLightNavigationBars = true
   }
   ```
   顺带确认：App 是**画到状态栏下面的**（内容 y=0 起），edge-to-edge 是隐式行为，
   哪天要正式接管 insets，这里就是入口。

7. ✅ **设置入口挪到授权页** —— 已完成：顶栏 `actions` 已删，改成授权页的「应用设置 → 打开设置」整行入口。
   ~~原记录如下~~
   **现状**：`MainActivity.kt` 的 `TopAppBar(actions = { Button(onClick = { showSettings = true }) ... })`；
   `OAuthScreen.kt` 已是「账号/授权」页，加一个 `SectionCard("其他")` 里的入口行即可。
   注意：`showSettings` 的返回栈逻辑（BackHandler 第一层）保持不变。

8. ✅ **页面切换像"开新标签页"（预测性返回视觉）** —— 已完成导航栈 + 转场 + 预测性返回（见「本轮已完成（打磨专场）」）。
   **注意**：系统层"预测性返回手势动画"开关没开时，系统不会自己画预览（`input swipe` 也只能喂到
   progress≈0.009）。真机手指拖是 0→1 连续进度。如需系统级预览，让用户去开发者选项打开。
   ~~原记录如下~~
   用户原话：点击进入一个界面应该像是开了新标签页，这样才能确保预测性返回的视觉效果是好的。
   **现状**：三个 tab 是同一个 Scaffold 里 `when(tab)` 直接换内容，没有返回栈、没有转场动画；
   设置页是 `if (showSettings)` 覆盖 + `BackHandler` 模拟层级 →
   这种写法下 Android 14+ 的预测性返回**画不出"缩回上一页"的预览**（没有对应的动画目标）。
   要做的：引入 navigation-compose（或多返回栈方案）+ `enableOnBackInvokedCallback="true"`
   + 每个 destination 的 `enterTransition/exitTransition`；工作量不小，且会动到现有返回栈逻辑。
   评估：与第 7 节第 4 条（发布）有冲突的风险 —— 改导航 = 回归面变大，建议**发布后**再做。

9. ✅ **顶栏"标题顶部留空太多"** —— 2026-10-28 打磨专场（第二次）已完成。
   根因：miuix `TopAppBar` 的**大标题布局把 `TopAppBarDefaults.CollapsedHeight`（52dp）硬编码**成
   大标题块的上内边距（`ui/Common.kt` 里有源码摘录），对外只暴露 `titlePadding`（横向），纵向改不了。
   实测本机（状态栏 104px / 密度 420）：状态栏底 → 标题第一笔之间空 **159px（60dp）**。
   改法：自己拼 `AppHeader`（`ui/Common.kt`），顶栏总高 **408px → 296px**，标题文字中心 **291 → 175**。
10. ✅ **「写什么」卡片重构 + 图片设置并入** —— 2026-10-28 打磨专场（第二次）已完成。
    · 图片宽度 / 格式从独立卡「出图设置」并入「写什么」卡（少一张卡、少滚一屏）；
    · 语气/人设、角度改成按钮（整行可点），点开**就地展开竖向预设菜单**（`ExpandablePicker`），
      选中打 ✓ 并自动收起；末尾「自定义…」就地展开输入框，保留原来自由填写的自由度；
    · 字数收成行内 **92dp 窄框**，只收数字、4 位上限。

11. ✅ **打磨细节（第 9/10 条的收尾）** —— 2026-10-28 打磨专场（第三次）已完成：
    字数框 52dp→**40dp**；预设菜单**限高 5 行 + 内部滚动 + 展开自动滚到当前选中项**；
    语气首项「不指定（默认）」文案改成「**默认**」。
12. ✅ **设置页删掉「返回」按钮** —— 已完成（返回走系统手势/返回键，`PredictiveBackHandler` 接管）。
13. ✅ **角度预设端到端验证** + **数据过期弹窗点完不关的 bug** —— 已完成（见「第三次」小节实测）。

14. ✅ **开发者模式应能直接在线生图（不弹「数据过期」窗）** + **字数框文字被裁** —— 2026-10-28 第四次，已完成。
    两条都是实测定位：dev 模式漏了分支（`refreshRecords()` 早就有 `bypassQuota`，只是界面没走）；
    字数框是 miuix `TextField` 内部按 56dp 排版、被 `height(40.dp)` 硬压成 9px 的缝。

15. ✅ **出图进度动画收进按钮内 → 最终改成"剧场光效"** —— 已完成。
    过程：按钮下独立进度行 → 按钮内圆环（踩了两个坑：尺寸必须走 `size` 参数 / 不能用 Row 挤文字）→
    **现方案：`ui/Common.kt → FlowLight`**（一条斜向柔光带反复扫过按钮，纯背景层、不占布局）。
    实测见「第五次」小节的表格。
16. ✅ **出图通知体系**（进度常驻 + 完成提醒 + 前台服务保活）配 **界面内完成弹窗** —— 已完成，
    后台实测能跑完：按 HOME 切走 75 秒后正常出图并弹完成通知。

**长期项（不阻塞发布）**：

- 出图端到端约 40 秒（提示词 1.3s + AI 36s + 渲染 4s），瓶颈是推理模型；代码已传 `reasoning.effort=low`。
- user prompt 7641 字（全量 831 首明细）—— **用户明确要求不压缩**，别自作主张改。
- 生成页 `format`（png/jpg/webp）选择器未接通，渲染固定输出 png。
- 资源包冻结 `cn:34274`（第 9 节第 1 条），等上游复活；`fetch_assets.py --probe` 可探测。
- 上游 3 个本地化文件与上游有差（`check_upstream.py` 的 `·` 状态），值得逐个确认有无该搬的上游修复。

## 发布准备（2026-10-28，App 1.5）

### 已完成

| 项 | 值 |
|---|---|
| 版本 | `versionCode 5` / `versionName 1.5`（`app/build.gradle.kts`） |
| Release 包 | `outputs/apk/b50-roast-release.apk` · **57,139,194 B（54.5 MB）** · sha256 `0a06b1a420de9806…` · md5 `7af0eeb3…` |
| 签名 | **APK Signature Scheme v2** ✓（minSdk 26 不需要 v1）；RSA **4096**；证书 `CN=Rinkyu, OU=b50-roast, O=b50-roast, C=CN`；证书 SHA-256 `1275c55751ed07e1…` |
| keystore | `secure/b50-release.jks`（alias `b50`，有效期 10000 天）+ `keystore.properties`，两者都在 `.gitignore` 里 |
| 密码 | 24 位随机串，存在 `secure/.storepass`（同一份也写在 `keystore.properties`） |
| 包内容抽检 | `assets/pack.zip` 22 MB / `assets/py_runtime.zip` 30.7 MB / `assets/pycode.zip` 37.8 KB / `lib/arm64-v8a/libpy_home.so` + `libpython3.14.so` 全在，共 105 个条目 |

复验命令（都在设备上跑得通）：
```bash
cd /storage/emulated/0/Tools/LSPilot/outputs/apk
apksigner verify --print-certs --verbose b50-roast-release.apk
/opt/termux-aapt2/bin/aapt2 dump badging b50-roast-release.apk | head -6
```
构建：`sh /sdcard/Tools/LSPilot/scripts/build_android.sh --release`（实测 4m10s，含 R8）。

### ⚠️ 三条硬提醒

1. **`secure/b50-release.jks` + 密码必须立刻备份到仓库之外**（网盘/U盘/密码管理器）。
   丢了就永远无法给已安装用户推送更新（只能换包名重发）。
2. **现在手机上装的是 debug 签名包**（md5 `38e24a4a…`）。release 签名不同 →
   `pm install -r` 会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，**必须先卸载**，
   而卸载会清掉 `/data/data/com.b50.roast`（水鱼绑定、AI Key、历史出图）。
   要切到 release 包：先导出 `shared_prefs/b50_app.xml` + `files/shots/`，卸载后再装。
3. release 包**内置了曲绘/图标/字体素材**（pack.zip 22 MB）。桌面版是靠"不附带素材、只给导入脚本"规避的，
   Android 版把素材打进了 APK —— 对外分发前需要你确认这个口径（见下方"待你决定"）。

### 已定的三件事（用户 2026-10-28 拍板）

| 问题 | 决定 |
|---|---|
| 本工程许可证 | **MIT**（与桌面版一致）→ 已写 `LICENSE`；上游 MIT 声明一并保留 |
| 发布渠道 | **fork 到自己账号**（不用求上游授权） |
| 手机上的包 | **暂不切 release**，继续用 debug 包调试（卸载会清掉绑定 / Key / 历史图） |

### 对外文案（已写完）

| 文件 | 内容 |
|---|---|
| `LICENSE` | MIT + 说明：本工程是上游 MIT 项目的独立 Android 实现，两段 MIT 声明都要保留 |
| `THIRD_PARTY_NOTICES.md` | 9 节：上游 4 个 vendored 文件与改动 / miuix Apache-2.0 / AndroidX+Kotlin / 内嵌 Python 3.14+PSF+Pillow / 字体 / **舞萌DX 曲绘图标（版权归华立·SEGA，仅随包本地渲染）** / 水鱼接口 / AI 服务 / 桌面版不在本仓库 |
| `README.md` | 全新**对外**版：功能、系统要求、安装、首次使用 4 步、隐私与权限、额度规则、从源码构建（assets 不入库，需跑 4 个脚本）、目录结构、已知限制、许可与致谢、免责声明 |
| `docs/DEVELOPMENT.md` | 原内部 README 平移（构建环境 / aapt2 / 编译期坑 / 源码地图），顶部指向新 README |

### 已完成发布（原「剩下 3 步」记录保留，供换机器时复用）

```bash
cd /storage/emulated/0/Tools/LSPilot/projects/b50-android
git add -A
git commit -m "feat: v1.5 Android 客户端（本地渲染 + AI 锐评）"
git remote add origin git@github.com:<你的账号>/b50-android.git
git push -u origin main
```
然后把 `outputs/apk/b50-roast-release.apk` 传到 Release 页（tag 建议 `v1.5`），正文至少写：
系统要求（Android 8.0+ / arm64）、首次使用 4 步、额度规则。

⚠️ push 之前确认 `git status` 里**没有** `keystore.properties`、`secure/`、`app/src/main/assets/*.zip`、`jniLibs/**/*.so`。

### 本轮已完成（2026-10-28 打磨专场 · 第五次）：按钮内进度 + 通知/保活 + 完成弹窗

用户诉求三句话：**进度动画收进按钮**、**点完就弹通知、完成再弹一次（好让用户去干别的，注意后台限制）**、
**人还在界面里完成时就弹窗提示**。

**① 出图进度表现：最终用「剧场光效」`FlowLight`（替代圆环 spinner）**

演进路线（三段，最后一段是定稿）：

| 阶段 | 做法 | 问题 |
|---|---|---|
| 1 | 按钮下方单独一行「转圈 + 状态」 | 会顶动下面的卡片 |
| 2 | 按钮内圆环（`Row { spinner; text }`） | 文字被顶开 31px；圆环不垂直居中 |
| 3 | **按钮内 `FlowLight`：一条斜向柔光带反复扫过** | ✔ 不占布局、不参与排版 |

阶段 2 的两个坑（**记住，下次别再犯**）：
- miuix `CircularProgressIndicator` 的尺寸**必须走 `size` 参数**，`Modifier.size(16.dp)` 只压画布，
  绘图半径仍按参数默认 30dp 算 → 圆环被裁、看着"不上下居中"；
- `Row { 圆环; Spacer; Text }` 会把文案整体右移（31px）= 侵占文字空间。

**定稿实现**（`ui/Common.kt → FlowLight` + `GenerateScreen` 三层结构）：

```kotlin
// 底色 / 光效 / 透明底按钮 —— 光在文字之下，扫过时不会冲淡文字
Box(
    Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(ButtonDefaults.CornerRadius))
        .background(if (canGo) btnColors.color else btnColors.disabledColor),
) {
    FlowLight(active = state.rendering, modifier = Modifier.matchParentSize())
    Button(
        onClick = { if (state.rendering) return@Button; ... },
        enabled = canGo,
        colors = ButtonDefaults.buttonColors(color = Color.Transparent, disabledColor = Color.Transparent),
    ) { Text(状态文案, maxLines = 1, overflow = Ellipsis) }
}
```

`FlowLight` 要点：`active=false` 时**直接 return**（不建无限动画，静止零开销）；
`keyframes` 做「停 18% → 扫 67% → 收 15%」，比匀速循环更像舞台灯；
渐变为 `透明 → 6% 长尾 → 满亮芯 → 6% 长尾 → 透明`，斜切量 `skew = h*0.9`；自身不裁剪，靠外层 `clip` 收边。
可调参数：`bandWidthRatio`(0.24)、`alpha`(0.95)、`periodMs`(2200)、`color`(White)。

**实测（连拍 4 帧，1080×2400，按钮 y1944..2065）**

| 帧 | 亮点位置 | 峰值亮度 |
|---|---|---|
| f1 | 无（光在画外/停顿段） | — |
| f2 | x≈910 | 250 |
| f3 | x≈580 | 243 |
| f4 | x≈170 | 250 |

- 底部条带 → 顶部条带的峰位横向错开 **44px / 105px 高** → 光带确实是**斜的**（不是竖直块）。
- 底色 #F0F0F0 = 240，光带峰值 253 → 对比 +13~14，柔和不刺眼。
- 文字在光带之上（Box 里 FlowLight 先画、Button 后画）→ 扫过时文字不会被打白。

**② 通知 + 前台服务保活（这是"去干别的"能成立的关键）**

| 部件 | 内容 |
|---|---|
| 渠道 | `b50_render_progress`（IMPORTANCE_LOW，静音、不可划掉、带不确定进度条）/ `b50_render_done`（IMPORTANCE_DEFAULT，会响一声） |
| 进度通知 | id=1001，`ONGOING|ONLY_ALERT_ONCE|NO_CLEAR|FOREGROUND_SERVICE`；文案随阶段更新（整理数据 → AI 写锐评 → 出图） |
| 完成/失败 | id=1002，`AUTO_CANCEL`，点开回 App |
| 保活 | `service/RenderKeepAlive`：**前台服务（dataSync 类型）**，不干业务，只把进程提到前台优先级 |
| 权限 | `POST_NOTIFICATIONS`（Runtime，首次启动申请，实测已授予）+ `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` |

**为什么必须上保活**：实测无前台服务时，用户点完出图切到微信，b50 立刻
`SmartPower: visible->invisible(48883ms) visible->background`，日志两分钟不动 —— MIUI 把后台进程冻住了，
等于"通知到了但图没出"。业务仍留在 Activity 的协程里（Python 两次进程 + LLM 依赖一堆 Compose 状态，
搬进 Service 风险太大），前台服务只负责"别被冻"。

**后台路径实测（按 HOME 切走）**
```
18:44:30  按 HOME（app 后台）
18:45:55  B50Roast 锐评正文 1887 字符
18:45:59  B50Py 出图成功 → shots/b50_1793184355245.png
18:45:59  B50Notify 前台服务已停止
通知栏     id=1002  importance=3(DEFAULT) AUTO_CANCEL
          title=「锐评图已生成」 text=「已保存到「历史」页 · 2545 KB · Python 引擎」
```
日志**连续无断档** → 冻结问题解决。前台服务运行期实测：
`ServiceRecord{…/.service.RenderKeepAlive} isForeground=true foregroundId=1001 types=0x00000001`。

**③ 界面可见时改成弹窗（不重复打扰）**

`AppState.finishNotify()` 一个判断分流：
```kotlin
val produced = lastShot != null && lastShot != shotBefore
if (produced) {
    if (AppForeground.visible) { cancelProgress(); cancelDone(); doneEvent = DoneEvent(renderInfo, renderError) }
    else RoastNotify.done(...)
} else { if (visible) cancelProgress() else RoastNotify.fail(...) }
```
- `AppForeground.visible` 由 `MainActivity.onResume/onPause` 维护（`@Volatile`，不引 lifecycle 依赖）。
- 弹窗是 `AppOverlay.Done`（走根部 `AppOverlayHost`，遮罩能盖住顶栏/底栏）：
  「出图完成 / 已保存到「历史」页 · 2084 KB · Python… / **去历史看看** / 知道了」。
- 实测：全程留在界面 → 完成后弹窗出现，且 `dumpsys notification` 里 b50 的活动通知数 = **0**（不留残留通知）；
  点「去历史看看」正确切到历史页（13 张）。

**已知限制（别当 bug）**
- 用户手动关掉通知权限 / 系统把渠道降级 → 通知不弹，但出图照常；界面内弹窗不受影响。
- Android 15+ 的 `dataSync` 前台服务有 6 小时/天累计上限 —— 一次出图 ~60 秒，用不到。
- MIUI 若把本 App 的自启动/后台完全禁掉，前台服务也可能起不来（`RenderKeepAlive.start` 会吞异常打日志）；
  这种情况下会退回"回到 App 才能跑完"的旧行为，需要用户去系统设置放开。
- 按钮内的转圈会让文案整体右移约 31px（转圈占位），属于常规加载按钮行为，未做特殊处理。

### 本轮已完成（2026-10-28 打磨专场 · 第四次）：开发者模式直接在线出图 + 字数框文字被裁

**① 开发者模式应该直接在线生图，但实际会弹「数据过期」窗**

根因很清楚：`AppState.refreshRecords()` 早就写了 `bypassQuota = devUnlocked`（既不查额度也不扣次数），
但 `GenerateScreen` 的出图按钮**没有这个分支** —— 它只判断 `hasData && dataIsStale()` 就弹窗，
于是 dev 模式也照样问「要刷新吗」。用户看到的是「明明显示不限次数，还要我选」。

改法（`GenerateScreen`，onClick 第一分支）：

```kotlin
if (state.devUnlocked) {
    // 额度不限 → 不弹窗，直接联网拉最新 + 出图
    scope.launch { state.roastAndRender(state.width, refreshFirst = true) }
} else if (hasData && state.dataIsStale()) {
    onOverlay(AppOverlay.StaleData(...))     // 普通用户的路径一字未改
} else { ... }
```

实测（dev_unlocked=true）：

| 证据 | 内容 |
|---|---|
| 无弹窗 | 点「出图 + AI 锐评」后 `ui_nodes` 搜「旧数据」= **0 条**，直接出现「AI 正在写锐评…」 |
| 真的联网了 | `18:29:11 B50Api: 全量记录已缓存：831 条`（点按钮后 1 秒内拉到新数据） |
| 没扣次数 | `18:29:11 B50Quota: 开发者模式，本次刷新不计次数` |
| 界面跟着更新 | 账号卡从「数据 6 小时前」→「数据 **1 分钟前**」 |
| 跑完出图 | `B50Llm code=200`、`锐评正文 1662 字符`、`出图成功 b50_1793183425864.png`（18:30） |

**② 字数框的文字被挤压 / 显示不全**

上一轮为了「高度调低」加了 `Modifier.height(40.dp)`，结果 miuix `TextField` 内部是按 **56dp** 排版的
（label 区 + 输入行），被压到 40dp 后**文字被裁成一条细缝**：
实测「600」的墨迹只剩 `y714..722` 共 **9px**（正常该是 40px 左右），肉眼就是一条黑线。

改法：`ui/Common.kt` 新增 `CompactTextField` —— 用 `BasicTextField` 自己排版，不再受 miuix 内部高度约束：

```kotlin
Box(
    modifier.height(40.dp).clip(RoundedCornerShape(12.dp))
        .background(cs.onSurface.copy(alpha = 0.06f))   // ≈ #F0F0F0，和原填充底一致
        .padding(horizontal = 10.dp),
    contentAlignment = Alignment.Center,
) {
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium,
                              color = cs.onSurface, textAlign = TextAlign.Center),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        cursorBrush = SolidColor(cs.primary),
        modifier = Modifier.fillMaxWidth(),
    )
}
```

实测：框仍是 **92 × 40dp**（x 713..955，y 661..764）；「600」墨迹 **y698..727 = 30 行**、
输入「750」后墨迹 **y688..727 = 40px**（完整不裁）；文字在框内垂直居中（框中心 712.5 / 墨迹中心 712.5）。
输入链路也验了：点框 → 数字键盘 → 输入 750 → `shared_prefs` 立刻 `words=750`（落盘生效），再改回 600。

**规律（记住这条就能少踩）**：miuix 组件的内部尺寸是写死的（`TextField` 56dp、`TopAppBar` 大标题 52dp 顶内边距…），
**`Modifier.height()` 硬压会裁内容**，要改尺寸就必须换成能自己排版的原语（`BasicTextField` / 自拼 `Column`）。

### 本轮已完成（2026-10-28 打磨专场 · 第三次）：五处细节 + 角度预设端到端验证

| # | 改了什么 | 取证（1080×2400 真机） |
|---|---|---|
| a | **字数框高度压到 40dp**（miuix `TextField` 默认 52dp，比整行文字高太多） | 文本框 y **660..765 = 105px = 40dp**（改前 660..797 = 137px = 52dp）；整行随之矮 12dp |
| b | **预设菜单限高 5 行 + 内部滚动 + 展开时自动滚到当前选中项** | 内嵌块 y **918..1443 = 525px = 200dp**（= 5 × 40dp）；行高 105px = 40dp；滑一下能看到尾部「自定义…」；选中「定数趋势」再展开时可视区是 推分建议/配置诊断/**定数趋势(✓)**/与同段对比/自定义…，「综合锐评」自动滚到上方 |
| c | 语气首项文案「不指定（默认）」→「**默认**」 | 行值显示「默认」；prompt 里 `用户指定语气` 出现次数 **0**（空串 → Python 侧整段省略，符合设计） |
| d | **设置页删掉「返回」按钮** | `ui_nodes` 搜「返回」= **0 条**；标题「设置」在 (90,167)；`input keyevent BACK` 仍能退回授权页（走 `PredictiveBackHandler`） |
| e | **修 bug：数据过期弹窗点完不关** —— `onUseOld/onRefresh` 只跑动作不关弹窗，出图那 20~60 秒弹窗一直挡着，"AI 正在写锐评…" 全在它下面看不见。现在两个按钮都是 `{ onDismiss(); action() }` | 点「用旧数据出图」后 `ui_nodes` 立刻搜不到「用旧数据」；同时能看到 **「AI 正在写锐评…」** 两个节点（按钮 + 进度行） |
| f | 弹窗标题文字「成绩数据是6 小时前」（少一个空格）→ **「成绩数据：6 小时前」** | 与页面上「成绩数据：6 小时前（出图不消耗次数）」口径统一 |

**角度预设端到端验证（真的跑了一次出图，不是只看代码）**

把「角度」切到 **定数趋势**，走「用旧数据出图」（不消耗刷新额度）：

| 环节 | 实测 |
|---|---|
| 提示词 | `PROMPT … system=1207 user=7599 words=(510, 690)` —— 字数 600 生效（600±15%） |
| 预设是否进入提示词 | `grep 【分析角度】` → **`【分析角度】定数趋势：重点说各定数档的软硬变化`**（与 `RoastPresets.ANGLES` 里的 value 逐字一致） |
| 语气=默认 | `grep -c 用户指定语气` → **0**（空串整段省略 ✓） |
| AI 返回 | `B50Llm: chat attempt=0 code=200`，`锐评正文 1159 字符` |
| 正文是否真的按角度写 | 正文里出现「十三档均达成 100.7288 / 十三加掉到 100.5654 / 十四档只剩 100.1585」—— 正是「定数趋势」要求的口径 ✓ |
| 出图 | `Python 退出码=0`，`b50_1793182763908.png 1200×2425 png`，2110 KB；页面出现「已生成 2110 KB · Python 引擎」 |

> 顺带确认：**出图/锐评不消耗在线刷新额度**（弹窗里第二个按钮当时是「额度已用完」，用旧数据这条路照样跑通）。

**两个小坑（下次别当成 App 的 bug）**
1. 出图跑着的时候，如果本会话的工具调用把 LSPilot 拉到前台，b50 会 **`visible->invisible/background`**（`SmartPower` 日志可见），
   MIUI 把后台进程冻住 → 日志会"卡住"两分钟，回到前台后继续跑完。**验证长任务时别中途切走**。
2. `uiautomator dump` 会报 `could not get idle state`（前台不是目标 App / 有持续动画时）。改用 `plugin__UiKit__ui_nodes` 更稳。

### 本轮已完成（2026-10-28 打磨专场 · 第二次）：顶栏留白 + 「写什么」卡片重构（第 7 节第 9/10 条）

一次构建带走两项，都是"按用户感受定位到像素"的活。

| # | 改了什么 | 取证（1080×2400 真机） |
|---|---|---|
| 9 | 顶栏换成自己拼的 `AppHeader`（`ui/Common.kt`）：`statusBars` inset + 8dp/10dp 内边距 + 大标题 + 副标题 + 1px 分割线 | 标题文字中心 y **291 → 175**（上移 116px）；顶栏分割线 y **407 → 296**；**整页内容上移 111px**（账号卡首行 490→379、「写什么」591、「画哪些分区」1504→1504-111） |
| 10a | 「写什么」卡重排：字数（92dp 窄框）→ 语气/人设（按钮）→ 角度（按钮）→ 图片宽度 → 格式 | 字数行：「字数」左、窄框右（x 743..952，宽 209px≈80dp 可见区）、「字」单位在 x≈991；框内只有一处数字（去掉浮动 label，否则会渲染成"上面小字 600 / 下面大字 600"） |
| 10b | `ExpandablePicker`：整行按钮 → `AnimatedVisibility` 就地展开竖向菜单（浅灰内嵌块 #F7F7F7，卡内 `clip(RoundedCornerShape(14.dp))`） | 展开中：内嵌块 x **64..1016**（卡内左右各 12dp），y **950..1788**，8 行（7 预设 + 自定义）；点「毒舌锐评」后收起，行值变「毒舌锐评」、`角度` 回到 y=1015 |
| 10c | 删掉独立「出图设置」卡 | `ui_nodes` 顶栏之外只剩：账号状态 / 写什么 / 画哪些分区 / 出图按钮区 |
| 10d | 预设数据源独立成 `data/RoastPresets.kt`（label + value 两段式，改文案不用碰 UI） | 语气 7 条（不指定/毒舌/温柔教练/雌小鬼/猫娘/中二解说/圈内老玩家）、角度 5 条（综合锐评/推分建议/配置诊断/定数趋势/与同段对比） |
| 10e | **顺手修掉一个真 bug：这几个参数以前根本没落盘**。`AppState.savePrefs()` 早就写好了，但全工程 `<b>一次都没调用过</b>`（`saveNow()` 也没有调用点）→ 选完语气/角度/字数，重启就退回默认。现在 `GenerateScreen` 里挂一个 `LaunchedEffect(state.words, tone, angle, width, format, sections) { state.saveNow() }` | 选「毒舌锐评」→ `am force-stop` 冷启动 → 行值仍是「毒舌锐评」；`shared_prefs/b50_app.xml` 里 `tone=毒舌但不人身攻击，专挑成绩结构里的痛点扎`、`words=600 / width=1200 / format=png / angle=综合锐评` 五个键都在 |

**几个设计取舍（下次别推翻，有理由的）**
- **菜单就地展开，不弹窗**：只是从几条预设里挑一个，弹窗会盖住卡片、还要走路 `AppOverlayHost` 的遮罩层级
  （那套是给"危险操作/确认"用的）。代价是展开时下面的卡片被顶下去（8 行 ≈ 320dp），属于可接受。
- **语气/人设的 value 是一整句描述**（如"毒舌但不人身攻击，专挑成绩结构里的痛点扎"），label 才是短词。
  Python 侧是 `【用户指定语气/人设】{tone}` 原样拼进提示词，所以这样写比只塞"毒舌"两字有效。
- **自定义兜底**：`RoastPresets.match()` 返回 null 就当"自定义"，行值用主色显示。
  老版本存下来的自由文本（比如"雌小鬼"）不会丢，展开后自动定位到「自定义…」并带出原文。
- **字数允许清空**：`AppState` 里统一 `words.trim().ifBlank { "600" }`。
  否则空串会让 `roast.parse_words` 回落到默认 `(150, 300)`，和界面"默认 600"的语义打架。
- 两个菜单**互斥**：`openPicker` 状态提到 `GenerateScreen` 顶层（KEY_TONE / KEY_ANGLE）。
- **参数落盘放在界面上而不是 AppState 里**：SavePrefs 需要一个"谁变了"的信号，Compose 的
  `LaunchedEffect(key1..key6)` 正好干这件事，不用给 6 个 `mutableStateOf` 各包一层 setter。

**取证时遇到的一个假 bug（重要，别浪费时间）**
构建后截图里右上出现了一条灰带（x≈754..1030、y≈156..860，右缘还有一条 85px 的暗条）。
一开始怀疑是卡片/阴影画错了，**换到 LSPilot 自己的界面截同一位置，图案逐像素一致** →
是 MIUI 系统层的残留（`dumpsys window` 里 `EdgeBackGestureHandler0` 276x704 + `MiuiBackGestureHookIndicator` 85xfill，
都是 `NAVIGATION_BAR_PANEL` 型的半透明窗口），和本 App 无关。
**下次看到"灰色带/暗条压着内容"，先用同一个坐标截另一个 App 对比，再决定要不要改代码。**

### 本轮已完成（2026-10-28 打磨专场）：6 项体验修补（第 6/7/8 条 + 3 处细节）

一次构建带走全部 6 项，逐项都有真机取证。

| # | 改了什么 | 取证 |
|---|---|---|
| 6 | 状态栏/导航栏图标改深色：`WindowCompat.getInsetsController(...)` 两行 | 状态栏深色像素 33 → **2959**（最暗 L=60 `#3C3C3C`，原来是 L≈157 的浅色/彩色图标压在 `#F7F7F7` 上） |
| 7 | 设置入口：删掉 `TopAppBar.actions` 的小按钮，改成授权页「应用设置 → 打开设置」整行（右侧 `›`，无按钮） | `ui_nodes` 顶栏已无「设置」；授权页出现「应用设置 / 打开设置」，点击能进 |
| 8 | 导航：`showSettings` 改成**叠层页面**（滑入 30% + 阴影 18dp）+ `PredictiveBackHandler` 跟手 + tab 切换 `AnimatedContent` 横向滑入淡入 | 慢速连拍：设置页从右侧滑入（5 帧渐变，左缘出现 `#DDDDDD` 阴影）；tab 切换位移 +320 → −108 → −28px 收敛；`input swipe` 触发返回时 progress 已送达 App（`0.0094`）并正确关闭页面 |
| 附1 | 「自定义」chip 钉在设置页 chip 行最右（不再被滚出屏幕） | `ui_nodes`：自定义 (934,888) 可见；provider=deepseek 时 DeepSeek chip 高亮正常 |
| 附2 | 分区 chip 排版：**短标签靠前 + 每行等宽铺满** | 改前 4/3 行、行尾大片空洞；改后 4+3 两行**等宽撑满**（x 间距 243px / 325px），`最高RA` 也不挤 |
| 附3 | 出图格式 png/jpg/webp **真正接通** | 直接用 App 自带 Python 运行时跑同一条命令行：png 1.77MB / jpg 556KB / webp 389KB，magic 分别 `89504E47` / `FFD8FFE0 JFIF` / `RIFF..WEBP`，全部 1200×2104 |

**py 改动**：`render_worker.py` 按扩展名决定编码（JPG 先 `convert("RGB")` 去 alpha，质量 92 渐进式；
WebP quality 92 method 4）→ `PY_VERSION` **6 → 7** + `tools/pack_pycode.py` 重打 zip。
Kotlin 侧只改一行：`AppState.renderWithPython()` 的出图文件名扩展名跟着 `format` 走。
`ShotStore` 本来就按 `b50_` 前缀扫盘、`mimeOf()` 也已覆盖 jpg/webp，所以存相册/分享/历史页全都自动可用。

**为什么原来"预测性返回没效果"**（根因，别忘）
manifest 里 `enableOnBackInvokedCallback="true"` 一直都开着，但设置页是 `if (showSettings) {...} else {...}`
直接换内容 —— 系统找不到"可以缩回去的那一页"，手势期间没有任何可动画的东西。
现在改成：主界面常在底层，设置页是叠在上面的 `graphicsLayer` 图层，
`PredictiveBackHandler` 把 `progress` 喂给 `translationX / scale / alpha`，才是系统期望的形态。

**已知限制（下次别当成 bug）**
- 本机开发者选项里的「预测性返回手势动画」是关的，系统不画自己的预览；
  且 `input swipe` 是注入事件，只能把 progress 推到 ≈0.009（真机手指是 0→1）。
  想要系统级预览效果，让用户在开发者选项里打开这个开关（我为了取证临时写过 `enable_back_animation=1`，已还原）。
- 取动画中间帧要临时把 `animator_duration_scale` 调成 10（Compose 的 `tween` 会跟随它），**测完记得改回 1**。

### 本轮已完成（2026-10-28 图标重绘）：启动图标（第 7 节第 3 条）

**做法**：不手搓 PNG，写 `tools/make_icons.py` 一次性生成 18 个文件，并把验收标准变成脚本里的断言。
以后改配色/字标只要改脚本顶部 4 个常量再跑一遍，不会出现「某一档密度忘了换」。

| 项 | 旧图标（实测） | 新图标 |
|---|---|---|
| 底色 | 深紫渐变 `#3B2866`（与浅色 miuix 主题不搭） | 深蓝渐变 `#1A46B4 → #3066EE`（与主色 `#4A80F7` 同族、压深一档保对比度） |
| 前景 | `B50` 字标 + **一行 6px 的极小副标题**（48px 下必然糊成一团） | 只有 `B50`（Torus SemiBold，与 App 内渲染的成绩图同源），无小字 |
| 形状 | legacy 图标只有方形/圆形两种遮罩 | legacy = **squircle**（`|x|^4.5+|y|^4.5≤1`）+ 圆形；自适应前景独立成层 |
| 中心 | 字标略偏上（目测） | 五档密度实测中心偏移 **±1px 内** |

**验收（脚本 `--verify` 输出，可复跑）**
```
== 自适应前景：安全区（中心 66dp 圆）==
  ✓ xxxhdpi 字标 53.0x21.2dp，最远角 28.8dp ≤ 33.0dp      ← 五档全过
== 字标 vs 底色对比度（取字标覆盖范围内最亮的背景）==
  ✓ 白色字标压在 #2A5EDF 上：5.58:1（门槛 4.5）           ← WCAG AA
== 遮罩模拟：圆形 / squircle 下是否被切 ==
  ✓ 五档均安全（字标最远角 < 可见区内切圆半径）
== 文件齐全 ==
  ✓ 18 个 res 图标文件齐全（+ app/src/ic_launcher-web.png）
```
> 第一版配色 `#2F63E0→#4A84FF` 被这个校验**拦下来了**（实测 4.29:1 < 4.5），
> 说明把验收标准写成断言是有用的，别心疼那点蓝。

**工具与用法**
```bash
python3 tools/make_icons.py                              # 生成 18 个文件 + 自检
python3 tools/make_icons.py --verify                     # 只校验现有文件（当门禁用）
python3 tools/make_icons.py --preview /tmp/icon-preview  # 额外导出圆/方遮罩模拟图
```
改风格只需动脚本顶部常量：`C_DARK` / `C_LIGHT`（渐变两端）、`GLYPH_W_RATIO`（字标占可见区宽度，0.78）、
`TEXT`（字标文本）、`SAFE_DP`（安全区，66 别动，这是 Android 规范）。

### 本轮已完成（2026-10-28 深夜场）：AI 供应商表按用户定稿重建 + 探针脚本收编（第 7 节第 2 条）

**表**（用户 2026-10-28 定稿）：DeepSeek / Gemini / Claude / OpenAI / Kimi，外加 `custom` 兜底。

| | 改前（10 项） | 改后（6 项 = 5 家 + 自定义） |
|---|---|---|
| 保留 | deepseek / moonshot(Kimi) / openai | 同左（id 一字未改，用户已存的 Key 不受影响） |
| 新增 | — | `gemini` / `claude`，都走**官方 OpenAI 兼容层** |
| 删除 | siliconflow / zhipu / dashscope / volces / hunyuan / openrouter | — |

**端点实测**（无 Key 探针，只看"路由对不对"；`tools/` 不需要 Key 也能复验）：

| 供应商 | baseUrl | GET /models | POST /chat/completions |
|---|---|---|---|
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | `400 {"message":"Please pass a valid API key"}` ✓ | `400` 同上 ✓ |
| Claude | `https://api.anthropic.com/v1` | `401 authentication_error` ✓ | `401 {"code":"authentication_error","message":"Invalid Anthropic API Key"}` ✓ |

两家都不需要额外 header（Claude 的兼容层不强制 `anthropic-version`），所以 `extraHeaders` 都是空。
`/models` 返回都是 `{"data":[{"id":…}]}`，现有 `LlmClient.fetchModels` 的解析直接吃得住 → 「获取」按钮可用。

⚠️ **内置模型名只作兜底**（`gemini-2.5-flash` / `claude-sonnet-4-5` 这类），没有真 Key 无法核对，
所以给这两家都写了 note 让用户点「获取」拉权威列表。**下次拿到真 Key 时优先验这两家。**

**"删了预设不炸"的保证（用户明确要求）**
- `LlmPresets.byId(id)` 找不到 → 返回 `CUSTOM`（原有行为）。
- 新增 `LlmPresets.isKnown(id)`，`AppState.llmProvider` 初始化时用它兜一层：
  旧版本存下的 `llm_provider=siliconflow` 之类会回落 `custom`，**否则设置页会出现"一个 chip 都没选中"**。
- `llm_url / llm_path / llm_key / llm_model` 是四个独立 prefs 字段，与供应商 id 解耦 →
  删供应商不会抹掉用户填的值；`llm_key_<老id>` 保留在 prefs 里（不主动清，万一以后加回来）。

**探针脚本收编**：`tools/ascii_view.py`、`tools/shadow_probe.py` 已进工程，
并登记进 `status.py` 的必备文件清单 + 「文档与工具」段（缺一个就红）。

### 本轮已完成（2026-10-28 夜场）：UI 层级 / 阴影（第 7 节第 1 条）

**做法**：不看"感觉"，逐屏截图 + PIL 量像素（`shadow_probe.py`，见下）。先立"改前"基线，改完用同一套探针复测。

改前实测（1080×2400 截图，采样坐标即真机像素）：

| 位置 | 改前 | 改后 |
|---|---|---|
| 卡片 vs 页面 | `#FFFFFF` ↔ `#F7F7F7` 之间 **1px 硬切**，全屏无任何渐变带（=零阴影） | 保持平面卡（miuix 规范如此），改为靠页面底色区分 |
| 设置页底色 | `#FAFAFA`（窗口底色，与主界面 `#F7F7F7` 不一致，进出整页闪色） | `#F7F7F7`，与主界面统一 |
| 设置页标题 | "设置" y=87，**正压在状态栏图标（y≈33~108）上** | y=191，完全让开 |
| 弹窗遮罩 | 从 y=406 才开始（Scaffold content 区），顶栏 0~406 与底栏**全亮** | 全屏 `#7B7B7B`（=247×0.5），含状态栏/顶栏/底栏 |
| 弹窗卡片 | 与遮罩 **1px 硬切**，无 elevation | miuix `Surface(shadowElevation=12dp)`：卡边外 36px 平滑渐变（103→121） |
| 弹窗点击穿透 | `Box` 只画背景不拦事件，点空白会**穿透到下层** | 遮罩自身吃掉点击；点空白关闭、点卡片不关闭 |
| `TopAppBar` 下沿 | 与内容同为 `#F7F7F7`，无分界 | y=406~408 一条 `#E4E4E4`（1dp）分割线 |
| 历史页缩略图 | 浅底图（`#D1FDE7`）直接贴 `#F7F7F7`，边缘糊 | 1dp 发丝描边：边缘像素 `#C1E9D5`（≈209×0.92） |

**改动文件**：`ui/Overlay.kt`（新增）、`MainActivity.kt`、`GenerateScreen.kt`、`HistoryScreen.kt`、`Common.kt`。

**验证方法（下次照做）**
```bash
# 造"数据过期"场景（否则 FRESH_MS=6h 内不弹）：
su -c 'touch -d "@$(awk -v s="$(date +%s)" "BEGIN{printf "%d", s-32400}")"         /data/data/com.b50.roast/files/cache/records.json'   # 把缓存 mtime 改成 9 小时前
# 然后进「生成」页点「出图 + AI 锐评」→ 弹层出现
```
像素探针脚本（不进工程，放工作区）：`/data/local/tmp/ws/projects/b50/scripts/`
- `ascii_view.py <png> [宽] [x0 y0 x1 y1]` —— 截图转 ASCII 灰度图，判断布局
- `shadow_probe.py colors|vprof|hprof|edge|matrix <png> ...` —— 量颜色、量边缘剖面、找阴影带

**已收编**：两个探针脚本现在就是工程工具，路径 `tools/ascii_view.py` / `tools/shadow_probe.py`
（`status.py` 的必备文件清单和「文档与工具」段都已登记，缺一个就红）。用法：
```bash
python3 tools/ascii_view.py  <shot.png> [字符宽] [x0 y0 x1 y1]     # 先找"哪里不对"
python3 tools/shadow_probe.py edge <shot.png> [x]                 # 再看边缘是渐变还是硬切
```

### 本轮已完成（2026-10-28 傍晚场）：配额语义重构

- 用户反馈：原来的「每日 5 次」直接限制了**出图**次数，但限制的本意是保护水鱼 client_id 的调用量。
- 改成「每日 3 次在线刷新」，出图永久免费。详见第 3.5 节。
- `DailyQuota`：`limit` 5 → 3，`blockReason()` 文案改成「今日 N 次在线刷新已用完」。
- `MaimaiApi`：`TTL_MS(10min)` → `FRESH_MS(6h)`（只用于界面提示）；
  `fetchRecords` 加 `allowNetwork` 参数，非 force 时只要有缓存就用、不看新旧；新增 `hasCache()`。
- `AppState`：`syncAccount` 降为 private；对外暴露两个语义清晰的入口
  `loadFromCache()`（绝不联网）和 `refreshRecords()`（唯一扣费点）。
  删掉 `renderWithQuota`；`roastAndRender` 增加 `refreshFirst` 参数、不再扣任何额度。
  新增 `hasLocalData() / dataAgeMs() / dataAgeText() / dataIsStale() / refreshError`。
- `GenerateScreen`：按钮不再受额度限制；新增「数据是 X 小时前，要刷新吗」确认层
  （自制覆盖层，两个按钮：用旧数据出图 / 先刷新再出图）。
- `OAuthScreen`：「刷新数据」改走 `refreshRecords()`，并显示额度与结果。
- `MainActivity`：冷启动改成 `loadFromCache()`（原来会因为缓存过期自动联网）。
- **点空白收起输入法**（Compose 没这内置行为）：`MainActivity.setContent` 里给根 `Box` 挂了
  `pointerInput { detectTapGestures { focusManager.clearFocus() } }`，一次覆盖所有页面
  （三个 tab + 设置页）。子组件消费掉的点击不会传上来，滑动也不算 tap，所以不影响原有交互。
  唯一例外是 `TopAppBar` 区域的空白：它自己会消费点击，点那里不会收键盘。
- 实测证据：
  - 冷启动日志 `B50Sync: syncAccount force=false allowNetwork=false`，无网络请求。
  - 用旧数据出图全程**零 B50Quota 日志**，额度 1/3 → 1/3 不变，图正常出。
  - 点「刷新数据」→ `B50Quota: 已消耗 1 次在线刷新，剩余 0/3`。
  - 额度 0/3 时点刷新 → `B50Quota: 刷新被拦：今日 3 次在线刷新已用完`。
  - **额度用完照样能出图**：0/3 状态下点出图，数据新鲜(1 分钟前)不弹层、直接出 1200×2376，无扣费日志。

### 本轮已完成（2026-10-28 下午场）

- **预测性返回（Android 13+ OnBackInvokedCallback）**
  - Manifest 开 `android:enableOnBackInvokedCallback="true"`。
  - 三层返回栈，全部用 `androidx.activity.compose.BackHandler`：
    1. 历史页大图预览 → 关预览
    2. 设置页 → 关设置（`MainActivity` 里 `showSettings` 分支内注册）
    3. 非「生成」tab → 回「生成」tab
    4. 在「生成」tab 再按 → 才真正退出到桌面
  - 实测：四层逐层退，App 进程一直活着（pid 不变），最后才回到 `com.miui.home`。
- **QQ 号移到「授权」页 + 持久化**
  - 生成页的「查谁」输入框删除，改成账号卡片里一行只读提示
    (`出图 QQ：xxx` / 未填时红字引导去授权页)。
  - `OAuthScreen` 顶部新增「出图用的 QQ 号」卡片；`AppState.updateQq()` 每敲一个字符就
    `prefs.apply()` 落盘，顺手过滤非数字 + 限长 12 位。
  - **踩到的坑**：方法一开始叫 `setQq`，和 `var qq` 自动生成的 setter 撞 JVM 签名
    （Platform declaration clash）→ 改名 `updateQq`，并给 `qq` 加 `private set`。
  - 实测：输入 1487481266 → 界面显示「已保存」→ 冷启动后生成页仍显示「出图 QQ：1487481266」。
- **彻底删掉 8765 后端链路**
  - 删 `data/B50Api.kt`（210 行）；`data/Models.kt` 从 106 行砍到 21 行，只留 `Sections`。
  - `AppState` 848 → 621 行：删掉 `api / baseUrl / ping / connected / connectionError / serviceBusy /
    presets / history / oauth / generating / progressText / result / resultBitmap / errorText`、
    `mode / repeat / presetName`、以及 `connect / refreshAll / loadDefaultsIfBlank / updateBaseUrl /
    runSu / startService / stopService / serviceStatus / generate / refreshOAuth / startOAuth /
    logoutOAuth / refreshHistory / loadHistoryImage / applyPreset`。
  - `GenerateScreen` 336 → 228 行：删掉「查谁」「套预设」「生成版本数」「错误卡片」「结果卡片」。
  - `MainActivity` 删掉首启那个 `refreshAll()` + `loadDefaultsIfBlank()`（原本每次冷启动
    都要等一次 8765 连接超时才继续）。
  - 实测：冷启动日志里已经没有 8765 相关输出，`B50Sync → B50Norm → 同步完成` 一串就走完了。

### 本轮已完成（2026-10-28 上半场），不用重做

- **每日 5 次配额接 UI**
  - `DailyQuota` 新增 `blockReason()`（空串=可出图）和 `untilResetText()`（"13 小时 54 分后重置"）。
  - `AppState` 新增 Compose 镜像：`quotaLeft` / `quotaBlock` / `refreshQuota()` / `quotaText()`，
    以及带门禁的出图入口 `renderWithQuota(analysis, width, consume=true)` —— **成功出图才扣，失败不扣**。
  - `GenerateScreen`：按钮 `enabled = bound && !rendering && !blocked`，文案带剩余次数；
    下方一行摘要显示倒计时；`LaunchedEffect` 每 30 秒刷一次配额，跨日 0 点自动解锁。
  - 首启自检调 `renderWithQuota(..., consume = false)`，不消耗用户额度。
  - 实测：`B50Quota: 已消耗 1 次，剩余 4/5`，UI 同步条 `今日剩余 4/5 次 · 13 小时 54 分后重置`。
- **历史页三列缩略图**
  - 不再打后端，`HistoryScreen` 直接扫 `filesDir/shots/`（`ShotStore.list()`，按时间倒序）。
  - `LazyVerticalGrid(GridCells.Fixed(3))` + `aspectRatio(1200/2104)` + `ContentScale.Crop`，
    缩略图 420px 目标宽、`inSampleSize` 二段解码、`RGB_565`、`LruCache` 上限取堆 1/8。
  - 每格底部压半透明时间条；点格子弹预览层。
  - 实测三列 bounds：x=[26,355] / [376,705] / [726,1054]，行 y=[580,1157] / [1183,1760]。
- **保存相册 / 分享 / 删除**
  - `ShotStore.saveToGallery()`：API 29+ 走 `MediaStore` + `RELATIVE_PATH=Pictures/B50` + `IS_PENDING`
    原子写（零权限）；失败会 `delete(uri)` 回滚，不留半张坏图。API < 29 退化成直接写目录。
  - `ShotStore.shareIntent()`：`FileProvider`（authority `${applicationId}.fileprovider`）
    + `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION` + `createChooser`。
  - 删除是二次确认（点一次变「确认删除」，再点才真删），无 Dialog 依赖。
  - **预览层操作区放在图片上方** —— 图比可视区高，按钮压底部会被挤出屏幕（踩过一次）。
  - 实测：`Pictures/B50/b50_1793153246026.png` 1744437 B 落盘；chooser 正常弹出；删除后列表 6→5 张。
- **顺手修的浪费**：原先每次冷启动都会自检渲染一张（`lastShot == null` 每进程都成立），
  现在加了 `!state.hasAnyShot()`，只有历史目录为空才自检。实测第二次冷启动不再出图。
- **API 界面改造（供应商 + 联网拉模型 + 测活）**
  - 设置页拆成两个区块：「AI 供应商」一排横滑 chip，点一下灌入 baseUrl / path / 内置模型；
    「接口参数」四个字段（密钥 / 基础地址 / 路径 / 模型）都可手改。
  - `LlmClient.fetchModels()`：GET `{baseUrl}/models`，兼容 `{"data":[{"id":…}]}`、
    `{"data":["…"]}`、裸数组三种格式；失败时把服务端 `error.message` 抠出来给用户看。
  - `LlmClient.testChat()`：POST 最小 chat（`max_tokens=8`），验证 Key / 模型 / 路径。
  - **每个供应商独立记 Key**（prefs `llm_key_<providerId>`），来回切不会互相覆盖。
  - 模型 chip 只显示 `id.substringAfterLast('/')`，点选后填完整 id —— 否则
    `deepseek-ai/DeepSeek-V3` 这种一排就把屏幕撑爆。
  - 模型列表用 `LazyRow`：有的家 `/models` 返回两三百个，`Row + horizontalScroll` 会卡。
  - 实测（真 Key）：`拉到 2 个模型`（deepseek-flash / deepseek-v4-pro）、
    `连接正常 · 503 ms · 模型回：Pong!`；切供应商→字段全变→切回→Key 自动恢复；
    重启后配置完整持久化。
  - **踩到的 bug**：`llmProvider` 原本声明在 `llmUrl` 之前，做老数据迁移时直接读
    `prefs["llm_url"]`——老用户从没存过这个键，于是永远落回「自定义」。
    改成声明在 `llmUrl` 之后、用已带默认值的 `llmUrl` 去反查预设。
    **教训：Kotlin 属性初始化有顺序依赖时，别在 init 链里跨字段读 prefs。**
- **LLM 锐评全链路（2026-10-28 下半场）**
  - `pycode/roast.py`：从桌面版复制前 236 行（`SYSTEM_PROMPT_TEMPLATE` / `parse_words` /
    `build_system_prompt` / `build_user_prompt`），砍掉 `_post / generate / _adjust_length`。
  - `render_worker.py` 新增锐评模式：环境变量 `B50_PROMPT_OUT` 有值时只吐提示词就退出；
    另支持 `B50_TONE / B50_ANGLE / B50_WORDS`。
  - `PythonRunner.makePrompt()` 新增；`PY_VERSION` 4 → **5**（pycode 变了必须触发重新解压）。
  - `LlmClient.chat()` 新增，带三轮容错：不认 `response_format` → 去掉重试；
    不认 `reasoning` → 去掉重试；`content` 为空（推理吃光 token）→ max_tokens 翻倍重试。
  - `AppState.roastAndRender()`：提示词 → AI → 渲染，全链路成功才扣配额；
    **AI 失败会退回纯出图且不扣次数**（用户拿到的图没正文，不该计费）。
  - `GenerateScreen` 按钮改「出图 + AI 锐评」，进度实时显示「整理数据 → AI 正在写 → 出图」。
  - 实测：提示词 `system=1207 user=7641`、AI 返回 1006 字符、出图 **1200×2277**（无锐评时是 1200×2104，
    高度差就是正文区）、`B50Quota: 已消耗 1 次，剩余 3/5`、端到端 41 秒。
  - **踩到的 bug**：`roastAndRender` 是 suspend 但没包 `withContext(IO)`，
    `LlmClient.chat` 在主线程直接 `NetworkOnMainThreadException`。
    这个异常恰好验证了 fallback 是对的：退回纯出图、配额没扣、界面给了原因。
    **教训：suspend 不等于切线程，`scope.launch {}` 默认还是 Main。**

## 8. 素材 / 运行时 / 依赖重建

### 8.1 素材包（pack.zip）—— 上游 + 版本代号

**上游（唯一来源）**：`https://dw.moant.cn:34274/assets.zip`（约 384 MB，解压 423 MB）
　　　⚠️ **该站证书已过期，但可正常下载** → `tools/fetch_assets.py` 默认跳过证书校验（要严格加 `--strict-tls`）。
　　　⚠️ **URL 里的 `34274` 就是版本号 = 机台内部版本代号（cn 服）**，上游每次更新素材就换一个代号（端口=版本号）。
　　　　App 里显示的就是它（设置页「资源包 cn:34274」、首启界面「本地资源包 cn:34274」）。

**链接核对（2026-10-28，直接查上游 git）**：地址本身**没错**，一直是 `dw.moant.cn:34274/assets.zip`。

| 来源 | 写法 | 说明 |
|---|---|---|
| main 分支 README | `https://dw.moant.cn:34274/assets.zip` | 2026-06-01 commit `00cd39bb`「更新资源下载链接」只把 `http` 改成 `https`，**地址没变** |
| dev 分支 README | `http://dw.moant.cn:34274/assets.zip` | 没跟上这次修改，还是明文 http |
| 上游代码（15 个文件） | 没有这个 URL 常量 | 链接只写在 README 里，不是从代码读的 |

所以 `tools/fetch_assets.py` 现在**两种协议都会试**（https 失败自动换 http）。
另外提醒：**端口 = 版本代号**，机台版本升级后上游会换端口，旧链接就指向不存在的版本 ——
那时要从上游 README 拿新地址，用 `--url` 指定。

```bash
python3 tools/fetch_assets.py            # 找素材：工程 assets/ → B50_ASSETS → 归档快照 → 归档 zip → 下载
python3 tools/fetch_assets.py --check    # 只检查不下载（新会话自检用）
python3 tools/fetch_assets.py --from-zip # 从归档素材包原件重建素材目录（离线兜底）
python3 tools/fetch_assets.py --no-archive  # 强制走网络（拿最新素材）
```

素材的**四条退路**（前三条都在本机，网络只排第四）：

| 优先级 | 位置 | 说明 |
|---|---|---|
| 1 | `<工程>/assets/` 或 `B50_ASSETS` | 你自己的工作副本 |
| 2 | `/sdcard/Tools/LSPilot/archive/b50-desktop-v1.3/assets` | 桌面版快照（已解压，1569 张曲绘） |
| 3 | `/sdcard/Tools/LSPilot/archive/b50-assets-34274.zip` | **上游素材包原件**（402 MB，2026-10-26 下载，md5 `a4ad030b…`） |
| 4 | 上游 URL | 下载 → 校验 → 解压，带断点续传 |

### 8.2 打包与校验

```bash
python3 tools/pack_assets.py <素材目录> app/src/main/assets [--version 34274]
```

打包脚本现在有**三道闸**（2026-10-28 加的，之前是裸的）：

1. **前置检查**：Pillow（含 WebP 支持）、`pyftsubset`、素材目录结构、磁盘空间 —— 缺一样立刻失败并给出安装命令。
   > 以前缺 `pyftsubset` 会**静默**产出一个没有字体的 pack.zip（App 里中文变方块），实测确认过。
2. **产物校验**：`ui/fonts/*.otf`（中文子集 ≥1 MB）、`ui/icons/bj.png`、`ui/default_cover.png`、三个 JSON、`cover/` ≥1400 张、包体 ≥10 MB。
3. **版本号一致性**：`--version`（默认 34274）必须等于 `ResourceInstaller.PACK_VERSION`，否则报错退出。
   > 不一致 = App 升级后不会重新解压，用户看到的还是旧素材。

**版本号规则**：`PACK_VERSION` 不再是自增数字，而是**素材版本代号**（机台 cn:NNNNN）。
素材更新 → 代号变 → `PACK_VERSION` 跟着改成同一个值。

### 8.3 Python 运行时（几乎不用重做）

```bash
sh tools/build_python_runtime.sh .
#   → 记得 PythonRunner.PY_VERSION + 1
```

### 8.4 Python 代码（py/ → pycode.zip，唯一入口）

```bash
python3 tools/pack_pycode.py --check-desktop   # 不带参数 = 与归档基线对账
python3 tools/pack_pycode.py                   # 重打 pycode.zip（内容没变会拒绝写盘）
#   → 记得 PythonRunner.PY_VERSION + 1（否则 App 不会重新解压，跑的还是旧代码）
```

### 8.5 上游同步（两个长期依赖）

| 上游 | 影响什么 | 怎么同步 |
|---|---|---|
| [nonebot-plugin-b50-analysis](https://github.com/HanYaaaaaaa/nonebot-plugin-b50-analysis) | `py/vendor/mai/{context_builder,paths,render,utils}.py`（vendored，不 pip 装） | `python3 tools/check_upstream.py`（带 `--fetch` 拉到 `/tmp/b50-upstream` 人工 diff） |
| [miuix](https://github.com/compose-miuix-ui/miuix) | `app/build.gradle.kts` 的 `miuix-ui` 版本（**今后 App 的默认 UI 库**） | 同上，它同时查 GitHub release 与 Maven Central |

`tools/upstream-baseline.json` 记录了「接入时的上游 commit + 各文件 sha256 + 当时的本地 sha256」，
所以 `check_upstream.py` 能区分三种情况：

- **上游更新了**（`↑` 标记）→ 需要人工看 diff，只搬真正修好的 bug
- **上游未变、我们本地改过**（`·` 标记）→ 正常，说明是本地化
- 完全一致（`✓`）→ 什么都不用做

同步完 / 首次接入后跑一次 `python3 tools/check_upstream.py --save-baseline` 更新基线。

**py/ 与 pycode.zip 的关系（2026-10-28 起）**：`py/` 是唯一真源，`pycode.zip` 只是产物。
`python3 tools/pack_pycode.py --check` 用来验证两者内容一致（比 CRC32+大小，退出码 0/1，可当门禁）。
打包是确定性的（固定顺序 + 时间戳 1981-01-01 + 固定权限），同一份 `py/` 反复打出来字节完全一致。

**桌面版已归档**：只读快照在 `/sdcard/Tools/LSPilot/archive/b50-desktop-v1.3`（含 `assets/` 1656 个素材文件），
密钥/个人数据（`.env`、OAuth 令牌、游玩记录、历史输出）在同级 `b50-desktop-v1.3-private/`（700）。
原运行副本 `/home/work/b50` 与开发副本 `projects/b50` 都已移除，**不要再往那两个路径写东西**。

## 9. 已知风险

- **资源包冻结在 `cn:34274`，已比机台版本低一点（2026-10-28 定案：暂不处理，等上游）**
  - 上游 `dw.moant.cn:34274` 站点**完全不可达**：https/http 均 `Connection reset by peer`，连主站都不通
    （域名解析到 `120.199.9.131` / `yd.sjcmc.cn`）。归档里那份 `archive/b50-assets-34274.zip`
    （402 MB，曲绘 1564 / 字体 2 / JSON 3）是目前唯一的离线原件。
  - **不受影响**：出图渲染、AI 锐评、版本校准（走水鱼公开 B50 反推，**不依赖素材包**）、
    已收录 1404 首曲目的曲绘/元数据。实测日志仍是 `自算 15274（接口 15274）`，账对得上。
  - **会退化**：机台**新增**曲目缺曲绘（回落 `ui/default_cover.png`，不会崩）+ 缺元数据
    （`music_data.json` 查不到 → 图上可能只显示 id 或缺 ds）。
  - **探测上游是否复活**：`python3 tools/fetch_assets.py --probe`（只发小请求，不下载）
  - **上游更新后恢复的 4 步**：
    1. `python3 tools/fetch_assets.py --no-archive`（下载 → 校验 → 解压）
    2. 看新端口号 = **新版本代号**（如 `:35001` → cn:35001）
    3. `python3 tools/pack_assets.py <素材目录> app/src/main/assets --version <新代号>`
       —— 它会校验 `PACK_VERSION` 是否同步，不一致直接报错
    4. `sh /sdcard/Tools/LSPilot/scripts/build_android.sh --install`

- **`/data/local/tmp` 堆着约 790 MB 历史 APK**（`b50-test/full`、`b50-v2`…`b50-v18`、`b50.apk`），
  **都不是当前版本**。判断"当前版本"只看 `outputs/apk/b50-roast-debug.apk` 的 md5 是否等于已安装包
  （`python3 tools/status.py` 第 5 节会自动帮你比）。别拿 tmp 里的包去 `pm install` 覆盖现网。
- **`py/` 与归档基线的比对要人盯着**：基线已冻结（桌面版不再更新），所以 `--check-desktop`
  现在只在你**自己改动了 py/ 里那些「应与基线一致」的文件**时才会报红 —— 那不是漂移，是你在改算法。
  真要改，改完就更新基线快照或明确记录「已接受偏离」，别把检查当噪声长期忽略。
- **APK 58 MB**：py_runtime.zip 占 29 MB。若要用 gzip 压缩到 20 MB，需要自己写可靠的 tar 解包（Java 没有内置 tar，我写的那个有 bug，已弃用改 zip）。
- **首次启动约 2.5 秒**（资源 1.1s + Python 1.2s），有进度界面。
- **Python 引擎起不来时**：`renderWithPython` 返回 null，可回退 `renderLocal`（Kotlin Canvas 版，效果较差但能出图）。回退逻辑**还没自动接**，目前需手动改。
- 桌面版 Python 侧仍用 `data/oauth_token.json` 里那份 token；**同一个 refresh_token 不能在 App 和桌面版同时用**（会互相踢），已实测。
