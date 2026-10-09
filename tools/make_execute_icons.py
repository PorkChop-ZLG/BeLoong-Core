"""Generate the ability icons for the Execute (zhan sha) passive.

Outputs (32x32 RGBA, vanilla GUI sprite atlas):
  assets/beloong/textures/gui/sprites/abilities/execute_0.png   (locked / grayscale)
  assets/beloong/textures/gui/sprites/abilities/execute_1.png   (unlocked / colored)

Follows the project convention used by the other self-made abilities:
  *_0 = grayscale (shown at ability level 0), *_1 = full color (level >= 1).

The script refuses to overwrite existing files unless --force is passed,
because the delivered icons may have been touched up by hand.

Run:  python tools/make_execute_icons.py [--force]
"""

from __future__ import annotations

import argparse
import os

from PIL import Image, ImageDraw

SIZE = 32
OUT_DIR = os.path.join(
    "src", "main", "resources", "assets", "beloong", "textures", "gui", "sprites", "abilities"
)

TRANSPARENT = (0, 0, 0, 0)


def draw_icon(colors: dict[str, tuple[int, int, int, int]]) -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), TRANSPARENT)
    d = ImageDraw.Draw(img)

    # --- health bar body: x 3..28, y 13..19 ---------------------------------
    d.rectangle([3, 12, 28, 19], fill=colors["frame"])
    d.rectangle([4, 13, 27, 18], fill=colors["empty"])
    # filled (current health) portion
    d.rectangle([4, 13, 20, 18], fill=colors["health"])

    # --- execute threshold: vertical line right after the filled portion ----
    d.rectangle([21, 9, 22, 22], fill=colors["line"])

    # --- blade / chevron pointing down at the threshold line ----------------
    d.polygon([(18, 3), (25, 3), (21, 9), (22, 9)], fill=colors["blade"])
    d.polygon([(18, 3), (25, 3), (21, 9)], fill=colors["blade"])
    # a small notch so the marker reads as a blade rather than a triangle
    d.point([(21, 5), (22, 5)], fill=colors["notch"])

    return img


LOCKED = {
    "frame": (26, 26, 26, 255),
    "empty": (58, 58, 58, 255),
    "health": (118, 118, 118, 255),
    "line": (206, 206, 206, 255),
    "blade": (168, 168, 168, 255),
    "notch": (26, 26, 26, 255),
}

UNLOCKED = {
    "frame": (20, 8, 8, 255),
    "empty": (58, 12, 12, 255),
    "health": (179, 36, 28, 255),
    "line": (255, 225, 74, 255),
    "blade": (255, 190, 40, 255),
    "notch": (140, 20, 16, 255),
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="overwrite existing icons")
    args = parser.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)

    for name, colors in (("execute_0", LOCKED), ("execute_1", UNLOCKED)):
        path = os.path.join(OUT_DIR, name + ".png")
        if os.path.exists(path) and not args.force:
            print("skip (already exists):", path)
            continue
        draw_icon(colors).save(path)
        print("wrote:", path)


if __name__ == "__main__":
    main()
