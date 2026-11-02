"""Contracts that keep F-Droid builds and auto-updates working with no per-release work.

F-Droid builds `assembleRelease` from each `v<version>` tag the release orchestrator
creates, reading the version from the tagged README and Gradle file through the
recipe's UpdateCheckData. These tests fail when that hand-off would silently break.
"""

import re
import unittest

from tools.tests.test_workflow_contracts import ROOT, read_workflow

RECIPE = ROOT / "fdroid" / "com.hermeswebui.android.fdroid.yml"
GRADLE = ROOT / "app" / "build.gradle.kts"
README = ROOT / "README.md"
FASTLANE = ROOT / "fastlane" / "metadata" / "android" / "en-US"

# Mirrors fdroidserver scanner.get_gradle_compile_commands for `gradle: [yes]` builds:
# only the default and `release` configurations are scanned, anchored at line start.
SCANNED_CONFIGURATION_RE = re.compile(
    r"""\s*['"]?(release)?(alias|api|apk|classpath|compile|compileOnly|id|implementation|provided|runtimeOnly)""",
    re.IGNORECASE,
)


def recipe_field(name: str) -> str:
    match = re.search(rf"^{name}:\s*(.+)$", RECIPE.read_text(encoding="utf-8"), re.MULTILINE)
    if match is None:
        raise AssertionError(f"F-Droid recipe is missing {name}")
    return match.group(1).strip().strip("'")


class FDroidRecipeTests(unittest.TestCase):
    def test_license_is_mit_everywhere(self) -> None:
        self.assertTrue((ROOT / "LICENSE").read_text(encoding="utf-8").startswith("MIT License"))
        self.assertEqual(recipe_field("License"), "MIT")

    def test_update_check_data_reads_tagged_version(self) -> None:
        code_file, code_regex, name_file, name_regex = recipe_field("UpdateCheckData").split("|")
        code = re.search(code_regex, (ROOT / code_file).read_text(encoding="utf-8"))
        name = re.search(name_regex, (ROOT / name_file).read_text(encoding="utf-8"))
        self.assertIsNotNone(code, "UpdateCheckData versionCode regex no longer matches")
        self.assertIsNotNone(name, "UpdateCheckData versionName regex no longer matches")
        major, minor, patch = (int(part) for part in name.group(1).split("."))
        self.assertEqual(int(code.group(1)), major * 10_000 + minor * 100 + patch)

    def test_tag_pattern_matches_orchestrator_tags(self) -> None:
        mode = recipe_field("UpdateCheckMode")
        self.assertTrue(mode.startswith("Tags "))
        version = re.search(r'val appVersionName = "(.+)"', GRADLE.read_text(encoding="utf-8")).group(1)
        self.assertRegex(f"v{version}", mode.removeprefix("Tags "))
        self.assertIn('echo "tag_name=v$version_name"', read_workflow("1-orchestration-release.yml"))

    def test_recipe_builds_play_free_release_build_type(self) -> None:
        recipe = RECIPE.read_text(encoding="utf-8")
        self.assertRegex(recipe, r"subdir: app\n\s+gradle:\n\s+- yes")
        gradle = GRADLE.read_text(encoding="utf-8")
        release_block = gradle.split("        release {", 1)[1].split("\n        }", 1)[0]
        self.assertIn('applicationIdSuffix = ".fdroid"', release_block)
        self.assertIn('"UPDATE_CHANNEL", "\\"none\\""', release_block)

    def test_play_core_never_uses_a_configuration_fdroid_scans(self) -> None:
        offending = [
            line.strip()
            for line in GRADLE.read_text(encoding="utf-8").splitlines()
            if SCANNED_CONFIGURATION_RE.match(line) and re.search(r"libs\.play\b|com\.google\.android\.(play|gms)", line)
        ]
        self.assertEqual(offending, [])
        self.assertIn('"playImplementation"(libs.play.app.update)', GRADLE.read_text(encoding="utf-8"))

    def test_fastlane_store_text_fits_fdroid_limits(self) -> None:
        title = (FASTLANE / "title.txt").read_text(encoding="utf-8").strip()
        short = (FASTLANE / "short_description.txt").read_text(encoding="utf-8").strip()
        full = (FASTLANE / "full_description.txt").read_text(encoding="utf-8").strip()
        self.assertTrue(0 < len(title) <= 50)
        self.assertTrue(0 < len(short) <= 80)
        self.assertTrue(0 < len(full) <= 4000)


if __name__ == "__main__":
    unittest.main()
