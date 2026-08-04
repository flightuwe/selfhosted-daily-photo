#!/usr/bin/env python3
"""Validate the canonical, app-readable Android release note document."""
import json
import re
import sys
from pathlib import Path

tag, source = sys.argv[1:3]
path = Path(source)
try:
    data = json.loads(path.read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError) as exc:
    raise SystemExit(f"Invalid release-note JSON {path}: {exc}")

expected = tag.removeprefix("v")
if not re.fullmatch(r"\d+\.\d+\.\d+", expected):
    raise SystemExit(f"Invalid semantic tag: {tag}")
if data.get("version") != expected:
    raise SystemExit(f"version mismatch: tag={expected}, json={data.get('version')!r}")
if not isinstance(data.get("title"), str) or not data["title"].strip():
    raise SystemExit("title must be a non-empty string")
for key in ("highlights", "details"):
    value = data.get(key, [] if key == "details" else None)
    if not isinstance(value, list) or any(not isinstance(item, str) or not item.strip() for item in value):
        raise SystemExit(f"{key} must be a list of non-empty strings")
if not data["highlights"] and not data.get("details", []):
    raise SystemExit("at least one highlight or detail is required")
