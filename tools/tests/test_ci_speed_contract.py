"""Contracts for the CI speed-ups and the CI gate job (Issue #156)."""

import json
import os
import re
import subprocess
import sys
import textwrap
import unittest

from tools.tests.test_workflow_contracts import ROOT, ci_job_blocks, read_workflow

GATING_JOBS = (
    "changes",
    "docs-render",
    "release-tooling-tests",
    "webui-script-syntax",
    "webui-script-lint",
    "unit-tests",
    "android-lint",
    "debug-build",
    "android-ui-smoke",
)


class CiSpeedContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = read_workflow("0-ci-build-and-test.yml")
        self.blocks = ci_job_blocks(self.workflow)

    def test_gradle_build_cache_is_enabled(self) -> None:
        properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
        self.assertRegex(properties, r"(?m)^org\.gradle\.caching=true\s*$")

    def test_source_reading_contract_tests_declare_their_inputs(self) -> None:
        # With the build cache on, a test that reads src/main off disk must declare it as an input,
        # or a manifest-only change reuses a stale passing result and the guard never runs.
        build = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertRegex(build, r"tasks\.withType<Test>\(\)\.configureEach \{\s+inputs\.dir\(\"src/main\"\)")
        readers = [
            path.name
            for path in (ROOT / "app" / "src" / "test").rglob("*.kt")
            if 'File("src/main")' in path.read_text(encoding="utf-8")
        ]
        self.assertTrue(readers, "expected contract tests that read src/main")

    def test_only_pull_request_runs_are_canceled_when_superseded(self) -> None:
        concurrency = self.workflow.split("\nconcurrency:\n", 1)[1].split("\npermissions:", 1)[0]
        value = re.search(r"^  cancel-in-progress: (.+)$", concurrency, re.MULTILINE)
        self.assertIsNotNone(value)
        # Never a bare `true`: runs on main must keep queuing so each merged commit gets full statuses.
        self.assertEqual("${{ github.event_name == 'pull_request' }}", value.group(1).strip())

    def test_ci_gate_always_reports_and_covers_every_gating_job(self) -> None:
        block = self.blocks["ci-gate"]
        header = block.split("\n    steps:\n", 1)[0]
        self.assertIn("if: ${{ always() }}", header)
        self.assertIn("timeout-minutes:", header)
        needs = set(re.findall(r"^      - ([a-z0-9-]+)$", header, re.MULTILINE))
        self.assertEqual(set(GATING_JOBS), needs)
        # The informational link check must never be able to block a merge.
        self.assertNotIn("docs-links", needs)

    def test_ci_gate_fails_on_failure_or_cancellation_but_accepts_skipped_jobs(self) -> None:
        block = self.blocks["ci-gate"]
        self.assertIn("toJSON(needs)", block)
        self.assertIn('not in ("success", "skipped")', block)
        self.assertIn("sys.exit(1)", block)

    def _run_gate(self, results: dict) -> subprocess.CompletedProcess:
        """Execute the gate's real inline script against a fake `needs` payload."""
        block = self.blocks["ci-gate"]
        script = textwrap.dedent(block.split("python3 - <<'PY'\n", 1)[1].split("\n          PY", 1)[0])
        needs = {job: {"result": result} for job, result in results.items()}
        env = dict(os.environ, NEEDS_JSON=json.dumps(needs))
        return subprocess.run([sys.executable, "-c", script], env=env, capture_output=True, text=True)

    def test_ci_gate_script_passes_success_and_skipped_only(self) -> None:
        ok = {job: "success" for job in GATING_JOBS}
        self.assertEqual(0, self._run_gate(ok).returncode)
        # The emulator matrix is skipped when no Android files changed.
        self.assertEqual(0, self._run_gate({**ok, "android-ui-smoke": "skipped"}).returncode)
        for bad in ("failure", "cancelled"):
            with self.subTest(result=bad):
                result = self._run_gate({**ok, "unit-tests": bad})
                self.assertEqual(1, result.returncode)
                self.assertIn("unit-tests", result.stdout)

    def test_every_gating_job_is_a_real_job(self) -> None:
        for job in GATING_JOBS:
            self.assertIn(job, self.blocks, msg=f"{job} is listed in the gate but is not a job")


if __name__ == "__main__":
    unittest.main()
