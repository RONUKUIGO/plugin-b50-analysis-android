# B50 锐评机 · Android 客户端（Compose + Miuix）

> **⚠️ 2026-10-28 起架构变了，这份文档也换了定位，请先读这一段。**
>
> 本文件原来是 v0 架构说明：App 通过 HTTP 调 `webui.py` 的 `/api/…`（打本机 8765 后端）。
> **那条链路已被彻底删除**（删除记录见 `HANDOFF.md` 第 7 节）。现在除 LLM 请求外全部在本机完成：
> 内嵌 **Python 3.14 + Pillow 13** 负责「规整数据 → 建 7 个分析分区 → 渲染出图」，
> Kotlin 负责 UI / 水鱼 OAuth / 配额 / LLM 的 HTTP 发送。
>
> - **现状 / 架构图 / 坑 / 待办 → 看 [`HANDOFF.md`](HANDOFF.md)**（权威交接文档，9 节）
> - **内嵌 Python 代码 → 看 [`py/README.md`](py/README.md)**
> - **本文件只留长期不变的部分**：构建环境、aapt2 替换、编译期踩坑、源码地图、文档地图。

- **当前版本：v1.4**（versionCode 4）；包名 `com.b50.roast`；应用名 B50 锐评机
- UI：`top.yukonga.miuix.kmp:miuix-ui:0.9.4`（Miuix / 澎湃风格），底部 3 tab（生成 / 历史 / 授权）+ 设置页
- minSdk 26 / compileSdk 37 / targetSdk 37
- 出图尺寸：不带锐评 1200×2104，带锐评 1200×2277（与桌面版像素级一致，因为用的是同一个 `render.py`）

---

## 0. 接手先跑这一条自检

```bash
cd /sdcard/Tools/LSPilot/projects/b50-android
python3 tools/status.py
```

它把「文档里写的」和「磁盘上真实的」对一遍，避免基于过期信息动手：

1. 20 个关键文件在不在（gradle / manifest / 三个 assets / jniLibs / py/ / tools）
2. `PACK_VERSION` / `PY_VERSION` 的值，**并与 HANDOFF 里写的值比对**（文档滞后会立刻报出来）
3. `py/` 与 `assets/pycode.zip` 内容是否一致
4. `py/` 与归档基线（桌面版 v1.3 快照）是否一致（8 个文件逐字节 + `roast.py` 提示词核心区）
5. 设备上已安装的 `com.b50.roast` 是不是工作区最新构建产物（比 md5）
6. 文档 / 工具在位情况 + mtime，并顺手打印 HANDOFF 第 7 节待办

退出码 `0` = 全绿，`1` = 有问题（可直接当门禁）。加 `--no-device` 跳过设备检查。

---

## 1. 构建环境（本机实测可用，aarch64 安卓设备自举）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21.0.12（Ubuntu apt，`/usr/lib/jvm/java-21-openjdk-arm64`） | 需要 javac |
| Gradle | 9.8.2（`/opt/gradle9/gradle-9.8.2`） | AGP 9 要求 Gradle 9.x |
| AGP | 9.4.1 | 支持 compileSdk 37；**内置 Kotlin**，不再需要 `kotlin.android` 插件 |
| Compose 编译器插件 | `org.jetbrains.kotlin.plugin.compose:2.4.21` | 必须显式声明 |
| Kotlin stdlib | 2.4.21（由 miuix 拉取 2.4.20+） | |
| Android SDK | `/opt/android-sdk`（platforms;android-37 + build-tools;37.0.0） | 手工解压官方 zip |
| **aapt2** | **Termux aarch64 构建（`/opt/termux-aapt2/bin/aapt2`）** | 官方 aapt2 只有 x86-64，必须替换 |

### 为什么必须替换 aapt2

官方 `com.android.tools.build:aapt2` 与 SDK build-tools 里的 `aapt2` 都是
`ELF 64-bit LSB x86-64`，在 arm64 设备上无法执行。解法：

1. 从 Termux 仓库取 aarch64 版本及其依赖：
   `aapt2 abseil-cpp libprotobuf fmt libc++ libexpat libpng libzopfli zlib`（全部 `.deb`）
