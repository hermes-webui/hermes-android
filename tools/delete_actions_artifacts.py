#!/usr/bin/env python3
"""Delete superseded GitHub Actions artifacts before a workflow uploads replacements."""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Iterable, Sequence
from typing import Any


def select_artifacts(
    artifacts: Iterable[dict[str, Any]],
    *,
    names: set[str],
    prefixes: Sequence[str],
) -> list[dict[str, Any]]:
    """Return artifacts whose names match an exact name or configured prefix."""
    if not names and not prefixes:
        raise ValueError("At least one artifact selector is required")

    return [
        artifact
        for artifact in artifacts
        if isinstance(artifact.get("name"), str)
        and (
            artifact["name"] in names
            or any(artifact["name"].startswith(prefix) for prefix in prefixes)
        )
    ]


class GitHubArtifactsClient:
    def __init__(self, *, api_url: str, repository: str, token: str) -> None:
        self.api_url = api_url.rstrip("/")
        self.repository = urllib.parse.quote(repository, safe="/")
        self.headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "User-Agent": "hermes-android-artifact-cleanup",
            "X-GitHub-Api-Version": "2022-11-28",
        }

    def _request(self, path: str, *, method: str = "GET") -> Any:
        request = urllib.request.Request(
            f"{self.api_url}{path}",
            headers=self.headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                if response.status == 204:
                    return None
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(
                f"GitHub API {method} {path} failed with HTTP {error.code}"
            ) from error
        except urllib.error.URLError as error:
            raise RuntimeError(
                f"GitHub API {method} {path} failed: {error.reason}"
            ) from error

    def list_artifacts(self) -> list[dict[str, Any]]:
        artifacts: list[dict[str, Any]] = []
        page = 1
        while True:
            payload = self._request(
                f"/repos/{self.repository}/actions/artifacts?per_page=100&page={page}"
            )
            page_artifacts = payload.get("artifacts", [])
            if not isinstance(page_artifacts, list):
                raise RuntimeError("GitHub API returned an invalid artifacts response")
            artifacts.extend(page_artifacts)
            if len(page_artifacts) < 100:
                return artifacts
            page += 1

    def delete_artifact(self, artifact_id: int) -> None:
        self._request(
            f"/repos/{self.repository}/actions/artifacts/{artifact_id}",
            method="DELETE",
        )


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", action="append", default=[], help="Exact artifact name")
    parser.add_argument("--prefix", action="append", default=[], help="Artifact name prefix")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    if not args.name and not args.prefix:
        print("At least one artifact selector is required", file=sys.stderr)
        return 2

    token = os.environ.get("GH_TOKEN")
    repository = os.environ.get("GITHUB_REPOSITORY")
    if not token or not repository:
        print("GH_TOKEN and GITHUB_REPOSITORY are required", file=sys.stderr)
        return 2

    client = GitHubArtifactsClient(
        api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        repository=repository,
        token=token,
    )
    artifacts = select_artifacts(
        client.list_artifacts(), names=set(args.name), prefixes=tuple(args.prefix)
    )
    for artifact in artifacts:
        artifact_id = artifact.get("id")
        if not isinstance(artifact_id, int):
            raise RuntimeError("GitHub API returned an artifact without a numeric id")
        print(f"Deleting artifact {artifact_id}: {artifact.get('name', '<unnamed>')}")
        client.delete_artifact(artifact_id)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
