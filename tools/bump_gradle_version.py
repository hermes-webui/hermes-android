#!/usr/bin/env python3
"""Bump appVersionName in app/build.gradle.kts from the latest published release tag.

- Reads the current appVersionName from Gradle.
- Computes the next patch version from a source tag (vX.Y.Z or X.Y.Z).
- Updates only the appVersionName assignment in-place.
- Prints the selected next version to stdout for workflow logging.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

SEMVER_RE = re.compile(r"^(?:v)?(\d+)\.(\d+)\.(\d+)$")
APP_VERSION_RE = re.compile(r'^(\s*val\s+appVersionName\s*=\s*")(\d+\.\d+\.\d+)("\s*)$', re.MULTILINE)


def parse_semver(raw: str) -> tuple[int, int, int]:
    match = SEMVER_RE.match(raw.strip())
    if not match:
        raise ValueError(f"Unsupported version format: {raw!r}. Expected vX.Y.Z or X.Y.Z")
    major, minor, patch = match.groups()
    return int(major), int(minor), int(patch)


def bump_patch(version: tuple[int, int, int]) -> str:
    major, minor, patch = version
    return f"{major}.{minor}.{patch + 1}"


def read_current_version(contents: str) -> str:
    match = APP_VERSION_RE.search(contents)
    if not match:
        raise ValueError("Could not find `val appVersionName = \"x.y.z\"` in Gradle file")
    return match.group(2)


def replace_version(contents: str, next_version: str) -> str:
    def _replacement(match: re.Match[str]) -> str:
        return f"{match.group(1)}{next_version}{match.group(3)}"

    replaced, count = APP_VERSION_RE.subn(_replacement, contents, count=1)
    if count != 1:
        raise ValueError("Failed to update appVersionName in Gradle file")
    return replaced


def main() -> int:
    parser = argparse.ArgumentParser(description="Bump appVersionName in app/build.gradle.kts")
    parser.add_argument("--gradle-file", required=True, help="Path to app/build.gradle.kts")
    parser.add_argument(
        "--latest-tag",
        default="",
        help="Latest published release tag (vX.Y.Z). If omitted, current Gradle version is used as the source.",
    )
    args = parser.parse_args()

    gradle_path = pathlib.Path(args.gradle_file)
    contents = gradle_path.read_text(encoding="utf-8")
    current_version = read_current_version(contents)

    source_raw = args.latest_tag.strip() or current_version
    next_version = bump_patch(parse_semver(source_raw))

    # Prevent accidental backward/duplicate updates if Gradle is already ahead.
    if parse_semver(next_version) <= parse_semver(current_version):
        next_version = bump_patch(parse_semver(current_version))

    updated = replace_version(contents, next_version)
    gradle_path.write_text(updated, encoding="utf-8")

    print(next_version)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # pragma: no cover - simple CLI guard
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)

