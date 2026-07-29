#!/usr/bin/env python3
"""Bake a Minecraft banner item string into a static board texture."""

from __future__ import annotations

import argparse
import ast
import re
import sys
import zipfile
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path

try:
    from PIL import Image, ImageChops
except ImportError as exc:
    raise SystemExit(
        "Pillow is required. Install it with: python -m pip install Pillow"
    ) from exc


# Minecraft 1.21.1 DyeColor texture diffuse colors.
DYE_COLORS: dict[str, tuple[int, int, int]] = {
    "white": (0xF9, 0xFF, 0xFE),
    "orange": (0xF9, 0x80, 0x1D),
    "magenta": (0xC7, 0x4E, 0xBD),
    "light_blue": (0x3A, 0xB3, 0xDA),
    "yellow": (0xFE, 0xD8, 0x3D),
    "lime": (0x80, 0xC7, 0x1F),
    "pink": (0xF3, 0x8B, 0xAA),
    "gray": (0x47, 0x4F, 0x52),
    "light_gray": (0x9D, 0x9D, 0x97),
    "cyan": (0x16, 0x9C, 0x9C),
    "purple": (0x89, 0x32, 0xB8),
    "blue": (0x3C, 0x44, 0xAA),
    "brown": (0x83, 0x54, 0x32),
    "green": (0x5E, 0x7C, 0x16),
    "red": (0xB0, 0x2E, 0x26),
    "black": (0x1D, 0x1D, 0x21),
}

RESOURCE_LOCATION = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
ITEM_AND_COMPONENTS = re.compile(
    r"^(?P<item>(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+)\s*(?P<components>\[.*\])?$",
    re.DOTALL,
)


class BannerTextureError(ValueError):
    """Raised when the item string or resource archive is invalid."""


@dataclass(frozen=True)
class PatternLayer:
    color: str
    pattern: str


@dataclass(frozen=True)
class BannerDefinition:
    base_color: str
    patterns: tuple[PatternLayer, ...]


def split_top_level(value: str, delimiter: str = ",") -> list[str]:
    parts: list[str] = []
    start = 0
    stack: list[str] = []
    quote: str | None = None
    escaped = False
    closing = {"[": "]", "{": "}"}

    for index, char in enumerate(value):
        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue

        if char in ('"', "'"):
            quote = char
        elif char in closing:
            stack.append(closing[char])
        elif char in ("}", "]"):
            if not stack or stack.pop() != char:
                raise BannerTextureError("Unbalanced brackets in banner item string")
        elif char == delimiter and not stack:
            parts.append(value[start:index].strip())
            start = index + 1

    if quote is not None or stack:
        raise BannerTextureError("Unterminated quote or bracket in banner item string")

    parts.append(value[start:].strip())
    return [part for part in parts if part]


def split_field(field: str) -> tuple[str, str]:
    quote: str | None = None
    escaped = False

    for index, char in enumerate(field):
        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
        elif char in ('"', "'"):
            quote = char
        elif char == ":":
            return field[:index].strip(), field[index + 1 :].strip()

    raise BannerTextureError(f"Expected key:value field, got: {field}")


def parse_string(value: str) -> str:
    value = value.strip()
    if not value:
        raise BannerTextureError("Empty value in banner pattern")
    if value[0] in ('"', "'"):
        try:
            parsed = ast.literal_eval(value)
        except (SyntaxError, ValueError) as exc:
            raise BannerTextureError(f"Invalid quoted string: {value}") from exc
        if not isinstance(parsed, str):
            raise BannerTextureError(f"Expected string value, got: {value}")
        return parsed
    return value


def extract_component_list(components: str, name: str) -> str | None:
    match = re.search(rf"(?:minecraft:)?{re.escape(name)}\s*=\s*\[", components)
    if match is None:
        return None

    opening_index = match.end() - 1
    depth = 0
    quote: str | None = None
    escaped = False

    for index in range(opening_index, len(components)):
        char = components[index]
        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue

        if char in ('"', "'"):
            quote = char
        elif char == "[":
            depth += 1
        elif char == "]":
            depth -= 1
            if depth == 0:
                return components[opening_index + 1 : index]

    raise BannerTextureError(f"Unterminated {name} component")


def normalize_resource_location(value: str) -> str:
    value = value if ":" in value else f"minecraft:{value}"
    if not RESOURCE_LOCATION.fullmatch(value):
        raise BannerTextureError(f"Invalid resource location: {value}")
    return value


