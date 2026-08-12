#!/usr/bin/env python3
from __future__ import annotations

import json
import hashlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "build_release_index.py"
FINGERPRINT = "72e05a43a7be5837d83c922ad3496782499547fd94a5efa431dec712df6d4138"


class BuildReleaseIndexTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.temp = Path(self.temp_dir.name)
        self.apk = self.temp / "app-release.apk"
        self.apk.write_bytes(b"signed-apk-placeholder")
        self.notes = self.temp / "notes.json"
        self.output = self.temp / "index.json"

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def run_generator(self, version: str, version_code: int, channel: str = "stable", prerelease: bool = False, **overrides: str) -> subprocess.CompletedProcess[str]:
        self.notes.write_text(json.dumps({"releasedAt": f"2026-08-{version_code % 20 + 1:02d}T00:00:00Z", "title": "Selfhosted"}), encoding="utf-8")
        values = {
            "apk_url_template": "https://downloads.example.net/daily/{tag}/app.apk",
            "changelog_url_template": "https://downloads.example.net/daily/{tag}/changelog.json",
            "release_url_template": "https://forge.example.net/team/daily/releases/{tag}",
        }
        values.update(overrides)
        command = [
            sys.executable, str(SCRIPT), "--version", version, "--version-code", str(version_code),
            "--channel", channel, "--prerelease", str(prerelease).lower(),
            "--package-name", "org.example.daily", "--signing-cert-sha256", FINGERPRINT,
            "--apk-sha256", hashlib.sha256(self.apk.read_bytes()).hexdigest(), "--apk-size", str(self.apk.stat().st_size),
            "--apk-url-template", values["apk_url_template"],
            "--changelog-url-template", values["changelog_url_template"],
            "--release-url-template", values["release_url_template"],
            "--apk", str(self.apk), "--notes", str(self.notes), "--output", str(self.output),
        ]
        return subprocess.run(command, text=True, capture_output=True)

    def test_foreign_selfhosting_domain_and_stable_metadata(self) -> None:
        result = self.run_generator("1.2.3", 123)
        self.assertEqual(0, result.returncode, result.stderr)
        index = json.loads(self.output.read_text(encoding="utf-8"))
        release = index["releases"][0]
        self.assertEqual("stable", release["channel"])
        self.assertFalse(release["prerelease"])
        self.assertEqual(123, release["versionCode"])
        self.assertEqual("org.example.daily", release["packageName"])
        self.assertNotIn("harzcloud.de", self.output.read_text(encoding="utf-8").lower())

    def test_prerelease_mixed_channels_and_version_code_sorting_are_deterministic(self) -> None:
        self.assertEqual(0, self.run_generator("1.0.0", 100).returncode)
        self.assertEqual(0, self.run_generator("1.1.0-beta.1", 102, "beta", True).returncode)
        self.assertEqual(0, self.run_generator("9.0.0", 101).returncode)
        first = self.output.read_bytes()
        self.assertEqual(0, self.run_generator("9.0.0", 101).returncode)
        self.assertEqual(first, self.output.read_bytes())
        releases = json.loads(first)["releases"]
        self.assertEqual([102, 101, 100], [item["versionCode"] for item in releases])
        self.assertEqual(["beta", "stable", "stable"], [item["channel"] for item in releases])

    def test_missing_or_invalid_required_parameters_fail(self) -> None:
        missing = subprocess.run([sys.executable, str(SCRIPT)], text=True, capture_output=True)
        self.assertNotEqual(0, missing.returncode)
        self.assertNotEqual(0, self.run_generator("1.0.0-beta.1", 110, "beta", False).returncode)
        self.assertNotEqual(0, self.run_generator("1.0.0", 0).returncode)
        self.assertNotEqual(0, self.run_generator("1.0.0", 100, apk_url_template="file:///tmp/app.apk").returncode)


if __name__ == "__main__":
    unittest.main()
