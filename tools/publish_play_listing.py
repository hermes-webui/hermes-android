"""Publish reviewed English Play listing text, then verify the committed API state."""

import argparse
from pathlib import Path


PACKAGE_NAME = "com.hermeswebui.android"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE_NAME}/edits"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def read_descriptions(directory: Path) -> dict[str, str]:
    result = {
        "shortDescription": (directory / "short_description.txt").read_text(encoding="utf-8").strip(),
        "fullDescription": (directory / "full_description.txt").read_text(encoding="utf-8").strip(),
    }
    for field, limit in (("shortDescription", 80), ("fullDescription", 4000)):
        if not 0 < len(result[field]) <= limit:
            raise ValueError(f"{field} must contain 1-{limit} characters")
    return result


def publish_listing(
    session,
    descriptions: dict[str, str],
    *,
    expected_production_version_code: int | None = None,
    expected_play_notes: str | None = None,
) -> None:
    if (expected_production_version_code is None) != (expected_play_notes is None):
        raise ValueError("Production version code and Play notes must be supplied together")

    def request(method: str, url: str, **kwargs):
        response = session.request(method, url, timeout=60, **kwargs)
        response.raise_for_status()
        return response.json() if response.content else {}

    edit_id = request("POST", API, json={})["id"]
    committed = False
    try:
        listing_url = f"{API}/{edit_id}/listings/en-US"
        existing = request("GET", listing_url)
        # Preserve the title and promotional video; only descriptions are being changed.
        updated = {key: existing[key] for key in ("title", "video") if key in existing}
        updated.update(descriptions)
        request("PUT", listing_url, json=updated)
        request("POST", f"{API}/{edit_id}:commit", json={})
        committed = True
    finally:
        if not committed:
            request("DELETE", f"{API}/{edit_id}")

    verification_id = request("POST", API, json={})["id"]
    try:
        actual = request("GET", f"{API}/{verification_id}/listings/en-US")
        for field, expected in updated.items():
            if actual.get(field) != expected:
                raise RuntimeError(f"Committed Play listing readback mismatch: {field}")
        if expected_production_version_code is not None and expected_play_notes is not None:
            track = request("GET", f"{API}/{verification_id}/tracks/production")
            releases = [
                release for release in track.get("releases", [])
                if str(expected_production_version_code) in release.get("versionCodes", [])
                and release.get("status") == "completed"
            ]
            if track.get("track") != "production" or not releases:
                raise RuntimeError(
                    f"Play production track readback mismatch for version code {expected_production_version_code}"
                )
            expected_notes = expected_play_notes.replace("\r\n", "\n").rstrip("\n")
            if not any(
                note.get("language") == "en-US"
                and isinstance(note.get("text"), str)
                and note["text"].replace("\r\n", "\n").rstrip("\n") == expected_notes
                for release in releases for note in release.get("releaseNotes", [])
            ):
                raise RuntimeError("Play production release notes readback mismatch")
    finally:
        request("DELETE", f"{API}/{verification_id}")
    print("Play en-US descriptions committed and verified through a fresh Publisher API edit.")
    if expected_production_version_code is not None:
        print(f"Play production track readback verified version code {expected_production_version_code}, "
              "completed status, and en-US release notes.")
    print("Public storefront visibility may still depend on Google Play review and propagation.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--credentials", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, default=Path("store/google-play/en-US"))
    parser.add_argument("--expected-production-version-code", type=int)
    parser.add_argument("--expected-play-notes", type=Path)
    args = parser.parse_args()
    if (args.expected_production_version_code is None) != (args.expected_play_notes is None):
        parser.error("--expected-production-version-code and --expected-play-notes must be supplied together")
    descriptions = read_descriptions(args.metadata)
    expected_play_notes = (
        args.expected_play_notes.read_text(encoding="utf-8") if args.expected_play_notes is not None else None
    )
    from google.auth.transport.requests import AuthorizedSession
    from google.oauth2.service_account import Credentials

    credentials = Credentials.from_service_account_file(str(args.credentials), scopes=[SCOPE])
    with AuthorizedSession(credentials) as session:
        publish_listing(
            session, descriptions,
            expected_production_version_code=args.expected_production_version_code,
            expected_play_notes=expected_play_notes,
        )


if __name__ == "__main__":
    main()
