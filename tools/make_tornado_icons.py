# -*- coding: utf-8 -*-
"""生成 Dragon Survival 技能图标：32x32 的青色龙卷风剪影，0~5 级共 6 张。

用法：
    python tools/make_tornado_icons.py <输出目录>

图标用「宽底窄顶」的经典漏斗形状（识别度优先——图标是符号，不是模型截图；
模型本身照抄灾变，是窄底宽顶）。级别越高：条纹越密、青色越亮。
"""
import os
import sys

from PIL import Image

SIZE = 32
TOP = 3          # 漏斗顶端所在行
BOTTOM = 29      # 漏斗底端所在行
MIN_HALF = 1.5   # 顶端半宽
MAX_HALF = 12.0  # 底端半宽
CENTER = 15.5    # 水平中心


def _cyan(value):
    """value 0~1 -> 青色。色相 187°。"""
    r = int(round(10 + 60 * value))
    g = int(round(120 + 120 * value))
    b = int(round(150 + 105 * value))
    return max(0, min(255, r)), max(0, min(255, g)), max(0, min(255, b))


def make_icon(level):
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()

    # 级别越高，横向条纹越密（镂空感越强，视觉上更"快"）
    period = 5 - min(level, 4) // 2

    for y in range(TOP, BOTTOM + 1):
        t = (y - TOP) / float(BOTTOM - TOP)          # 0 = 顶端，1 = 底端
        half = MIN_HALF + (MAX_HALF - MIN_HALF) * (t ** 1.6)

        # 条纹：让图标有旋转的层次感
        if (y + level) % period == 0:
            continue

        value = 0.45 + 0.35 * t + 0.04 * level
        color = _cyan(min(1.0, value))

        x0 = int(round(CENTER - half))
        x1 = int(round(CENTER + half))
        for x in range(x0, x1 + 1):
            if 0 <= x < SIZE:
                px[x, y] = (*color, 255)

    return img


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        raise SystemExit(2)
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    for level in range(6):
        path = os.path.join(out_dir, "tornado_%d.png" % level)
        make_icon(level).save(path)
        print("已写出 %s" % path)


if __name__ == "__main__":
    main()
