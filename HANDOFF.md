# HANDOFF · b50 安卓客户端（App 内嵌 Python 渲染）

> 更新时间：2026-10-28 12:45 (CST)　**当前版本 v1.4（versionCode 4）**
> **项目范围：只做安卓版**（`com.b50.roast`）。桌面版（CLI / 本地 Web / QQ 插件）已于 2026-10-28 归档到
> `/sdcard/Tools/LSPilot/archive/b50-desktop-v1.3`（只读快照，见其 `ARCHIVED.md`），不再开发、不再运行。
> 状态：**可编译、可安装、可运行、App 内独立出图（不依赖任何后端）**
> **接手第一件事**：`python3 tools/status.py`（20 个关键文件 / 版本号 / py/ 一致性 / 已安装包比对，全绿再动手）
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
> 本文档写给下个会话：读完这一页即可继续开发，不用重新摸索。

---

## 0. 一句话现状

App 在手机内独立完成整条链路：
**绑定水鱼账号 → 拉全量 831 条 → 规整 B35/B15 → 生成 7 个分析分区上下文 → 用原版 `render.py` 渲染出图**。
渲染引擎是**内嵌的 Python 3.14 + Pillow 13**（不是 Kotlin 重写），所以出图效果与桌面版**像素级一致**（1200×2104）。
**功能已全部完成**，桌面版的核心链路在 App 内跑齐了：
绑定 → 拉全量 → 规整 B50 → 7 分区上下文 → AI 写锐评 → 渲染出图 → 配额扣减。
剩的都是收尾清理（见第 7 节）。

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
7. **版本号**：素材变了改 `ResourceInstaller.PACK_VERSION`（当前 **34274** ← 素材版本代号，规则见第 8 节）；Python 运行时变了改 `PythonRunner.PY_VERSION`（当前 **6**）。任一变化，App 升级后自动重新解压。

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
│   ├── LlmPresets.kt            供应商预设表（baseUrl / path / 内置模型 / 额外 header / 提示语）
│   ├── LlmClient.kt             OpenAI 兼容最小客户端：GET /models、POST /chat/completions 测活
│   ├── ResourceInstaller.kt     assets/pack.zip → filesDir/res（版本化 + 原子替换）
│   ├── PythonRunner.kt          assets/py_runtime.zip → filesDir/py + ProcessBuilder 调用
│   ├── Res.kt                   资源路径入口
│   └── Models.kt                只剩 Sections（v0 的 B50Api.kt 已于 2026-10-28 删除）
├── render/B50Renderer.kt        Kotlin Canvas 渲染器（**兜底**，Python 起不来时用）
└── ui/{GenerateScreen,HistoryScreen,OAuthScreen,SettingsScreen,Common}.kt

app/src/main/assets/
├── pack.zip                     21 MB   资源（曲绘 1569 + 图标 + 字体 + JSON + kb）
├── py_runtime.zip               29 MB   Python 3.14 + Pillow 13 + 63 MB 解压后
└── pycode.zip                   32 KB   **产物**（真源在 py/，用 tools/pack_pycode.py 生成，别手工改）

app/src/main/jniLibs/arm64-v8a/
├── libpy_home.so                4.6 KB  python3 主程序（改名后放 lib 目录才能 exec）
├── libpython3.14.so             5.9 MB
├── libandroid-support.so / libc++_shared.so

tools/
├── fetch_assets.py              素材获取：本地 → 归档 zip → 上游下载（上游证书过期，默认跳过校验）
├── pack_assets.py               423 MB 素材 → pack.zip 21 MB（前置检查 + 产物校验 + 版本号一致性）
├── check_upstream.py            上游同步：代码 4 文件三态比对 + miuix 版本（带基线快照）
├── pack_pycode.py               py/ → pycode.zip（确定性打包；--check 校验 / --check-desktop 与桌面版对账）
├── status.py                    工程自检：文件齐否 / 版本号 / py 一致性 / 已安装包比对（新会话先跑它）
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
5. 沙箱里这个包是空壳：`/data/data/com.b50.roast` 在 su shell 里看不到（挂载隐藏），
   `ls /proc/<pid>/root/...` 也不行。**验证 App 私有数据只能通过 UI 或 App 自己打日志。**

