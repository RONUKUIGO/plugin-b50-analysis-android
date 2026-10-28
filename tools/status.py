#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一条命令看清工程现状 —— 新会话 / 换人接手时**先跑这个**。

    cd /sdcard/Tools/LSPilot/projects/b50-android
    python3 tools/status.py              # 全量检查
    python3 tools/status.py --no-device  # 跳过设备检查（不在手机上 / 没 root 时）

它替你把「文档里写的」和「磁盘上真实的」对一遍，避免新会话基于过期信息乱改：

 1. 工程关键文件是否齐（gradle / manifest / 三个 assets / jniLibs / py/ / tools）
 2. 版本号 PACK_VERSION / PY_VERSION，且与 HANDOFF.md 里写的一致
 3. py/ 与 assets/pycode.zip 内容是否一致（复用 tools/pack_pycode.py 的指纹）
 4. py/ 与归档基线（桌面版 v1.3 快照）是否漂移（逐字节 + roast.py 核心区）
 5. 打包依赖（Pillow / pyftsubset / 素材源 / 现有 pack.zip 体检）—— 改素材时才会用到，但缺了会打出坏包
 6. 设备上已安装的 com.b50.roast 是否就是工作区最新构建产物（比 md5）
 7. 文档 / 工具在位情况 + mtime

