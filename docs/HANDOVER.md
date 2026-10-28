# 交接单 · 维护者 / 下一个接手的人（含 AI 会话）

> 三分钟能读完。要**细节**去 `HANDOFF.md`（76 KB，权威记录：已验证事实 / 架构 / 每个坑的实测数据）；
> 要**构建环境**去 `docs/DEVELOPMENT.md`；要**对外说明**看 `README.md`。

---

## 0. 现状（2026-10-28）

| 项 | 状态 |
|---|---|
| 版本 | **v1.5（versionCode 5）**，已发布 |
| 仓库 | `plugin-b50-analysis-android`（public，默认分支 `main`） |
| Release | tag `v1.5`，附件 `b50-roast-release.apk`（57,139,194 B，v2 签名 / RSA 4096） |
| 功能 | **完整**：绑定 → 拉全量 → 规整 B35/B15 → 7 分区上下文 → AI 锐评 → 本地渲染出图（1200 宽，与桌面版像素级一致） |
| 手机端的 App | 装的是 debug 签名包（和 Release 签名不同，想换正式包要先卸载，会清数据） |

一句话架构：**Kotlin 管 UI / OAuth / 配额 / LLM 的 HTTP；内嵌 Python 3.14 + Pillow 管规整 + 提示词 + 渲染**。
出图不扣额度，只有「在线刷新成绩」扣（每天 3 次）。

## 1. 代码地图（改东西先定位到这里）

| 位置 | 职责 | 备注 |
|---|---|---|
| `MainActivity.kt` | 顶栏 `AppHeader` + 3 tab + 设置页叠层（预测性返回） | 弹窗**必须**交给根部 `AppOverlayHost` |
| `AppState.kt` | 状态中枢：绑定 / 同步 / 规整 / 渲染 / LLM / 配额 / 完成通知分流 | 出图入口 `roastAndRender()` |
| `data/` | OAuth、水鱼接口、规整、配额、出图仓库、LLM 预设、`RoastNotify` | |
| `service/RenderKeepAlive.kt` | 出图期间的前台服务（`dataSync`），**只保活不干业务** | 防 MIUI 冻结后台 |
| `ui/` | 生成 / 历史 / 授权 / 设置 + `Common.kt`（`AppHeader`/`Chip`/`ExpandablePicker`/`CompactTextField`/`FlowLight`）+ `Overlay.kt` | 组件尺寸坑见 §6 |
| `py/` | 内嵌 Python 真源（`render_worker.py` / `roast.py` / `vendor/mai/*`） | **改完必须 +1 `PY_VERSION` 并重打 `pycode.zip`** |
| `tools/` | 素材、运行时、py 打包、工程自检 | `status.py` 是门禁 |

## 2. 跑起来

```bash
# 前置：JDK 17+、Android SDK 37（compileSdk 37 / buildTools 37.0.0）
python3 tools/fetch_assets.py          # 素材（曲绘/图标/字体/JSON）
python3 tools/pack_assets.py           # → app/src/main/assets/pack.zip
sh      tools/build_python_runtime.sh  # → py_runtime.zip + jniLibs/*.so
python3 tools/pack_pycode.py           # py/ → pycode.zip
./gradlew :app:assembleDebug
```

`assets/*.zip` 与 `jniLibs/*.so` **不入库**（体积大、可重建）。新 clone 直接编译会缺资源，先跑上面 4 条。

## 3. 发版 SOP（下次发 v1.6 照这个来）

1. `app/build.gradle.kts`：`versionCode +1`、`versionName` 改新版本号；
2. 素材变了 → `ResourceInstaller.PACK_VERSION +1` 并重打 `pack.zip`；py 变了 → `PythonRunner.PY_VERSION +1` 并重打 `pycode.zip`；
3. `python3 tools/status.py` 必须 **全绿（0 提醒）**；
4. `sh tools/build_android.sh --release`（签名用 `keystore.properties`；没有会回落 debug 签名，**能装不能发**）；
5. 验签 + 验版本号：
   ```bash
   apksigner verify --print-certs --verbose outputs/apk/b50-roast-release.apk
   aapt2 dump badging outputs/apk/b50-roast-release.apk | head -3   # → versionCode/versionName
   ```
6. 发布：
   ```bash
   git add -A && git commit -m "release: v1.6" && git push
   gh release create v1.6 outputs/apk/b50-roast-release.apk \
      --title "B50 锐评机 · Android v1.6" --notes-file <notes.md>
   gh release view v1.6 --json assets   # 确认 state=uploaded 且 digest 与本地 sha256 一致
   ```

## 4. 签名密钥（**最容易把项目搞死的地方**）

- keystore：`secure/b50-release.jks`（alias `b50`，RSA 4096，10000 天）；密码在 `keystore.properties` 与 `secure/.storepass`。
- 三者都在 `.gitignore` 里（`*.jks` / `keystore.properties` / `secure/`），**永远不要提交**。
- **必须有仓库之外的备份**（密码管理器 / 网盘）。丢了 → 无法给已装用户推送更新，只能换 `applicationId` 重发。
- 换签名意味着所有老用户必须卸载重装（会清数据：水鱼绑定、AI Key、历史图）。

## 5. 改代码前的门禁