## 7. 待办

> **下一轮的 5 件事（用户 2026-10-28 定稿，按此顺序做）。** 每条下面写了「已知事实/缺口」，
> 都是这轮体检时实测的，省得下次重新摸。

1. **UI 优化 · 阴影问题**：部分界面与弹窗的阴影不对（下次逐屏检验再定位）。
   检验清单：设置页 `SectionCard`、生成页的「数据过期」覆盖层、历史页大图预览、`TopAppBar` 下分割线。
   验收：无脏边/双影，阴影层级符合 miuix 规范（`MiuixTheme` elevation）。
2. **API 预设删减**：删掉部分供应商 —— **等用户给最终表格再动手**。
   现状：`data/LlmPresets.kt` 里 6 项 = `custom` + DeepSeek + 硅基流动 + Kimi + 智谱 GLM + 通义百炼。
   注意：删供应商时若用户已存了该家的 Key，要保证不炸（读不到预设就回落 custom，别让设置页崩）。
3. **图标重绘（miuix 风格）**：现有 18 个文件，都是旧风格：
   `res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher[_round|_foreground].png` +
   `res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`（自适应图标）+ `res/drawable/ic_launcher_background.png` + `src/ic_launcher-web.png`。
   验收：自适应图标在圆形/方形遮罩下都完整，深色背景下不糊。
4. **App 1.5 = 首个发布版**：`versionName 1.4 → 1.5`、`versionCode 4 → 5`。
   **缺口（实测）**：`app/build.gradle.kts` 只有 `buildTypes`，**没有 `signingConfigs`** ——
   release 包目前是未签名/沿用 debug key，发布前必须加签名（新 keystore + `assembleRelease`）。
   另外确认 `PACK_VERSION`(34274) / `PY_VERSION`(6) 是否需要动（素材/py 没变就不动）。
5. **上游仓库分支 + 发布**：在上游项目（`HanYaaaaaaa/nonebot-plugin-b50-analysis`）下建分支发布安卓端。
   **缺口（实测）**：`b50-android/` 里**没有 LICENSE、没有 THIRD_PARTY_NOTICES.md**（这两份只存在于归档的桌面版）。
   发布三件套：
   - **协议**：上游 MIT（`Copyright (c) 2026 HanYaaaaaaa`），我们 vendored 了 `py/vendor/mai/` 的 4 个文件，
     必须带 MIT 声明 + 说明本地改动；本工程自身许可还要定（建议 MIT/AGPL 二选一，问用户）。
   - **介绍**：README 面向外部（现在这份是内部开发文档，含机器路径与调试命令，需要另写一份）。
   - **仓库结构**：`app/`（Kotlin）+ `py/`（内嵌 Python 真源）+ `tools/`（4 个脚本）+ `docs`（HANDOFF 保留或精简）。
   **待确认**：是「上游作者给权限后直接在主仓库开分支」还是「fork 到自己账号发布」——
   这两种在协议落款、README 措辞、issue 归属上都不一样，下次先问清楚。

**长期项（不阻塞发布）**：

- 出图端到端约 40 秒（提示词 1.3s + AI 36s + 渲染 4s），瓶颈是推理模型；代码已传 `reasoning.effort=low`。
- user prompt 7641 字（全量 831 首明细）—— **用户明确要求不压缩**，别自作主张改。
- 生成页 `format`（png/jpg/webp）选择器未接通，渲染固定输出 png。
- 资源包冻结 `cn:34274`（第 9 节第 1 条），等上游复活；`fetch_assets.py --probe` 可探测。
- 上游 3 个本地化文件与上游有差（`check_upstream.py` 的 `·` 状态），值得逐个确认有无该搬的上游修复。

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
