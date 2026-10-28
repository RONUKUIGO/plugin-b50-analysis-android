#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一键生成全部启动图标（miuix 风格：深蓝渐变底 + 白色 B50 字标 + 自适应图标）。

为什么要脚本而不是手搓 PNG：
  · 18 个文件要覆盖 5 个密度 × 3 个用途（legacy / round / adaptive 前景）+ 背景 + 商店图，
    手工切图必然出现某一档没换、或换了但偏移 1~2px 的情况；
  · 自适应图标的「安全区」是硬约束（中心 66dp 圆内不能被任何遮罩切到），
    靠眼睛看不出越界，只能算。

设计约束（验收标准）
  · 自适应图标：前景内容必须落在中心 66dp 圆内 → 圆形/方形/圆角遮罩下都完整；
  · 字标白色 #FFFFFF 压在蓝底上，对比度 ≥ 4.5:1（WCAG AA 正文级），深色壁纸下也不糊；
  · 字标宽度 = 可见区宽度的 78%，legacy / round / adaptive 三套构图完全同源。

用法
    python3 tools/make_icons.py               # 生成 18 个文件（默认动作）
    python3 tools/make_icons.py --verify      # 只校验现有文件（安全区/对比度/遮罩模拟）
    python3 tools/make_icons.py --preview /tmp/icon-preview   # 额外导出遮罩模拟图

