#!/usr/bin/env python3
"""Build the public Daily release index from signed, immutable artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    version = args.version.strip().removeprefix("v")
    notes = json.loads(args.notes.read_text(encoding="utf-8"))
    tag = f"v{version}"
    item = {
        "version": version,
        "releasedAt": notes.get("releasedAt") or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "title": notes.get("title") or f"Daily {version}",
        "highlights": notes.get("highlights", []),
        "details": notes.get("details", []),
        "apkUrl": f"https://releases.daily.harzcloud.de/apk/{tag}/app-release.apk",
        "changelogUrl": f"https://releases.daily.harzcloud.de/apk/{tag}/changelog.json",
        "releaseUrl": f"https://code.harzcloud.de/daily-harzcloud/daily/releases/tag/{tag}",
        "sha256": sha256(args.apk),
        "size": args.apk.stat().st_size,
    }
    existing = {"releases": []}
    if args.output.exists():
        existing = json.loads(args.output.read_text(encoding="utf-8"))
    releases = [entry for entry in existing.get("releases", []) if entry.get("version") != version]
    releases.append(item)
    releases.sort(key=lambda entry: tuple(int(part) for part in entry["version"].split(".")), reverse=True)
    result = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "latest": releases[0]["version"],
        "releases": releases,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

