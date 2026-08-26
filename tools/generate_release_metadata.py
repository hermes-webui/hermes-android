#!/usr/bin/env python3
"""Generate GitHub release notes and Play What's New text from generated notes."""

from __future__ import annotations

import argparse
import pathlib
import re
from collections.abc import Sequence

DEFAULT_PLAY_MAX_CHARS = 480
DEFAULT_REPORT_LINE = "Report issues through the in-app bug report tool."
DEFAULT_REPOSITORY = "hermes-webui/hermes-android"


def _pull_url(repository: str, number: str) -> str:
    return f"https://github.com/{repository}/pull/{number}"


def _issue_url(repository: str, number: str) -> str:
    return f"https://github.com/{repository}/issues/{number}"


def format_github_release_notes(
    generated_notes: str,
    repository: str = DEFAULT_REPOSITORY,
) -> str:
    notes = generated_notes.strip()
    if not notes:
        return "## What's New\n\n- Bug fixes and Android wrapper improvements.\n"

    notes = re.sub(r"^## What's Changed", "## What's New", notes, count=1)
    notes = re.sub(
        r"\s+in\s+#(\d+)\s*$",
        lambda match: f" in [PR #{match.group(1)}]({_pull_url(repository, match.group(1))})",
        notes,
        flags=re.MULTILINE,
    )
    if not re.search(r"^##\s", notes, flags=re.MULTILINE):
        notes = f"## What's New\n\n{notes}"
    return notes.rstrip() + "\n"


def _clean_play_bullet(raw_line: str, repository: str) -> str | None:
    line = raw_line.strip()
    if not line.startswith(("* ", "- ")):
        return None

    content = line[2:].strip()
    pull_numbers = re.findall(
        rf"https://github\.com/{re.escape(repository)}/pull/(\d+)",
        content,
        flags=re.IGNORECASE,
    )
    suffix = re.search(
        r"\s+by\s+@[A-Za-z0-9-]+(?:\s+in\s+(#(\d+)|https://github\.com/[^\s]+/pull/(\d+)))?\s*$",
        content,
        flags=re.IGNORECASE,
    )
    if suffix:
        suffix_pull = suffix.group(2) or suffix.group(3)
        if suffix_pull:
            pull_numbers.append(suffix_pull)
        content = content[: suffix.start()]

    content = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", content)
    content = re.sub(r"@[A-Za-z0-9-]+", "", content)
    content = content.replace("`", "")
    content = re.sub(r"\s+", " ", content).strip()
    if not content:
        return None

    issue_numbers = re.findall(r"#(\d+)", content)
    references: list[str] = []
    for number in dict.fromkeys(pull_numbers):
        references.append(f"PR #{number}: {_pull_url(repository, number)}")
    for number in dict.fromkeys(issue_numbers):
        if number not in pull_numbers:
            references.append(f"#{number}: {_issue_url(repository, number)}")

    bullet = f"- {content}"
    if references:
        bullet += " — " + "; ".join(references)
    return bullet


def format_play_whats_new(
    generated_notes: str,
    max_chars: int = DEFAULT_PLAY_MAX_CHARS,
    report_line: str = DEFAULT_REPORT_LINE,
    repository: str = DEFAULT_REPOSITORY,
) -> str:
    if max_chars <= len(report_line) + 3:
        raise ValueError("max_chars is too small for the required report line")

    bullets: list[str] = []
    for raw_line in generated_notes.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "Full Changelog" in line or "compare/" in line:
            continue
        if line.lower().startswith("new contributors"):
            continue
        bullet = _clean_play_bullet(line, repository)
        if bullet and bullet not in bullets:
            bullets.append(bullet)

    if not bullets:
        bullets = ["- Bug fixes and Android wrapper improvements."]

    selected: list[str] = []
    for bullet in bullets:
        candidate = "\n".join(selected + [bullet, "", report_line])
        if len(candidate) <= max_chars:
            selected.append(bullet)
        if len(selected) >= 4:
            break

    if not selected:
        available = max_chars - len(report_line) - 2
        selected = [bullets[0][:available].rstrip()]

    text = "\n".join(selected + ["", report_line])
    if len(text) > max_chars:
        available = max_chars - len(report_line) - 2
        text = "\n".join(selected)[:available].rstrip() + "\n\n" + report_line
    return text + "\n"


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-file", required=True)
    parser.add_argument("--github-output", required=True)
    parser.add_argument("--play-output", required=True)
    parser.add_argument("--play-max-chars", type=int, default=DEFAULT_PLAY_MAX_CHARS)
    parser.add_argument("--repository", default=DEFAULT_REPOSITORY)
    args = parser.parse_args(argv)

    generated_notes = pathlib.Path(args.input_file).read_text(encoding="utf-8")
    github_path = pathlib.Path(args.github_output)
    play_path = pathlib.Path(args.play_output)
    github_path.parent.mkdir(parents=True, exist_ok=True)
    play_path.parent.mkdir(parents=True, exist_ok=True)
    github_path.write_text(
        format_github_release_notes(generated_notes, repository=args.repository),
        encoding="utf-8",
    )
    play_path.write_text(
        format_play_whats_new(
            generated_notes,
            max_chars=args.play_max_chars,
            repository=args.repository,
        ),
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
