import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_DIR = ROOT / ".github" / "workflows"
EXTERNAL_ACTION_RE = re.compile(r"^\s*uses:\s*([^\s#]+)", re.MULTILINE)
PINNED_ACTION_RE = re.compile(r"^[^/]+/[^@]+@[0-9a-f]{40}$")


def read_workflow(name: str) -> str:
    return (WORKFLOW_DIR / name).read_text(encoding="utf-8")


def ci_job_blocks(workflow: str) -> dict[str, str]:
    """Map each job id in the CI workflow to its YAML block."""
    jobs_section = workflow.split("\njobs:\n", 1)[1]
    names = re.findall(r"^  ([a-z0-9-]+):$", jobs_section, re.MULTILINE)
    blocks = re.split(r"^  [a-z0-9-]+:$", jobs_section, flags=re.MULTILINE)[1:]
    return dict(zip(names, blocks))


class WorkflowContractTests(unittest.TestCase):
    def test_every_external_action_is_pinned_to_commit(self) -> None:
        failures: list[str] = []
        for workflow in sorted(WORKFLOW_DIR.glob("*.yml")):
            for action in EXTERNAL_ACTION_RE.findall(workflow.read_text(encoding="utf-8")):
                if action.startswith("./"):
                    continue
                if not PINNED_ACTION_RE.fullmatch(action):
                    failures.append(f"{workflow.name}: {action}")
        self.assertEqual(failures, [])

    def test_local_reusable_workflow_targets_define_workflow_call(self) -> None:
        failures: list[str] = []
        for workflow in sorted(WORKFLOW_DIR.glob("*.yml")):
            for action in EXTERNAL_ACTION_RE.findall(workflow.read_text(encoding="utf-8")):
                if not action.startswith("./.github/workflows/"):
                    continue
                target = ROOT / action.removeprefix("./")
                if not target.exists() or "workflow_call:" not in target.read_text(encoding="utf-8"):
                    failures.append(f"{workflow.name}: {action}")
        self.assertEqual(failures, [])

    def test_ci_runs_tooling_quality_and_android_15_16_contracts(self) -> None:
        workflow = read_workflow("0-ci-build-and-test.yml")
        self.assertIn("python3 -m unittest discover -s tools/tests", workflow)
        self.assertIn('branches: ["main"]', workflow)
        self.assertIn("pull_request:", workflow)
        self.assertRegex(workflow, r"api-level:\s*\[35, 36\]")
        self.assertIn("connectedDebugAndroidTest", workflow)
        self.assertNotIn("testInstrumentationRunnerArguments.class", workflow)
        self.assertIn("if: needs.changes.outputs.android_app == 'true'", workflow)

    def test_ci_reports_each_quality_gate_as_its_own_job(self) -> None:
        workflow = read_workflow("0-ci-build-and-test.yml")
        for job, command in (
            ("release-tooling-tests:", "python3 -m unittest discover -s tools/tests"),
            ("unit-tests:", "./gradlew --no-daemon testDebugUnitTest"),
            ("android-lint:", "./gradlew --no-daemon lintDebug"),
            ("debug-build:", "./gradlew --no-daemon assembleDebug"),
        ):
            self.assertIn(f"\n  {job}\n", workflow)
            self.assertIn(command, workflow)
        # A combined invocation hides which gate failed.
        self.assertNotIn("testDebugUnitTest lintDebug assembleDebug", workflow)

    def test_ci_guards_the_javascript_android_injects_into_the_webview(self) -> None:
        workflow = read_workflow("0-ci-build-and-test.yml")
        self.assertIn("\n  webui-script-syntax:\n", workflow)
        self.assertIn("\n  webui-script-lint:\n", workflow)
        self.assertIn("python3 tools/extract_webui_scripts.py", workflow)
        self.assertIn("node --check", workflow)
        self.assertIn("eslint.runtime-guard.config.mjs", workflow)
        self.assertTrue((ROOT / "tools" / "extract_webui_scripts.py").exists())
        self.assertTrue((ROOT / "eslint.runtime-guard.config.mjs").exists())

    def test_ci_checks_documentation_when_markdown_changes(self) -> None:
        workflow = read_workflow("0-ci-build-and-test.yml")
        self.assertIn("\n  docs-render:\n", workflow)
        self.assertIn("\n  docs-links:\n", workflow)
        self.assertIn("python3 tools/check_markdown.py", workflow)
        self.assertIn("needs.changes.outputs.docs == 'true'", workflow)
        self.assertTrue((ROOT / "tools" / "check_markdown.py").exists())
        # The external link check must never block a merge on network flake.
        docs_links = workflow.split("\n  docs-links:\n", 1)[1].split("\n  release-", 1)[0]
        self.assertIn("continue-on-error: true", docs_links)

    def test_docs_only_change_sets_skip_the_gradle_gates_but_fail_safe(self) -> None:
        workflow = read_workflow("0-ci-build-and-test.yml")
        blocks = ci_job_blocks(workflow)
        for job in ("unit-tests", "android-lint", "debug-build"):
            block = blocks[job]
            header = block.split("\n    steps:\n", 1)[0]
            directives = "\n".join(
                line for line in header.splitlines() if not line.strip().startswith("#")
            )
            # The job itself must always run. A *skipped* required status check
            # reports as pending forever, which wedges the pull request.
            self.assertIn("if: ${{ always() }}", directives, msg=job)
            self.assertNotIn(
                "docs_only", directives, msg=f"{job} must not skip at job level"
            )
            self.assertIn("Docs-only short-circuit", block)
            # Every step must be guarded, or a docs-only run still pays for it.
            steps = block.split("\n      - ")[1:]
            unguarded = [step.splitlines()[0] for step in steps if "docs_only" not in step]
            self.assertEqual(unguarded, [], msg=f"{job} has unguarded steps")
        # Release tooling tests assert README release metadata, so a docs-only
        # change is exactly when they matter most.
        self.assertNotIn("docs_only", blocks["release-tooling-tests"])
        # The detector must default to running everything.
        self.assertIn('docs_only="false"', blocks["changes"])
        self.assertIn("trap emit EXIT", blocks["changes"])

    def test_required_check_candidates_always_report_a_conclusion(self) -> None:
        """Jobs intended as required status checks must never be skipped.

        GitHub reports a skipped required check as pending forever, so a job that
        can be skipped must not be made required. Keep this list aligned with the
        repository ruleset.
        """
        workflow = read_workflow("0-ci-build-and-test.yml")
        blocks = ci_job_blocks(workflow)
        required = (
            "docs-render",
            "release-tooling-tests",
            "webui-script-syntax",
            "webui-script-lint",
            "unit-tests",
            "android-lint",
            "debug-build",
        )
        for job in required:
            block = blocks[job]
            condition = re.search(r"^    if: (.+)$", block, re.MULTILINE)
            if condition is None:
                continue
            self.assertEqual(
                condition.group(1).strip(),
                "${{ always() }}",
                msg=f"{job} is a required check and must not be conditionally skipped",
            )

    def test_every_ci_job_declares_a_timeout(self) -> None:
        workflow = read_workflow("0-ci-build-and-test.yml")
        blocks = ci_job_blocks(workflow)
        self.assertGreater(len(blocks), 1)
        missing = [
            job for job, block in blocks.items() if "timeout-minutes:" not in block
        ]
        self.assertEqual(missing, [])

    def test_orchestrator_builds_reviewed_version_without_source_mutation(self) -> None:
        workflow = read_workflow("1-orchestration-release.yml")
        for forbidden in ("git push", "git commit", "bump_gradle_version.py", "auto-bump"):
            self.assertNotIn(forbidden, workflow)
        self.assertIn("python3 -m unittest discover -s tools/tests", workflow)
        self.assertIn("api-level: 36", workflow)
        self.assertIn("connectedDebugAndroidTest", workflow)
        self.assertIn("test lintDebug :app:stageGithubReleaseApk :app:bundleRelease", workflow)
        self.assertIn("apksigner\" verify --verbose --print-certs", workflow)
        self.assertIn("jarsigner -verify", workflow)

    def test_release_notes_are_generated_once_and_bundled_with_artifacts(self) -> None:
        workflows = {
            path.name: path.read_text(encoding="utf-8")
            for path in WORKFLOW_DIR.glob("*.yml")
        }
        generators = [name for name, text in workflows.items() if "releases/generate-notes" in text]
        self.assertEqual(generators, ["1-orchestration-release.yml"])

        orchestrator = workflows["1-orchestration-release.yml"]
        self.assertIn("permissions:\n  contents: write\n  actions: read", orchestrator)
        self.assertIn("tools/generate_release_metadata.py", orchestrator)
        self.assertIn("build/release/release-notes.md", orchestrator)
        self.assertIn("build/release/play-whatsnew/whatsnew-en-US", orchestrator)

        github_publisher = workflows["2-publish-github-apk.yml"]
        play_publisher = workflows["_publish-play-store.yml"]
        self.assertIn("test -s build/release/release-notes.md", github_publisher)
        self.assertIn("test -s build/release/play-whatsnew/whatsnew-en-US", play_publisher)
        self.assertIn("apksigner\" verify --verbose --print-certs", github_publisher)
        self.assertIn("jarsigner -verify", play_publisher)
        self.assertNotIn("wc -m", orchestrator)
        self.assertNotIn("wc -m", play_publisher)

    def test_retry_publishers_validate_version_commit_run_and_artifact(self) -> None:
        for name in (
            "2-publish-github-apk.yml",
            "_publish-play-store.yml",
        ):
            workflow = read_workflow(name)
            self.assertIn("Validate release metadata", workflow)
            self.assertIn("Tag/version mismatch", workflow)
            self.assertIn("Invalid commit SHA", workflow)
            self.assertIn("Invalid build run ID", workflow)
            self.assertIn("actions/runs/$BUILD_RUN_ID", workflow)
            self.assertIn('run_sha" = "$COMMIT_SHA', workflow)

    def test_play_wrappers_share_one_publisher_with_explicit_tracks(self) -> None:
        production = read_workflow("3-publish-play-store-production.yml")
        beta = read_workflow("play-store-beta-manual.yml")
        for workflow in (production, beta):
            self.assertIn("uses: ./.github/workflows/_publish-play-store.yml", workflow)
        self.assertIn("track: production", production)
        self.assertIn("track: beta", beta)


