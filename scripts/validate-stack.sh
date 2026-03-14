#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-}"
ADMIN_TOKEN="${2:-}"
FROM_ISO="${3:-}"
TO_ISO="${4:-}"

if [[ -z "$BASE_URL" ]]; then
  echo "Usage: validate-stack.sh <base_url> [admin_token] [from_iso] [to_iso]"
  exit 1
fi

normalize_base_url() {
  local u="${1%/}"
  if [[ "$u" != */api ]]; then
    u="$u/api"
  fi
  echo "$u"
}

API_BASE="$(normalize_base_url "$BASE_URL")"
FROM_ISO="${FROM_ISO:-$(date -u -d '2 hours ago' +%Y-%m-%dT%H:%M:%SZ)}"
TO_ISO="${TO_ISO:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"

echo "Pruefe /health ..."
curl -fsS "$API_BASE/health" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("ok") is True; print("Health OK, version:", d.get("version","-"))'

if [[ -n "$ADMIN_TOKEN" ]]; then
  AUTH=(-H "Authorization: Bearer $ADMIN_TOKEN")
  echo "Pruefe /admin/trigger-runtime ..."
  curl -fsS "${AUTH[@]}" "$API_BASE/admin/trigger-runtime" >/dev/null
  echo "Pruefe /admin/incidents/export ..."
  curl -fsS "${AUTH[@]}" "$API_BASE/admin/incidents/export?from=${FROM_ISO}&to=${TO_ISO}&format=json" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); m=d.get("meta",{}); assert m.get("schemaVersion"); print("Incident schema:", m.get("schemaVersion"))'
fi

echo "Stack-Validierung erfolgreich."
