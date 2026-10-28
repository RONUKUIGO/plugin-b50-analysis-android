#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""素材包获取：定位 / 下载 / 校验 / 解压（开发侧「资源包依赖引导」的入口）。

上游素材包（曲绘 1569 + 字体 + JSON + kb，约 384 MB 压缩 / 423 MB 解压）：
    https://dw.moant.cn:34274/assets.zip
    ⚠️ 该站证书已过期（仍可正常下载），所以本脚本默认 **跳过证书校验**；
       且上游 main 分支 README 写 https、dev 分支写 http，脚本会 **两种协议都试**。
    ⚠️ URL 里的 `34274` 就是**资源包版本号 = 机台内部版本代号**（cn 服）。
       上游每次更新资源包就换端口号，所以「端口 = 版本号」，App 里显示的就是这个值。

用法
----
    python3 tools/fetch_assets.py                  # 只体检：本地有素材就直接用，没有再下载
    python3 tools/fetch_assets.py --check          # 纯检查，绝不下载（CI / 新会话自检用）
    python3 tools/fetch_assets.py --url URL        # 指定源（镜像 / 新版本）
    python3 tools/fetch_assets.py --dest DIR       # 解压/定位目标目录（默认 ./assets）
    python3 tools/fetch_assets.py --strict-tls     # 强制校验证书（站点修好证书后可用）
    python3 tools/fetch_assets.py --force          # 已存在也重新下载

素材搜索顺序（找到就用，全没有才下载）
--------------------------------------
 1. `--dest` 指定的目录（默认 `<工程>/assets`）
 2. 环境变量 `B50_ASSETS` 指向的目录
 3. 归档基线 `/sdcard/Tools/LSPilot/archive/b50-desktop-v1.3/assets`（桌面版快照，已解压）
 4. 归档素材包原件 `/sdcard/Tools/LSPilot/archive/b50-assets-34274.zip` → 校验后解压到 `<dest>`
 5. 环境变量 `B50_ASSETS_URL` 或默认上游 URL → 下载到 `<dest>/../assets.zip` → 解压到 `<dest>`

