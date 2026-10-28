# py/ · App 内嵌 Python 的唯一真源

这里的内容由 `tools/pack_pycode.py` 打成 `app/src/main/assets/pycode.zip`，
App 首启解压到 `filesDir/py/app/`，由 `PythonRunner` 通过 `ProcessBuilder` 调用 `render_worker.py`。

> 在这之前 `pycode.zip` 是黑盒：整台设备上都找不到 `render_worker.py` 的源码，
> 只有打包好的 zip（另两份旧版躺在 `/tmp/pyapp/b50/`、`/data/local/tmp/pytest/b50/`，随时会被清）。
> 现在**改 py 代码一律改这里**，zip 由脚本生成，别再手工改 zip。

---

## 文件来源

| 文件 | 与**归档基线**（桌面版 v1.3 快照 `/sdcard/Tools/LSPilot/archive/b50-desktop-v1.3`）的关系 |
|---|---|
| `fetch.py` | **逐字节相同** |
| `profile.py` | **逐字节相同** |
| `vendor/__init__.py`、`vendor/mai/__init__.py` | 空文件，**逐字节相同** |
| `vendor/mai/paths.py` | **逐字节相同** |
| `vendor/mai/render.py` | **逐字节相同**（渲染器；出图与桌面版像素级一致就是因为用的同一份） |
| `vendor/mai/utils.py` | **逐字节相同** |
| `vendor/mai/context_builder.py` | **逐字节相同**（7 个分析分区） |
| `roast.py` | **裁剪版**：只保留到 `return "\n".join(lines)` 的提示词构建（218 行核心区），砍掉 `_post / _parse_json / _collapse / _normalize_result / _adjust_length / generate / LLMError` 和 `import httpx`。HTTP 由 Kotlin `LlmClient` 发（系统 CA / 超时 / 错误提示可控） |
| `render_worker.py` | **App 专有**，桌面版没有。入参/两种模式（提示词模式 / 渲染模式）见文件头 docstring |

## 常用命令

```bash
cd /sdcard/Tools/LSPilot/projects/b50-android

# 1. 改了 py/ 之后，先看有没有跟归档基线漂移（fetch/profile/vendor 必须逐字节一致）
python3 tools/pack_pycode.py --check-desktop        # 不带参数 = 用归档基线

# 2. 重打 zip（内容没变会拒绝写盘，加 --force 强制）
python3 tools/pack_pycode.py

# 3. 只校验，不写盘（CI / 提交前）
python3 tools/pack_pycode.py --check

# 4. 列条目
python3 tools/pack_pycode.py --list
```

- 退出码：`0` = 一致/成功，`1` = 有差异（可直接当门禁）。
- 打包是**确定性**的：固定顺序 + 固定时间戳 `1981-01-01` + 固定权限，同一份 `py/`
  反复打出来的 zip **字节完全一致**（实测两次 md5 相同），方便 diff 与追责。
- 因此新 zip 与历史 zip **不字节相同**（老包用了真实 mtime 和 `os.walk` 顺序），
  内容是否一致一律比 **CRC32 + 大小**，不比字节。

## 改了 python 之后必须做的事

1. `python3 tools/pack_pycode.py` 重打 zip；
2. `PythonRunner.PY_VERSION` **+1**（否则 App 升级后不会重新解压，跑的还是旧代码）；
3. `sh /sdcard/Tools/LSPilot/scripts/build_android.sh --install`。

## 与归档基线的关系（2026-10-28 起）

桌面版已**归档冻结**（`/sdcard/Tools/LSPilot/archive/b50-desktop-v1.3`，不再开发）。所以：

- 这里不再有「上游改了、要同步过来」的问题 —— `py/` 就是**唯一活跃真源**；
- `--check-desktop` 的语义变成「确认没被改成与冻结基线不同」；
- 真要改这些算法（提示词、渲染、规整），改完就在 `py/` 里定版；若改动较大，把归档快照里对应文件一起更新，或在本文档记一笔「已接受与基线偏离」，别让检查长期报红。

`roast.py` 仍是裁剪版（App 只保留提示词构建，HTTP 归 Kotlin），这点不变。
`--check-desktop` 会逐行比对「核心区」，漂移时打印首个差异行，照着手工同步即可
（同步时记得：不要引入 `import httpx`，不要带回 `_post / generate`）。
