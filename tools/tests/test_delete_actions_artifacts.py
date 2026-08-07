import unittest
from pathlib import Path
from unittest import mock

from tools import delete_actions_artifacts


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class DeleteActionsArtifactsTests(unittest.TestCase):
    def test_select_artifact_ids_matches_exact_names_and_prefixes(self) -> None:
        artifacts = [
            {"id": 1, "name": "hermes-debug-apk"},
            {"id": 2, "name": "hermes-webui-v1.0.14-github-apk"},
            {"id": 3, "name": "hermes-webui-v1.0.14-play-aab"},
            {"id": 4, "name": "unrelated-report"},
        ]

        selected = delete_actions_artifacts.select_artifacts(
            artifacts,
            names={"hermes-debug-apk"},
            prefixes=("hermes-webui-v",),
        )

        self.assertEqual([artifact["id"] for artifact in selected], [1, 2, 3])

    def test_select_artifacts_requires_at_least_one_selector(self) -> None:
        with self.assertRaisesRegex(ValueError, "selector"):
            delete_actions_artifacts.select_artifacts([], names=set(), prefixes=())

    def test_ci_verifies_debug_build_without_retaining_apk_artifacts(self) -> None:
        workflow = (
            REPOSITORY_ROOT / ".github/workflows/0-ci-build-and-test.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("Assemble debug APK", workflow)
        self.assertIn("Workflow tooling tests", workflow)
        self.assertNotIn("Upload debug APK", workflow)
        self.assertEqual(workflow.count("actions: write"), 1)

    def test_release_cleans_all_app_artifacts_before_build_and_upload(self) -> None:
        workflow = (
            REPOSITORY_ROOT / ".github/workflows/1-orchestration-release.yml"
        ).read_text(encoding="utf-8")

        cleanup = workflow.index("Delete previous release artifacts")
        build = workflow.index("Build signed release artifacts")
        upload = workflow.index("Upload signed GitHub APK artifact")

        self.assertLess(cleanup, build)
        self.assertLess(cleanup, upload)
        self.assertIn("actions: write", workflow)
        self.assertIn("group: release-artifact-lifecycle", workflow)
        self.assertIn("hermes-webui-v", workflow)

    def test_client_lists_all_artifact_pages(self) -> None:
        client = delete_actions_artifacts.GitHubArtifactsClient(
            api_url="https://api.github.test",
            repository="owner/repo",
            token="test-token",
        )
        first_page = [
            {"id": artifact_id, "name": "artifact"} for artifact_id in range(100)
        ]
        calls = []

        def fake_request(path: str, *, method: str = "GET"):
            calls.append((method, path))
            return {
                "artifacts": first_page
                if path.endswith("&page=1")
                else [{"id": 100, "name": "artifact"}]
            }

        with mock.patch.object(client, "_request", side_effect=fake_request):
            artifacts = client.list_artifacts()

        self.assertEqual(len(artifacts), 101)
        self.assertEqual(
            calls,
            [
                ("GET", "/repos/owner/repo/actions/artifacts?per_page=100&page=1"),
                ("GET", "/repos/owner/repo/actions/artifacts?per_page=100&page=2"),
            ],
        )

    def test_client_deletes_artifact_by_numeric_id(self) -> None:
        client = delete_actions_artifacts.GitHubArtifactsClient(
            api_url="https://api.github.test",
            repository="owner/repo",
            token="test-token",
        )

        with mock.patch.object(client, "_request") as request:
            client.delete_artifact(42)

        request.assert_called_once_with(
            "/repos/owner/repo/actions/artifacts/42", method="DELETE"
        )


if __name__ == "__main__":
    unittest.main()
