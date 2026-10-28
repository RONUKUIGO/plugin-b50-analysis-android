#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""截图取证的像素探针 —— 专门回答"这个边缘到底有没有阴影 / 是脏边还是硬切"。

判断标准（本项目实测阈值）
  · 有阴影：跨边缘有 20~40px 的平滑亮度渐变（如 103 → 121），且随 y 连续
  · 无阴影：1~2px 内直接跳到目标色（如 247 → 255）
  · 脏边/双影：渐变里出现两个极值、或出现偏色像素（用 matrix 看 RGB 三通道是否一致）

子命令
    colors  <png>                       最常见颜色（判断页面底色 / 卡片色 / 遮罩色）
    vprof   <png> x y0 y1 [step]        沿一列打印 RGB + 亮度（看上下边缘的阴影带）
    hprof   <png> y x0 x1 [step]        沿一行打印 RGB + 亮度（看左右边缘）
    edge    <png> [x] [span]            从某列自上而下找边界，打印每个边界 ±span 的亮度剖面
    matrix  <png> x0 y0 x1 y1           二维亮度矩阵；偏色像素标成 "RR."，用来抓脏边

示例
    python3 tools/shadow_probe.py colors shot.png
    python3 tools/shadow_probe.py vprof  shot.png 540 470 500
    python3 tools/shadow_probe.py matrix shot.png 22 596 40 610
"""
import sys
from collections import Counter

from PIL import Image


def lum(p):
    r, g, b = p[:3]
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def _abs_int(v):
    return int(float(v))


def cmd_colors(path):
    im = Image.open(path).convert("RGB")
    counter = Counter(im.getdata())
    total = sum(counter.values())
    print("尺寸 %dx%d  像素 %d" % (im.size[0], im.size[1], total))
    for col, n in counter.most_common(12):
        print("  #%02X%02X%02X  %6.2f%%  (%d)"
              % (col[0], col[1], col[2], n * 100.0 / total, n))


def cmd_vprof(path, x, y0, y1, step=1):
    im = Image.open(path).convert("RGB")
    print("列 x=%d  y=%d..%d" % (x, y0, y1))
    prev = None
    for y in range(y0, y1, step):
        p = im.getpixel((x, y))
        v = lum(p)
        d = "" if prev is None else " %+6.1f" % (v - prev)
        print("  y=%4d  #%02X%02X%02X  L=%6.1f%s" % (y, p[0], p[1], p[2], v, d))
        prev = v


def cmd_hprof(path, y, x0, x1, step=1):
    im = Image.open(path).convert("RGB")
    print("行 y=%d  x=%d..%d" % (y, x0, x1))
    prev = None
    for x in range(x0, x1, step):
        p = im.getpixel((x, y))
        v = lum(p)
        d = "" if prev is None else " %+6.1f" % (v - prev)
        print("  x=%4d  #%02X%02X%02X  L=%6.1f%s" % (x, p[0], p[1], p[2], v, d))
        prev = v


def cmd_edge(path, x=None, span=24):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    x = w // 2 if x is None else x
    col = [lum(im.getpixel((x, y))) for y in range(h)]
    edges, last = [], -99
    for y in range(1, h):
        if abs(col[y] - col[y - 1]) > 6 and y - last > 8:
            edges.append(y)
            last = y
    print("列 x=%d 检出 %d 个水平边界" % (x, len(edges)))
    for i, e in enumerate(edges):
        lo, hi = max(0, e - span), min(h, e + span)
        print("\n-- 边界#%d y=%d  (%d..%d) --" % (i, e, lo, hi))
        print("   " + " ".join("%.0f" % col[y] for y in range(lo, hi)))


def cmd_matrix(path, x0, y0, x1, y1):
    im = Image.open(path).convert("RGB")
    print("区域 x %d..%d  y %d..%d" % (x0, x1, y0, y1))
    print("     " + "".join("%4d" % x for x in range(x0, x1)))
    for y in range(y0, y1):
        line = ""
        for x in range(x0, x1):
            r, g, b = im.getpixel((x, y))
            if abs(r - g) <= 2 and abs(g - b) <= 2:
                line += "%4d" % r
            else:
                line += " %02X." % r        # 偏色像素：只标红色分量
        print("%4d|%s" % (y, line))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    cmd, rest = sys.argv[1], sys.argv[2:]
    if cmd == "colors":
        cmd_colors(rest[0])
    elif cmd == "vprof":
        cmd_vprof(rest[0], _abs_int(rest[1]), _abs_int(rest[2]), _abs_int(rest[3]),
                  _abs_int(rest[4]) if len(rest) > 4 else 1)
    elif cmd == "hprof":
        cmd_hprof(rest[0], _abs_int(rest[1]), _abs_int(rest[2]), _abs_int(rest[3]),
                  _abs_int(rest[4]) if len(rest) > 4 else 1)
    elif cmd == "edge":
        cmd_edge(rest[0],
                 _abs_int(rest[1]) if len(rest) > 1 else None,
                 _abs_int(rest[2]) if len(rest) > 2 else 24)
    elif cmd == "matrix":
        cmd_matrix(rest[0], _abs_int(rest[1]), _abs_int(rest[2]),
                   _abs_int(rest[3]), _abs_int(rest[4]))
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