字体：Torus SemiBold（pack.zip 内，maimix 官方字宽，和 App 内渲染的成绩图同源）。
"""
import argparse
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
PACK_ZIP = os.path.join(ROOT, "app/src/main/assets/pack.zip")
FONT_IN_ZIP = "ui/fonts/Torus SemiBold.otf"
FONT_CACHE = "/tmp/b50-icon-font.otf"

# 密度 → 倍率
DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]

LEGACY_DP = 48          # ic_launcher.png / ic_launcher_round.png 的边长
VISIBLE_DP = 72         # 自适应图标「被看见」的区域（108dp 画布中心 72dp）
CANVAS_DP = 108         # 自适应图标画布
SAFE_DP = 66            # 自适应图标安全区直径
GLYPH_W_RATIO = 0.78    # 字标宽度占可见区宽度的比例
TEXT = "B50"

# 深蓝渐变（左上深、右下浅）。压得比 App 主色 #4A80F7 更深一档 ——
# 白字压在 #4A80F7 上只有 3.7:1，实测不够看（深色壁纸下更明显），
# 这组让「字标覆盖范围内的最亮背景」仍有 ≥4.5:1。
C_DARK = (0x1A, 0x46, 0xB4)
C_LIGHT = (0x30, 0x66, 0xEE)
C_GLYPH = (0xFF, 0xFF, 0xFF)


# ----------------------------------------------------------------- 基础绘制

def font(size):
    if not os.path.exists(FONT_CACHE):
        import zipfile
        with zipfile.ZipFile(PACK_ZIP) as z:
            data = z.read(FONT_IN_ZIP)
        with open(FONT_CACHE, "wb") as f:
            f.write(data)
    return ImageFont.truetype(FONT_CACHE, size)


def gradient(size):
    """135° 线性渐变。先在 64×64 上算再放大 —— 纯 Pillow 也能平滑，且结果可复现。"""
    small = Image.new("RGB", (64, 64))
    px = small.load()
    for y in range(64):
        for x in range(64):
            t = (x + y) / 126.0                      # 0=左上 1=右下
            px[x, y] = tuple(int(C_DARK[i] + (C_LIGHT[i] - C_DARK[i]) * t) for i in range(3))
    return small.resize((size, size), Image.BICUBIC)


def glyph_layer(size_px, glyph_w_px):
    """透明画布 + 居中的白色字标，返回 (图层, 字标包围盒)。"""
    layer = Image.new("RGBA", (size_px, size_px), (0, 0, 0, 0))
    # 先按 1/4 画布宽试排，再按目标字宽缩放字号
    probe = font(max(8, size_px // 4))
    bbox = probe.getbbox(TEXT)
    natural = bbox[2] - bbox[0]
    f = font(max(8, int(round(size_px // 4 * glyph_w_px / natural))))
    bbox = f.getbbox(TEXT)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    d = ImageDraw.Draw(layer)
    d.text(((size_px - w) / 2 - bbox[0], (size_px - h) / 2 - bbox[1]), TEXT, font=f, fill=C_GLYPH)
    return layer, layer.getbbox()


def superellipse_mask(size, n=4.5):
    """squircle 遮罩（|x|^n + |y|^n ≤ 1），miuix / MIUI 的方形图标就是这个形状。"""
    mask = Image.new("L", (size, size), 0)
    px = mask.load()
    c = (size - 1) / 2.0
    for y in range(size):
        for x in range(size):
            u = abs((x - c) / c)
            v = abs((y - c) / c)
            if u ** n + v ** n <= 1.0:
                px[x, y] = 255
    return mask


def circle_mask(size):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


# ----------------------------------------------------------------- 合成

def compose_visible(size_px):
    """可见区构图（bg + 字标），尺寸 = 72dp 对应的像素。"""
    img = gradient(size_px).convert("RGBA")
    layer, _ = glyph_layer(size_px, int(round(size_px * GLYPH_W_RATIO)))
    img.alpha_composite(layer)
    return img


def build_all(preview_dir=None):
    written = []
    for name, scale in DENSITIES:
        d = os.path.join(RES, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)

        legacy_px = int(round(LEGACY_DP * scale))
        visible = compose_visible(int(round(VISIBLE_DP * scale)))

        # legacy 方形（squircle）+ 圆形
        sq = visible.resize((legacy_px, legacy_px), Image.LANCZOS)
        square = Image.new("RGBA", (legacy_px, legacy_px), (0, 0, 0, 0))
        square.paste(sq, (0, 0), superellipse_mask(legacy_px))
        square.save(os.path.join(d, "ic_launcher.png"))
        written.append("mipmap-%s/ic_launcher.png" % name)

        rnd = Image.new("RGBA", (legacy_px, legacy_px), (0, 0, 0, 0))
        rnd.paste(sq, (0, 0), circle_mask(legacy_px))
        rnd.save(os.path.join(d, "ic_launcher_round.png"))
        written.append("mipmap-%s/ic_launcher_round.png" % name)

        # 自适应前景：108dp 透明画布，字标与可见区构图同一尺寸 → 视觉完全对齐
        canvas_px = int(round(CANVAS_DP * scale))
        fg = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
        glyph, _ = glyph_layer(canvas_px, int(round(VISIBLE_DP * scale * GLYPH_W_RATIO)))
        fg.alpha_composite(glyph)
        fg.save(os.path.join(d, "ic_launcher_foreground.png"))
        written.append("mipmap-%s/ic_launcher_foreground.png" % name)

        if preview_dir:
            os.makedirs(preview_dir, exist_ok=True)
            for label, mask in (("circle", circle_mask(legacy_px)),
                                ("squircle", superellipse_mask(legacy_px))):
                out = Image.new("RGBA", (legacy_px, legacy_px), (0, 0, 0, 0))
                out.paste(sq, (0, 0), mask)
                out.save(os.path.join(preview_dir, "%s_%s.png" % (name, label)))

    # 背景层：自适应图标用，必须是满画布（会被裁）
    bg = gradient(512)
    bg.save(os.path.join(RES, "drawable/ic_launcher_background.png"))
    written.append("drawable/ic_launcher_background.png")

    # 商店图 512（Play 要求不透明、不带圆角）
    web = compose_visible(512)
    web.convert("RGB").save(os.path.join(ROOT, "app/src/ic_launcher-web.png"))
    written.append("../ic_launcher-web.png")

    return written


# ----------------------------------------------------------------- 校验

def _lum(c):
    def ch(v):
        v = v / 255.0
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])


def contrast(a, b):
    la, lb = _lum(a), _lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def verify():
    problems = []

    def ok(msg):
        print("  ✓ " + msg)

    def bad(msg):
        print("  ✗ " + msg)
        problems.append(msg)

    print("== 自适应前景：安全区（中心 66dp 圆）==")
    for name, scale in DENSITIES:
        p = os.path.join(RES, "mipmap-%s/ic_launcher_foreground.png" % name)
        if not os.path.exists(p):
            bad("缺 " + p)
            continue
        im = Image.open(p).convert("RGBA")
        size_px = im.size[0]
        bbox = im.getbbox()
        if bbox is None:
            bad("%s 前景是空白图" % name)
            continue
        x0, y0, x1, y1 = bbox
        dp = size_px / float(CANVAS_DP)
        # 换算成「以画布中心为原点」的 dp 坐标，再算包围盒四角到中心的距离
        cx = cy = size_px / 2.0
        corners = [(x0, y0), (x1, y0), (x0, y1), (x1, y1)]
        far = max(((abs(x - cx)) ** 2 + (abs(y - cy)) ** 2) ** 0.5 for x, y in corners) / dp
        need = SAFE_DP / 2.0
        rect_w = (x1 - x0) / dp
        rect_h = (y1 - y0) / dp
        if far > need:
            bad("%s 字标越界：最远角距中心 %.1fdp > 安全半径 %.1fdp（字标 %.1fx%.1fdp）"
                % (name, far, need, rect_w, rect_h))
        else:
            ok("%s 字标 %.1fx%.1fdp，最远角 %.1fdp ≤ %.1fdp"
               % (name, rect_w, rect_h, far, need))

    print("\n== 字标 vs 底色对比度（取字标覆盖范围内**最亮**的背景，最坏情况）==")
    vis = int(round(VISIBLE_DP * 4))
    bg = gradient(vis)
    fg_only, gbox = glyph_layer(vis, int(round(vis * GLYPH_W_RATIO)))
    crop = bg.crop(gbox)
    alpha = fg_only.crop(gbox).split()[3]
    worst, worst_l = None, -1.0
    for y in range(0, crop.size[1], 2):
        for x in range(0, crop.size[0], 2):
            if alpha.getpixel((x, y)) < 8:        # 只看没被字标盖住的背景
                c = crop.getpixel((x, y))[:3]
                l = _lum(c)
                if l > worst_l:
                    worst, worst_l = c, l
    ratio = contrast(C_GLYPH, worst)
    (ok if ratio >= 4.5 else bad)("白色字标压在 #%02X%02X%02X 上：%.2f:1（门槛 4.5）"
                                  % (worst[0], worst[1], worst[2], ratio))

    print("\n== 遮罩模拟：圆形 / squircle 下是否被切 ==")
    visible_dp = VISIBLE_DP
    for name, scale in DENSITIES:
        p = os.path.join(RES, "mipmap-%s/ic_launcher_foreground.png" % name)
        im = Image.open(p).convert("RGBA")
        canvas_px = im.size[0]
        # Android 的做法：前景按 108dp 画布缩到 72dp 可见区
        vis_px = int(round(canvas_px * visible_dp / float(CANVAS_DP)))
        scaled = im.resize((vis_px, vis_px), Image.LANCZOS)
        bbox = scaled.getbbox()
        x0, y0, x1, y1 = bbox
        c = vis_px / 2.0
        # 圆形遮罩：visible 区内切圆半径 = vis_px/2
        far = max(((abs(x - c)) ** 2 + (abs(y - c)) ** 2) ** 0.5 for x, y in
                  [(x0, y0), (x1, y0), (x0, y1), (x1, y1)])
        (ok if far <= c else bad)("%s 可见区内切圆 r=%.1fpx，字标最远 %.1fpx%s"
                                  % (name, c, far, "（会被切角）" if far > c else "（安全）"))

    print("\n== 文件齐全 ==")
    need_files = []
    for name, _ in DENSITIES:
        for f in ("ic_launcher.png", "ic_launcher_round.png", "ic_launcher_foreground.png"):
            need_files.append("mipmap-%s/%s" % (name, f))
    need_files += ["drawable/ic_launcher_background.png", "mipmap-anydpi-v26/ic_launcher.xml",
                   "mipmap-anydpi-v26/ic_launcher_round.xml"]
    missing = [f for f in need_files if not os.path.exists(os.path.join(RES, f))]
    if missing:
        for f in missing:
            bad("缺 " + f)
    else:
        ok("%d 个 res 图标文件齐全（+ app/src/ic_launcher-web.png）" % len(need_files))

    print()
    if problems:
        print("✗ 有 %d 项不达标" % len(problems))
        return 1
    print("✓ 全部达标")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true", help="只校验，不生成")
    ap.add_argument("--preview", metavar="DIR", help="额外导出遮罩模拟图到该目录")
    args = ap.parse_args()

    if args.verify:
        return verify()

    written = build_all(args.preview)
    print("已生成 %d 个文件：" % len(written))
    for w in written:
        print("  · " + w)
    print()
    return verify()


if __name__ == "__main__":
    sys.exit(main())
