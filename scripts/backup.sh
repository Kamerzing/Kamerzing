#!/usr/bin/env bash
set -euo pipefail
umask 077

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${DEPLOY_ENV_FILE:-$root/deploy/deploy.env}"
backup_dir="${BACKUP_DIR:-$root/deploy/backups}"
compose=(docker compose --env-file "$env_file" -f "$root/deploy/compose.prod.yaml")

test -f "$env_file" || { echo "Missing deployment env file: $env_file" >&2; exit 1; }
mkdir -p "$backup_dir"
temporary="$(mktemp "$backup_dir/.payment_core_XXXXXX")"
trap 'rm -f -- "$temporary"' EXIT
archive="$backup_dir/payment_core_$(date -u +%Y%m%dT%H%M%SZ)_$$.dump"
test ! -e "$archive" || { echo "Backup destination already exists" >&2; exit 1; }

"${compose[@]}" exec -T postgres sh -c \
  'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner --no-acl' > "$temporary"
test -s "$temporary" || { echo "Empty backup" >&2; exit 1; }
"${compose[@]}" exec -T postgres pg_restore --list < "$temporary" > /dev/null
mv -- "$temporary" "$archive"
(cd "$backup_dir" && sha256sum "$(basename "$archive")" > "$(basename "$archive").sha256")
echo "Backup created: $archive"
echo "Copy the archive and checksum to storage outside this server."