2. 解包后放到**可执行分区**（`/data/data` 是 `tmpfs noexec`，不能放）：`/opt/termux-aapt2/{bin,lib}`
3. 用一个 wrapper 注入 `LD_LIBRARY_PATH`：

   ```sh
   #!/bin/sh
   exec env LD_LIBRARY_PATH=/opt/termux-aapt2/lib /opt/termux-aapt2/bin/aapt2.real "$@"
   ```
4. 在 `gradle.properties` 指向它：

   ```properties
   android.aapt2FromMavenOverride=/opt/termux-aapt2/bin/aapt2
   ```

---

## 2. 构建与安装（**别手敲 gradle**，用现脚本）

```bash
# 一键：把本工作区同步到 /opt/work（ext4，避开 FUSE）→ gradle assembleDebug → APK 回拷 outputs/apk/
sh /sdcard/Tools/LSPilot/scripts/build_android.sh

# 构建 + 直接装到设备并启动
sh /sdcard/Tools/LSPilot/scripts/build_android.sh --install
```

脚本里已经处理的坑：`rm -rf app/build/outputs`（增量构建会把 APK 追加成 52 MB）、
`JAVA_HOME` / `GRADLE_OPTS=-Djava.io.tmpdir=/opt/tmp`（tmpdir 也必须在可执行分区）、
先 `cp` 到 `/data/local/tmp` 再 `pm install`（`/sdcard` 不能被 system_server 直接读）。

产物：`/sdcard/Tools/LSPilot/outputs/apk/b50-roast-debug.apk`（约 58 MB）

改了 `py/` 或素材之后，**记得先重打包并 +1 版本号**，流程见 `HANDOFF.md` 第 8 节。

---

## 3. 编译期踩过的坑（别重踩）

1. **`/data/data` 是 `tmpfs noexec`** —— 二进制和 `.so` 放这里会 `Permission denied`，连 `dlopen` 都失败。
   Gradle 的 `java.io.tmpdir` 同理，要指到 `/opt/tmp`。
2. **compileSdk 必须是 37** —— miuix 0.9.4 与 androidx.compose 1.12 的 AAR metadata 强制
   `compileSdk >= 37`，而 AGP 8.13 最高支持 36 → 只能上 AGP 9。
3. **AGP 9 内置 Kotlin** —— 再声明 `org.jetbrains.kotlin.android` 会直接报错并中止；
   只保留 `com.android.application` + `org.jetbrains.kotlin.plugin.compose`。
4. **Kotlin 块注释是嵌套的** —— 文档注释里写 `/api/*` 会开启嵌套 `/*`，导致 "Unclosed comment"，
   整个文件的类全部 unresolved。写注释时别放 `/*`。
5. **AGP 9 只扫 `src/main/java`** —— Kotlin 源码放 `src/main/kotlin` 不会参与编译。
6. **`mutableStateOf<T>()` 不能省略参数** —— 无参重载不存在，用 `mutableStateOf(null as T?)`。
7. **`sh` 工具里嵌套 `su -c` 会卡死** —— LSPilot 的 sh 本身就跑在 chroot 里，再套 `su -c` 会阻塞不返回。
8. **suspend 不等于切线程** —— `scope.launch {}` 默认还在 Main，网络请求要自己 `withContext(IO)`，
   否则 `NetworkOnMainThreadException`（HANDOFF 第 7 节记了这次的现场）。

---

## 4. 源码地图

