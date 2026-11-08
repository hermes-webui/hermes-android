#!/usr/bin/env python3
"""Prepare release metadata for a new version in one step.

Edits the four places a release PR must keep in sync:

* ``appVersionName`` in ``app/build.gradle.kts``
* the README ``Version name`` / ``Version code`` lines
* the F-Droid changelog ``fastlane/.../changelogs/<versionCode>.txt``
* a new ``REL-nnn`` row in ``ROADMAP.md``

The version code follows Gradle: ``major * 10000 + minor * 100 + patch``. Nothing is committed or
pushed; review ``git diff`` and open the release PR as usual. See RELEASE.md.

Example::

    python tools/bump_version.py 1.2.1 \
        --change "Fixed pasting images in the Hermes Agent WebApp." \
        --summary "image paste for the Hermes Agent Webapp and Gboard (Issue #180)" \
        --kind patch
"""
from __future__ import annotations

import argparse
import datetime
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app" / "build.gradle.kts"
README = ROOT / "README.md"
ROADMAP = ROOT / "ROADMAP.md"
CHANGELOGS = ROOT / "fastlane" / "metadata" / "android" / "en-US" / "changelogs"

SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
GRADLE_VERSION = re.compile(r'^val appVersionName = "(\d+\.\d+\.\d+)"', re.MULTILINE)
README_NAME = re.compile(r"^- Version name: `[^`]*`", re.MULTILINE)
README_CODE = re.compile(r"^- Version code: `[^`]*`", re.MULTILINE)
REL_ROW = re.compile(r"^\| REL-(\d+) \|", re.MULTILINE)

KIND_REASON = {
    "patch": "A patch bump because it fixes broken behavior with no new setting.",
    "minor": "A minor bump because it adds a user-facing feature with no breaking change.",
    "major": "A major bump because it changes behavior in a breaking way.",
}


def parse(version: str) -> tuple[int, int, int]:
    match = SEMVER.match(version)
    if not match:
        raise SystemExit(f"version must look like 1.2.3, got {version!r}")
    return tuple(int(part) for part in match.groups())  # type: ignore[return-value]


def version_code(version: str) -> int:
    major, minor, patch = parse(version)
    return major * 10000 + minor * 100 + patch


def read(path: Path) -> tuple[str, str]:
    """Return (text, newline) so CRLF checkouts stay CRLF."""
    raw = path.read_bytes().decode("utf-8")
    return raw, "\r\n" if "\r\n" in raw else "\n"


def write(path: Path, text: str) -> None:
    path.write_bytes(text.encode("utf-8"))


def current_version(gradle_text: str) -> str:
    match = GRADLE_VERSION.search(gradle_text)
    if not match:
        raise SystemExit("could not find `val appVersionName = \"x.y.z\"` in app/build.gradle.kts")
    return match.group(1)


def build_roadmap_row(number: int, today: str, version: str, code: int, summary: str, kind: str) -> str:
    return (
        f"| REL-{number:03d} | {today} | Release preparation | Prepared Android and README "
        f"metadata for v{version} (version code {code}): {summary}. {KIND_REASON[kind]} "
        "GitHub and Play production publication are pending the approved orchestration run; "
        "F-Droid picks up the tag after fdroiddata review (Issue #141). |"
    )


def plan(version: str, changes: list[str], summary: str, kind: str, today: str) -> dict[Path, str]:
    """Compute every file's new text without touching disk."""
    gradle, gradle_nl = read(GRADLE)
    old = current_version(gradle)
    if parse(version) <= parse(old):
        raise SystemExit(f"{version} must be greater than the current version {old}")
    code = version_code(version)
    changelog = CHANGELOGS / f"{code}.txt"
    if changelog.exists():
        raise SystemExit(f"{changelog.relative_to(ROOT)} already exists")

    readme, _ = read(README)
    if not README_NAME.search(readme) or not README_CODE.search(readme):
        raise SystemExit("README.md is missing the `Version name` / `Version code` lines")
    roadmap, roadmap_nl = read(ROADMAP)
    rows = list(REL_ROW.finditer(roadmap))
    if not rows:
        raise SystemExit("ROADMAP.md has no REL-nnn rows to extend")
    next_rel = max(int(m.group(1)) for m in rows) + 1
    last_row_end = roadmap.index("\n", rows[-1].start())
    last_row_end = last_row_end - 1 if roadmap[last_row_end - 1] == "\r" else last_row_end

    new_row = build_roadmap_row(next_rel, today, version, code, summary, kind)
    return {
        GRADLE: GRADLE_VERSION.sub(f'val appVersionName = "{version}"', gradle, count=1),
        README: README_CODE.sub(
            f"- Version code: `{code}`",
            README_NAME.sub(f"- Version name: `{version}`", readme, count=1),
            count=1,
        ),
        changelog: "\n".join(change.strip() for change in changes) + "\n",
        ROADMAP: roadmap[:last_row_end] + roadmap_nl + new_row + roadmap[last_row_end:],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("version", help="new semantic version, e.g. 1.2.1")
    parser.add_argument(
        "--change",
        action="append",
        required=True,
        help="one user-facing F-Droid changelog line; repeat for several lines",
    )
    parser.add_argument("--summary", required=True, help="short phrase for the ROADMAP release row")
    parser.add_argument("--kind", choices=sorted(KIND_REASON), default="patch")
    parser.add_argument("--date", default=datetime.date.today().isoformat(), help="ROADMAP date (YYYY-MM-DD)")
    parser.add_argument("--dry-run", action="store_true", help="show the plan without writing files")
    args = parser.parse_args(argv)

    if not re.match(r"^\d{4}-\d{2}-\d{2}$", args.date):
        raise SystemExit("--date must be YYYY-MM-DD")
    updates = plan(args.version, args.change, args.summary, args.kind, args.date)
    for path in updates:
        print(("would update " if args.dry_run else "updating ") + str(path.relative_to(ROOT)))
    if not args.dry_run:
        for path, text in updates.items():
            write(path, text)
        print(f"Prepared v{args.version} (version code {version_code(args.version)}). Review `git diff`, then open the release PR.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
