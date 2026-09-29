#!/usr/bin/env python3
"""Verify a staged Android APK's identity before release publication.

Checks the APK file SHA-256, package name, versionName, and versionCode
parsed with `aapt dump badging`, plus the signing certificate SHA-256 digest
from `apksigner verify --print-certs`. Only digests are printed — never
keystore paths, aliases, or passwords.

Exit code 0 when every check passes; non-zero with a clear error otherwise.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

PACKAGE_RE = re.compile(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", re.MULTILINE)
CERT_SHA256_RE = re.compile(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})")


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_tool(cmd: list[str]) -> str:
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    except FileNotFoundError as exc:
        raise RuntimeError(f"Required tool not found: {cmd[0]} ({exc})") from exc
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(f"`{' '.join(cmd[:2])} ...` failed (exit {result.returncode}): {detail[:500]}")
    return result.stdout


def find_build_tool(name: str) -> str | None:
    on_path = shutil.which(name)
    if on_path:
        return on_path

    for sdk_variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk_root = os.environ.get(sdk_variable)
        if not sdk_root:
            continue
        build_tools = Path(sdk_root) / "build-tools"
        if not build_tools.is_dir():
            continue
        versions = sorted(
            (
                directory for directory in build_tools.iterdir()
                if directory.is_dir() and re.fullmatch(r"\d+(?:\.\d+)*", directory.name)
            ),
            key=lambda directory: tuple(int(part) for part in directory.name.split(".")),
            reverse=True,
        )
        for directory in versions:
            found = shutil.which(name, path=str(directory))
            if found:
                return found
    return None


def parse_badging(output: str) -> tuple[str, int, str]:
    match = PACKAGE_RE.search(output)
    if not match:
        raise RuntimeError("Could not parse package/version from aapt badging output")
    package, version_code, version_name = match.groups()
    return package, int(version_code), version_name


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify staged release APK identity metadata.")
    parser.add_argument("--apk", required=True, help="Path to the staged APK file")
    parser.add_argument("--expected-package", required=True, help="Expected applicationId (package name)")
    parser.add_argument("--expected-version-name", required=True, help="Expected versionName including suffixes")
    parser.add_argument(
        "--expected-version-code",
        type=int,
        default=None,
        help="Expected versionCode; when omitted only the APK's own code is reported",
    )
    args = parser.parse_args()

    aapt = find_build_tool("aapt") or find_build_tool("aapt2")
    apksigner = find_build_tool("apksigner")
    if not aapt:
        print("ERROR: neither aapt nor aapt2 found on PATH or in the Android SDK", file=sys.stderr)
        return 1
    if not apksigner:
        print("ERROR: apksigner not found on PATH or in the Android SDK", file=sys.stderr)
        return 1

    try:
        apk_sha256 = sha256_of(args.apk)
        package, version_code, version_name = parse_badging(run_tool([aapt, "dump", "badging", args.apk]))
        cert_digest = CERT_SHA256_RE.search(run_tool([apksigner, "verify", "--print-certs", args.apk]))
    except (OSError, RuntimeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    if not cert_digest:
        print("ERROR: apksigner did not report a signing certificate SHA-256 digest", file=sys.stderr)
        return 1
    cert_sha256 = cert_digest.group(1).lower()

    failures = []
    if package != args.expected_package:
        failures.append(f"package: expected {args.expected_package!r}, got {package!r}")
    if version_name != args.expected_version_name:
        failures.append(f"versionName: expected {args.expected_version_name!r}, got {version_name!r}")
    if args.expected_version_code is not None and version_code != args.expected_version_code:
        failures.append(f"versionCode: expected {args.expected_version_code}, got {version_code}")

    print("## Staged APK verification")
    print()
    print(f"- APK file: `{args.apk}`")
    print(f"- Package: `{package}`")
    print(f"- Version name: `{version_name}`")
    print(f"- Version code: `{version_code}`")
    print(f"- APK SHA-256: `{apk_sha256}`")
    print(f"- Signing cert SHA-256: `{cert_sha256}`")

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    print("All identity checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
