#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_DIR="${1:-/backup/flow-platform}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

echo "=== FlowPlatform Database Backup ==="
echo "Time: ${TIMESTAMP}"
echo "Backup dir: ${BACKUP_DIR}"

mkdir -p "${BACKUP_DIR}"

DB_HOST="${SPRING_DATASOURCE_URL:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-flow_platform}"
DB_USER="${DB_USER:-root}"

BACKUP_FILE="${BACKUP_DIR}/flow_platform_${TIMESTAMP}.sql.gz"

echo "Backing up ${DB_NAME}..."
mysqldump -h "${DB_HOST}" -P "${DB_PORT}" -u "${DB_USER}" -p \
    --single-transaction \
    --routines \
    --triggers \
    --events \
    "${DB_NAME}" | gzip > "${BACKUP_FILE}"

echo "✓ Backup complete: ${BACKUP_FILE}"
echo "  Size: $(du -h "$BACKUP_FILE" | cut -f1)"

find "${BACKUP_DIR}" -name "flow_platform_*.sql.gz" -mtime +30 -delete
echo "✓ Cleaned backups older than 30 days"