def parse_banner_item(item_string: str) -> BannerDefinition:
    match = ITEM_AND_COMPONENTS.fullmatch(item_string.strip())
    if match is None:
        raise BannerTextureError("Invalid banner item string")

    item_id = normalize_resource_location(match.group("item"))
    item_path = item_id.split(":", 1)[1]
    if not item_path.endswith("_banner"):
        raise BannerTextureError(f"Item is not a banner: {item_id}")

    base_color = item_path[: -len("_banner")]
    if base_color not in DYE_COLORS:
        raise BannerTextureError(f"Unsupported banner base color: {base_color}")

    components = match.group("components")
    if components is None:
        return BannerDefinition(base_color, ())

    pattern_list = extract_component_list(components, "banner_patterns")
    if pattern_list is None or not pattern_list.strip():
        return BannerDefinition(base_color, ())

    layers: list[PatternLayer] = []
    for entry in split_top_level(pattern_list):
        if not (entry.startswith("{") and entry.endswith("}")):
            raise BannerTextureError(f"Expected pattern compound, got: {entry}")

        fields: dict[str, str] = {}
        for raw_field in split_top_level(entry[1:-1]):
            key, raw_value = split_field(raw_field)
            if key in fields:
                raise BannerTextureError(f"Duplicate pattern field: {key}")
            fields[key] = parse_string(raw_value)

        if "color" not in fields or "pattern" not in fields:
            raise BannerTextureError(f"Pattern requires color and pattern fields: {entry}")
        if fields["color"] not in DYE_COLORS:
            raise BannerTextureError(f"Unsupported pattern color: {fields['color']}")

        layers.append(
            PatternLayer(
                color=fields["color"],
                pattern=normalize_resource_location(fields["pattern"]),
            )
        )

    return BannerDefinition(base_color, tuple(layers))


def find_assets_jar(explicit_path: Path | None) -> Path:
    if explicit_path is not None:
        path = explicit_path.expanduser().resolve()
        if not path.is_file():
            raise BannerTextureError(f"Assets JAR does not exist: {path}")
        return path

    project_root = Path(__file__).resolve().parents[1]
    artifacts = project_root / "build" / "moddev" / "artifacts"
    candidates = list(artifacts.glob("*client-extra*.jar"))
    candidates.extend(artifacts.glob("*minecraft-resources*.jar"))
    candidates = sorted(set(candidates), key=lambda path: path.stat().st_mtime, reverse=True)
    if not candidates:
        raise BannerTextureError(
            "Could not find a Minecraft resources JAR under build/moddev/artifacts; "
            "pass one with --assets-jar"
        )
    return candidates[0].resolve()


def read_texture(archive: zipfile.ZipFile, resource_path: str) -> Image.Image:
    try:
        data = archive.read(resource_path)
    except KeyError as exc:
        raise BannerTextureError(f"Texture is missing from assets JAR: {resource_path}") from exc

    with Image.open(BytesIO(data)) as image:
        return image.convert("RGBA")


def tint_texture(texture: Image.Image, color: tuple[int, int, int]) -> Image.Image:
    tint = Image.new("RGBA", texture.size, (*color, 255))
    return ImageChops.multiply(texture, tint)


def pattern_texture_path(resource_location: str) -> str:
    namespace, path = resource_location.split(":", 1)
    return f"assets/{namespace}/textures/entity/banner/{path}.png"


def bake_texture(definition: BannerDefinition, assets_jar: Path) -> Image.Image:
    with zipfile.ZipFile(assets_jar) as archive:
        result = read_texture(archive, "assets/minecraft/textures/entity/banner_base.png")
        base_mask = read_texture(archive, "assets/minecraft/textures/entity/banner/base.png")
        if result.size != base_mask.size:
            raise BannerTextureError("Minecraft banner base textures have different sizes")

        result = Image.alpha_composite(
            result,
            tint_texture(base_mask, DYE_COLORS[definition.base_color]),
        )

        for layer in definition.patterns:
            pattern = read_texture(archive, pattern_texture_path(layer.pattern))
            if pattern.size != result.size:
                raise BannerTextureError(
                    f"Pattern texture has unexpected size {pattern.size}: {layer.pattern}"
                )
            result = Image.alpha_composite(
                result,
                tint_texture(pattern, DYE_COLORS[layer.color]),
            )

    return result


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Bake a Minecraft banner item string into a 64x64 board texture."
    )
    parser.add_argument(
        "banner",
        help="Banner item string containing an optional banner_patterns component",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        required=True,
        help="Output PNG path",
    )
    parser.add_argument(
        "--assets-jar",
        type=Path,
        help="Minecraft client resources JAR (auto-detected for this project by default)",
    )
    return parser


def main() -> int:
    parser = build_argument_parser()
    args = parser.parse_args()

    try:
        definition = parse_banner_item(args.banner)
        assets_jar = find_assets_jar(args.assets_jar)
        texture = bake_texture(definition, assets_jar)
        if texture.size != (64, 64):
            raise BannerTextureError(
                f"Expected a 64x64 banner texture, got {texture.width}x{texture.height}"
            )

        output = args.output.expanduser().resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        texture.save(output, format="PNG")
    except (BannerTextureError, OSError, zipfile.BadZipFile) as exc:
        parser.error(str(exc))

    print(f"Assets JAR: {assets_jar}")
    print(f"Base color: {definition.base_color}")
    for index, layer in enumerate(definition.patterns, start=1):
        print(f"Pattern {index}: {layer.color} {layer.pattern}")
    print(f"Output: {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
