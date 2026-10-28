#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""上游同步检查：代码上游（b50-analysis）+ UI 上游（miuix）。

两个上游（2026-10-28 用户指定为长期依赖）：
    代码  https://github.com/HanYaaaaaaa/nonebot-plugin-b50-analysis
          → 我们 `py/vendor/mai/{context_builder,paths,render,utils}.py` 的来源（vendored，见 DECISIONS D2）
    UI    https://github.com/compose-miuix-ui/miuix
          → `app/build.gradle.kts` 里的 `top.yukonga.miuix.kmp:miuix-ui`，**今后开发 App 的默认 UI 库**

用法
----
    python3 tools/check_upstream.py             # 只报告（默认，不联网改任何东西）
    python3 tools/check_upstream.py --fetch     # 把上游文件下载到 /tmp/b50-upstream 供人工 diff
    python3 tools/check_upstream.py --offline   # 只看本地（不请求网络）

输出
----
  - 代码上游：最新 commit、4 个文件与本地 vendor 的差异（行数增删 / 是否一致）
  - UI 上游：GitHub 最新 release、Maven Central 最新版本 vs 我们 gradle 里写的版本

退出码：0 = 都与上游一致 / 已是最新；1 = 有可同步的更新（不影响构建，只提醒）。
"""
import argparse
import difflib
import json
import os
import re
import ssl
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE_DIR = "/tmp/b50-upstream"
#: 基线快照：记录「当初 vendored 时的上游 commit + 文件 sha256 + 当时的本地 sha256」
#: 有了它才能区分「上游更新了」（要搬）和「我们本地改过」（正常）。随工程走，可追踪。
BASELINE = os.path.join(ROOT, "tools", "upstream-baseline.json")

CODE_REPO = "HanYaaaaaaa/nonebot-plugin-b50-analysis"
CODE_BRANCH = "main"
#: 上游包目录名 → 我们 vendor 目录
UPSTREAM_PKG = "nonebot_plugin_b50_analysis"
VENDOR_DIR = os.path.join(ROOT, "py", "vendor", "mai")
VENDOR_FILES = ["context_builder.py", "paths.py", "render.py", "utils.py"]

UI_REPO = "compose-miuix-ui/miuix"
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")
MVN_META = ("https://repo1.maven.org/maven2/top/yukonga/miuix/kmp/"
            "miuix-ui/maven-metadata.xml")


# ---------------------------------------------------------------- HTTP

def get(url, timeout=25):
    req = urllib.request.Request(url, headers={
        "User-Agent": "b50-android-check-upstream/1.0",
        "Accept": "application/vnd.github+json, text/plain, */*",
    })
    ctx = ssl.create_default_context()
    with urllib.request.urlopen(req, context=ctx, timeout=timeout) as resp:
        return resp.read().decode("utf-8", "replace")


def get_json(url, timeout=25):
    return json.loads(get(url, timeout))


def sha256(text):
    import hashlib
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_baseline():
    try:
        return json.loads(open(BASELINE, encoding="utf-8").read())
    except Exception:  # noqa: BLE001
        return {}


def save_baseline(data):
    with open(BASELINE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def human_diff(local_text, upstream_text):
    """返回 (增行, 删行, 首个差异描述)"""
    a = local_text.splitlines()
    b = upstream_text.splitlines()
    diff = list(difflib.unified_diff(a, b, lineterm="", n=1))
    added = sum(1 for l in diff if l.startswith("+") and not l.startswith("+++"))
    removed = sum(1 for l in diff if l.startswith("-") and not l.startswith("---"))
    first = ""
    for line in diff:
        if line.startswith(("+", "-")) and not line.startswith(("+++", "---")):
            first = line[:100]
            break
    return added, removed, first


# ---------------------------------------------------------------- 代码上游

def check_code(offline=False, fetch=False):
    print("\n== 1. 代码上游：%s ==" % CODE_REPO)
    problems = []

    if not offline:
        try:
            info = get_json("https://api.github.com/repos/%s" % CODE_REPO)
            print("  仓库最新 push：%s（默认分支 %s）" % (info["pushed_at"][:19], info["default_branch"]))
            commits = get_json("https://api.github.com/repos/%s/commits?per_page=1&sha=%s"
                               % (CODE_REPO, CODE_BRANCH))
            if commits:
                c = commits[0]
                print("  最新 commit：%s  %s" % (c["sha"][:8], c["commit"]["author"]["date"][:19]))
                print("    %s" % c["commit"]["message"].splitlines()[0][:90])
        except Exception as exc:  # noqa: BLE001
            print("  ! 拿不到上游信息（%s）—— 按 --offline 处理" % exc)
            offline = True

    os.makedirs(CACHE_DIR, exist_ok=True)
    for name in VENDOR_FILES:
        local_path = os.path.join(VENDOR_DIR, name)
        if not os.path.exists(local_path):
            print("  ✗ 本地缺 py/vendor/mai/%s" % name)
            problems.append(name)
            continue
        local_text = open(local_path, encoding="utf-8").read()

        if offline:
            print("  · %-20s 本地 %d 行（离线，未比对）" % (name, local_text.count("\n")))
            continue

        url = "https://raw.githubusercontent.com/%s/%s/%s/%s" % (CODE_REPO, CODE_BRANCH, UPSTREAM_PKG, name)
        try:
            up = get(url)
        except Exception as exc:  # noqa: BLE001
            print("  ! %-20s 下载失败：%s" % (name, exc))
            continue
        if fetch:
            with open(os.path.join(CACHE_DIR, name), "w", encoding="utf-8") as f:
                f.write(up)

        base = (load_baseline().get("code", {}).get("files", {}) or {}).get(name, {})
        up_sha, loc_sha = sha256(up), sha256(local_text)
        upstream_changed = bool(base.get("upstream_sha")) and base["upstream_sha"] != up_sha
        local_changed = bool(base.get("local_sha")) and base["local_sha"] != loc_sha

        if up == local_text:
            print("  ✓ %-20s 与上游完全一致（%d 行）" % (name, up.count("\n")))
            continue
        added, removed, first = human_diff(local_text, up)
        if not base:
            print("  ? %-20s 上游 %d 行 / 本地 %d 行；差 +%d / -%d（无基线，无法判断谁变了）"
                  % (name, up.count("\n"), local_text.count("\n"), added, removed))
            problems.append(name)
        elif upstream_changed:
            print("  ↑ %-20s **上游更新了**；本地 %d 行 / 上游 %d 行；差 +%d / -%d"
                  % (name, local_text.count("\n"), up.count("\n"), added, removed))
            if first:
                print("      首个差异：%s" % first)
            problems.append(name)
        else:
            # 上游没动，是我们本地本地化过 —— 正常，不必处理
            print("  · %-20s 本地已本地化（上游未变；差 +%d / -%d）%s"
                  % (name, added, removed, "，本地也已偏离基线" if local_changed else ""))
        if first:
            print("      首个差异：%s" % first)
        print("      对比：diff %s/%s  https://github.com/%s/blob/%s/%s/%s"
              % (CACHE_DIR if fetch else "<上游>", name, CODE_REPO, CODE_BRANCH, UPSTREAM_PKG, name))

    if problems:
        print("\n  ⚠ %d 个文件与上游不同。" % len(problems))
        print("     主意：这些文件是 **vendored + 本地化过** 的（roast.py 裁剪、paths 改写等），")
        print("     不要直接覆盖。先 --fetch 拉下来人工 diff，只把「确实是上游修好的 bug」搬回来，")
        print("     搬完跑 python3 tools/pack_pycode.py --check-desktop /  status.py 验证。")
    return problems


# ---------------------------------------------------------------- UI 上游

def gradle_miuix_version():
    try:
        text = open(GRADLE, encoding="utf-8").read()
    except OSError:
        return None
    m = re.search(r'miuix[-a-z]*:([0-9][^"\s)]*)', text) or re.search(r'miuix[^:]*:\s*([0-9][^"\s]*)', text)
    return m.group(1) if m else None


def check_ui(offline=False):
    print("\n== 2. UI 上游：%s ==" % UI_REPO)
    cur = gradle_miuix_version()
    print("  我们用的版本：%s（app/build.gradle.kts）" % (cur or "未找到"))
    if offline:
        print("  · 离线模式，未检查上游")
        return []

    problems = []
    try:
        rel = get_json("https://api.github.com/repos/%s/releases?per_page=3" % UI_REPO)
        if rel:
            latest = rel[0]
            print("  GitHub 最新 release：%s（%s）%s" % (
                latest["tag_name"], latest["published_at"][:10],
                "  ← 预发布" if latest.get("prerelease") else ""))
            tag_ver = latest["tag_name"].lstrip("v")
            if cur and tag_ver != cur:
                problems.append("GitHub release %s ≠ 我们 %s" % (latest["tag_name"], cur))
    except Exception as exc:  # noqa: BLE001
        print("  ! 查 GitHub release 失败：%s" % exc)

    try:
        meta = get(MVN_META)
        vers = re.findall(r"<version>([^<]+)</version>", meta)
        stable = [v for v in vers if not re.search(r"[a-zA-Z]", v)]
        latest_mvn = stable[-1] if stable else (vers[-1] if vers else "")
        print("  Maven Central 最新：%s（共 %d 个版本）" % (latest_mvn, len(vers)))
        if cur and latest_mvn and latest_mvn != cur:
            problems.append("Maven 最新 %s ≠ 我们 %s" % (latest_mvn, cur))
    except Exception as exc:  # noqa: BLE001
        print("  ! 查 Maven Central 失败：%s" % exc)

    if problems:
        print("\n  ⚠ 有更新的 miuix 可用：")
        for p in problems:
            print("     - %s" % p)
        print("     升级方式：改 app/build.gradle.kts 的 miuix-ui 版本 → 重新构建 →")
        print("     重点回归：设置页/生成页的按钮、TextField、TopAppBar（miuix 常有 API 微调）")
    elif cur:
        print("  ✓ 已是最新（%s）" % cur)
    return problems


# ---------------------------------------------------------------- main

def save_baseline_now():
    """记录当前上游状态作为基线（首次接入 / 确认同步完就跑一次）。"""
    print("\n== 记录上游基线 ==")
    data = load_baseline() or {}
    data.setdefault("code", {})
    data["code"]["repo"] = CODE_REPO
    data["code"]["branch"] = CODE_BRANCH
    try:
        commits = get_json("https://api.github.com/repos/%s/commits?per_page=1&sha=%s"
                           % (CODE_REPO, CODE_BRANCH))
        data["code"]["commit"] = commits[0]["sha"] if commits else ""
        data["code"]["commit_date"] = commits[0]["commit"]["author"]["date"] if commits else ""
    except Exception as exc:  # noqa: BLE001
        print("  ! 拿不到上游 commit：%s" % exc)
    import datetime
    data["code"]["saved_at"] = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")

    files = {}
    for name in VENDOR_FILES:
        url = "https://raw.githubusercontent.com/%s/%s/%s/%s" % (CODE_REPO, CODE_BRANCH, UPSTREAM_PKG, name)
        try:
            up = get(url)
        except Exception as exc:  # noqa: BLE001
            print("  ! %s 下载失败：%s" % (name, exc))
            continue
        local_path = os.path.join(VENDOR_DIR, name)
        local = open(local_path, encoding="utf-8").read() if os.path.exists(local_path) else ""
        files[name] = {"upstream_sha": sha256(up), "local_sha": sha256(local)}
        print("  ✓ %-20s 上游 %d 行 / 本地 %d 行" % (name, up.count("\n"), local.count("\n")))
    data["code"]["files"] = files

    ui_v = gradle_miuix_version()
    data.setdefault("ui", {})
    data["ui"]["repo"] = UI_REPO
    data["ui"]["version"] = ui_v
    data["ui"]["saved_at"] = data["code"]["saved_at"]
    save_baseline(data)
    print("\n✓ 基线已写入 %s" % BASELINE)
    print("  上游 commit：%s" % data["code"].get("commit", "")[:8])
    print("  UI 版本：%s" % ui_v)
    print("  以后跑 tools/check_upstream.py 就能区分「上游更新」和「我们本地改过」。")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="上游同步检查（代码 + UI 库）",
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    ap.add_argument("--fetch", action="store_true", help="把上游文件下到 %s 供人工 diff" % CACHE_DIR)
    ap.add_argument("--offline", action="store_true", help="不联网，只看本地状态")
    ap.add_argument("--save-baseline", action="store_true",
                    help="把当前上游 commit + 各文件 sha256 记进 tools/upstream-baseline.json（首次接入 / 确认同步后跑）")
    args = ap.parse_args(argv)

    print("上游同步检查  ·  %s" % ROOT)

    if args.save_baseline:
        return save_baseline_now()

    code_problems = check_code(offline=args.offline, fetch=args.fetch)
    ui_problems = check_ui(offline=args.offline)

    print("\n" + "=" * 58)
    total = len(code_problems) + len(ui_problems)
    if total:
        print("⚠ %d 项可关注（代码 %d / UI %d）" % (total, len(code_problems), len(ui_problems)))
        if args.fetch:
            print("上游文件已下到 %s，可 diff 后手工搬回" % CACHE_DIR)
        return 1
    print("✓ 与上游一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
