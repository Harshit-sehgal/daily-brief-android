#!/usr/bin/env python3
"""Re-embeds the plates from build/preview-plates into preview.html.

    scripts/capture-preview.sh && scripts/embed-preview-plates.py

preview.html owns the words; this owns the pixels. Each plate is matched by the
`data-full="<name>"` key already in the page, so captions, order and layout are
edited in the HTML and never here. A plate with no matching PNG is left alone and
reported, so a partial capture cannot silently blank the gallery.

Every image is inlined as a WebP data URI: the page has to stay one file that
opens from disk with no server and no adjacent assets.
"""
import base64
import io
import re
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - a missing Pillow is the whole story
    sys.exit("embed: needs Pillow (pip install pillow)")

ROOT = Path(__file__).resolve().parent.parent
PAGE = ROOT / "preview.html"
PLATES = ROOT / "build" / "preview-plates"

# Thumbnails sit in a grid a few hundred pixels wide; the lightbox copy is the
# one worth spending bytes on. Wide plates are landscape, so they get more width.
PHONE = {"thumb": 520, "full": 960, "quality": 68}
WIDE = {"thumb": 1000, "full": 1600, "quality": 66}


def encode(path: Path, width: int, quality: int) -> tuple[str, int, int]:
    image = Image.open(path)
    height = round(width * image.size[1] / image.size[0])
    buffer = io.BytesIO()
    image.resize((width, height), Image.LANCZOS).save(
        buffer, "WEBP", quality=quality, method=6
    )
    encoded = base64.b64encode(buffer.getvalue()).decode()
    return f"data:image/webp;base64,{encoded}", width, height


def main() -> int:
    if not PLATES.is_dir():
        sys.exit(f"embed: no plates in {PLATES} — run scripts/capture-preview.sh first")

    page = PAGE.read_text(encoding="utf8")
    keys = re.findall(r'data-full="([^"]+)"', page)
    if not keys:
        sys.exit("embed: preview.html has no data-full plates to fill")

    replaced, missing = [], []
    sources = {}
    for key in keys:
        plate = PLATES / f"{key}.png"
        if not plate.exists():
            missing.append(key)
            continue
        spec = WIDE if key.startswith("wide_") else PHONE
        thumb, width, height = encode(plate, spec["thumb"], spec["quality"])
        sources[key], _, _ = encode(plate, spec["full"], spec["quality"])

        # Rewrite the one <img> inside this plate's button, and its dimensions.
        pattern = re.compile(
            r'(data-full="' + re.escape(key) + r'"[^>]*>\s*<img src=")[^"]*(")'
            r'([^>]*?)(/>)',
            re.S,
        )
        def fill(match: re.Match) -> str:
            attrs = re.sub(r'width="\d+"', f'width="{width}"', match.group(3))
            attrs = re.sub(r'height="\d+"', f'height="{height}"', attrs)
            return match.group(1) + thumb + match.group(2) + attrs + match.group(4)

        page, count = pattern.subn(fill, page, count=1)
        if count:
            replaced.append(key)
        else:
            missing.append(key)

    if sources:
        entries = ", ".join(f'"{key}": "{value}"' for key, value in sources.items())
        page = re.sub(
            r"const sources = \{.*?\};",
            "const sources = {" + entries + "};",
            page,
            flags=re.S,
        )

    PAGE.write_text(page, encoding="utf8")
    print(f"embed: filled {len(replaced)} plates — {PAGE.stat().st_size // 1024} KB")
    if missing:
        print(f"embed: left alone (no capture): {', '.join(missing)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
