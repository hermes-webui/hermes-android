#!/usr/bin/env python3
"""Check repository Markdown for rendering breaks and dead in-repo links.

Deliberately NOT a Markdown style linter. It does not care about line length,
heading levels, list markers, or trailing whitespace. It reports only two things:

1. Rendering breaks - a link whose destination is split across a newline, or an
   inline link that is never closed. Both render as literal `[text](` garbage.
2. Dead in-repo links - a relative link or image whose target file does not
   exist. External `http(s)`/`mailto` links are left to the link checker in CI,
   which is informational because the network is not a build dependency.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

FENCE_RE = re.compile(r"^\s*(```|~~~)")
INLINE_LINK_RE = re.compile(r"(!?)\[(?P<text>[^\]]*)\]\((?P<dest>[^()\s]*)")
# `[text](` with no destination and no closing paren on the same line.
UNCLOSED_LINK_RE = re.compile(r"!?\[[^\]]*\]\([^)]*$")
EXTERNAL_SCHEME_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*:")


@dataclass(frozen=True)
class Finding:
    path: Path
    line: int
    message: str

    def render(self, root: Path) -> str:
        relative = self.path.relative_to(root).as_posix()
        return f"{relative}:{self.line}: {self.message}"


def strip_code_fences(lines: list[str]) -> list[tuple[int, str]]:
    """Return `(1-based line number, text)` for lines outside fenced code."""
    result = []
    in_fence = False
    for number, line in enumerate(lines, start=1):
        if FENCE_RE.match(line):
            in_fence = not in_fence
            continue
        if not in_fence:
            result.append((number, line))
    return result


def check_rendering_breaks(path: Path, lines: list[tuple[int, str]]) -> list[Finding]:
    findings = []
    for number, line in lines:
        # Inline code can legitimately contain an unbalanced bracket sequence.
        without_code = re.sub(r"`[^`]*`", "", line)
        if UNCLOSED_LINK_RE.search(without_code):
            findings.append(
                Finding(path, number, "unclosed inline link - renders as literal text")
            )
    return findings


def check_repo_links(root: Path, path: Path, lines: list[tuple[int, str]]) -> list[Finding]:
    findings = []
    for number, line in lines:
        without_code = re.sub(r"`[^`]*`", "", line)
        for match in INLINE_LINK_RE.finditer(without_code):
            dest = match.group("dest")
            if not dest or dest.startswith("#"):
                continue
            if EXTERNAL_SCHEME_RE.match(dest):
                continue
            target_path = dest.split("#", 1)[0]
            if not target_path:
                continue
            base = root if target_path.startswith("/") else path.parent
            target = (base / target_path.lstrip("/")).resolve()
            if not target.exists():
                findings.append(Finding(path, number, f"link target not found: {dest}"))
    return findings


def check_file(root: Path, path: Path) -> list[Finding]:
    lines = strip_code_fences(path.read_text(encoding="utf-8").splitlines())
    return check_rendering_breaks(path, lines) + check_repo_links(root, path, lines)


def discover(root: Path) -> list[Path]:
    skip = {".git", "build", "node_modules", ".gradle", ".idea"}
    return sorted(
        path
        for path in root.rglob("*.md")
        if not skip & set(path.relative_to(root).parts)
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", help="Markdown files (default: whole repo)")
    parser.add_argument("--root", default=str(ROOT), help="repository root")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    targets = [Path(p).resolve() for p in args.paths] if args.paths else discover(root)
    targets = [path for path in targets if path.is_file()]

    findings = [finding for path in targets for finding in check_file(root, path)]
    for finding in findings:
        print(finding.render(root), file=sys.stderr)

    print(f"Checked {len(targets)} Markdown file(s); {len(findings)} problem(s) found.")
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
