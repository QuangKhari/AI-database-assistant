#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="${PROJECT_DIR:-/opt/ai-database-assistant}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/ai-database-assistant}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

cd "$PROJECT_DIR"
mkdir -p "$BACKUP_DIR"

docker compose --env-file .env.production -f compose.prod.yml exec -T system-db \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump --single-transaction --routines --triggers -u root "$MYSQL_DATABASE"' \
  | gzip > "$BACKUP_DIR/system-db-$TIMESTAMP.sql.gz"

find "$BACKUP_DIR" -type f -name 'system-db-*.sql.gz' -mtime +14 -delete
echo "$BACKUP_DIR/system-db-$TIMESTAMP.sql.gz"
