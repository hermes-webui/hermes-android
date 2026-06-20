#!/usr/bin/env python3
"""Generate Play Store icon assets from the launcher vector geometry.

Outputs:
- play-store/icon-512.svg
- play-store/icon-512.png
"""

from __future__ import annotations

from pathlib import Path


SVG_CONTENT = """<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"512\" height=\"512\" viewBox=\"0 0 108 108\">\n  <defs>\n    <linearGradient id=\"staffGradient\" x1=\"54\" y1=\"29.25\" x2=\"54\" y2=\"81\" gradientUnits=\"userSpaceOnUse\">\n      <stop offset=\"0\" stop-color=\"#F5C542\" />\n      <stop offset=\"1\" stop-color=\"#D4961C\" />\n    </linearGradient>\n  </defs>\n\n  <!-- Opaque background to satisfy Play listing icon requirements. -->\n  <rect x=\"0\" y=\"0\" width=\"108\" height=\"108\" fill=\"#1A1A1A\" />\n\n  <path d=\"M54,29.25 a2.25,2.25 0 0,1 2.25,2.25 v47.25 a2.25,2.25 0 0,1 -2.25,2.25 a2.25,2.25 0 0,1 -2.25,-2.25 v-47.25 a2.25,2.25 0 0,1 2.25,-2.25Z\" fill=\"url(#staffGradient)\" />\n\n  <path d=\"M51.75,38.25 C45,33.75 33.75,33.75 29.25,38.25 C33.75,36 42.75,36 49.5,40.5Z\" fill=\"#F5C542\" fill-opacity=\"0.902\" />\n  <path d=\"M51.75,42.75 C47.25,39.375 38.25,39.375 33.75,42.75 C38.25,40.5 45,40.5 49.5,45Z\" fill=\"#F5C542\" fill-opacity=\"0.8\" />\n\n  <path d=\"M56.25,38.25 C63,33.75 74.25,33.75 78.75,38.25 C74.25,36 65.25,36 58.5,40.5Z\" fill=\"#F5C542\" fill-opacity=\"0.902\" />\n  <path d=\"M56.25,42.75 C60.75,39.375 69.75,39.375 74.25,42.75 C69.75,40.5 63,40.5 58.5,45Z\" fill=\"#D4961C\" fill-opacity=\"0.8\" />\n\n  <path d=\"M54,72 C42.75,67.5 40.5,60.75 47.25,56.25 C40.5,58.5 38.25,65.25 45,69.75 C38.25,63 42.75,51.75 51.75,49.5 C45,54 42.75,60.75 49.5,65.25\" fill=\"none\" stroke=\"#F5C542\" stroke-width=\"2.8\" stroke-linecap=\"round\" />\n  <path d=\"M56.25,72 C63.75,67.5 63.75,60.75 60.75,56.25 C63.75,58.5 69.75,65.25 63,69.75 C69.75,63 65.25,51.75 56.25,49.5 C63,54 65.25,60.75 58.5,65.25\" fill=\"none\" stroke=\"#D4961C\" stroke-width=\"2.8\" stroke-linecap=\"round\" />\n\n  <path d=\"M49.5,29.25 a4.5,4.5 0 1,0 9,0 a4.5,4.5 0 1,0 -9,0Z\" fill=\"#F5C542\" />\n  <path d=\"M51.75,29.25 a2.25,2.25 0 1,0 4.5,0 a2.25,2.25 0 1,0 -4.5,0Z\" fill=\"#FFF8E1\" fill-opacity=\"0.698\" />\n</svg>\n"""


