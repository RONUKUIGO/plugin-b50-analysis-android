# 第三方声明 · Third-Party Notices

本工程（B50 锐评机 Android 客户端，`com.b50.roast`）自身以 **MIT** 许可发布，见 [LICENSE](LICENSE)。
下面是它依赖 / 内置的第三方内容。**打包进 APK 的东西都在这里逐条列明**，不做模糊处理。

---

## 1. nonebot-plugin-b50-analysis（MIT）

`py/vendor/mai/` 下的 `context_builder.py`、`render.py`、`utils.py`、`paths.py` 四个文件本地化自：

- 项目：https://github.com/HanYaaaaaaa/nonebot-plugin-b50-analysis
- 许可：**MIT License，Copyright (c) 2026 HanYaaaaaaa**
- 许可全文：https://github.com/HanYaaaaaaa/nonebot-plugin-b50-analysis/blob/main/LICENSE

本工程对这些文件的改动：

1. `paths.py` 改为相对资源根目录解析（Android 端的资源在 `filesDir/res`，不是项目目录）；
2. `render.py` 增加字体多级回退、rating 数字兜底，以及 `OUT_WIDTH / SECTIONS_ENABLED / CACHE_AVATAR / CACHE_COVER`
   四个模块级开关（供 App 侧按用户设置调整出图形态）；
3. 移除对 NoneBot 框架的运行期依赖，改为 `py/render_worker.py` 以一次进程调用完成
   「规整数据 → 建 7 个分析分区 → 渲染出图」。

> 出图与桌面版**像素级一致**，靠的就是复用这同一套 `render.py`。

## 2. UI 组件库 miuix（Apache-2.0）

- `top.yukonga.miuix.kmp:miuix-ui:0.9.4`
- 项目：https://github.com/compose-miuix-ui/miuix
- 许可：**Apache License 2.0**

## 3. Android / Kotlin 运行时与库（Apache-2.0）

Jetpack Compose、AndroidX（activity-compose / core-ktx）、Kotlin 标准库、kotlinx-coroutines，
均为 **Apache License 2.0**。

## 4. 内嵌 Python 运行时

App 内置一套 Python，用于本地渲染（不走网络、不装任何东西）：

| 组件 | 版本 | 许可 |
|---|---|---|
| Python | 3.14 | **PSF License 2.0** |
| Pillow（含 WebP 支持） | 13.x | **HPND License** |
| libandroid-support | Termux 构建 | Apache-2.0 |
| libc++ / libpython3.14 | 随运行时 | Apache-2.0 / PSF |

来源为 Termux 的 aarch64 包，经 `tools/build_python_runtime.sh` 重打包（`patchelf` 改 rpath）。
对应的源码可从 https://github.com/termux 与 https://www.python.org 获取。

## 5. 字体（版权归原权利人）

渲染出图需要中文字体与数字字体，资源包 `assets/pack.zip` 内含：

- `ui/fonts/ResourceHanRoundedCN.otf` —— 思源/Resource Han Rounded 系中文字体
- `ui/fonts/Torus SemiBold.otf` —— 数字/拉丁字形

这些字体**版权归各自权利人**（字体厂商 / 华立科技 / SEGA 等），本项目仅随包内置于本地渲染流程中使用，
不对字体本身做任何授权，也不单独分发字体文件。如需在其他场景使用，请自行取得授权。

## 6. 游戏素材（版权归原权利人，仅本地渲染用）

`assets/pack.zip` 内的 `cover/*.png`（曲绘）、`ui/icons/*.png`（难度 / 评级 / 背景图标）、
`music_data.json`、`chart_summary.json` 等，均来自舞萌DX（maimai DX）。

- 版权归 **华立科技 / SEGA** 等原权利人所有。
- 本 App 内置这些素材**仅为了在用户本机离线完成渲染**（不联网取图、不上传），
  **不提供任何素材的单独导出 / 分发功能**。
- 使用者需自行确认在自己的使用场景下合规；本项目不对素材的再分发承担责任。

## 7. 数据来源：水鱼查分器

- https://www.diving-fish.com/maimaidx/prober/
- 本 App 通过其**公开接口**只读成绩数据，不写入、不修改、不上传任何用户数据到第三方。
- 凭据（OAuth token）仅保存在本机 `SharedPreferences`，不会离开设备。

## 8. AI 服务（用户自行配置）

App 不内置任何 API Key。用户需在「设置」里填自己的 OpenAI 兼容服务（DeepSeek / Gemini / Claude /
OpenAI / Kimi 等，也支持自定义），提示词与锐评正文由该服务提供方生成。
本项目不对模型输出内容负责，也不承担由此产生的费用。

## 9. 与本仓库无关的部分

桌面版 / 网页版（`webui.py` + NoneBot 插件形态）不在本仓库内，其许可与维护跟上上游项目一致。
