#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "build_release_index.py"
FINGERPRINT = "72e05a43a7be5837d83c922ad3496782499547fd94a5efa431dec712df6d4138"


class BuildReleaseIndexTest(unittest.TestCase):
    def test_adds_installation_identity_and_preserves_legacy_history(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            apk = temp / "app-release.apk"
            apk.write_bytes(b"signed-apk-placeholder")
            notes = temp / "notes.json"
            notes.write_text(json.dumps({"title": "Bridge", "highlights": ["Neutral"]}), encoding="utf-8")
            output = temp / "index.json"
            output.write_text(
                json.dumps({"schemaVersion": 1, "latest": "0.8.28", "releases": [{"version": "0.8.28"}]}),
                encoding="utf-8",
            )

            subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--version", "0.8.29",
                    "--version-code", "142029",
                    "--package-name", "com.selfhosted.daily",
                    "--signing-cert-sha256", FINGERPRINT,
                    "--apk", str(apk),
                    "--notes", str(notes),
                    "--output", str(output),
                ],
                check=True,
            )

            index = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("0.8.29", index["latest"])
            self.assertEqual(2, len(index["releases"]))
            current = index["releases"][0]
            self.assertEqual(142029, current["versionCode"])
            self.assertEqual("com.selfhosted.daily", current["packageName"])
            self.assertEqual(FINGERPRINT, current["signingCertSha256"])
            self.assertEqual(len(apk.read_bytes()), current["size"])
            self.assertRegex(current["sha256"], r"^[a-f0-9]{64}$")


if __name__ == "__main__":
    unittest.main()