def main() -> None:
    repo_root = Path(__file__).resolve().parents[1]
    out_dir = repo_root / "play-store"
    out_dir.mkdir(parents=True, exist_ok=True)

    svg_path = out_dir / "icon-512.svg"
    png_path = out_dir / "icon-512.png"

    svg_path.write_text(SVG_CONTENT, encoding="utf-8")

    try:
        import cairo
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "pycairo is required. Install with: py -3 -m pip install --user pycairo"
        ) from exc

    size = 512
    scale = size / 108.0
    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    ctx = cairo.Context(surface)
    ctx.scale(scale, scale)

    # Background
    ctx.set_source_rgb(0x1A / 255.0, 0x1A / 255.0, 0x1A / 255.0)
    ctx.rectangle(0, 0, 108, 108)
    ctx.fill()

    # Staff with vertical gradient
    grad = cairo.LinearGradient(54, 29.25, 54, 81)
    grad.add_color_stop_rgb(0.0, 0xF5 / 255.0, 0xC5 / 255.0, 0x42 / 255.0)
    grad.add_color_stop_rgb(1.0, 0xD4 / 255.0, 0x96 / 255.0, 0x1C / 255.0)
    x, y, w, h, r = 51.75, 29.25, 4.5, 51.75, 2.25
    ctx.new_path()
    ctx.move_to(x + r, y)
    ctx.line_to(x + w - r, y)
    ctx.arc(x + w - r, y + r, r, -1.57079632679, 0)
    ctx.line_to(x + w, y + h - r)
    ctx.arc(x + w - r, y + h - r, r, 0, 1.57079632679)
    ctx.line_to(x + r, y + h)
    ctx.arc(x + r, y + h - r, r, 1.57079632679, 3.14159265359)
    ctx.line_to(x, y + r)
    ctx.arc(x + r, y + r, r, 3.14159265359, 4.71238898038)
    ctx.close_path()
    ctx.set_source(grad)
    ctx.fill()

    def fill_path(r: int, g: int, b: int, a: float, path_fn) -> None:
        ctx.new_path()
        path_fn()
        ctx.set_source_rgba(r / 255.0, g / 255.0, b / 255.0, a)
        ctx.fill()

    fill_path(
        245,
        197,
        66,
        0.902,
        lambda: (
            ctx.move_to(51.75, 38.25),
            ctx.curve_to(45, 33.75, 33.75, 33.75, 29.25, 38.25),
            ctx.curve_to(33.75, 36, 42.75, 36, 49.5, 40.5),
            ctx.close_path(),
        ),
    )
    fill_path(
        245,
        197,
        66,
        0.8,
        lambda: (
            ctx.move_to(51.75, 42.75),
            ctx.curve_to(47.25, 39.375, 38.25, 39.375, 33.75, 42.75),
            ctx.curve_to(38.25, 40.5, 45, 40.5, 49.5, 45),
            ctx.close_path(),
        ),
    )
    fill_path(
        245,
        197,
        66,
        0.902,
        lambda: (
            ctx.move_to(56.25, 38.25),
            ctx.curve_to(63, 33.75, 74.25, 33.75, 78.75, 38.25),
            ctx.curve_to(74.25, 36, 65.25, 36, 58.5, 40.5),
            ctx.close_path(),
        ),
    )
    fill_path(
        212,
        150,
        28,
        0.8,
        lambda: (
            ctx.move_to(56.25, 42.75),
            ctx.curve_to(60.75, 39.375, 69.75, 39.375, 74.25, 42.75),
            ctx.curve_to(69.75, 40.5, 63, 40.5, 58.5, 45),
            ctx.close_path(),
        ),
    )

    # Left snake
    ctx.new_path()
    ctx.move_to(54, 72)
    ctx.curve_to(42.75, 67.5, 40.5, 60.75, 47.25, 56.25)
    ctx.curve_to(40.5, 58.5, 38.25, 65.25, 45, 69.75)
    ctx.curve_to(38.25, 63, 42.75, 51.75, 51.75, 49.5)
    ctx.curve_to(45, 54, 42.75, 60.75, 49.5, 65.25)
    ctx.set_source_rgb(245 / 255.0, 197 / 255.0, 66 / 255.0)
    ctx.set_line_width(2.8)
    ctx.set_line_cap(cairo.LineCap.ROUND)
    ctx.stroke()

    # Right snake
    ctx.new_path()
    ctx.move_to(56.25, 72)
    ctx.curve_to(63.75, 67.5, 63.75, 60.75, 60.75, 56.25)
    ctx.curve_to(63.75, 58.5, 69.75, 65.25, 63, 69.75)
    ctx.curve_to(69.75, 63, 65.25, 51.75, 56.25, 49.5)
    ctx.curve_to(63, 54, 65.25, 60.75, 58.5, 65.25)
    ctx.set_source_rgb(212 / 255.0, 150 / 255.0, 28 / 255.0)
    ctx.set_line_width(2.8)
    ctx.set_line_cap(cairo.LineCap.ROUND)
    ctx.stroke()

    # Orbs
    ctx.new_path()
    ctx.arc(54, 29.25, 4.5, 0, 6.28318530718)
    ctx.set_source_rgb(245 / 255.0, 197 / 255.0, 66 / 255.0)
    ctx.fill()

    ctx.new_path()
    ctx.arc(54, 29.25, 2.25, 0, 6.28318530718)
    ctx.set_source_rgba(1.0, 248 / 255.0, 225 / 255.0, 0.698)
    ctx.fill()

    surface.write_to_png(str(png_path))

    print(f"Wrote {svg_path}")
    print(f"Wrote {png_path}")


if __name__ == "__main__":
    main()


