#!/usr/bin/env python3
"""Render the human GitHub release body from the canonical JSON document."""
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(f"## Daily v{data['version']} – {data['title']}")
for heading, key in (("Highlights", "highlights"), ("Details", "details"), ("Bekannte Hinweise", "knownIssues"), ("Inkompatibilitaeten", "breakingChanges")):
    values = [str(value).strip() for value in data.get(key, []) if str(value).strip()]
    if values:
        print(f"\n### {heading}")
        for value in values:
            print(f"- {value}")
print("\n### Assets\n- Android APK: `app-release.apk`\n- Changelog JSON: `changelog.json`")
