#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""py/ → app/src/main/assets/pycode.zip（唯一真源 → 构建产物，可复现）

背景
----
pycode.zip 曾经是黑盒：磁盘上找不到 render_worker.py 的源码，只有打包好的 zip。
本脚本把关系倒过来 —— **py/ 是唯一真源，pycode.zip 是可随时重建的产物**。

用法
----
    python3 tools/pack_pycode.py                     # py/ → assets/pycode.zip（内容真变了才写盘）
    python3 tools/pack_pycode.py --check             # 只校验：py/ 与现有 zip 内容是否一致（不写）
    python3 tools/pack_pycode.py --check-desktop   # 检查与归档基线（桌面版 v1.3 快照）是否漂移
    python3 tools/pack_pycode.py --check-desktop /some/dir   # 也可以指定别的基线目录
    python3 tools/pack_pycode.py --list              # 列出 py/ 与 zip 的内容表
    python3 tools/pack_pycode.py --force             # 即使内容相同也重写 zip
    python3 tools/pack_pycode.py --out /tmp/x.zip    # 打到别的路径（不碰 assets）

退出码：0 = 一致 / 成功；1 = 有差异或出错（可直接用在脚本里当门禁）

设计要点
--------
1. **确定性打包**：条目顺序排序、时间戳固定 1981-01-01、权限固定 0644/0755，
   同一份 py/ 反复打出来的 zip 字节完全一致（便于 diff、便于 CI、便于追责）。
2. 与历史产物**不保证字节一致**（当年那个 zip 用的是真实 mtime + os.walk 顺序），
   所以内容比较一律走 CRC32 + 文件大小，而不是比字节。
3. 只比较「非目录条目」：`vendor/` 这类目录条目只是为了老解压器兼容，纯摆设。
4. 纯标准库（不用 Pillow / 不用 httpx），Termux、proot、桌面都能跑。

改了 py/ 之后的标准动作（忘了 App 不会生效）：
    python3 tools/pack_pycode.py --check-desktop   # 先看有没有跟归档基线漂移
    python3 tools/pack_pycode.py                                  # 重打 zip
    # PythonRunner.PY_VERSION + 1，否则 App 升级后不会重新解压
    sh /sdcard/Tools/LSPilot/scripts/build_android.sh --install
