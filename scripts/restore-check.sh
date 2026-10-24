#!/usr/bin/env bash
set -euo pipefail

archive="${1:?Usage: restore-check.sh path/to/backup.dump}"
test -r "$archive" || { echo "Backup is not readable: $archive" >&2; exit 1; }
checksum="${archive}.sha256"
if [[ -r "$checksum" ]]; then
  (cd "$(dirname "$archive")" && sha256sum --check "$(basename "$checksum")")
fi
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${DEPLOY_ENV_FILE:-$root/deploy/deploy.env}"
test -f "$env_file" || { echo "Missing deployment env file: $env_file" >&2; exit 1; }
compose=(docker compose --env-file "$env_file" -f "$root/deploy/compose.prod.yaml")
restore_db="restore_check_$(date -u +%Y%m%d%H%M%S)_$$"
created=0
cleanup() {
  if [[ "$created" == 1 && "${KEEP_RESTORE_DB:-0}" != 1 ]]; then
    "${compose[@]}" exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" "$1"' sh "$restore_db"
  fi
}
trap cleanup EXIT

"${compose[@]}" exec -T postgres sh -c 'createdb -U "$POSTGRES_USER" "$1"' sh "$restore_db"
created=1
"${compose[@]}" exec -T postgres sh -c \
  'exec pg_restore -U "$POSTGRES_USER" -d "$1" --no-owner --no-acl --exit-on-error' sh "$restore_db" < "$archive"

counts_sql="SELECT (SELECT count(*) FROM payments)::text || ':' ||
  (SELECT count(*) FROM ledger_entries)::text || ':' ||
  (SELECT count(*) FROM idempotency_records)::text;"
restored_counts="$("${compose[@]}" exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$1" -At -v ON_ERROR_STOP=1 -c "$2"' sh "$restore_db" "$counts_sql")"

reconciliation_sql="SELECT
  (SELECT count(*) FROM ledger_accounts a WHERE a.balance_minor::numeric <>
    (SELECT COALESCE(sum(e.delta_minor),0) FROM ledger_entries e WHERE e.account_id=a.id)),
  (SELECT count(*) FROM (SELECT t.id FROM ledger_transactions t
    LEFT JOIN ledger_entries e ON e.transaction_id=t.id
    GROUP BY t.id HAVING COALESCE(sum(e.delta_minor),0) <> 0 OR count(e.id) <> 2) bad),
  (SELECT count(*) FROM ledger_accounts WHERE kind='CUSTOMER' AND balance_minor<0),
  (SELECT count(*) FROM payments p WHERE p.status='POSTED' AND NOT EXISTS
    (SELECT 1 FROM ledger_transactions t WHERE t.payment_id=p.id));"
discrepancies="$("${compose[@]}" exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$1" -At -F "|" -v ON_ERROR_STOP=1 -c "$2"' sh "$restore_db" "$reconciliation_sql")"
test "$discrepancies" = '0|0|0|0' || {
  echo "Restored journal has discrepancies: $discrepancies" >&2
  exit 1
}

# Spring starts with Flyway validation and Hibernate ddl-auto=validate against the restored DB.
"${compose[@]}" run --rm --no-deps -e "DB_URL=jdbc:postgresql://postgres:5432/$restore_db" \
  app --spring.main.web-application-type=none > /dev/null
echo "Restore verified in isolated database $restore_db: payments:entries:idempotency=$restored_counts; reconciliation is clean."
if [[ "${KEEP_RESTORE_DB:-0}" == 1 ]]; then
  echo "Database retained because KEEP_RESTORE_DB=1."
fi