```
app/src/main/java/com/b50/roast/
├── MainActivity.kt        入口：首启解压门 → 账号卡片 → 3 tab + 设置页（含预测性返回三层栈）
├── AppState.kt            状态中枢：绑定 / 同步 / 规整 / 渲染 / LLM / 配额（726 行）
├── data/
│   ├── OAuthClient.kt       水鱼设备码绑定 + refresh 轮换（client_id 为 XOR 编码，dex 里搜不到明文）
│   ├── MaimaiApi.kt         GET /player/records + 缓存 + 版本校准
│   ├── MusicDb.kt           music_data.json 索引
│   ├── RecordsNormalizer.kt 831 条 → B35/B15（Python 不可用时的兜底）
│   ├── DailyQuota.kt        每日 3 次「在线刷新」配额（出图不计）
│   ├── ShotStore.kt         shots/ 扫描、缩略图 LruCache、存相册、分享、删除
│   ├── LlmPresets.kt        供应商预设表（baseUrl / path / 内置模型 / 额外 header）
│   ├── LlmClient.kt         OpenAI 兼容最小客户端：GET /models、POST /chat/completions
│   ├── ResourceInstaller.kt assets/pack.zip → filesDir/res（版本化 + 原子替换）
│   ├── PythonRunner.kt      py_runtime.zip + pycode.zip → filesDir/py，ProcessBuilder 调用
│   ├── Res.kt               资源路径入口
│   ├── DevKey.kt            开发者模式凭据（XOR + 倒序存放）
│   └── Models.kt            只剩 Sections（v0 的 B50Api.kt 已删）
├── render/B50Renderer.kt   Kotlin Canvas 渲染器（Python 起不来时的兜底）
└── ui/{Common,GenerateScreen,HistoryScreen,OAuthScreen,SettingsScreen}.kt

app/src/main/assets/
├── pack.zip        21 MB   素材（曲绘 1569 张 200² WebP + 图标 + GB2312 子集字体 + JSON + kb）
├── py_runtime.zip  29 MB   Python 3.14 + Pillow 13（解压 63 MB）
└── pycode.zip      37 KB   **产物**，真源在 py/（tools/pack_pycode.py 生成）

app/src/main/jniLibs/arm64-v8a/
├── libpy_home.so           4.6 KB  python3 主程序（改名放 lib 目录才能 exec）
├── libpython3.14.so        5.9 MB
└── libandroid-support.so / libc++_shared.so

py/                       App 内嵌 Python 的唯一真源（详见 py/README.md）
tools/                    pack_assets.py / pack_pycode.py / build_python_runtime.sh / status.py
```

---

## 5. 文档地图（按这个顺序读）

| 文件 | 看什么 |
|---|---|
| `tools/status.py` | **先跑**：一条命令核对工程状态（含打包依赖），全绿再动手 |
| `HANDOFF.md` | **权威交接**：已验证事实（带 logcat 证据）/ 架构图 / 关键约束 / 文件索引 / 构建 / 调试 / 待办 / 重建 / 风险 |
| `py/README.md` | 内嵌 Python 真源：每个文件与桌面版的关系、改完要做什么 |
| `README.md`（本文） | 构建环境 / aapt2 / 编译期踩坑 / 源码地图 |

## 6. 上游与素材依赖（长期维护的两个上游 + 一个素材源）

| 依赖 | 地址 | 我们怎么用 | 同步工具 |
|---|---|---|---|
| **代码上游** | https://github.com/HanYaaaaaaa/nonebot-plugin-b50-analysis | `py/vendor/mai/{context_builder,paths,render,utils}.py` 的来源（vendored，**不 pip 装**，见 DECISIONS D2） | `python3 tools/check_upstream.py`（三态：上游更新 / 本地本地化 / 一致） |
| **UI 库**（今后默认） | https://github.com/compose-miuix-ui/miuix | `app/build.gradle.kts` 的 `miuix-ui`（当前 **0.9.4**，已是最新 release + Maven Central） | 同上，同时查 GitHub release 与 Maven Central |
| **素材包** | `https://dw.moant.cn:34274/assets.zip` | `pack.zip` 的来源；**证书已过期但可下载**；URL 端口 **34274 = 素材版本代号 = 机台 cn 版本** | `python3 tools/fetch_assets.py`（本地→归档 zip→下载，四层退路） |

上游基线记在 `tools/upstream-baseline.json`（接入时的 commit + 文件 sha256），
所以 `check_upstream.py` 能分清「上游改了」（要搬）和「我们自己本地化过」（正常）。
同步完跑一次 `python3 tools/check_upstream.py --save-baseline` 更新基线。

**桌面版（CLI + 本地 Web + QQ 插件）已归档**：只读快照 `/sdcard/Tools/LSPilot/archive/b50-desktop-v1.3`（最后版本 v1.3，
见其 `ARCHIVED.md`）。本项目现在只做安卓版，桌面版不再开发。