"""
import argparse
import os
import sys
import zipfile
import zlib

# ---------------------------------------------------------------- 常量

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PY_DIR = os.path.join(ROOT, "py")
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "pycode.zip")

#: 打进 zip 的文件后缀白名单（py/ 里的 README.md、__pycache__ 之类不进包）
INCLUDE_EXT = {".py"}

#: 桌面版已于 2026-10-28 归档（不再开发），这份只读快照是 `py/` 的对账基线
DESKTOP_ARCHIVE = "/sdcard/Tools/LSPilot/archive/b50-desktop-v1.3"

FIXED_DT = (1981, 1, 1, 0, 0, 0)          # zip 能可靠表示的最早时间戳
EXTERNAL_FILE = 0o100644 << 16            # rw-r--r--
EXTERNAL_DIR = (0o40755 << 16) | 0x10     # drwxr-xr-x + MS-DOS 目录位
COMPRESS_LEVEL = 9

#: 应当与桌面版**逐字节相同**的文件
DESKTOP_SAME = [
    "fetch.py",
    "profile.py",
    "vendor/__init__.py",
    "vendor/mai/__init__.py",
    "vendor/mai/paths.py",
    "vendor/mai/render.py",
    "vendor/mai/utils.py",
    "vendor/mai/context_builder.py",
]

#: App 专有的**裁剪版**（只保留提示词构建，HTTP 交给 Kotlin LlmClient）
DESKTOP_TRIMMED = ["roast.py"]

#: 桌面版根本没有的文件
DESKTOP_ONLY_IN_APP = ["render_worker.py"]


# ---------------------------------------------------------------- 收集与打包

def collect(py_dir=PY_DIR):
    """按确定顺序收集待打包条目。

    返回 [(name, abspath, is_dir)]：先同级目录条目、再该目录下的文件（各自按名字排序）。
    """
    items = []
    if not os.path.isdir(py_dir):
        raise SystemExit("找不到真源目录：%s" % py_dir)
    for dirpath, dirnames, filenames in os.walk(py_dir):
        dirnames.sort()
        dirnames[:] = [d for d in dirnames if not d.startswith(".") and d != "__pycache__"]
        rel_dir = os.path.relpath(dirpath, py_dir)
        if rel_dir != ".":
            items.append((rel_dir.replace(os.sep, "/"), dirpath, True))
        for fn in sorted(filenames):
            if os.path.splitext(fn)[1].lower() not in INCLUDE_EXT:
                continue
            if fn.startswith("."):
                continue
            abspath = os.path.join(dirpath, fn)
            rel = os.path.relpath(abspath, py_dir).replace(os.sep, "/")
            items.append((rel, abspath, False))
    return items


def build_zip(out_path, items, compresslevel=COMPRESS_LEVEL):
    """确定性写出 zip（先写 .tmp 再原子替换，避免打一半留下坏包）。"""
    out_dir = os.path.dirname(os.path.abspath(out_path))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    tmp = out_path + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=compresslevel) as z:
        for name, path, is_dir in items:
            if is_dir:
                zi = zipfile.ZipInfo(name + "/", FIXED_DT)
                zi.external_attr = EXTERNAL_DIR
                zi.compress_type = zipfile.ZIP_STORED
                z.writestr(zi, b"")
            else:
                zi = zipfile.ZipInfo(name, FIXED_DT)
                zi.external_attr = EXTERNAL_FILE
                zi.compress_type = zipfile.ZIP_DEFLATED
                with open(path, "rb") as f:
                    z.writestr(zi, f.read())
    os.replace(tmp, out_path)


# ---------------------------------------------------------------- 内容指纹

def source_fingerprints(items):
    """py/ → {name: (crc32, size)}"""
    out = {}
    for name, path, is_dir in items:
        if is_dir:
            continue
        with open(path, "rb") as f:
            data = f.read()
        out[name] = (zlib.crc32(data) & 0xFFFFFFFF, len(data))
    return out


def zip_fingerprints(path):
    """已有 zip → {name: (crc32, size)}，忽略目录条目"""
    out = {}
    with zipfile.ZipFile(path) as z:
        for i in z.infolist():
            if i.is_dir():
                continue
            out[i.filename] = (i.CRC & 0xFFFFFFFF, i.file_size)
    return out


def diff_fingerprints(left, right):
    """返回 (只在 left, 只在 right, CRC/大小不同)"""
    only_left = sorted(set(left) - set(right))
    only_right = sorted(set(right) - set(left))
    changed = sorted(k for k in set(left) & set(right) if left[k] != right[k])
    return only_left, only_right, changed


def print_fingerprint_table(title, left_name, right_name, only_left, only_right, changed):
    print("\n== %s ==" % title)
    print("   %-24s %-24s  结果" % (left_name, right_name))
    for k in only_left:
        print("   %-24s %-24s  只有左边有" % (k, "-"))
    for k in only_right:
        print("   %-24s %-24s  只有右边有" % ("-", k))
    for k in changed:
        print("   %-24s %-24s  内容不同" % (k, k))
    if not (only_left or only_right or changed):
        print("   （全部一致）")


# ---------------------------------------------------------------- 与桌面版对账

def split_docstring(text):
    """切出文件开头的第一个三引号块，返回 (docstring, rest)。找不到就整篇当正文。"""
    i = text.find('"""')
    if i < 0:
        return "", text
    j = text.find('"""', i + 3)
    if j < 0:
        return "", text
    return text[i:j + 3], text[:i] + text[j + 3:]


def normalize_code(text):
    """去行尾空白 + 连续空行压成一个 + 去首尾空行。"""
    out = []
    blank = False
    for line in text.splitlines():
        line = line.rstrip()
        if not line:
            if blank:
                continue
            blank = True
        else:
            blank = False
        out.append(line)
    return "\n".join(out).strip()


def roast_core(text, cut_at_llm):
    """roast.py 的核心区：去掉文件头 docstring、可选的 LLM 部分、以及 import httpx。

    App 版整篇都是「提示词构建」；桌面版在其后还有 _post/generate/_adjust_length，
    所以桌面版要截到 `class LLMError` 之前再比。
    """
    _, rest = split_docstring(text)
    if cut_at_llm:
        k = rest.find("\nclass LLMError")
        if k >= 0:
            rest = rest[:k]
    rest = "\n".join(line for line in rest.splitlines() if line.strip() != "import httpx")
    return normalize_code(rest)


