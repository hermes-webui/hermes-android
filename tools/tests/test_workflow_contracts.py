import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_DIR = ROOT / ".github" / "workflows"
EXTERNAL_ACTION_RE = re.compile(r"^\s*uses:\s*([^\s#]+)", re.MULTILINE)
PINNED_ACTION_RE = re.compile(r"^[^/]+/[^@]+@[0-9a-f]{40}$")


def read_workflow(name: str) -> str:
    return (WORKFLOW_DIR / name).read_text(encoding="utf-8")


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
        self.assertIn("testDebugUnitTest lintDebug assembleDebug", workflow)
        self.assertIn('branches: ["main"]', workflow)
        self.assertIn("pull_request:", workflow)
        self.assertRegex(workflow, r"api-level:\s*\[35, 36\]")
        self.assertIn("connectedDebugAndroidTest", workflow)
        self.assertNotIn("testInstrumentationRunnerArguments.class", workflow)
        self.assertIn("if: needs.changes.outputs.android_app == 'true'", workflow)

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


if __name__ == "__main__":
    unittest.main()
