#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

REGISTRY_ROOT="code.harzcloud.de/daily-harzcloud/daily"
STACK_DIR="/opt/daily/stack"
BASE_FILE="$STACK_DIR/docker-compose.yml"
ENV_OVERRIDE="$STACK_DIR/docker-compose.override.yml"
RELEASE_FILE="$STACK_DIR/docker-compose.forgejo-release.yml"
STATE_DIR="/var/lib/daily-forgejo-deploy"
BACKUP_ROOT="/opt/daily/deploy-backups"
DB_FILE="/opt/daily/backend-data/app.db"
HEALTH_URL="http://127.0.0.1:13379/api/health/live"
LOCK_FILE="/run/lock/daily-forgejo-deploy.lock"

usage() {
  echo "usage: $0 deploy <40-char-commit-sha> | rollback <backup-id> [--restore-db] | status" >&2
  exit 2
}

[ "$(id -u)" -eq 0 ] || { echo "must run as root" >&2; exit 1; }
mkdir -p "$STATE_DIR" "$BACKUP_ROOT"
exec 9>"$LOCK_FILE"
flock -n 9 || { echo "another Daily deploy is active" >&2; exit 1; }

compose_files() {
  COMPOSE_ARGS=(-f "$BASE_FILE")
  [ ! -f "$ENV_OVERRIDE" ] || COMPOSE_ARGS+=(-f "$ENV_OVERRIDE")
  [ ! -f "$RELEASE_FILE" ] || COMPOSE_ARGS+=(-f "$RELEASE_FILE")
}

compose_up() {
  compose_files
  (cd "$STACK_DIR" && docker compose "${COMPOSE_ARGS[@]}" up -d --no-deps backend admin)
}

wait_healthy() {
  local expected="$1" i body version
  for i in $(seq 1 60); do
    if body="$(curl -fsS --max-time 4 "$HEALTH_URL" 2>/dev/null)"; then
      version="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("version", ""))' <<<"$body")"
      if [ "$version" = "$expected" ] && docker exec daily-admin wget -qO- http://127.0.0.1/ >/dev/null; then
        return 0
      fi
    fi
    sleep 2
  done
  return 1
}

resolve_digest() {
  local tag="$1" prefix="$2" digest
  digest="$(docker image inspect "$tag" --format '{{range .RepoDigests}}{{println .}}{{end}}' | awk -v p="$prefix@" 'index($0,p)==1 {print; exit}')"
  [ -n "$digest" ] || { echo "no matching RepoDigest for $tag" >&2; return 1; }
  printf '%s' "$digest"
}

rollback_files() {
  local backup="$1"
  if [ -f "$backup/previous-release.yml" ]; then
    install -o root -g root -m 0600 "$backup/previous-release.yml" "$RELEASE_FILE"
  else
    rm -f -- "$RELEASE_FILE"
  fi
}

case "${1:-}" in
  deploy)
    sha="${2:-}"
    [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || usage
    short="${sha:0:7}"
    expected="srv-forgejo-${short}"
    backend_tag="$REGISTRY_ROOT/backend:sha-$sha"
    admin_tag="$REGISTRY_ROOT/admin:sha-$sha"
    docker pull "$backend_tag"
    docker pull "$admin_tag"
    backend_ref="$(resolve_digest "$backend_tag" "$REGISTRY_ROOT/backend")"
    admin_ref="$(resolve_digest "$admin_tag" "$REGISTRY_ROOT/admin")"
    stamp="$(date -u +%Y%m%dT%H%M%SZ)"
    backup="$BACKUP_ROOT/$stamp-$short"
    mkdir -m 0700 "$backup"
    [ ! -f "$RELEASE_FILE" ] || cp -a "$RELEASE_FILE" "$backup/previous-release.yml"
    docker inspect daily-backend daily-admin > "$backup/previous-containers.json"
    cp -a "$BASE_FILE" "$backup/docker-compose.yml"
    [ ! -f "$ENV_OVERRIDE" ] || cp -a "$ENV_OVERRIDE" "$backup/docker-compose.override.yml"
    compose_files
    (cd "$STACK_DIR" && docker compose "${COMPOSE_ARGS[@]}" stop backend)
    sqlite3 "$DB_FILE" ".timeout 30000" ".backup '$backup/app.db'"
    test "$(sqlite3 "$backup/app.db" 'pragma integrity_check;')" = "ok"
    cat > "$backup/next-release.yml" <<EOF
services:
  backend:
    image: "$backend_ref"
  admin:
    image: "$admin_ref"
EOF
    install -o root -g root -m 0600 "$backup/next-release.yml" "$RELEASE_FILE"
    if compose_up && wait_healthy "$expected"; then
      python3 - "$STATE_DIR/current.json" "$stamp" "$sha" "$expected" "$backend_ref" "$admin_ref" "$backup" <<'PY'
import json,sys
path,stamp,sha,version,backend,admin,backup=sys.argv[1:]
data={'deployedAt':stamp,'commit':sha,'version':version,'backend':backend,'admin':admin,'backup':backup,'status':'healthy'}
open(path,'w',encoding='utf-8').write(json.dumps(data,indent=2,sort_keys=True)+'\n')
PY
      chmod 0600 "$STATE_DIR/current.json"
      echo "deployed $sha as $expected"
    else
      echo "deploy health verification failed; rolling back" >&2
      compose_files
      (cd "$STACK_DIR" && docker compose "${COMPOSE_ARGS[@]}" stop backend) || true
      cp -a "$backup/app.db" "$DB_FILE"
      rm -f -- "${DB_FILE}-wal" "${DB_FILE}-shm"
      rollback_files "$backup"
      compose_up
      exit 1
    fi
    ;;
  rollback)
    backup_id="${2:-}"
    [ -n "$backup_id" ] || usage
    backup="$BACKUP_ROOT/$backup_id"
    [ -d "$backup" ] || { echo "backup not found: $backup" >&2; exit 1; }
    compose_files
    (cd "$STACK_DIR" && docker compose "${COMPOSE_ARGS[@]}" stop backend)
    if [ "${3:-}" = "--restore-db" ]; then
      test "$(sqlite3 "$backup/app.db" 'pragma integrity_check;')" = "ok"
      cp -a "$backup/app.db" "$DB_FILE"
      rm -f -- "${DB_FILE}-wal" "${DB_FILE}-shm"
    fi
    rollback_files "$backup"
    compose_up
    echo "rollback applied from $backup_id; verify application health manually"
    ;;
  status)
    [ ! -f "$STATE_DIR/current.json" ] || cat "$STATE_DIR/current.json"
    curl -fsS "$HEALTH_URL"
    echo
    ;;
  *) usage ;;
esac
