#!/usr/bin/env python3
"""Build a deterministic, provider-neutral Daily release index."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlsplit


SEMVER = re.compile(r"^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$")
CHANNEL = re.compile(r"^[A-Za-z0-9._-]{1,40}$")
PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
SHA256 = re.compile(r"[a-f0-9]{64}")
TEMPLATE_FIELDS = {"version", "tag", "versionCode", "channel"}


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def boolean(value: str) -> bool:
    normalized = value.strip().lower()
    if normalized in {"true", "1", "yes"}:
        return True
    if normalized in {"false", "0", "no"}:
        return False
    raise argparse.ArgumentTypeError("expected true or false")


def render_url(parser: argparse.ArgumentParser, name: str, template: str, values: dict[str, object]) -> str:
    fields = set(re.findall(r"{([^{}]+)}", template))
    unknown = fields - TEMPLATE_FIELDS
    if unknown:
        parser.error(f"{name} contains unsupported placeholders: {', '.join(sorted(unknown))}")
    try:
        rendered = template.format(**values)
    except (KeyError, ValueError) as error:
        parser.error(f"{name} is invalid: {error}")
    parsed = urlsplit(rendered)
    if parsed.scheme not in {"https", "http"} or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
        parser.error(f"{name} must render an absolute HTTP(S) URL without credentials or fragment")
    return rendered


def sort_key(entry: dict[str, object]) -> tuple[int, str, str]:
    code = entry.get("versionCode")
    return (code if isinstance(code, int) and code > 0 else -1, str(entry.get("version", "")), str(entry.get("channel", "stable")))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--channel", required=True)
    parser.add_argument("--prerelease", required=True, type=boolean)
    parser.add_argument("--package-name", required=True)
    parser.add_argument("--signing-cert-sha256", required=True)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--apk-size", required=True, type=int)
    parser.add_argument("--apk-url-template", required=True)
    parser.add_argument("--changelog-url-template", required=True)
    parser.add_argument("--release-url-template", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    version = args.version.strip().removeprefix("v")
    if not SEMVER.fullmatch(version):
        parser.error("--version must be SemVer, optionally with a prerelease suffix")
    if args.version_code < 1:
        parser.error("--version-code must be positive")
    channel = args.channel.strip().lower()
    if not CHANNEL.fullmatch(channel):
        parser.error("--channel is invalid")
    if args.prerelease != ("-" in version):
        parser.error("--prerelease must match the version suffix")
    package_name = args.package_name.strip()
    if not PACKAGE.fullmatch(package_name):
        parser.error("--package-name must be a valid Android application ID")
    signing_cert_sha256 = re.sub(r"[^a-fA-F0-9]", "", args.signing_cert_sha256).lower()
    if not SHA256.fullmatch(signing_cert_sha256):
        parser.error("--signing-cert-sha256 must contain exactly 64 hexadecimal characters")
    if not args.apk.is_file() or args.apk.stat().st_size < 1:
        parser.error("--apk must be a non-empty file")
    expected_apk_sha256 = args.apk_sha256.strip().lower()
    if not SHA256.fullmatch(expected_apk_sha256) or expected_apk_sha256 != file_sha256(args.apk):
        parser.error("--apk-sha256 must be a valid SHA-256 matching --apk")
    if args.apk_size < 1 or args.apk_size != args.apk.stat().st_size:
        parser.error("--apk-size must be positive and match --apk")
    if not args.notes.is_file():
        parser.error("--notes must exist")

    notes = json.loads(args.notes.read_text(encoding="utf-8"))
    released_at = str(notes.get("releasedAt", "")).strip()
    if not released_at:
        parser.error("release notes must contain deterministic releasedAt")
    values = {"version": version, "tag": f"v{version}", "versionCode": args.version_code, "channel": channel}
    item = {
        "version": version,
        "versionCode": args.version_code,
        "channel": channel,
        "prerelease": args.prerelease,
        "packageName": package_name,
        "signingCertSha256": signing_cert_sha256,
        "releasedAt": released_at,
        "title": notes.get("title") or f"Daily {version}",
        "highlights": notes.get("highlights", []),
        "details": notes.get("details", []),
        "apkUrl": render_url(parser, "--apk-url-template", args.apk_url_template, values),
        "changelogUrl": render_url(parser, "--changelog-url-template", args.changelog_url_template, values),
        "releaseUrl": render_url(parser, "--release-url-template", args.release_url_template, values),
        "sha256": expected_apk_sha256,
        "size": args.apk_size,
    }
    existing = {"releases": []}
    if args.output.exists():
        existing = json.loads(args.output.read_text(encoding="utf-8"))
    releases = [
        entry for entry in existing.get("releases", [])
        if not (entry.get("version") == version and str(entry.get("channel", "stable")).lower() == channel)
    ]
    releases.append(item)
    releases.sort(key=sort_key, reverse=True)
    result = {
        "schemaVersion": 1,
        "generatedAt": max(str(entry.get("releasedAt", "")) for entry in releases),
        "latest": releases[0]["version"],
        "releases": releases,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