退出码：0 = 素材就绪；1 = 缺素材且无法获取 / 校验不通过。
"""
import argparse
import io
import json
import os
import re
import shutil
import ssl
import sys
import time
import urllib.error
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DEFAULT_URL = "https://dw.moant.cn:34274/assets.zip"
ARCHIVE_ASSETS = "/sdcard/Tools/LSPilot/archive/b50-desktop-v1.3/assets"
#: 上游素材包原件（桌面版当初从 dw.moant.cn:34274 下载的，402 MB，2026-10-26 归档）
ARCHIVE_ZIP = "/sdcard/Tools/LSPilot/archive/b50-assets-34274.zip"

#: 素材目录里必须存在的东西（打包的正确性以此为准，pack_assets.py 共用这份清单）
REQUIRED_DIRS = ["cover", "ui"]
REQUIRED_FILES = [
    "ui/fonts/ResourceHanRoundedCN.otf",
    "ui/fonts/Torus SemiBold.otf",
    "music_data.json",
]
MIN_COVERS = 1400          # 实测 1569；少于这个数说明素材不完整


# ---------------------------------------------------------------- 工具

def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return ("%.0f %s" % (n, unit)) if unit == "B" else ("%.1f %s" % (n, unit))
        n /= 1024.0


def version_from_url(url):
    """从上游 URL 里取版本代号：`https://host:34274/assets.zip` → `34274`。

    上游把**版本号当端口用**（cn 服机台版本代号），所以端口就是素材包版本。
    """
    m = re.search(r"://[^/:]+:(\d{4,5})/", url or "")
    return m.group(1) if m else ""


def look_like_assets(path):
    """目录是否像一份完整素材（不抛异常，只回答是/否 + 原因）。"""
    if not os.path.isdir(path):
        return False, "目录不存在"
    for d in REQUIRED_DIRS:
        if not os.path.isdir(os.path.join(path, d)):
            return False, "缺目录 %s/" % d
    for f in REQUIRED_FILES:
        if not os.path.exists(os.path.join(path, f)):
            return False, "缺文件 %s" % f
    cov = os.path.join(path, "cover")
    n = len(os.listdir(cov)) if os.path.isdir(cov) else 0
    if n < MIN_COVERS:
        return False, "曲绘只有 %d 张（应 ≥ %d）" % (n, MIN_COVERS)
    return True, "曲绘 %d 张" % n


def candidates(dest, use_archive=True):
    """按优先级返回 [(说明, 路径)]"""
    out = [("--dest / 工程默认", os.path.abspath(dest))]
    env_assets = os.environ.get("B50_ASSETS", "").strip()
    if env_assets:
        out.append(("环境变量 B50_ASSETS", os.path.abspath(env_assets)))
    if use_archive:
        out.append(("归档基线（桌面版快照）", ARCHIVE_ASSETS))
    return out


def _ssl_context(strict_tls):
    if strict_tls:
        return ssl.create_default_context()
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


# ---------------------------------------------------------------- 下载（断点续传）

def url_variants(url):
    """上游 https 证书过期、且 dev 分支 README 用的是 http，所以两种协议都试。

    2026-10-28 核对：main 分支 README = https，dev 分支 README = http，**地址本身一致**
    （`dw.moant.cn:34274/assets.zip`），只有协议不同。
    """
    out = [url]
    if url.startswith("https://"):
        out.append("http://" + url[len("https://"):])
    elif url.startswith("http://"):
        out.append("https://" + url[len("http://"):])
    return out


def download(url, save_to, strict_tls=False, timeout=120):
    """流式下载，支持断点续传。返回 (ok, 说明)。"""
    os.makedirs(os.path.dirname(os.path.abspath(save_to)), exist_ok=True)
    have = os.path.getsize(save_to) if os.path.exists(save_to) else 0
    headers = {"User-Agent": "b50-android-fetch-assets/1.0"}
    if have:
        headers["Range"] = "bytes=%d-" % have
        print("  · 断点续传：已有 %s，从该位置继续" % human(have))

    req = urllib.request.Request(url, headers=headers)
    ctx = _ssl_context(strict_tls)
    if not strict_tls and url.startswith("https://"):
        print("  ! 跳过证书校验（上游证书已过期，这是预期行为；要严格校验加 --strict-tls）")
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=timeout) as resp:
            total = resp.headers.get("Content-Length")
            total = int(total) + have if total and total.isdigit() else 0
            mode = "ab" if have and resp.status == 206 else "wb"
            if mode == "wb":
                have = 0
            done = have
            t0 = time.time()
            with open(save_to, mode) as f:
                while True:
                    chunk = resp.read(256 * 1024)
                    if not chunk:
                        break
                    f.write(chunk)
                    done += len(chunk)
                    if total:
                        pct = done * 100 // total
                        if pct % 5 == 0:
                            sys.stdout.write("\r  · 下载 %3d%%  %s / %s" % (pct, human(done), human(total)))
                            sys.stdout.flush()
            sys.stdout.write("\r  · 下载 100%%  %s（%.1f 秒）\n" % (human(os.path.getsize(save_to)), time.time() - t0))
    except urllib.error.HTTPError as exc:
        return False, "HTTP %s：%s" % (exc.code, exc.reason)
    except Exception as exc:  # noqa: BLE001
        return False, "%s: %s" % (type(exc).__name__, exc)
    return True, "已下载 %s" % human(os.path.getsize(save_to))


# ---------------------------------------------------------------- 校验 / 解压

def inspect_zip(path):
    """返回 (ok, 说明, 统计 dict)"""
    try:
        z = zipfile.ZipFile(path)
    except Exception as exc:  # noqa: BLE001
        return False, "不是合法 zip：%s" % exc, {}
    names = [n for n in z.namelist() if not n.endswith("/")]
    # 上游 zip 可能带一层 assets/ 前缀，这里只做统计，不做强制判断
    covers = sum(1 for n in names if "/cover/" in n or n.startswith("cover/"))
    fonts = [n for n in names if "ResourceHanRoundedCN" in n or "Torus SemiBold" in n]
    jsons = [n for n in names if n.endswith(("music_data.json", "chart_summary.json", "peer_stats.json"))]
    stats = {"files": len(names), "covers": covers, "fonts": len(fonts), "jsons": len(jsons)}
    problems = []
    if covers < MIN_COVERS:
        problems.append("曲绘只有 %d 张" % covers)
    if len(fonts) < 2:
        problems.append("字体只有 %d 个" % len(fonts))
    if len(jsons) < 2:
        problems.append("关键 JSON 只有 %d 个" % len(jsons))
    if problems:
        return False, "内容不完整：" + "、".join(problems), stats
    return True, "%d 个文件 / 曲绘 %d / 字体 %d / JSON %d" % (len(names), covers, len(fonts), len(jsons)), stats


def unzip_to(zip_path, dest):
    os.makedirs(dest, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        names = z.namelist()
        # 上游包若带顶层 assets/ 前缀，剥掉再解，保证 dest 下面直接是 ui/ cover/ ...
        prefix = ""
        first = names[0] if names else ""
        if "/" in first and not first.endswith("/") is False:
            top = first.split("/")[0]
            if top and all(n.startswith(top + "/") for n in names if n.strip()):
                prefix = top + "/"
        if prefix:
            print("  · 检测到顶层目录 %s，解压时剥离" % prefix)
        n = 0
        for info in z.infolist():
            if info.is_dir():
                continue
            rel = info.filename[len(prefix):] if prefix and info.filename.startswith(prefix) else info.filename
            out = os.path.join(dest, rel)
            os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
            with z.open(info) as src, open(out, "wb") as dst:
                shutil.copyfileobj(src, dst)
            n += 1
    return n


def write_version_note(dest, version):
    """把素材版本写进目录，供人工/脚本核对（App 侧读的是 Kotlin 常量）。"""
    if not version:
        return
    try:
        with open(os.path.join(dest, "ASSET_VERSION.txt"), "w", encoding="utf-8") as f:
            f.write(version + "\n")
    except OSError:
        pass


# ---------------------------------------------------------------- main

def probe(url):
    """探测上游是否活着 + 能取到多少（不发大请求）。"""
    print("\n== 探测上游（不下载） ==")
    print("  地址：%s" % url)
    print("  版本代号（取自 URL 端口）：%s" % (version_from_url(url) or "无法识别"))
    alive_any = False
    for u in url_variants(url):
        t0 = time.time()
        req = urllib.request.Request(u, headers={
            "User-Agent": "b50-android-fetch-assets/1.0", "Range": "bytes=0-1023"})
        try:
            ctx = _ssl_context(False)
            with urllib.request.urlopen(req, context=ctx, timeout=15) as resp:
                n = len(resp.read(64 * 1024))
                print("  ✓ %-42s HTTP %s，读到 %s（%.1f 秒）" % (u, resp.status, human(n), time.time() - t0))
                alive_any = True
        except Exception as exc:  # noqa: BLE001
            print("  ✗ %-42s %s: %s（%.1f 秒）" % (u, type(exc).__name__, str(exc)[:60], time.time() - t0))
    if alive_any:
        print("\n  → 上游活着，可以跑：python3 tools/fetch_assets.py --no-archive")
        return 0
    print("\n  → 上游两种协议都不可达。要么等它恢复，要么用 --url 换镜像。")
    print("     本机素材仍可用（cn:%s），缺的只是机台新增曲目。" % (version_from_url(url) or "?"))
    return 1


def main(argv=None):
    ap = argparse.ArgumentParser(description="素材包获取 / 校验（开发侧资源依赖入口）",
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    ap.add_argument("--dest", default=os.path.join(ROOT, "assets"), help="目标/查找目录，默认 <工程>/assets")
    ap.add_argument("--url", default=os.environ.get("B50_ASSETS_URL") or DEFAULT_URL, help="素材包 URL")
    ap.add_argument("--check", action="store_true", help="只检查，不下载")
    ap.add_argument("--force", action="store_true", help="已存在也重新下载")
    ap.add_argument("--strict-tls", action="store_true", help="强制校验证书（默认跳过，因上游证书过期）")
    ap.add_argument("--no-archive", action="store_true",
                    help="不把归档快照当候选源（强制走下载，用于拿最新素材 / 归档已删时）")
    ap.add_argument("--probe", action="store_true",
                    help="只探测上游是否可达（不下载），用于「等资源包更新」时定期看一眼")
    ap.add_argument("--from-zip", action="store_true",
                    help="直接从归档素材包 zip 重建素材目录（不走解压好的快照，也不下载）")
    args = ap.parse_args(argv)

    if args.probe:
        print("素材包探测  ·  工程 %s" % ROOT)
        return probe(args.url)

    dest = os.path.abspath(args.dest)
    version = version_from_url(args.url)
    print("素材包获取  ·  工程 %s" % ROOT)
    print("上游：%s%s" % (args.url, ("（版本代号 cn:%s）" % version) if version else ""))

    # ---------- 1. 先找本地 ----------
    print("\n== 1. 本地素材 ==")
    use_dir_scan = not (args.no_archive or args.from_zip)
    for label, path in candidates(dest, use_archive=use_dir_scan):
        good, why = look_like_assets(path)
        print("  %s %-28s %s" % ("✓" if good else "·", label, path))
        print("      %s" % why)
        if good and not args.force:
            write_version_note(path, version)
            print("\n✓ 素材就绪（%s）" % path)
            if version:
                print("  版本代号 cn:%s（App 设置页显示这个；改素材后记得 PACK_VERSION 与之同步）" % version)
            return 0

    # ---------- 1.5 本地素材包 zip（归档原件 / 上次没解压完的） ----------
    local_zips = []
    if not args.no_archive and os.path.exists(ARCHIVE_ZIP):
        local_zips.append(("归档素材包原件", ARCHIVE_ZIP))
    pending_zip = os.path.join(os.path.dirname(dest), "assets.zip")
    if os.path.exists(pending_zip):
        local_zips.append(("本地已下载的 zip", pending_zip))
    for label, zpath in local_zips:
        good, why, _ = inspect_zip(zpath)
        print("\n  %s %-22s %s" % ("✓" if good else "·", label, zpath))
        print("      %s" % why)
        if not good:
            continue
        if args.check:
            print("      --check 模式，不解压；去掉 --check 会自动解压到 %s" % dest)
            continue
        print("\n== 解压 %s → %s ==" % (label, dest))
        n = unzip_to(zpath, dest)
        write_version_note(dest, version)
        ok2, why2 = look_like_assets(dest)
        print("  %s 解压 %d 个文件，%s" % ("✓" if ok2 else "✗", n, why2))
        if ok2:
            print("\n✓ 素材就绪（来自%s）%s" % (label, ("，版本代号 cn:%s" % version) if version else ""))
            return 0

    if args.check:
        print("\n✗ 本地没有可用素材，且指定了 --check（不下载）")
        print("  获取办法：python3 tools/fetch_assets.py            # 自动下载上游")
        print("           或手工下载 %s 解压到 %s" % (args.url, dest))
        return 1

    # ---------- 2. 下载 ----------
    print("\n== 2. 下载上游素材包 ==")
    zip_path = os.path.join(os.path.dirname(dest), "assets.zip")
    ok, msg = False, ""
    for i, u in enumerate(url_variants(args.url)):
        if i:
            print("  · 换协议重试：%s" % u)
        ok, msg = download(u, zip_path, strict_tls=args.strict_tls)
        if ok:
            break
    if not ok:
        print("  ✗ 下载失败（https / http 都试过了）：%s" % msg)
        print("\n这可能的原因（按概率）：")
        print("  1. 网络到不了 dw.moant.cn（部分地区/移动网络会 reset 连接）→ 换网络或用镜像：")
        print("     python3 tools/fetch_assets.py --url <镜像地址>")
        print("  2. 上游换了地址 → 看 https://github.com/HanYaaaaaaa/nonebot-plugin-b50-analysis 的 README")
        print("  3. **版本代号变了**：URL 端口就是版本代号（cn:34274）。机台版本升级后上游会换端口，")
        print("     旧链接就指向不存在的版本 → 去上游 README 看当前端口，或用 --url 指定新地址")
        print("  4. 没权限写 %s" % zip_path)
        return 1
    print("  ✓ %s" % msg)

    # ---------- 3. 校验 ----------
    print("\n== 3. 校验 zip 内容 ==")
    good, why, stats = inspect_zip(zip_path)
    print("  %s %s" % ("✓" if good else "✗", why))
    if not good:
        print("  拒绝解压（坏包会让 App 出图异常）。可加 --force 重新下载，或换 --url。")
        return 1

    # ---------- 4. 解压 ----------
    print("\n== 4. 解压到 %s ==" % dest)
    n = unzip_to(zip_path, dest)
    write_version_note(dest, version)
    good, why = look_like_assets(dest)
    print("  %s 解压 %d 个文件，%s" % ("✓" if good else "✗", n, why))
    if not good:
        return 1

    print("\n✓ 完成。下一步：")
    print("  python3 tools/pack_assets.py %s app/src/main/assets --version %s" % (dest, version or "<版本>"))
    print("  → 打包后记得让 ResourceInstaller.PACK_VERSION 与版本代号一致（pack_assets.py 会检查）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
