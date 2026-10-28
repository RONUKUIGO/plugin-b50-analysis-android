#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把截图降采样成 ASCII 灰度图 —— 不"看"图也能判断布局。

为什么需要它：AI / 无头环境拿不到"视觉"，但布局问题（卡片有没有留白、元素有没有重叠、
分割线在不在）都能从亮度分布上看出来。

用法
    python3 tools/ascii_view.py shot.png                     # 整屏，宽 96 字符
    python3 tools/ascii_view.py shot.png 64                  # 指定字符宽度
    python3 tools/ascii_view.py shot.png 80 0 600 1080 1400  # 只画 x0 y0 x1 y1 这块

字符表：' ' 最暗 → '@' 最亮（白底深字，所以文字是 '#' 附近，背景是 '@'）。
行号 = ASCII 行，列号见顶部标尺；换算回像素：x = 列 × (宽/字符宽)，y = 行 × (高/行数 × 2)。

配套：找阴影/边缘的精确数值用 tools/shadow_probe.py（ASCII 只用来找"哪里不对"）。
"""
import sys

from PIL import Image

RAMP = " .:-=+*#%@"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    w_chars = int(sys.argv[2]) if len(sys.argv) > 2 else 96
    im = Image.open(path).convert("L")
    if len(sys.argv) > 6:
        x0, y0, x1, y1 = (int(v) for v in sys.argv[3:7])
        im = im.crop((x0, y0, x1, y1))
    w, h = im.size
    rows = max(1, int(w_chars * h / w * 0.5))      # 0.5 = 终端字符高宽比修正
    im = im.resize((w_chars, rows), Image.BOX)
    px = im.load()

    print("img %dx%d -> ascii %dx%d" % (w, h, w_chars, rows))
    print("   " + "".join(str((i // 10) % 10) for i in range(w_chars)))
    for y in range(rows):
        line = "".join(RAMP[min(9, px[x, y] * 10 // 256)] for x in range(w_chars))
        print("%3d|%s" % (y, line))
    return 0


if __name__ == "__main__":
    sys.exit(main())