退出码：0 = 全绿；1 = 有需要处理的问题（可直接当门禁）。
"""
import hashlib
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

try:                      # 归档基线路径由 pack_pycode 统一维护，避免两处写死不一致
    from pack_pycode import DESKTOP_ARCHIVE as DESKTOP
except ImportError:
    DESKTOP = "/sdcard/Tools/LSPilot/archive/b50-desktop-v1.3"
PKG = "com.b50.roast"
BUILD_APK = os.path.join(ROOT, "app/build/outputs/apk/debug/app-debug.apk")
DIST_APK = "/sdcard/Tools/LSPilot/outputs/apk/b50-roast-debug.apk"
HANDOFF = os.path.join(ROOT, "HANDOFF.md")

problems = []
warns = []


def head(title):
    print("\n== %s ==" % title)


def ok(msg):
    print("  ✓ " + msg)


def bad(msg):
    print("  ✗ " + msg)
    problems.append(msg)


def warn(msg):
    print("  ! " + msg)
    warns.append(msg)


def info(msg):
    print("  · " + msg)


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def human(path):
    n = os.path.getsize(path)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return ("%.0f %s" % (n, unit)) if unit == "B" else ("%.1f %s" % (n, unit))
        n /= 1024.0


def const_of(rel_path, name):
    """从 kt 源码里抠 `const val NAME = "x"`"""
    p = os.path.join(ROOT, rel_path)
    try:
        s = open(p, encoding="utf-8").read()
    except OSError:
        return None
    m = re.search(name + r'\s*=\s*"([^"]+)"', s)
    return m.group(1) if m else None


# ---------------------------------------------------------------- 1. 必备文件

REQUIRED = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "app/build.gradle.kts",
    "app/proguard-rules.pro",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/assets/pack.zip",
    "app/src/main/assets/py_runtime.zip",
    "app/src/main/assets/pycode.zip",
    "app/src/main/jniLibs/arm64-v8a/libpy_home.so",
    "app/src/main/jniLibs/arm64-v8a/libpython3.14.so",
    "app/src/main/java/com/b50/roast/MainActivity.kt",
    "app/src/main/java/com/b50/roast/AppState.kt",
    "py/render_worker.py",
    "py/roast.py",
    "tools/pack_pycode.py",
    "tools/pack_assets.py",
    "tools/build_python_runtime.sh",
    "tools/ascii_view.py",
    "tools/shadow_probe.py",
    "tools/make_icons.py",
    "tools/reclaim_mem.sh",
    "HANDOFF.md",
    "README.md",
]


def check_files():
    head("1. 工程文件（%s）" % ROOT)
    missing = [r for r in REQUIRED if not os.path.exists(os.path.join(ROOT, r))]
    if missing:
        for r in missing:
            bad("缺文件：" + r)
    else:
        ok("%d 个关键文件全在" % len(REQUIRED))
    for r in ("app/src/main/assets/pack.zip",
              "app/src/main/assets/py_runtime.zip",
              "app/src/main/assets/pycode.zip"):
        p = os.path.join(ROOT, r)
        if os.path.exists(p):
            info("%-34s %8s  md5=%s" % (os.path.basename(r), human(p), md5(p)[:12]))


# ---------------------------------------------------------------- 2. 版本号

def check_versions():
    head("2. 版本号（改了 assets 忘了 +1 是本项目最常见的坑）")
    pack_v = const_of("app/src/main/java/com/b50/roast/data/ResourceInstaller.kt", "PACK_VERSION")
    py_v = const_of("app/src/main/java/com/b50/roast/data/PythonRunner.kt", "PY_VERSION")
    info("PACK_VERSION = %s   （素材/曲绘/字体变了要 +1）" % pack_v)
    info("PY_VERSION   = %s   （py/ 或 Python 运行时变了要 +1）" % py_v)

    if not (pack_v and py_v):
        bad("没能从源码里读到版本号常量")
        return

    try:
        doc = open(HANDOFF, encoding="utf-8").read()
    except OSError:
        warn("读不到 HANDOFF.md，跳过文档一致性检查")
        return
    for label, code_v in (("PACK_VERSION", pack_v), ("PY_VERSION", py_v)):
        m = re.search(label + r"`?（当前 \*\*(\d+)\*\*", doc)
        if not m:
            warn("HANDOFF.md 里找不到 %s 的当前值说明" % label)
        elif m.group(1) != str(code_v):
            bad("HANDOFF.md 写 %s 当前 **%s**，代码是 %s（文档滞后，会误导下一个会话）"
                % (label, m.group(1), code_v))
        else:
            ok("%s 文档与代码一致（%s）" % (label, code_v))


# ---------------------------------------------------------------- 3 & 4. py/ 一致性

def check_pycode():
    head("3. py/ 与 assets/pycode.zip")
    try:
        import pack_pycode as pp
    except ImportError as exc:
        bad("导入 pack_pycode 失败：%s" % exc)
        return
    zip_path = os.path.join(ROOT, "app/src/main/assets/pycode.zip")
    if not os.path.exists(zip_path):
        bad("pycode.zip 不存在")
        return
    items = pp.collect(os.path.join(ROOT, "py"))
    src = pp.source_fingerprints(items)
    cur = pp.zip_fingerprints(zip_path)
    only_src, only_zip, changed = pp.diff_fingerprints(src, cur)
    if only_src or only_zip or changed:
        for k in only_src:
            bad("py/ 里有、zip 里没有：" + k)
        for k in only_zip:
            bad("zip 里有、py/ 里没有：" + k)
        for k in changed:
            bad("内容不同：" + k)
        info("修法：python3 tools/pack_pycode.py（然后 PY_VERSION + 1）")
    else:
        ok("一致：%d 个文件，现有 zip 就是 py/ 打出来的" % len(src))

    head("4. py/ 与归档基线（%s）" % DESKTOP)
    if not os.path.isdir(DESKTOP):
        warn("归档基线目录不可见（%s），跳过漂移检查" % DESKTOP)
        return
    drifted = []
    same = 0
    for name in pp.DESKTOP_SAME:
        a, b = os.path.join(ROOT, "py", name), os.path.join(DESKTOP, name)
        if not (os.path.exists(a) and os.path.exists(b)):
            continue
        if open(a, "rb").read() == open(b, "rb").read():
            same += 1
        else:
            drifted.append(name)
    if drifted:
        for name in drifted:
            bad("与桌面版不一致：" + name)
        info("修法：把桌面版对应文件拷进 py/，然后重打包")
    else:
        ok("%d 个文件与桌面版逐字节一致" % same)

    try:
        app_core = pp.roast_core(open(os.path.join(ROOT, "py", "roast.py"), encoding="utf-8").read(), False)
        desk_core = pp.roast_core(open(os.path.join(DESKTOP, "roast.py"), encoding="utf-8").read(), True)
    except OSError:
        warn("读不到 roast.py，跳过核心区比对")
        return
    if app_core == desk_core:
        ok("roast.py 提示词核心区与桌面版一致（%d 行）" % (app_core.count("\n") + 1))
    else:
        bad("roast.py 核心区已漂移（桌面版改了提示词没同步到 py/）")
        info("修法：python3 tools/pack_pycode.py --check-desktop %s 看首个差异行" % DESKTOP)


# ---------------------------------------------------------------- 4.5 打包依赖

def check_pack_deps():
    head("4.5 打包依赖（改素材/重打包时才用，缺了会静默产出坏包）")
    # Pillow
    try:
        import PIL
        from PIL import features
        webp = features.check("webp")
        (ok if webp else bad)("Pillow %s（WebP %s）" % (PIL.__version__, "可用" if webp else "不可用 ← pack.zip 全是 WebP"))
    except Exception as exc:  # noqa: BLE001
        bad("Pillow 不可用：%s" % exc)
    # pyftsubset
    import shutil as _sh
    p = _sh.which("pyftsubset")
    (ok if p else bad)("pyftsubset %s" % (p or "未找到 ← pip install fonttools"))
    # 素材源 + 现有 pack.zip 体检
    try:
        sys.path.insert(0, os.path.join(ROOT, "tools"))
        import fetch_assets as fa
        found = None
        for label, path in fa.candidates(os.path.join(ROOT, "assets"), use_archive=True):
            good, why = fa.look_like_assets(path)
            if good:
                found = (label, path, why)
                break
        if found:
            ok("素材源：%s（%s）" % (found[0], found[2]))
        else:
            warn("本机没有可用素材源（改素材时先跑 tools/fetch_assets.py）")
        pk = const_of("app/src/main/java/com/b50/roast/data/ResourceInstaller.kt", "PACK_VERSION")
        info("素材版本代号 cn:%s（App 里显示这个；上游更新后要同步改 PACK_VERSION）" % (pk or "?"))
        pack = os.path.join(ROOT, "app/src/main/assets/pack.zip")
        if os.path.exists(pack):
            good, why, _ = fa.inspect_zip(pack)
            (ok if good else bad)("现有 pack.zip：%s" % why)
    except Exception as exc:  # noqa: BLE001
        warn("素材检查跳过（%s）" % exc)


# ---------------------------------------------------------------- 5. 设备

def sh(cmd, timeout=20):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return (p.stdout or "") + (p.stderr or "")
    except Exception:
        return ""


def check_device():
    head("5. 设备上的已安装版本 vs 工作区构建产物")
    out = sh(["/system/bin/pm", "path", PKG])
    if "package:" not in out:
        warn("读不到 %s 的安装信息（无 root / 不在手机环境），跳过" % PKG)
        return
    apk = out.strip().split("package:", 1)[1].splitlines()[0].strip()
    dump = sh(["/system/bin/dumpsys", "package", PKG])
    ver = re.search(r"versionName=(\S+)", dump)
    code = re.search(r"versionCode=(\d+)", dump)
    upd = re.search(r"lastUpdateTime=([0-9:\- ]+)", dump)
    info("已安装：%s  versionName=%s versionCode=%s" % (
        apk, ver.group(1) if ver else "?", code.group(1) if code else "?"))
    if upd:
        info("安装时间：%s" % upd.group(1).strip())

    try:
        inst_md5 = md5(apk)
    except OSError:
        warn("读不到已安装 APK（权限问题），跳过 md5 比较")
        return
    info("已安装 APK md5 = %s（%s）" % (inst_md5, human(apk)))

    candidates = [p for p in (BUILD_APK, DIST_APK) if os.path.exists(p)]
    if not candidates:
        warn("工作区还没有构建产物，无法比较（先跑 build_android.sh）")
        return
    for p in candidates:
        same = md5(p) == inst_md5
        tag = os.path.basename(p) if p == BUILD_APK else "outputs/apk/b50-roast-debug.apk"
        (ok if same else info)("%s  %s%s" % (tag, "= 已安装" if same else "≠ 已安装", ""))
    if all(md5(p) != inst_md5 for p in candidates):
        warn("工作区的 APK 与设备上装的不同 —— 要么改了没装，要么装了旧包")


# ---------------------------------------------------------------- 6. 文档地图

def check_docs():
    head("6. 文档与工具（接手先读这几个）")
    docs = [
        ("HANDOFF.md", "**权威交接文档**：已验证事实 / 架构 / 坑 / 文件索引 / 构建 / 调试 / 待办"),
        ("README.md", "对外 README（安装 / 隐私 / 许可）；开发文档见 docs/DEVELOPMENT.md"),
        ("py/README.md", "App 内嵌 Python 真源说明：来源、同步、改完做什么"),
        ("tools/pack_pycode.py", "py/ → pycode.zip 打包 + 一致性门禁"),
        ("tools/fetch_assets.py", "素材获取：本地/归档 zip/下载，证书过期也能下"),
        ("tools/pack_assets.py", "素材 → pack.zip（前置检查 + 产物校验 + 版本号一致性）"),
        ("tools/check_upstream.py", "上游同步：代码 4 文件三态比对 + miuix 版本"),
        ("tools/build_python_runtime.sh", "Python 运行时 → py_runtime.zip + jniLibs"),
        ("tools/ascii_view.py", "截图 → ASCII 灰度图（不看图也能判断布局）"),
        ("tools/shadow_probe.py", "像素探针：量底色/边缘剖面/阴影带/脏边"),
        ("tools/make_icons.py", "启动图标生成 + 安全区/对比度/遮罩校验（18 个文件）"),
        ("tools/reclaim_mem.sh", "回收内存：停 Gradle/Kotlin 守护进程 + am kill-all（含前后对比）"),
        ("tools/status.py", "本脚本"),
    ]
    for rel, desc in docs:
        p = os.path.join(ROOT, rel)
        if not os.path.exists(p):
            bad("缺 " + rel)
            continue
        mtime = subprocess.run(["/system/bin/sh", "-c", "date -r '%s' '+%%m-%%d %%H:%%M'" % p],
                               capture_output=True, text=True)
        when = mtime.stdout.strip() or "?"
        info("%-30s %s  %s" % (rel, when, desc))

    todos = []
    try:
        doc = open(HANDOFF, encoding="utf-8").read()
        m = re.search(r"## 7\. 待办.*?\n(.*?)\n###", doc, re.S)
        if m:
            for line in m.group(1).splitlines():
                if re.match(r"^\d+\.", line.strip()):
                    todos.append(line.strip()[:110])
    except OSError:
        pass
    if todos:
        print("\n  HANDOFF 第 7 节待办：")
        for t in todos:
            print("   - " + t)


# ---------------------------------------------------------------- main

def main(argv):
    skip_device = "--no-device" in argv
    print("b50 安卓工程自检  ·  %s" % ROOT)
    check_files()
    check_versions()
    check_pycode()
    check_pack_deps()
    if not skip_device:
        check_device()
    check_docs()

    print("\n" + "=" * 60)
    if problems:
        print("✗ %d 个问题，%d 个提醒" % (len(problems), len(warns)))
        for p in problems:
            print("   - " + p)
        print("文档入口：%s/HANDOFF.md" % ROOT)
        return 1
    print("✓ 全绿（%d 个提醒）" % len(warns))
    print("下一步：读 HANDOFF.md 第 7 节「待办」，或按第 8 节重建产物。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
