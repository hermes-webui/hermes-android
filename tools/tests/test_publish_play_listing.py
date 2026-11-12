import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from tools.publish_play_listing import API, publish_listing, read_descriptions
from tools.tests.test_workflow_contracts import ROOT, read_workflow


class PlayListingTests(unittest.TestCase):
    def session(self, mismatch=False, track=None):
        session = Mock()
        values = [
            {"id": "write"},
            {"title": "Keep title", "video": "keep-video", "shortDescription": "Old"},
            {}, {},
            {"id": "verify"},
            {"title": "Keep title", "video": "keep-video",
             "shortDescription": "Wrong" if mismatch else "Short", "fullDescription": "Full"},
            {},
        ]
        if track is not None:
            values.insert(6, track)
        session.request.side_effect = [
            Mock(content=b"json", json=Mock(return_value=value)) for value in values
        ]
        return session

    def test_commits_descriptions_and_verifies_new_edit_without_changing_title_or_video(self):
        session = self.session()
        publish_listing(session, {"shortDescription": "Short", "fullDescription": "Full"})
        calls = session.request.call_args_list
        self.assertEqual(calls[2].kwargs["json"], {
            "title": "Keep title", "video": "keep-video",
            "shortDescription": "Short", "fullDescription": "Full",
        })
        self.assertEqual(calls[3].args, ("POST", f"{API}/write:commit"))
        self.assertEqual(calls[5].args, ("GET", f"{API}/verify/listings/en-US"))
        self.assertEqual(calls[6].args, ("DELETE", f"{API}/verify"))
        self.assertTrue(all(call.kwargs["timeout"] == 60 for call in calls))

    def test_readback_mismatch_is_failure_not_success(self):
        session = self.session(mismatch=True)
        with self.assertRaisesRegex(RuntimeError, "readback mismatch"):
            publish_listing(session, {"shortDescription": "Short", "fullDescription": "Full"})
        self.assertEqual(session.request.call_args.args, ("DELETE", f"{API}/verify"))

    def test_production_readback_checks_version_status_and_english_notes(self):
        track = {
            "track": "production",
            "releases": [{
                "status": "completed",
                "versionCodes": ["10300"],
                "releaseNotes": [{"language": "en-US", "text": "Release notes\n"}],
            }],
        }
        session = self.session(track=track)
        publish_listing(
            session, {"shortDescription": "Short", "fullDescription": "Full"},
            expected_production_version_code=10300, expected_play_notes="Release notes\n",
        )
        self.assertEqual(session.request.call_args_list[6].args, ("GET", f"{API}/verify/tracks/production"))
        self.assertEqual(session.request.call_args.args, ("DELETE", f"{API}/verify"))

    def test_production_readback_rejects_wrong_track_version_status_or_notes(self):
        release = {
            "status": "completed",
            "versionCodes": ["10300"],
            "releaseNotes": [{"language": "en-US", "text": "Release notes\n"}],
        }
        tracks = [
            {"track": "beta", "releases": [release]},
            {"track": "production", "releases": [{**release, "versionCodes": ["10202"]}]},
            {"track": "production", "releases": [{**release, "status": "draft"}]},
            {"track": "production", "releases": [{**release, "releaseNotes": [
                {"language": "en-US", "text": "Wrong notes"}
            ]}]},
            {"track": "production", "releases": [{**release, "releaseNotes": [
                {"language": "fr-FR", "text": "Release notes\n"}
            ]}]},
            {"track": "production", "releases": [
                {**release, "releaseNotes": [{"language": "en-US", "text": "Wrong notes"}]},
                {**release, "versionCodes": ["10202"]},
            ]},
            {"track": "production", "releases": []},
        ]
        for track in tracks:
            with self.subTest(track=track):
                session = self.session(track=track)
                with self.assertRaisesRegex(RuntimeError, "production.*readback"):
                    publish_listing(
                        session, {"shortDescription": "Short", "fullDescription": "Full"},
                        expected_production_version_code=10300, expected_play_notes="Release notes\n",
                    )
                self.assertEqual(session.request.call_args.args, ("DELETE", f"{API}/verify"))

    def test_production_readback_requires_version_and_notes_together(self):
        for kwargs in (
            {"expected_production_version_code": 10300},
            {"expected_play_notes": "Release notes"},
        ):
            with self.subTest(kwargs=kwargs):
                session = Mock()
                with self.assertRaises(ValueError):
                    publish_listing(session, {}, **kwargs)
                session.request.assert_not_called()

    def test_failed_listing_update_discards_edit_and_does_not_commit(self):
        session = self.session()
        failure = Mock()
        failure.raise_for_status.side_effect = RuntimeError("API rejected listing")
        responses = list(session.request.side_effect)
        session.request.side_effect = [responses[0], responses[1], failure, responses[6]]
        with self.assertRaisesRegex(RuntimeError, "API rejected"):
            publish_listing(session, {"shortDescription": "Short", "fullDescription": "Full"})
        self.assertEqual(session.request.call_args.args, ("DELETE", f"{API}/write"))
        self.assertFalse(any(":commit" in call.args[1] for call in session.request.call_args_list))

    def test_store_text_fits_play_limits_and_announces_both_frontends(self):
        descriptions = read_descriptions(ROOT / "store" / "google-play" / "en-US")
        self.assertIn("Hermes Agent WebApp", descriptions["fullDescription"])
        self.assertIn("Hermes WebUI", descriptions["fullDescription"])
        self.assertNotIn("This F-Droid build", descriptions["fullDescription"])

    def test_invalid_metadata_is_rejected_before_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "short_description.txt").write_text("x" * 81)
            (path / "full_description.txt").write_text("Full")
            with self.assertRaises(ValueError):
                read_descriptions(path)

    def test_production_publish_uses_exact_reviewed_commit_and_existing_credentials(self):
        workflow = read_workflow("_publish-play-store.yml")
        self.assertIn("ref: ${{ inputs.commit_sha }}", workflow)
        self.assertIn("python3 tools/publish_play_listing.py", workflow)
        self.assertIn("if: inputs.track == 'production'", workflow)
        self.assertIn("--credentials signing/google-play-service-account.json", workflow)
        self.assertIn("--expected-production-version-code", workflow)
        self.assertIn("--expected-play-notes", workflow)