def check_desktop(desktop_dir, py_dir=PY_DIR):
    """检查 App 版 python 与桌面版是否漂移。返回 True 表示全部符合预期。"""
    if not os.path.isdir(desktop_dir):
        print("找不到桌面版目录：%s" % desktop_dir)
        return False
    ok = True

    print("\n== 应与桌面版逐字节相同 ==")
    for name in DESKTOP_SAME:
        a = os.path.join(py_dir, name)
        b = os.path.join(desktop_dir, name)
        if not os.path.exists(a):
            print("   ✗ %-40s py/ 里缺这个文件" % name)
            ok = False
            continue
        if not os.path.exists(b):
            print("   ? %-40s 桌面版没有，跳过" % name)
            continue
        with open(a, "rb") as f:
            da = f.read()
        with open(b, "rb") as f:
            db = f.read()
        if da == db:
            print("   ✓ %-40s 一致 (%d 字节)" % (name, len(da)))
        else:
            print("   ✗ %-40s 已漂移（py/ 版 %d 字节 / 桌面版 %d 字节）" % (name, len(da), len(db)))
            ok = False

    print("\n== 裁剪版：只比「提示词构建」核心区 ==")
    for name in DESKTOP_TRIMMED:
        a = os.path.join(py_dir, name)
        b = os.path.join(desktop_dir, name)
        if not (os.path.exists(a) and os.path.exists(b)):
            print("   ? %-40s 缺文件，跳过" % name)
            continue
        with open(a, encoding="utf-8") as f:
            app_core = roast_core(f.read(), cut_at_llm=False)
        with open(b, encoding="utf-8") as f:
            desk_core = roast_core(f.read(), cut_at_llm=True)
        if app_core == desk_core:
            print("   ✓ %-40s 核心区一致（%d 行）" % (name, app_core.count("\n") + 1))
        else:
            ok = False
            print("   ✗ %-40s 核心区已漂移（桌面版改了提示词但没同步到 py/）" % name)
            al, dl = app_core.splitlines(), desk_core.splitlines()
            print("      py/ 版 %d 行 / 桌面版 %d 行" % (len(al), len(dl)))
            for i in range(max(len(al), len(dl))):
                x = al[i] if i < len(al) else "<缺行>"
                y = dl[i] if i < len(dl) else "<缺行>"
                if x != y:
                    print("      首个差异在第 %d 行：" % (i + 1))
                    print("        py/  : %s" % x[:120])
                    print("        桌面 : %s" % y[:120])
                    break

    print("\n== App 专有（桌面版没有，正常） ==")
    for name in DESKTOP_ONLY_IN_APP:
        p = os.path.join(py_dir, name)
        size = os.path.getsize(p) if os.path.exists(p) else -1
        print("   · %-40s %s" % (name, ("%d 字节" % size) if size >= 0 else "缺失！"))
        if size < 0:
            ok = False

    return ok


# ---------------------------------------------------------------- CLI

def main(argv=None):
    ap = argparse.ArgumentParser(
        description="py/ → assets/pycode.zip（可复现打包 + 与桌面版对账）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    ap.add_argument("--out", default=DEFAULT_OUT, help="输出 zip 路径，默认 assets/pycode.zip")
    ap.add_argument("--py-dir", default=PY_DIR, help="真源目录，默认工程根的 py/")
    ap.add_argument("--check", action="store_true", help="只校验 py/ 与现有 zip 是否一致，不写盘")
    ap.add_argument("--check-desktop", nargs="?", const=DESKTOP_ARCHIVE, metavar="DIR",
                    help="与桌面版对账；不带值则用归档基线 %s" % DESKTOP_ARCHIVE)
    ap.add_argument("--list", action="store_true", help="列出 py/ 与 zip 的条目表")
    ap.add_argument("--force", action="store_true", help="内容相同也重写 zip")
    args = ap.parse_args(argv)

    py_dir = os.path.abspath(args.py_dir)
    out = os.path.abspath(args.out)

    items = collect(py_dir)
    file_count = sum(1 for _, _, d in items if not d)
    print("真源：%s（%d 个文件）" % (py_dir, file_count))

    ok = True
    if args.check_desktop:
        ok = check_desktop(os.path.abspath(args.check_desktop), py_dir) and ok

    if args.list:
        print("\n== py/ 条目 ==")
        for name, path, is_dir in items:
            if is_dir:
                print("   [dir] %s" % name)
            else:
                print("   %7d  %s" % (os.path.getsize(path), name))
        if os.path.exists(out):
            print("\n== 现有 zip：%s ==" % out)
            with zipfile.ZipFile(out) as z:
                for i in z.infolist():
                    print("   %7d  %s%s" % (i.file_size, i.filename, "  [dir]" if i.is_dir() else ""))

    src_fp = source_fingerprints(items)
    if not os.path.exists(out):
        print("\n目标 zip 不存在，直接生成：%s" % out)
        build_zip(out, items)
        print("✓ 已生成（%d 个文件）" % len(src_fp))
        return 0 if ok else 1

    zip_fp = zip_fingerprints(out)
    only_src, only_zip, changed = diff_fingerprints(src_fp, zip_fp)
    print_fingerprint_table("py/ vs 现有 pycode.zip", "py/", "zip", only_src, only_zip, changed)
    same = not (only_src or only_zip or changed)

    if args.check:
        print("\n%s" % ("✓ 一致：现有 zip 就是 py/ 打出来的" if same else "✗ 不一致：需要重新打包"))
        return 0 if (same and ok) else 1

    if same and not args.force:
        print("\n✓ 内容无变化，未写入（要强制重写加 --force）")
        return 0 if ok else 1

    build_zip(out, items)
    print("\n✓ 已写入 %s（%d 个文件，%d 字节）" % (out, len(src_fp), os.path.getsize(out)))
    print("  下一步：PythonRunner.PY_VERSION + 1，然后 build_android.sh --install")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
