import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

from check_markdown import check_file, discover, main  # noqa: E402


class MarkdownCheckTests(unittest.TestCase):
    def _check(self, text: str, extra_files: tuple[str, ...] = ()) -> list[str]:
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in extra_files:
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("", encoding="utf-8")
            doc = root / "doc.md"
            doc.write_text(text, encoding="utf-8")
            return [finding.message for finding in check_file(root, doc)]

    def test_clean_document_reports_nothing(self) -> None:
        self.assertEqual(
            self._check("See [the guide](guide.md) and [GitHub](https://github.com).", ("guide.md",)),
            [],
        )

    def test_unclosed_inline_link_is_reported(self) -> None:
        findings = self._check("Read [the guide](guide.md\nfor details.")
        self.assertIn("unclosed inline link - renders as literal text", findings)

    def test_destination_split_across_a_newline_is_reported(self) -> None:
        findings = self._check("Read [the guide](\nguide.md) now.")
        self.assertIn("unclosed inline link - renders as literal text", findings)

    def test_missing_relative_target_is_reported(self) -> None:
        self.assertEqual(
            self._check("See [the guide](guide.md)."),
            ["link target not found: guide.md"],
        )

    def test_existing_relative_target_passes(self) -> None:
        self.assertEqual(self._check("See [the guide](guide.md).", ("guide.md",)), [])

    def test_anchor_on_a_real_file_passes(self) -> None:
        self.assertEqual(
            self._check("See [setup](guide.md#setup).", ("guide.md",)),
            [],
        )

    def test_root_relative_target_resolves_from_the_repository_root(self) -> None:
        self.assertEqual(
            self._check("See [the guide](/docs/guide.md).", ("docs/guide.md",)),
            [],
        )

    def test_bare_anchors_and_external_schemes_are_ignored(self) -> None:
        self.assertEqual(
            self._check("[top](#top) [mail](mailto:a@b.c) [site](https://example.com)"),
            [],
        )

    def test_image_targets_are_checked(self) -> None:
        self.assertEqual(
            self._check("![logo](assets/logo.png)"),
            ["link target not found: assets/logo.png"],
        )

    def test_fenced_code_blocks_are_skipped(self) -> None:
        text = "```\n[not a link](missing.md)\n```\n"
        self.assertEqual(self._check(text), [])

    def test_inline_code_is_not_parsed_as_a_link(self) -> None:
        self.assertEqual(self._check("Use `[text](dest)` as the format."), [])


class RepositoryDocsTests(unittest.TestCase):
    def test_generated_and_vendored_directories_are_excluded(self) -> None:
        discovered = {path.relative_to(ROOT).as_posix() for path in discover(ROOT)}
        self.assertIn("README.md", discovered)
        self.assertIn("AGENTS.md", discovered)
        for path in discovered:
            self.assertNotIn("node_modules", path)
            self.assertFalse(path.startswith("build/"), path)

    def test_repository_markdown_is_currently_clean(self) -> None:
        self.assertEqual(main(["--root", str(ROOT)]), 0)


if __name__ == "__main__":
    unittest.main()
