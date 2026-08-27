import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

from extract_webui_scripts import (  # noqa: E402
    SOURCE_FILES,
    ExtractionError,
    declaration_name,
    extract_all,
    extract_file,
    find_raw_string_blocks,
    trim_indent,
)


SAMPLE_KOTLIN = '''package com.example

object Sample {
    val firstScript = """
        (function() {
          var a = 1;
        })();
    """.trimIndent()

    fun buildSecond(url: String): String {
        val quotedUrl = JSONObject.quote(url)
        return """
            (function() {
              var target = $quotedUrl;
            })();
        """.trimIndent()
    }
}
'''


class TrimIndentTests(unittest.TestCase):
    def test_strips_the_common_margin_and_blanks_whitespace_only_lines(self) -> None:
        text = "\n        a\n\n          b\n        "
        self.assertEqual(trim_indent(text), "\na\n\n  b\n")


class RawStringScanTests(unittest.TestCase):
    def test_finds_each_block_with_its_start_line(self) -> None:
        blocks = find_raw_string_blocks(SAMPLE_KOTLIN)
        self.assertEqual(len(blocks), 2)
        self.assertEqual([start for start, _ in blocks], [3, 11])

    def test_unbalanced_delimiters_are_rejected(self) -> None:
        with self.assertRaises(ExtractionError):
            find_raw_string_blocks('val a = """ oops')


class DeclarationNameTests(unittest.TestCase):
    def test_uses_the_property_name_for_a_plain_val_block(self) -> None:
        self.assertEqual(declaration_name(SAMPLE_KOTLIN, 3, "fallback"), "firstScript")

    def test_prefers_the_enclosing_function_over_a_local_val(self) -> None:
        self.assertEqual(declaration_name(SAMPLE_KOTLIN, 11, "fallback"), "buildSecond")


class ExtractFileTests(unittest.TestCase):
    def _extract_sample(self) -> dict[str, str]:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "Sample.kt"
            path.write_text(SAMPLE_KOTLIN, encoding="utf-8")
            return dict(extract_file(path))

    def test_names_each_output_after_its_source_and_declaration(self) -> None:
        self.assertEqual(
            sorted(self._extract_sample()),
            ["Sample.buildSecond.js", "Sample.firstScript.js"],
        )

    def test_kotlin_interpolation_becomes_a_valid_js_literal(self) -> None:
        script = self._extract_sample()["Sample.buildSecond.js"]
        self.assertNotIn("$quotedUrl", script)
        self.assertIn('var target = "__HERMES_KOTLIN_INTERPOLATION__";', script)

    def test_padding_keeps_js_line_numbers_aligned_with_kotlin(self) -> None:
        script = self._extract_sample()["Sample.firstScript.js"]
        lines = script.split("\n")
        # `(function() {` sits on Kotlin line 5, so it must land on JS line 5.
        self.assertEqual(lines[4], "(function() {")
        self.assertEqual(lines[:4], ["", "", "", ""])


class RepositoryExtractionTests(unittest.TestCase):
    def test_every_declared_source_file_exists(self) -> None:
        missing = [name for name in SOURCE_FILES if not (ROOT / name).exists()]
        self.assertEqual(missing, [])

    def test_extraction_produces_the_known_injected_scripts(self) -> None:
        with TemporaryDirectory() as tmp:
            written = extract_all(ROOT, Path(tmp))
            names = {path.name for path in written}
        for expected in (
            "HermesWebUiScripts.viewportFixScript.js",
            "HermesWebUiScripts.microphoneFallbackScript.js",
            "HermesWebUiScripts.enterKeyNewlineScript.js",
            "HermesWebUiScripts.appSettingsEntryScript.js",
            "HermesWebUiScripts.suppressClarifyAutofocusScript.js",
            "HermesWebUiScripts.buildRouteRecoveryScript.js",
            "HermesWebUiScripts.buildNotificationBridgeScript.js",
        ):
            self.assertIn(expected, names)

    def test_extracted_scripts_contain_no_kotlin_interpolation(self) -> None:
        with TemporaryDirectory() as tmp:
            for path in extract_all(ROOT, Path(tmp)):
                text = path.read_text(encoding="utf-8")
                self.assertNotRegex(
                    text,
                    r"\$\{|\$[A-Za-z_]",
                    msg=f"{path.name} still carries Kotlin string-template syntax",
                )


if __name__ == "__main__":
    unittest.main()
