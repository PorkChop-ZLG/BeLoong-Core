# -*- coding: utf-8 -*-
"""把灾变 cataclysm:textures/entity/koboleton/sandstorm.png 重上色为青色，
输出 beloong:textures/entity/tornado.png。

用法：
    python tools/recolor_tornado_texture.py <源贴图> <输出贴图>

源贴图的 alpha 是二值的（只有 0 和 255），逐像素原样保留；
亮度驱动明度，色相固定，暗部饱和度更高以保住颗粒感。
"""
import sys

from PIL import Image

HUE = 187.0     # 目标色相
SAT_HI = 0.90   # 亮部饱和度
SAT_LO = 0.72   # 暗部饱和度
GAMMA = 0.9


def _hsv_to_rgb(h, s, v):
    if s <= 0.0:
        c = int(round(v * 255))
        return c, c, c
    h6 = (h % 1.0) * 6.0
    i = int(h6)
    f = h6 - i
    p = v * (1.0 - s)
    q = v * (1.0 - s * f)
    t = v * (1.0 - s * (1.0 - f))
    i %= 6
    rgb = [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i]
    return tuple(int(round(c * 255)) for c in rgb)


def recolor(src, hue_deg=HUE):
    src = src.convert("RGBA")
    out = Image.new("RGBA", src.size, (0, 0, 0, 0))
    src_px, dst_px = src.load(), out.load()
    hue = (hue_deg % 360.0) / 360.0
    for y in range(src.height):
        for x in range(src.width):
            r, g, b, a = src_px[x, y]
            if a == 0:
                continue
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            value = min(1.0, lum ** GAMMA)
            sat = SAT_LO + (SAT_HI - SAT_LO) * (1.0 - value)
            rr, gg, bb = _hsv_to_rgb(hue, sat, value)
            dst_px[x, y] = (rr, gg, bb, a)
    return out


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        raise SystemExit(2)
    src = Image.open(sys.argv[1]).convert("RGBA")
    if src.size != (128, 128):
        print("警告：源贴图尺寸 %s，期望 (128, 128)" % (src.size,))
    recolor(src).save(sys.argv[2])
    print("已写出 %s" % sys.argv[2])


if __name__ == "__main__":
    main()
