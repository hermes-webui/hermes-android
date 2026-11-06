import re
import unittest

from tools.tests.test_workflow_contracts import ROOT


class SetupPromptTests(unittest.TestCase):
    def test_readme_copy_blocks_exactly_match_packaged_prompts(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        for label, file in (
            ("Copy command to set up WebUI", "setup_webui_prompt.txt"),
            ("Copy command to set up Hermes WebApp", "setup_webapp_prompt.txt"),
        ):
            match = re.search(rf"\*\*{re.escape(label)}\*\* \(paste into Hermes chat\):\n\n```text\n(.*?)\n```",
                              readme, re.DOTALL)
            self.assertIsNotNone(match, label)
            prompt = (ROOT / "app" / "src" / "main" / "res" / "raw" / file).read_text(encoding="utf-8").strip()
            self.assertEqual(match.group(1), prompt)
            self.assertIn("phone-side connection check", prompt)
            self.assertIn("sign-in", prompt)
            self.assertIn("HTTPS", prompt)
            self.assertIn("127.0.0.1", prompt)


if __name__ == "__main__":
    unittest.main()
