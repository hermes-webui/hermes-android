import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import bump_version  # noqa: E402


def _fixture(root: Path, newline: str = "\n") -> None:
    (root / "app").mkdir()
    (root / "app" / "build.gradle.kts").write_bytes(
        newline.join(['val appVersionName = "1.2.0"', "other"]).encode()
    )
    (root / "README.md").write_bytes(
        newline.join(["- Version name: `1.2.0`", "- Version code: `10200`", "tail"]).encode()
    )
    (root / "ROADMAP.md").write_bytes(
        newline.join(
            ["| REL-035 | d | Release preparation | old |", "| REL-036 | d | Release preparation | newest |", "", "after"]
        ).encode()
    )
    changelogs = root / "fastlane" / "metadata" / "android" / "en-US" / "changelogs"
    changelogs.mkdir(parents=True)
    (changelogs / "10200.txt").write_text("old\n")


class BumpVersionTests(unittest.TestCase):
    def _run(self, newline: str = "\n", *argv: str) -> Path:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        _fixture(root, newline)
        patches = {
            "ROOT": root,
            "GRADLE": root / "app" / "build.gradle.kts",
            "README": root / "README.md",
            "ROADMAP": root / "ROADMAP.md",
            "CHANGELOGS": root / "fastlane" / "metadata" / "android" / "en-US" / "changelogs",
        }
        with patch.multiple(bump_version, **patches):
            bump_version.main(list(argv))
        return root

    ARGS = ("1.2.1", "--change", "Fixed it.", "--summary", "a fix (Issue #1)", "--date", "2026-11-08")

    def test_version_code_matches_gradle_formula(self) -> None:
        self.assertEqual(bump_version.version_code("1.2.1"), 10201)
        self.assertEqual(bump_version.version_code("2.0.0"), 20000)

    def test_updates_all_four_files(self) -> None:
        root = self._run("\n", *self.ARGS)
        self.assertIn('val appVersionName = "1.2.1"', (root / "app/build.gradle.kts").read_text())
        readme = (root / "README.md").read_text()
        self.assertIn("- Version name: `1.2.1`", readme)
        self.assertIn("- Version code: `10201`", readme)
        changelog = root / "fastlane/metadata/android/en-US/changelogs/10201.txt"
        self.assertEqual(changelog.read_text(), "Fixed it.\n")
        roadmap = (root / "ROADMAP.md").read_text().split("\n")
        self.assertTrue(roadmap[2].startswith("| REL-037 | 2026-11-08 | Release preparation |"))
        self.assertIn("v1.2.1 (version code 10201)", roadmap[2])
        self.assertIn("A patch bump", roadmap[2])
        self.assertEqual(roadmap[3:], ["", "after"])

    def test_inserts_release_row_after_highest_existing_release_id(self) -> None:
        root = tempfile.TemporaryDirectory()
        self.addCleanup(root.cleanup)
        path = Path(root.name)
        _fixture(path)
        roadmap_path = path / "ROADMAP.md"
        roadmap_path.write_text(
            "| REL-035 | d | Release preparation | old |"
            "\n| REL-036 | d | Release preparation | newest |"
            "\n| REL-034 | d | Release preparation | older, recorded later |"
            "\n\nafter\n"
        )
        patches = {
            "ROOT": path,
            "GRADLE": path / "app" / "build.gradle.kts",
            "README": path / "README.md",
            "ROADMAP": roadmap_path,
            "CHANGELOGS": path / "fastlane" / "metadata" / "android" / "en-US" / "changelogs",
        }
        with patch.multiple(bump_version, **patches):
            bump_version.main(list(self.ARGS))

        roadmap = roadmap_path.read_text().splitlines()
        self.assertTrue(roadmap[2].startswith("| REL-037 |"))
        self.assertEqual(roadmap[3], "| REL-034 | d | Release preparation | older, recorded later |")

    def test_preserves_crlf_line_endings(self) -> None:
        root = self._run("\r\n", *self.ARGS)
        for name in ("README.md", "ROADMAP.md"):
            data = (root / name).read_bytes()
            self.assertNotIn(b"\n", data.replace(b"\r\n", b""), name)

    def test_rejects_non_increasing_version_and_bad_format(self) -> None:
        for version in ("1.2.0", "1.1.9", "1.2", "v1.2.1"):
            with self.subTest(version=version), self.assertRaises(SystemExit):
                self._run("\n", version, "--change", "x", "--summary", "y")

    def test_dry_run_writes_nothing(self) -> None:
        root = self._run("\n", *self.ARGS, "--dry-run")
        self.assertIn('"1.2.0"', (root / "app/build.gradle.kts").read_text())
        self.assertFalse((root / "fastlane/metadata/android/en-US/changelogs/10201.txt").exists())


if __name__ == "__main__":
    unittest.main()
