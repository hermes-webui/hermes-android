#!/usr/bin/env python3
"""Extract the JavaScript that Android injects into the Hermes WebUI WebView.

The injected shims (viewport polyfill, mic fallback, Enter-key shim, sidebar
settings injector, notification bridge, route recovery) live inside Kotlin raw
strings, so the Kotlin compiler and Android Lint only ever see them as opaque
text. A typo, a `const` reassignment, or a reference to an undefined helper
compiles clean and only throws once a real WebView executes the page.

This script pulls each raw-string block out into a standalone `.js` file so
`node --check` and ESLint can inspect it. Extracted files are padded with blank
lines so that JavaScript line N maps to Kotlin line N, which keeps parser and
linter diagnostics pointing at the real source location.
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Kotlin sources that embed injected WebView JavaScript. Every raw string in
# these files is treated as JavaScript; add a file here when it starts carrying
# injected script text.
SOURCE_FILES = (
    "app/src/main/java/com/hermeswebui/android/webui/HermesWebUiScripts.kt",
    "app/src/main/java/com/hermeswebui/android/MainActivity.kt",
)

RAW_DELIMITER = '"""'
DECLARATION_RE = re.compile(r"\b(?:val|var|fun)\s+([A-Za-z_][A-Za-z0-9_]*)")
# Indentation of a top-level member inside an `object`/`class` body.
MEMBER_INDENT = 4
# Kotlin string templates: `${expr}` or `$identifier`.
INTERPOLATION_RE = re.compile(r"\$\{[^{}]*\}|\$[A-Za-z_][A-Za-z0-9_]*")
# Interpolated values are always JSON-quoted strings or booleans at the call
# site, so a string literal is a faithful stand-in for syntax/lint purposes.
INTERPOLATION_PLACEHOLDER = '"__HERMES_KOTLIN_INTERPOLATION__"'


class ExtractionError(RuntimeError):
    pass


def trim_indent(text: str) -> str:
    """Approximate Kotlin's `String.trimIndent()` for raw-string blocks."""
    lines = text.split("\n")
    indents = [len(line) - len(line.lstrip()) for line in lines if line.strip()]
    margin = min(indents) if indents else 0
    return "\n".join(line[margin:] if line.strip() else "" for line in lines)


def find_raw_string_blocks(source: str) -> list[tuple[int, str]]:
    """Return `(zero-based start line, block body)` for each Kotlin raw string."""
    delimiters = []
    index = source.find(RAW_DELIMITER)
    while index != -1:
        delimiters.append(index)
        index = source.find(RAW_DELIMITER, index + len(RAW_DELIMITER))

    if len(delimiters) % 2 != 0:
        raise ExtractionError("unbalanced raw-string delimiters")

    blocks = []
    for open_index, close_index in zip(delimiters[0::2], delimiters[1::2]):
        body_start = open_index + len(RAW_DELIMITER)
        body = source[body_start:close_index]
        start_line = source.count("\n", 0, body_start)
        blocks.append((start_line, body))
    return blocks


def declaration_name(source: str, block_start_line: int, fallback: str) -> str:
    """Name a block after the enclosing member declaration.

    Scanning backwards finds the nearest `val`/`fun`, but a builder function
    declares locals (`val quotedUrl = ...`) just above its returned raw string.
    Preferring the least-indented declaration walks out of those locals and up
    to the member that callers actually reference.
    """
    lines = source.split("\n")
    best_name = fallback
    best_indent = None
    for line in reversed(lines[: block_start_line + 1]):
        match = DECLARATION_RE.search(line)
        if not match:
            continue
        indent = len(line) - len(line.lstrip())
        if best_indent is None or indent < best_indent:
            best_name, best_indent = match.group(1), indent
        if indent <= MEMBER_INDENT:
            break
    return best_name


def extract_file(path: Path) -> list[tuple[str, str]]:
    """Return `(output file name, JavaScript text)` for one Kotlin source file."""
    source = path.read_text(encoding="utf-8")
    results = []
    for ordinal, (start_line, body) in enumerate(find_raw_string_blocks(source), start=1):
        script = INTERPOLATION_RE.sub(INTERPOLATION_PLACEHOLDER, trim_indent(body))
        name = declaration_name(source, start_line, fallback=f"block{ordinal}")
        # Pad so JS line numbers match the Kotlin file the block came from. The
        # body keeps its own leading newline, which accounts for content that
        # starts on the line after the opening delimiter.
        padded = "\n" * start_line + script
        results.append((f"{path.stem}.{name}.js", padded))
    return results


def extract_all(root: Path, out_dir: Path) -> list[Path]:
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    written = []
    for relative in SOURCE_FILES:
        path = root / relative
        if not path.exists():
            raise ExtractionError(f"missing source file: {relative}")
        for name, script in extract_file(path):
            target = out_dir / name
            if target.exists():
                raise ExtractionError(f"duplicate extracted script name: {name}")
            target.write_text(script, encoding="utf-8")
            written.append(target)

    if not written:
        raise ExtractionError("no injected scripts were extracted")
    return written


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out-dir",
        default="build/webui-scripts",
        help="directory to write extracted .js files into (recreated on each run)",
    )
    parser.add_argument("--root", default=str(ROOT), help="repository root")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    out_dir = Path(args.out_dir)
    if not out_dir.is_absolute():
        out_dir = root / out_dir

    try:
        written = extract_all(root, out_dir)
    except ExtractionError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    for path in written:
        print(path.relative_to(root).as_posix())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
