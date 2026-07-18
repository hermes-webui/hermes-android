import tempfile
import unittest
from pathlib import Path

from tools import bump_gradle_version


class BumpGradleVersionTests(unittest.TestCase):
    def test_parse_semver_accepts_v_prefix(self) -> None:
        self.assertEqual(bump_gradle_version.parse_semver("v1.2.3"), (1, 2, 3))
        self.assertEqual(bump_gradle_version.parse_semver("1.2.3"), (1, 2, 3))

    def test_parse_semver_rejects_invalid(self) -> None:
        with self.assertRaises(ValueError):
            bump_gradle_version.parse_semver("1.2")

    def test_replace_version_updates_first_assignment(self) -> None:
        original = 'val appVersionName = "0.1.10"\nval another = "keep"\n'
        updated = bump_gradle_version.replace_version(original, "0.1.11")
        self.assertIn('val appVersionName = "0.1.11"', updated)
        self.assertIn('val another = "keep"', updated)

    def test_version_code_is_derived_from_semver(self) -> None:
        self.assertEqual(bump_gradle_version.version_code("1.2.3"), 10203)

    def test_replace_readme_metadata_updates_displayed_release_state(self) -> None:
        original = "\n".join(
            [
                "Current pre-release version: `v0.1.15`.",
                "",
                "- Version name: `0.1.15`",
                "- Version code: `115` (derived from semantic version as `major*10000 + minor*100 + patch`)",
            ]
        )

        updated = bump_gradle_version.replace_readme_metadata(original, "0.1.16")

        self.assertIn("Current pre-release version: `v0.1.16`.", updated)
        self.assertIn("- Version name: `0.1.16`", updated)
        self.assertIn("- Version code: `116`", updated)

    def test_replace_readme_metadata_allows_current_metadata_without_legacy_pre_release_line(self) -> None:
        original = "\n".join(
            [
                "- Version name: `0.1.24`",
                "- Version code: `124`",
            ]
        )

        updated = bump_gradle_version.replace_readme_metadata(original, "0.1.25")

        self.assertEqual(
            updated,
            "- Version name: `0.1.25`\n- Version code: `125`",
        )

    def test_bump_from_latest_tag_writes_next_patch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            gradle_file = Path(tmp_dir) / "build.gradle.kts"
            gradle_file.write_text('val appVersionName = "0.1.10"\n', encoding="utf-8")

            next_version = bump_gradle_version.bump_patch(
                bump_gradle_version.parse_semver("v0.1.10")
            )
            self.assertEqual(next_version, "0.1.11")

            updated = bump_gradle_version.replace_version(
                gradle_file.read_text(encoding="utf-8"),
                next_version,
            )
            gradle_file.write_text(updated, encoding="utf-8")

            self.assertIn(
                'val appVersionName = "0.1.11"',
                gradle_file.read_text(encoding="utf-8"),
            )

    def test_when_gradle_ahead_of_latest_tag_next_version_uses_gradle(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            gradle_file = Path(tmp_dir) / "build.gradle.kts"
            gradle_file.write_text('val appVersionName = "0.2.0"\n', encoding="utf-8")

            source_next = bump_gradle_version.bump_patch(
                bump_gradle_version.parse_semver("v0.1.10")
            )
            current = bump_gradle_version.read_current_version(
                gradle_file.read_text(encoding="utf-8")
            )
            if bump_gradle_version.parse_semver(source_next) <= bump_gradle_version.parse_semver(current):
                source_next = bump_gradle_version.bump_patch(
                    bump_gradle_version.parse_semver(current)
                )

            updated = bump_gradle_version.replace_version(
                gradle_file.read_text(encoding="utf-8"),
                source_next,
            )
            gradle_file.write_text(updated, encoding="utf-8")

            self.assertIn(
                'val appVersionName = "0.2.1"',
                gradle_file.read_text(encoding="utf-8"),
            )


if __name__ == "__main__":
    unittest.main()

