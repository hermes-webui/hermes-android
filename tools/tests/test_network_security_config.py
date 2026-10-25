import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONFIG_PATH = ROOT / "app" / "src" / "main" / "res" / "xml" / "network_security_config.xml"
MANIFEST_PATH = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class NetworkSecurityConfigTests(unittest.TestCase):
    def test_manifest_uses_the_network_security_config(self) -> None:
        manifest = ET.parse(MANIFEST_PATH).getroot()
        application = manifest.find("application")

        self.assertIsNotNone(application)
        self.assertEqual(
            application.get(f"{ANDROID_NS}networkSecurityConfig"),
            "@xml/network_security_config",
        )

    def test_base_config_trusts_system_and_user_cas_without_disabling_cleartext(self) -> None:
        root = ET.parse(CONFIG_PATH).getroot()
        base_config = root.find("base-config")

        self.assertIsNotNone(base_config)
        self.assertEqual(base_config.get("cleartextTrafficPermitted"), "true")

        trust_anchors = base_config.find("trust-anchors")
        self.assertIsNotNone(trust_anchors)
        self.assertEqual(
            {
                certificates.get("src")
                for certificates in trust_anchors.findall("certificates")
            },
            {"system", "user"},
        )
        self.assertEqual(len(root.findall("domain-config")), 0)


if __name__ == "__main__":
    unittest.main()
