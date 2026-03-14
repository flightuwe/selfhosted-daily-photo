#!/usr/bin/env bash
set -euo pipefail

BUNDLE_ARCHIVE="${1:-}"
TARGET_BACKEND_DATA_PATH="${2:-}"
TARGET_SECRETS_PATH="${3:-}"
TARGET_STACK_FILE_PATH="${4:-}"
TARGET_ENV_FILE_PATH="${5:-}"

if [[ -z "$BUNDLE_ARCHIVE" || -z "$TARGET_BACKEND_DATA_PATH" || -z "$TARGET_SECRETS_PATH" ]]; then
  echo "Usage: restore-full.sh <bundle.tar.gz> <target_backend_data> <target_secrets> [target_stack_file] [target_env_file]"
  exit 1
fi

if [[ ! -f "$BUNDLE_ARCHIVE" ]]; then
  echo "Bundle nicht gefunden: $BUNDLE_ARCHIVE"
  exit 1
fi

EXTRACT_ROOT="$(mktemp -d /tmp/daily-restore.XXXXXX)"
tar -xzf "$BUNDLE_ARCHIVE" -C "$EXTRACT_ROOT"

python3 - <<'PY' "$EXTRACT_ROOT"
import hashlib, json, os, sys
root = sys.argv[1]
manifest_path = os.path.join(root, "manifest.json")
if not os.path.isfile(manifest_path):
    raise SystemExit("manifest.json fehlt im Bundle")
with open(manifest_path, "r", encoding="utf-8") as f:
    manifest = json.load(f)
for item in manifest.get("files", []):
    path = os.path.join(root, item["path"])
    if not os.path.isfile(path):
        raise SystemExit(f"Datei fehlt laut Manifest: {item['path']}")
    h = hashlib.sha256()
    with open(path, "rb") as src:
        while True:
            b = src.read(1024 * 1024)
            if not b:
                break
            h.update(b)
    if h.hexdigest() != item["sha256"]:
        raise SystemExit(f"Checksum-Fehler bei {item['path']}")
print("Manifest-Pruefung OK")
PY

mkdir -p "$TARGET_BACKEND_DATA_PATH" "$TARGET_SECRETS_PATH"

if [[ -f "$EXTRACT_ROOT/backend-data/app.db" ]]; then
  cp -f "$EXTRACT_ROOT/backend-data/app.db" "$TARGET_BACKEND_DATA_PATH/app.db"
fi
if [[ -d "$EXTRACT_ROOT/backend-data/uploads" ]]; then
  mkdir -p "$TARGET_BACKEND_DATA_PATH/uploads"
  cp -a "$EXTRACT_ROOT/backend-data/uploads/." "$TARGET_BACKEND_DATA_PATH/uploads/"
fi
if [[ -d "$EXTRACT_ROOT/secrets" ]]; then
  cp -a "$EXTRACT_ROOT/secrets/." "$TARGET_SECRETS_PATH/"
fi
if [[ -n "$TARGET_STACK_FILE_PATH" && -f "$EXTRACT_ROOT/config/stack.yml" ]]; then
  mkdir -p "$(dirname "$TARGET_STACK_FILE_PATH")"
  cp -f "$EXTRACT_ROOT/config/stack.yml" "$TARGET_STACK_FILE_PATH"
fi
if [[ -n "$TARGET_ENV_FILE_PATH" && -f "$EXTRACT_ROOT/config/.env" ]]; then
  mkdir -p "$(dirname "$TARGET_ENV_FILE_PATH")"
  cp -f "$EXTRACT_ROOT/config/.env" "$TARGET_ENV_FILE_PATH"
fi

echo "Restore erfolgreich."