class ReleaseVersionMetadataTests(unittest.TestCase):
    def test_gradle_and_readme_release_metadata_match(self) -> None:
        gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        gradle_version = re.search(r'val appVersionName = "(\d+\.\d+\.\d+)"', gradle)
        readme_version = re.search(r"- Version name: `(\d+\.\d+\.\d+)`", readme)
        readme_code = re.search(r"- Version code: `(\d+)`", readme)
        self.assertIsNotNone(gradle_version)
        self.assertIsNotNone(readme_version)
        self.assertIsNotNone(readme_code)

        version = gradle_version.group(1)
        self.assertEqual(readme_version.group(1), version)
        major, minor, patch = (int(part) for part in version.split("."))
        self.assertEqual(int(readme_code.group(1)), major * 10_000 + minor * 100 + patch)


class ReleaseNoteLabelTests(unittest.TestCase):
    """`.github/release.yml` may only reference labels that really exist.

    GitHub silently ignores an unknown label, so a category keyed to one renders
    as nothing at all. The repository label set is small and documented in
    AGENTS.md; this keeps the two from drifting apart again.
    """

    def _configured_labels(self) -> set[str]:
        text = (ROOT / ".github" / "release.yml").read_text(encoding="utf-8")
        # Bare sequence scalars only; `- title: ...` entries are category headers.
        labels = set(re.findall(r'^\s+- "?([^\s:"]+)"?$', text, re.MULTILINE))
        return labels - {"*"}

    def test_release_note_labels_are_documented_in_agents_md(self) -> None:
        agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
        undocumented = sorted(
            label for label in self._configured_labels() if f"`{label}`" not in agents
        )
        self.assertEqual(undocumented, [])

    def test_release_note_config_avoids_retired_placeholder_labels(self) -> None:
        configured = self._configured_labels()
        retired = {
            "feature",
            "bugfix",
            "fix",
            "testing-notes",
            "needs-testing",
            "maintenance",
            "release",
            "docs",
            "internal-only",
        }
        self.assertEqual(sorted(configured & retired), [])


if __name__ == "__main__":
    unittest.main()
