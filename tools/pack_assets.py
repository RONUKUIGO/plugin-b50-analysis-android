#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 b50 的 423MB 素材 → app/src/main/assets/pack.zip（约 21MB），运行时首启解压。

用法：python3 tools/pack_assets.py /path/to/b50/assets app/src/main/assets
依赖：pip install pillow fonttools

压缩手段（实测收益）：
  曲绘 1569 张 256~400px PNG (357MB) → 200x200 WebP q82 (18.2MB)   19.6x
  中文圆体 14MB → GB2312 子集 7535 字 (2.8MB)
  11.otf 14MB / b50_bg.png 7.9MB / icons/resources 8.4MB → 渲染器未引用，丢弃
  bj.png 12.2MB → 1400 宽 WebP (185KB)，41 个图标按绘制尺寸x2 (0.27MB)
  zip 内 WebP 用 STORE（已压缩），文本/字体用 DEFLATE-9

改素材后记得把 ResourceInstaller.PACK_VERSION 一起 +1，App 才会重新解压。
"""
import argparse
import io, os, sys, glob, shutil, subprocess, zipfile, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

#: 素材包版本代号（= 机台内部版本代号 cn:NNNNN，也是上游 URL 的端口号）
#: 上游每次更新资源包就换一个端口号，所以「端口 = 版本号」。
#: App 侧显示与重解压判定用的是 ResourceInstaller.PACK_VERSION，两者必须一致（本脚本会检查）。
ASSET_VERSION = "34274"

#: 产物里必须存在的东西（打包后校验；与 Res.sanityCheck 共用同一份事实）
REQUIRED_IN_PACK = [
    "ui/fonts/ResourceHanRoundedCN.otf",
    "ui/fonts/Torus SemiBold.otf",
    "ui/icons/bj.png",
    "ui/default_cover.png",
    "music_data.json",
    "chart_summary.json",
    "peer_stats.json",
]
MIN_FONT_BYTES = 1 * 1024 * 1024      # 中文子集字体实测 2.8 MB；<1 MB 基本是子集失败的空壳
MIN_COVERS_IN_PACK = 1400            # 实测 1569

try:
    from PIL import Image
except ImportError:
    sys.stderr.write(
        "\n✗ 缺少 Pillow（打包素材必需，WebP 压缩全靠它）\n"
        "  装法：pip install pillow\n"
        "  本机若已装但报 WebP 不支持：需要带 WebP 的 Pillow（pip install --force-reinstall pillow）\n"
    )
    sys.exit(2)

from concurrent.futures import ThreadPoolExecutor

COVER_SIZE, COVER_Q = 200, 82      # 想更小：160 / 78（约省 7MB）
BG_WIDTH = 1400
DROP = {"b50_bg.png", "resources", "11.otf"}
ICON_TARGETS = {"UI_NUM_Drating": (40, 48), "UI_CMN_DXRating": (446, 84),
                "UI_TTR_Rank": (220, 110), "UI_MSS_MBase_Icon": (144, 144)}
SPECIAL = {"bj.png": (BG_WIDTH, int(BG_WIDTH * 2978 / 1400)),
           "Basic.png": (200, 96), "Advanced.png": (200, 96), "Expert.png": (200, 96),
           "Master.png": (200, 96), "Re_master.png": (200, 96),
           "UI_CMN_TabTitle_NewSong.png": (400, 100)}


def target_size(name):
    if name in SPECIAL:
        return SPECIAL[name]
    for k, v in ICON_TARGETS.items():
        if name.startswith(k):
            return v
    return None


def font_subset(src, dst):
    """GB2312 全集 + 日文假名 + ASCII + 全角标点 → 约 2.8MB"""
    chars = set()
    for b1 in range(0xA1, 0xFE):
        for b2 in range(0xA1, 0xFE):
            try:
                chars.add(bytes([b1, b2]).decode("gb2312"))
            except Exception:
                pass
    for cp in (list(range(0x20, 0x7F)) + list(range(0x3000, 0x3040))
               + list(range(0x3041, 0x3100)) + list(range(0xFF01, 0xFF61))):
        chars.add(chr(cp))
    txt = dst + ".chars"
    open(txt, "w", encoding="utf-8").write("".join(sorted(chars)))
    try:
        if os.path.exists(dst):
            os.remove(dst)
        proc = subprocess.run(
            ["pyftsubset", src, "--text-file=" + txt, "--output-file=" + dst,
             "--no-hinting", "--desubroutinize"],
            capture_output=True, text=True,
        )
    except FileNotFoundError:
        raise RuntimeError(
            "找不到 pyftsubset（fonttools 未安装或不在 PATH）\n"
            "  装法：pip install fonttools\n"
            "  校验：pyftsubset --help\n"
            "  注意：这一条以前是静默失败的 —— 会打出一个没有字体的 pack.zip，App 里中文变方块。"
        )
    finally:
        if os.path.exists(txt):
            os.remove(txt)
    if proc.returncode != 0:
        raise RuntimeError("pyftsubset 失败（exit=%d）：%s" % (proc.returncode, (proc.stderr or "").strip()[:400]))
    if not os.path.exists(dst) or os.path.getsize(dst) < MIN_FONT_BYTES:
        raise RuntimeError(
            "字体子集化产物异常：%s（大小 %s 字节，应 ≥ %d）"
            % (dst, os.path.getsize(dst) if os.path.exists(dst) else "不存在", MIN_FONT_BYTES)
        )
    return len(chars)


def shrink_cover(args):
    src, outdir = args
    try:
        im = Image.open(src).convert("RGBA").resize((COVER_SIZE, COVER_SIZE), Image.LANCZOS)
        out = os.path.join(outdir, os.path.splitext(os.path.basename(src))[0] + ".webp")
        im.save(out, "WEBP", quality=COVER_Q, method=4)
        return os.path.getsize(out)
    except Exception:
        return 0



# ---------------------------------------------------------------- 前置检查 / 产物校验

def preflight(src):
    """打包前把「依赖 + 素材 + 空间」一次性检查完，缺什么立刻说清楚。

    2026-10-28 之前这里是空白：缺 pyftsubset 会静默产出没有字体的 pack.zip，
    素材路径写错要等 1569 张曲绘转完才在字体那步抛 FileNotFoundError。
    """
    print("[0/4] 前置检查")
    problems = []

    # ① 依赖
    if not shutil.which("pyftsubset"):
        problems.append("找不到 pyftsubset（fonttools）→ pip install fonttools")
    try:
        import PIL
        from PIL import features
        webp = features.check("webp")          # 整个 pack.zip 都是 WebP，这个必须为真
        print("      ✓ Pillow %s（WebP 支持: %s）" % (PIL.__version__, "是" if webp else "否"))
        if not webp:
            problems.append("Pillow 缺 WebP 支持 → pip install --force-reinstall pillow")
    except Exception as exc:  # noqa: BLE001
        problems.append("Pillow 不可用（%s）→ pip install pillow" % exc)

    # ② 素材结构（复用 fetch_assets 的判定，保证与「素材获取」同一把尺子）
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import fetch_assets
        ok, why = fetch_assets.look_like_assets(src)
    except Exception as exc:  # noqa: BLE001
        ok, why = os.path.isdir(src), "（粗略检查：%s）%s" % (type(exc).__name__, exc)
    if ok:
        print("      ✓ 素材 %s（%s）" % (src, why))
    else:
        problems.append("素材目录不可用：%s → %s" % (src, why))
        problems.append("  获取办法：python3 tools/fetch_assets.py            # 自动找/下载")
        problems.append("            python3 tools/fetch_assets.py --from-zip # 从归档素材包 zip 重建")

    # ③ 磁盘（tmp 里要放 ~100 MB 的中间产物）
    try:
        free = shutil.disk_usage(os.path.dirname(src) or ".").free
        need = 200 * 1024 * 1024
        if free < need:
            problems.append("磁盘不足：需要约 200 MB，只剩 %.0f MB" % (free / 1048576))
        else:
            print("      ✓ 磁盘可用 %.0f MB" % (free / 1048576))
    except OSError:
        pass

    if problems:
        print("\n✗ 前置检查未通过：")
        for i, msg in enumerate(problems, 1):
            print("   %d. %s" % (i, msg))
        return False
    return True


def verify_pack(pack, version):
    """打包后校验产物 —— 宁可这里失败，也别让 App 装上一个缺字体的包。"""
    print("[4/4] 校验产物 %s" % os.path.basename(pack))
    try:
        z = zipfile.ZipFile(pack)
    except Exception as exc:  # noqa: BLE001
        print("      ✗ 打不开：%s" % exc)
        return False
    names = {n: i.file_size for i, n in [(i, i.filename) for i in z.infolist()] if True}
    problems = []
    for need in REQUIRED_IN_PACK:
        if need not in names:
            problems.append("缺 %s" % need)
    # 阈值按字体分：中文子集 ~2.9 MB，拉丁 ~66 KB（两个量级不同，别一刀切）
    if "ui/fonts/ResourceHanRoundedCN.otf" in names and names["ui/fonts/ResourceHanRoundedCN.otf"] < MIN_FONT_BYTES:
        problems.append("ResourceHanRoundedCN.otf 只有 %d 字节（子集失败？应 ≥ %d）"
                        % (names["ui/fonts/ResourceHanRoundedCN.otf"], MIN_FONT_BYTES))
    if "ui/fonts/Torus SemiBold.otf" in names and names["ui/fonts/Torus SemiBold.otf"] < 10_000:
        problems.append("Torus SemiBold.otf 只有 %d 字节（应 ≥ 10240）"
                        % names["ui/fonts/Torus SemiBold.otf"])
    covers = sum(1 for n in names if n.startswith("cover/"))
    if covers < MIN_COVERS_IN_PACK:
        problems.append("曲绘只有 %d 张（应 ≥ %d）" % (covers, MIN_COVERS_IN_PACK))
    size_mb = os.path.getsize(pack) / 1048576
    if size_mb < 10:
        problems.append("包体积只有 %.1f MB（应约 21 MB，可能漏了曲绘）" % size_mb)

    if problems:
        print("      ✗ 产物不合格：")
        for p in problems:
            print("         - %s" % p)
        return False
    print("      ✓ %d 个文件 · 曲绘 %d · %.1f MB · 版本代号 cn:%s" % (len(names), covers, size_mb, version))
    return True


def check_pack_version_const(version):
    """确认 App 侧 PACK_VERSION 与素材版本代号一致（不一致 = App 不会重新解压）。"""
    kt = os.path.join(ROOT, "app", "src", "main", "java", "com", "b50", "roast", "data", "ResourceInstaller.kt")
    try:
        text = io.open(kt, encoding="utf-8").read()
    except OSError:
        print("      ! 读不到 ResourceInstaller.kt，跳过版本常量校验")
        return True
    import re as _re
    m = _re.search(r'PACK_VERSION\s*=\s*"([^"]+)"', text)
    if not m:
        print("      ! ResourceInstaller.kt 里找不到 PACK_VERSION")
        return True
    cur = m.group(1)
    if cur == version:
        print("      ✓ PACK_VERSION = %s（与素材版本一致）" % cur)
        return True
    print("      ✗ PACK_VERSION = %s，但素材版本代号是 %s" % (cur, version))
    print("        → 改 app/src/main/java/com/b50/roast/data/ResourceInstaller.kt 里的 PACK_VERSION")
    print("        → 否则 App 升级后不会重新解压，用户看到的还是旧素材")
    return False


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="423MB 素材 → app/src/main/assets/pack.zip",
        formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    ap.add_argument("src", nargs="?", help="素材目录（含 cover/ ui/ music_data.json 等）")
    ap.add_argument("dst", nargs="?", help="输出目录（一般是 app/src/main/assets）")
    ap.add_argument("--version", default=ASSET_VERSION, help="素材版本代号，默认 %s" % ASSET_VERSION)
    ap.add_argument("--skip-preflight", action="store_true", help="跳过前置检查（不推荐）")
    ap.add_argument("--skip-verify", action="store_true", help="跳过产物校验（不推荐）")
    args = ap.parse_args(argv)

    if not args.src or not args.dst:
        print(__doc__)
        return 1
    version = str(args.version).strip()
    src, dst = os.path.abspath(args.src), os.path.abspath(args.dst)

    if not args.skip_preflight and not preflight(src):
        return 2
    os.makedirs(dst, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix="b50pack_")

    # ① 曲绘（线程池；不要用 multiprocessing，proot 下会炸）
    cov = sorted(glob.glob(os.path.join(src, "cover", "*.png")))
    covdir = os.path.join(tmp, "covers")
    os.makedirs(covdir)
    with ThreadPoolExecutor(max_workers=8) as ex:
        sizes = list(ex.map(shrink_cover, [(p, covdir) for p in cov]))
    print(f"[1/4] 曲绘 {len(cov)} 张 → {sum(sizes)/1048576:.2f} MB")

    # ② 图标 / 背景 / 默认封面
    uidir = os.path.join(tmp, "ui")
    os.makedirs(uidir)
    for p in glob.glob(os.path.join(src, "ui", "icons", "*")):
        name = os.path.basename(p)
        if name in DROP or os.path.isdir(p):
            continue
        im = Image.open(p).convert("RGBA")
        t = target_size(name)
        if t:
            im = im.resize(t, Image.LANCZOS)
        im.save(os.path.join(uidir, os.path.splitext(name)[0] + ".png"), "WEBP", quality=88, method=6)
    dc = os.path.join(src, "ui", "default_cover.png")
    if os.path.exists(dc):
        Image.open(dc).convert("RGBA").resize((240, 240), Image.LANCZOS) \
            .save(os.path.join(uidir, "default_cover.png"), "WEBP", quality=90)
    print(f"[2/4] 图标 {len(os.listdir(uidir))} 个 → "
          f"{sum(os.path.getsize(os.path.join(uidir,f)) for f in os.listdir(uidir))/1048576:.2f} MB")

    # ③ 字体
    # ★ 字体必须放在 ui/fonts/ 且沿用桌面版文件名：
    #   render.py 的 _font_path() 是按文件名关键字（han/cn/cjk、torus/bold）找的
    fdir = os.path.join(tmp, "ui_fonts")
    os.makedirs(fdir, exist_ok=True)
    n = font_subset(os.path.join(src, "ui", "fonts", "ResourceHanRoundedCN.otf"),
                    os.path.join(fdir, "ResourceHanRoundedCN.otf"))
    shutil.copy2(os.path.join(src, "ui", "fonts", "Torus SemiBold.otf"),
                 os.path.join(fdir, "Torus SemiBold.otf"))
    print(f"[3/4] 字体子集化 {n} 字 → ui/fonts/ResourceHanRoundedCN.otf")

    # ④ 打包
    pack = os.path.join(dst, "pack.zip")
    if os.path.exists(pack):
        os.remove(pack)

    def store(z, path, arc):
        z.write(path, arc, compress_type=zipfile.ZIP_STORED)

    def deflate(z, path, arc):
        z.write(path, arc, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)

    with zipfile.ZipFile(pack, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        # ★ 必须是 cover/<id>.png：render.py 的 cover_cache_dir = assets/"cover"
        for p in glob.glob(os.path.join(covdir, "*.webp")):
            store(z, p, "cover/" + os.path.splitext(os.path.basename(p))[0] + ".png")
        # ★ 必须是 ui/icons/xxx.png：render.py 里 self.icons = assets/"ui"/"icons"，
        #   且按文件名硬拼 .png（PIL 用文件头判断真实格式，WebP 内容装 .png 名字没问题）
        for p in glob.glob(os.path.join(uidir, "*")):
            name = os.path.basename(p)
            if name.startswith("default_cover"):
                store(z, p, "ui/" + name)
            else:
                store(z, p, "ui/icons/" + name)
        for p in glob.glob(os.path.join(fdir, "*")):
            deflate(z, p, "ui/fonts/" + os.path.basename(p))
        # music_data.json 优先取 b50 根目录的 data/（1404 首，含最新版本曲目，814 KB）
        # assets/ 里那份是旧的 1326 首（2.3 MB），缺 118xx 新曲会导致版本校准失效
        root_dir = os.path.dirname(src)
        music_cands = [os.path.join(root_dir, "data", "music_data.json"),
                       os.path.join(src, "music_data.json")]
        music = next((p for p in music_cands if os.path.exists(p)), None)
        if music:
            deflate(z, music, "music_data.json")
            print("      music_data <- " + music)
        for f in ("chart_summary.json", "peer_stats.json"):
            q = os.path.join(src, f)
            if os.path.exists(q):
                deflate(z, q, f)
        for p in glob.glob(os.path.join(src, "kb", "*")):
            deflate(z, p, "kb/" + os.path.basename(p))

    shutil.rmtree(tmp, ignore_errors=True)

    ok = True
    if not args.skip_verify:
        ok = verify_pack(pack, version) and ok
    if not args.skip_verify:
        ok = check_pack_version_const(version) and ok
    print("\n%s %s（%.2f MB，版本代号 cn:%s）" % ("✓" if ok else "✗", pack, os.path.getsize(pack) / 1048576, version))
    if ok:
        print("  下一步：sh /sdcard/Tools/LSPilot/scripts/build_android.sh   （会把新 pack.zip 打进 APK）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
