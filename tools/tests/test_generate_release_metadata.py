import tempfile
import unittest
from pathlib import Path

from tools import generate_release_metadata


class GenerateReleaseMetadataTests(unittest.TestCase):
    def test_github_notes_rename_generated_heading(self) -> None:
        notes = "## What's Changed\n\n- Fixed settings\n"

        self.assertEqual(
            generate_release_metadata.format_github_release_notes(notes),
            "## What's New\n\n- Fixed settings\n",
        )

    def test_github_notes_add_heading_when_missing(self) -> None:
        self.assertEqual(
            generate_release_metadata.format_github_release_notes("- Fixed settings"),
            "## What's New\n\n- Fixed settings\n",
        )

    def test_github_notes_expand_bare_pull_request_reference(self) -> None:
        result = generate_release_metadata.format_github_release_notes(
            "## What's Changed\n\n- Fix settings by @contributor in #123"
        )

        self.assertIn(
            "[PR #123](https://github.com/hermes-webui/hermes-android/pull/123)",
            result,
        )

    def test_play_notes_clean_generated_bullets_and_append_report_line(self) -> None:
        notes = "\n".join(
            [
                "## What's Changed",
                "- Fix settings issue #90 by @contributor in #123",
                "* Add `security` checks #124",
                "**Full Changelog**: https://example.test/compare/v1...v2",
            ]
        )

        result = generate_release_metadata.format_play_whats_new(notes)

        self.assertIn("- Fix settings issue #90", result)
        self.assertIn("PR #123: https://github.com/hermes-webui/hermes-android/pull/123", result)
        self.assertIn("#90: https://github.com/hermes-webui/hermes-android/issues/90", result)
        self.assertIn("- Add security checks #124", result)
        self.assertIn("#124: https://github.com/hermes-webui/hermes-android/issues/124", result)
        self.assertNotIn("@contributor", result)
        self.assertNotIn("Full Changelog", result)
        self.assertTrue(result.rstrip().endswith(generate_release_metadata.DEFAULT_REPORT_LINE))
        self.assertLessEqual(len(result.rstrip()), generate_release_metadata.DEFAULT_PLAY_MAX_CHARS)

    def test_play_notes_deduplicate_and_fall_back(self) -> None:
        duplicate = "- Fixed settings\n- Fixed settings"
        self.assertEqual(
            generate_release_metadata.format_play_whats_new(duplicate).count("- Fixed settings"),
            1,
        )
        self.assertIn(
            "- Bug fixes and Android wrapper improvements.",
            generate_release_metadata.format_play_whats_new("## Empty"),
        )

    def test_play_notes_respect_length_limit(self) -> None:
        notes = "\n".join(f"- {'x' * 200} {index}" for index in range(6))

        result = generate_release_metadata.format_play_whats_new(notes, max_chars=160)

        self.assertLessEqual(len(result.rstrip()), 160)
        self.assertTrue(result.rstrip().endswith(generate_release_metadata.DEFAULT_REPORT_LINE))

    def test_cli_writes_both_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            source = root / "generated.md"
            github_output = root / "release-notes.md"
            play_output = root / "play" / "whatsnew-en-US"
            source.write_text("## What's Changed\n\n- Fixed settings\n", encoding="utf-8")

            exit_code = generate_release_metadata.main(
                [
                    "--input-file",
                    str(source),
                    "--github-output",
                    str(github_output),
                    "--play-output",
                    str(play_output),
                ]
            )

            self.assertEqual(exit_code, 0)
            self.assertTrue(github_output.read_text(encoding="utf-8").startswith("## What's New"))
            self.assertIn("Fixed settings", play_output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
