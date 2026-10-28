# B50 锐评机 · Android

> 在手机上一步出图：**绑定水鱼账号 → 拉全量成绩 → 7 分区分析 → AI 写锐评 → 本地渲染出图**。
> 渲染引擎内嵌在 App 里（Python + Pillow），出图与桌面版**像素级一致**；除了 AI 请求，全流程离线。

<p align="left">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-26-4A80F7">
  <img alt="targetSdk" src="https://img.shields.io/badge/targetSdk-37-4A80F7">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-4A80F7">
</p>

---

## 它做什么

1. 用**水鱼查分器**的 OAuth 设备码绑定账号（只读成绩，不能改任何数据）；
2. 拉全量成绩，规整成 **B35 / B15**，算出 15274 这种 DX rating；
3. 按 7 个分区（亮点 / 重合 / 推分 / 配置 / 普通点 / 少PC / 最高RA）整理证据；
4. 交给你自己配置的 AI（DeepSeek / Gemini / Claude / OpenAI / Kimi / 任意 OpenAI 兼容服务）写一段锐评；
5. 用内嵌的 Python + Pillow 在本机渲染成一张 1200 宽的成图，可直接存相册或分享。

## 功能

- **账号**：设备码绑定 + token 自动轮换，凭据只存在本机
- **分析分区**：任意勾选，卡片上实时显示"画哪些分区"
- **语气 / 人设、角度**：预设按钮一键切换（毒舌 / 温柔教练 / 雌小鬼 / 猫娘 / 中二解说…；综合锐评 / 推分建议 / 配置诊断 / 定数趋势 / 与同段对比），也可以自己写
- **出图参数**：字数、宽度 900/1200/1500、格式 PNG/JPG/WebP
- **长任务体验**：出图 20~60 秒，按钮上有「剧场追光」动效 + 进度/完成通知（前台服务保活，切去微信也会跑完）
- **历史**：预览、存相册、分享、删除
- **额度友好**：出图 + AI 锐评**不消耗**水鱼接口的在线刷新次数

## 系统要求

| 项 | 要求 |
|---|---|
| Android | 8.0（API 26）及以上，**仅 arm64-v8a** |
| 存储 | 首次启动解压资源约 **24 MB** + Python 运行时约 **63 MB** |
| 网络 | 仅绑定/刷新成绩、取头像、调用 AI 时需要 |

## 安装

1. 到 [Releases](../../releases) 下载 `b50-roast-1.5.apk`；
2. 点开安装（首次需允许"安装未知来源应用"）；
3. 首次启动会自动解压资源包与 Python 运行时，约 5~10 秒。

## 首次使用（4 步）

1. **授权**页 → 绑定水鱼账号（会显示一个设备码，去水鱼网站确认）；
2. **授权**页 → 填 QQ 号（成图头像用）；
3. **设置**页（授权页 →「应用设置 → 打开设置」）→ 选 AI 供应商 → 填自己的 API Key → 点「获取模型」；
4. **生成**页 → 「出图 + AI 锐评」。

> 出图期间可以切走，完成后会通知你；回到 App 时会直接弹窗提示。

## 隐私与权限

| 权限 | 用途 |
|---|---|
| `INTERNET` | 水鱼接口、头像下载、AI 请求 |
| `POST_NOTIFICATIONS` | 出图进度 / 完成提醒（可拒绝，不影响出图） |
| `FOREGROUND_SERVICE(_DATA_SYNC)` | 出图期间保活，避免切后台被系统冻结 |

- 成绩数据、水鱼 token、AI Key **全部只在本机**，不上传任何自建服务器；
- 曲绘 / 图标 / 字体随 APK 内置，渲染完全离线，不会因为出图而访问网络。

## 额度规则（别被"次数"绕晕）

- 额度限制的对象是**水鱼接口的调用量**，不是出图次数：**每天 3 次在线刷新**，次日 0 点重置；
- **出图 + AI 锐评永远不扣次数**（本地有缓存就能出）；
- **首次拉取免费**；数据旧了会先问一句"要不要花一次额度刷新"。

## 从源码构建

需要 JDK 17+ 与 Android SDK 37（`compileSdk 37` / `buildTools 37.0.0`）。

体积大的 `assets/pack.zip`、`assets/py_runtime.zip`、`jniLibs/*.so` **不入库**，需要自己生成：

```bash
python3 tools/fetch_assets.py        # 取素材（曲绘/图标/字体/JSON）
python3 tools/pack_assets.py         # → app/src/main/assets/pack.zip
sh      tools/build_python_runtime.sh # → app/src/main/assets/py_runtime.zip + jniLibs
python3 tools/pack_pycode.py         # py/ → app/src/main/assets/pycode.zip

./gradlew :app:assembleDebug         # 或 assembleRelease
```

发布签名：把 `keystore.properties.template` 复制成 `keystore.properties` 填好（文件已在 `.gitignore` 里），
`assembleRelease` 会自动用它签名；没有则回落 debug 签名。

构建环境、aapt2 替换、编译期踩坑、源码地图 → 见 **[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)**；
想接手维护（发版 SOP / 签名管理 / 踩过的坑 / 下一步）→ 见 **[docs/HANDOVER.md](docs/HANDOVER.md)**。

## 目录结构

```
app/src/main/java/com/b50/roast/
├── MainActivity.kt          顶栏 + 3 tab + 设置页叠层（预测性返回）
├── AppState.kt              状态中枢：绑定 / 同步 / 规整 / 渲染 / LLM / 配额
├── data/                    OAuth、接口、规整、配额、出图仓库、LLM 预设、通知
├── service/RenderKeepAlive  出图期间的前台服务（只保活）
└── ui/                      生成 / 历史 / 授权 / 设置 / 通用组件 / 浮层
py/                          内嵌 Python 真源（规整 + 分区 + 提示词 + 渲染）
py/vendor/mai/               上游渲染逻辑（MIT，见 THIRD_PARTY_NOTICES.md）
tools/                       素材打包、Python 运行时、工程自检等脚本
```

## 已知限制

- 只发布 **arm64-v8a**（内嵌 Python 运行时是 aarch64 的）；
- 资源包版本冻结在 `cn:34274`，上游素材更新后需同步 `PACK_VERSION`；
- 渲染宽度可选 900/1200/1500，其余尺寸按比例；
- 「预测性返回」的系统预览需要你在开发者选项里打开"预测性返回手势动画"。

## 许可证与致谢

- 本工程：**MIT**，见 [LICENSE](LICENSE)
- 渲染逻辑来自 [nonebot-plugin-b50-analysis](https://github.com/HanYaaaaaaa/nonebot-plugin-b50-analysis)（MIT，Copyright (c) 2026 HanYaaaaaaa）
- UI 组件库 [miuix](https://github.com/compose-miuix-ui/miuix)（Apache-2.0）
- 内嵌 Python 3.14（PSF-2.0）与 Pillow（HPND）
- 字体与舞萌DX 曲绘 / 图标的版权归各自权利人（华立科技 / SEGA 等），详见 **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**

## 免责声明

非官方项目，与华立科技、SEGA、水鱼查分器均无隶属关系。
成绩数据来自水鱼查分器的公开接口，AI 锐评由用户自己配置的模型生成，
本项目不对模型输出内容或素材使用场景的合规性负责。
