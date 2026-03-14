#!/usr/bin/env bash
set -euo pipefail

BACKEND_DATA_PATH="${1:-}"
SECRETS_PATH="${2:-}"
STACK_FILE_PATH="${3:-}"
ENV_FILE_PATH="${4:-}"
OUTPUT_DIR="${5:-./migration-backups}"
LABEL="${6:-$(date +%Y%m%d-%H%M%S)}"

if [[ -z "$BACKEND_DATA_PATH" || -z "$SECRETS_PATH" ]]; then
  echo "Usage: backup-full.sh <backend_data_path> <secrets_path> [stack_file] [env_file] [output_dir] [label]"
  exit 1
fi

BUNDLE_NAME="daily-migration-${LABEL}"
STAGING_ROOT="$(mktemp -d "/tmp/${BUNDLE_NAME}.XXXXXX")"
mkdir -p "$OUTPUT_DIR"

copy_if_exists() {
  local src="$1"
  local dst="$2"
  if [[ -e "$src" ]]; then
    mkdir -p "$(dirname "$dst")"
    cp -a "$src" "$dst"
  fi
}

copy_if_exists "$BACKEND_DATA_PATH/app.db" "$STAGING_ROOT/backend-data/app.db"
copy_if_exists "$BACKEND_DATA_PATH/uploads" "$STAGING_ROOT/backend-data/uploads"
copy_if_exists "$SECRETS_PATH" "$STAGING_ROOT/secrets"
[[ -n "$STACK_FILE_PATH" ]] && copy_if_exists "$STACK_FILE_PATH" "$STAGING_ROOT/config/stack.yml"
[[ -n "$ENV_FILE_PATH" ]] && copy_if_exists "$ENV_FILE_PATH" "$STAGING_ROOT/config/.env"

python3 - <<'PY' "$STAGING_ROOT"
import hashlib, json, os, sys, datetime
root = sys.argv[1]
files = []
for base, _, names in os.walk(root):
    for n in names:
        p = os.path.join(base, n)
        rel = os.path.relpath(p, root).replace("\\", "/")
        h = hashlib.sha256()
        with open(p, "rb") as f:
            while True:
                b = f.read(1024 * 1024)
                if not b:
                    break
                h.update(b)
        files.append({"path": rel, "size": os.path.getsize(p), "sha256": h.hexdigest()})
manifest = {
    "schemaVersion": "daily_migration_bundle_v1",
    "generatedAt": datetime.datetime.utcnow().replace(microsecond=0).isoformat() + "Z",
    "fileCount": len(files),
    "files": sorted(files, key=lambda x: x["path"]),
}
with open(os.path.join(root, "manifest.json"), "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=True, indent=2)
PY

BUNDLE_ZIP="${OUTPUT_DIR}/${BUNDLE_NAME}.tar.gz"
tar -czf "$BUNDLE_ZIP" -C "$STAGING_ROOT" .
SHA="$(sha256sum "$BUNDLE_ZIP" | awk '{print $1}')"
echo "Backup erstellt: $BUNDLE_ZIP"
echo "SHA256: $SHA"
