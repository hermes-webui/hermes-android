import hashlib
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools import verify_release_apk


VALID_BADGING = (
    "package: name='com.hermeswebui.android' versionCode='1029' versionName='1.0.29'\n"
    "launchable-activity: name='com.hermeswebui.android.MainActivity' icon-URI='res://56'\n"
)

VALID_CERTS = (
    "Verified using v1 scheme (JAR signing): false\n"
    "Verified using v2 scheme (APK Signature Scheme v2): true\n"
    "Signer #1 certificate SHA-256 digest: "
    "d098ad9b834d2aec2782e51dbcaadf718e70197ca5af3c0571c41132d61b7ad2\n"
)

FIXTURE_BYTES = b"hermes-release-apk-fixture-v1\n"
FIXTURE_SHA256 = hashlib.sha256(FIXTURE_BYTES).hexdigest()


def run_main(argv: list[str], badging: str, certs: str) -> int:
    with mock.patch.object(verify_release_apk, "find_build_tool", side_effect=lambda name: f"/sdk/{name}"):
        with mock.patch.object(
            verify_release_apk,
            "run_tool",
            side_effect=lambda cmd: badging if "badging" in cmd else certs,
        ):
            with mock.patch.object(verify_release_apk.sys, "argv", ["verify_release_apk.py"] + argv):
                return verify_release_apk.main()


class ParseBadgingTests(unittest.TestCase):
    def test_parse_badging_extracts_package_version_code_and_name(self) -> None:
        package, version_code, version_name = verify_release_apk.parse_badging(VALID_BADGING)
        self.assertEqual(package, "com.hermeswebui.android")
        self.assertEqual(version_code, 1029)
        self.assertEqual(version_name, "1.0.29")

    def test_parse_badging_rejects_output_without_package_line(self) -> None:
        with self.assertRaises(RuntimeError):
            verify_release_apk.parse_badging("launchable-activity: name='x'\n")

    def test_parse_badging_rejects_non_numeric_version_code(self) -> None:
        output = "package: name='com.example.app' versionCode='abc' versionName='1.0.0'\n"
        with self.assertRaises((RuntimeError, ValueError)):
            verify_release_apk.parse_badging(output)


class CertSha256RegexTests(unittest.TestCase):
    def test_cert_sha256_re_matches_valid_digest(self) -> None:
        match = verify_release_apk.CERT_SHA256_RE.search(VALID_CERTS)
        self.assertIsNotNone(match)
        self.assertEqual(
            match.group(1),
            "d098ad9b834d2aec2782e51dbcaadf718e70197ca5af3c0571c41132d61b7ad2",
        )

    def test_cert_sha256_re_matches_uppercase_digest(self) -> None:
        output = "certificate SHA-256 digest: " + "A" * 64
        match = verify_release_apk.CERT_SHA256_RE.search(output)
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1), "A" * 64)

    def test_cert_sha256_re_rejects_short_digest(self) -> None:
        self.assertIsNone(verify_release_apk.CERT_SHA256_RE.search("certificate SHA-256 digest: abc123\n"))

    def test_cert_sha256_re_rejects_non_hex_characters(self) -> None:
        self.assertIsNone(verify_release_apk.CERT_SHA256_RE.search("certificate SHA-256 digest: " + "g" * 64))


class Sha256OfTests(unittest.TestCase):
    def test_sha256_of_matches_known_fixture(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            apk = Path(tmp_dir) / "app.apk"
            apk.write_bytes(FIXTURE_BYTES)
            self.assertEqual(verify_release_apk.sha256_of(str(apk)), FIXTURE_SHA256)


class FindBuildToolTests(unittest.TestCase):
    def test_finds_newest_sdk_build_tools_when_not_on_path(self) -> None:
        with tempfile.TemporaryDirectory() as sdk_root:
            for version in ("36.0.0", "37.0.0"):
                directory = Path(sdk_root) / "build-tools" / version
                directory.mkdir(parents=True)
                (directory / "aapt").touch()

            def sdk_which(name: str, path: str | None = None) -> str | None:
                candidate = Path(path) / name if path else None
                return str(candidate) if candidate and candidate.is_file() else None

            with mock.patch.dict(os.environ, {"ANDROID_HOME": sdk_root}, clear=True):
                with mock.patch.object(verify_release_apk.shutil, "which", side_effect=sdk_which):
                    self.assertEqual(
                        verify_release_apk.find_build_tool("aapt"),
                        str(Path(sdk_root) / "build-tools" / "37.0.0" / "aapt"),
                    )

    def test_missing_tool_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as sdk_root:
            with mock.patch.dict(os.environ, {"ANDROID_HOME": sdk_root}, clear=True):
                with mock.patch.object(verify_release_apk.shutil, "which", return_value=None):
                    self.assertIsNone(verify_release_apk.find_build_tool("apksigner"))


class MainExitCodeTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.apk = Path(self._tmp.name) / "app-release.apk"
        self.apk.write_bytes(FIXTURE_BYTES)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def base_args(self, **overrides: object) -> list[str]:
        args: dict[str, object] = {
            "package": "com.hermeswebui.android",
            "version_name": "1.0.29",
            "version_code": 1029,
        }
        args.update(overrides)
        return [
            "--apk",
            str(self.apk),
            "--expected-package",
            str(args["package"]),
            "--expected-version-name",
            str(args["version_name"]),
            "--expected-version-code",
            str(args["version_code"]),
        ]

    def test_main_exits_zero_when_all_identity_checks_pass(self) -> None:
        self.assertEqual(run_main(self.base_args(), VALID_BADGING, VALID_CERTS), 0)

    def test_main_executes_resolved_sdk_tools(self) -> None:
        with mock.patch.object(
            verify_release_apk, "find_build_tool", side_effect=lambda name: f"/sdk/{name}"
        ):
            with mock.patch.object(
                verify_release_apk,
                "run_tool",
                side_effect=lambda cmd: VALID_BADGING if "badging" in cmd else VALID_CERTS,
            ) as run_tool:
                with mock.patch.object(
                    verify_release_apk.sys, "argv", ["verify_release_apk.py"] + self.base_args()
                ):
                    self.assertEqual(verify_release_apk.main(), 0)
        run_tool.assert_any_call(["/sdk/apksigner", "verify", "--print-certs", str(self.apk)])

    def test_main_exits_one_on_package_mismatch(self) -> None:
        args = self.base_args(package="com.other.app")
        self.assertEqual(run_main(args, VALID_BADGING, VALID_CERTS), 1)

    def test_main_exits_one_on_version_name_mismatch(self) -> None:
        args = self.base_args(version_name="9.9.9")
        self.assertEqual(run_main(args, VALID_BADGING, VALID_CERTS), 1)

    def test_main_exits_one_on_version_code_mismatch(self) -> None:
        args = self.base_args(version_code=999)
        self.assertEqual(run_main(args, VALID_BADGING, VALID_CERTS), 1)


if __name__ == "__main__":
    unittest.main()