- `python3 tools/status.py`：文件齐否 / 版本号是否与文档一致 / py 一致性 / 设备已装包是否等于产物；
- `python3 tools/pack_pycode.py --check`：`py/` 与 `pycode.zip` 是否同步（**不同步装机后跑的是旧代码**）；
- `python3 tools/check_upstream.py`：与上游 4 个 vendored 文件的三态比对；
- 红线：**别给 miuix `Card` 加阴影**（它没有 elevation 参数，浮层才用 `Surface(shadowElevation=)`）；
  **弹窗写在 `AppOverlayHost` 之外会盖不住顶栏/底栏**；**别手敲 gradle**（用 `build_android.sh`，里面规避了 4 个坑）。

## 6. 踩过的坑 TOP 10（都有实测数据，详见 HANDOFF）

1. **miuix 组件的内部尺寸是写死的**：`TextField` 56dp、`TopAppBar` 大标题顶部 52dp、
   `CircularProgressIndicator` 的半径按 `size` **参数**算（`Modifier.size()` 只压画布 → 圆环被裁、看着不居中）。
   **`Modifier.height()` 硬压会裁内容** —— 要改尺寸就换成能自己排版的原语（`BasicTextField` / 自拼 Column / Canvas）。
2. **`Card` 没有 elevation**；需要阴影只能用 `Surface(shadowElevation=)`，且形状跟随 `shape`（否则出现方形影子）。
3. **弹窗必须在根部 `AppOverlayHost`**：写在屏幕里的 `Box(fillMaxSize)` 只覆盖 Scaffold content 区，顶栏/底栏会全亮。
4. **`PACK_VERSION` / `PY_VERSION` 忘了 +1** → App 升级后不重新解压，用户看到旧素材/旧算法（本项目最常见的坑）。
5. **`filesDir` 里的文件不能 exec**：Python 主程序必须放 `jniLibs/arm64-v8a/libpy_home.so`；
   配套 `keepDebugSymbols`（防 strip）+ `patchelf --set-rpath '$ORIGIN'`。
6. **素材目录结构必须与桌面版完全一致**（`res/ui/icons`、`res/ui/fonts`、`res/cover`…，`render.py` 里是硬编码的）；
   字体文件名要含 `han`/`cn`、`torus`/`bold` 关键字才会被 `_font_path()` 选中。
7. **`refresh_token` 会轮换**：每次换票都要回写；**同一个 token 不能在桌面版和 App 同时用**（会互相踢）。
8. **后台会被冻结**：MIUI 下点完出图切走，进程 `visible->invisible->background` 后日志几分钟不动 ——
   这就是为什么要有 `RenderKeepAlive`（前台服务 `dataSync`）。别把它删掉。
9. **增量构建会让 APK 翻倍**（27 MB → 52 MB）：`build_android.sh` 已 `rm -rf outputs`；别手工 `gradlew` 连跑。
10. **取证别被假 bug 骗**：截图里出现的灰带/暗条可能来自系统层残留窗口（如 MIUI 的返回手势指示层）。
    判断法：**同一坐标截另一个 App，图案一样就是系统的**。另外 `uiautomator dump` 会让列表滚回顶部。

## 7. 已知限制 / 未验证项

- 只有 **arm64-v8a**（内嵌 Python 是 aarch64 的）；
- 资源包冻结 `cn:34274`，上游素材站点长期不可达；机台**新增**曲目会缺曲绘（回落默认封面，不崩）；
- 出图端到端 20~60 秒，瓶颈是推理模型（已传 `reasoning.effort=low`）；
- **user prompt 约 7.6 K 字（全量 831 首明细），用户明确要求不压缩，别自作主张改**；
- `renderLocal`（Kotlin Canvas 兜底渲染）存在但**没有自动回退**，Python 起不来时需手动改；
- 「语气/角度」预设里的新口径（定数趋势 / 与同段对比）只做过一次真机出图验证，样本少。

## 8. 下一步建议（按性价比排序）

1. 收集真实使用反馈，按"用着别扭"的点继续调（转场时长在 `MainActivity` 的 `animateFloatAsState`，
   追光参数在 `Common.kt → FlowLight`：`bandWidthRatio / alpha / periodMs / color`）；
2. 把 `renderLocal` 接成 Python 失败时的**自动回退**（现在要手动改）；
3. 上游素材复活后的恢复 4 步（见 HANDOFF §9），或做「缺曲绘时用 id 生成占位图」；
4. 想减小包体：`py_runtime.zip` 占 30 MB，需要自己写可靠的 tar 解包才能换 gzip（Java 无内置 tar，试过一版有 bug 已弃）。

## 9. 文档索引

| 文件 | 用途 |
|---|---|
| `README.md` | 对外：功能 / 安装 / 首次使用 / 隐私 / 额度 / 从源码构建 |
| `HANDOFF.md` | **权威内部记录**：已验证事实、架构、每个坑的实测数据、发布准备、已知风险 |
| `docs/DEVELOPMENT.md` | 构建环境、aapt2 替换、编译期踩坑、源码地图 |
| `docs/HANDOVER.md` | 本文件：接手 5 分钟单 + 发版 SOP |
| `THIRD_PARTY_NOTICES.md` | 第三方与素材版权声明（**发布前必须同步更新**） |
| `py/README.md` | 内嵌 Python 真源的来源、同步、改完要做什么 |
