import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import app_version  # noqa: E402


class AppVersionTest(unittest.TestCase):
    def test_next_version_rolls_over_at_nine(self):
        self.assertEqual(app_version.next_version("0.9.5"), "0.9.6")
        self.assertEqual(app_version.next_version("0.9.9"), "1.0.0")
        self.assertEqual(app_version.next_version("1.0.9"), "1.1.0")
        self.assertEqual(app_version.next_version("1.9.9"), "2.0.0")
        self.assertEqual(app_version.next_version("12.3.4"), "12.3.5")

    def test_version_code_matches_existing_releases_and_grows(self):
        self.assertEqual(app_version.version_code("0.9.4"), 904001)
        self.assertEqual(app_version.version_code("0.9.5"), 905001)
        self.assertEqual(app_version.version_code("1.0.0"), 1_000_001)
        version, previous = "0.9.5", 0
        for _ in range(250):
            code = app_version.version_code(version)
            self.assertGreater(code, previous)
            previous, version = code, app_version.next_version(version)

    def test_latest_picks_the_highest_release_tag(self):
        tags = ["v0.6.0", "v0.9.5", "v0.9.10-beta", "v0.10.0", "nightly", "v0.9.4"]
        # v0.10.0 is a tag shape the scheme never creates, but it is still the highest.
        self.assertEqual(app_version.latest(tags), "0.10.0")
        self.assertEqual(app_version.latest(["v0.7.1", "v0.9.5", "v0.9.4"]), "0.9.5")
        with self.assertRaises(ValueError):
            app_version.latest(["nightly"])

    def test_rejects_versions_outside_the_scheme(self):
        for bad in ["0.9", "0.10.0", "0.9.10", "v0.9.5", "0.9.5-dev", ""]:
            with self.assertRaises(ValueError, msg=bad):
                app_version.parse(bad)

    def test_override_must_be_newer(self):
        self.assertEqual(app_version.checked_override("1.0.0", "0.9.5"), "1.0.0")
        with self.assertRaises(ValueError):
            app_version.checked_override("0.9.5", "0.9.5")
        with self.assertRaises(ValueError):
            app_version.checked_override("0.9.4", "0.9.5")


if __name__ == "__main__":
    unittest.main()
