import json
import re
import shutil
import subprocess
import unittest

from tools.tests.test_workflow_contracts import ROOT

QA = ROOT / "tools" / "qa"
# Tailscale tailnet hostnames look like `<machine>.tail<hex>.ts.net`.
TAILNET_HOST = re.compile(r"[a-z0-9-]+\.tail[0-9a-f]+\.ts\.net", re.IGNORECASE)
# Made-up hostnames used as parser fixtures. Add to this set only for an obviously fake name.
FIXTURE_HOSTS = {"node.tailabc.ts.net"}


def real_tailnet_hosts(text):
    return {h for h in TAILNET_HOST.findall(text) if h.lower() not in FIXTURE_HOSTS}


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)


class QaHarnessTests(unittest.TestCase):
    def test_no_private_tailnet_hostname_is_tracked(self):
        listed = git("ls-files")
        if listed.returncode != 0:
            self.skipTest("not a git checkout")
        offenders = []
        for rel in listed.stdout.splitlines():
            path = ROOT / rel
            if not path.is_file():
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                continue
            if real_tailnet_hosts(text):
                offenders.append(rel)
        self.assertEqual([], offenders, "private tailnet hostnames must stay out of the repository")

    def test_local_only_qa_files_are_gitignored(self):
        if git("rev-parse", "--is-inside-work-tree").returncode != 0:
            self.skipTest("not a git checkout")
        for rel in ("tools/qa/.env.qa", "tools/qa/out/report.json"):
            self.assertEqual(0, git("check-ignore", "-q", rel).returncode, f"{rel} must be ignored")
        self.assertNotEqual(0, git("check-ignore", "-q", "tools/qa/.env.qa.example").returncode,
                            "the placeholder example must stay trackable")

    def test_hostname_guard_flags_a_real_looking_name_but_not_the_fixture(self):
        # Assembled at runtime so this file does not itself contain a tailnet-shaped literal.
        sample = "forge." + "tail" + "1a2b3c" + ".ts" + ".net"
        self.assertEqual({sample}, real_tailnet_hosts(f"x {sample} y"))
        self.assertEqual(set(), real_tailnet_hosts("node.tailabc.ts.net"))

    def test_example_env_holds_only_a_placeholder(self):
        example = (QA / ".env.qa.example").read_text(encoding="utf-8")
        self.assertIn("HERMES_QA_SERVER_URL=", example)
        self.assertIn(".invalid", example)
        self.assertEqual(set(), real_tailnet_hosts(example))

    def test_harness_scripts_parse(self):
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is not installed")
        for script in sorted(QA.rglob("*.mjs")):
            result = subprocess.run([node, "--check", str(script)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, f"{script.name}: {result.stderr}")

    def test_report_redactor_hides_url_origin_and_host(self):
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is not installed")
        url = "https://qa-host.example.invalid:8443/some/path"
        module = (QA / "lib" / "report.mjs").as_uri()
        code = (
            f"import {{ makeRedactor }} from {json.dumps(module)};"
            f"const r = makeRedactor({json.dumps(url)});"
            "console.log(JSON.stringify(r('see https://qa-host.example.invalid:8443/some/path and "
            "qa-host.example.invalid:8443 and qa-host.example.invalid')));"
        )
        result = subprocess.run([node, "--input-type=module", "-e", code], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        redacted = json.loads(result.stdout)
        self.assertNotIn("qa-host", redacted)
        self.assertIn("<server>", redacted)


if __name__ == "__main__":
    unittest.main()
