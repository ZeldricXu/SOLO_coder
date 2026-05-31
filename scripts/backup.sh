#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

BACKUP_DIR="${PROJECT_DIR}/backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

echo "=========================================="
echo "  ChainETL Platform Backup"
echo "  Timestamp: ${TIMESTAMP}"
echo "=========================================="

mkdir -p "${BACKUP_DIR}"

backup_database() {
    echo "[1/2] Backing up database..."
    
    DB_HOST="${DB_HOST:-localhost}"
    DB_PORT="${DB_PORT:-3306}"
    DB_NAME="${DB_NAME:-chainetl}"
    DB_USER="${DB_USERNAME:-root}"
    DB_PASS="${DB_PASSWORD:-password}"
    
    BACKUP_FILE="${BACKUP_DIR}/db-backup-${TIMESTAMP}.sql"
    
    if command -v mysqldump &> /dev/null; then
        mysqldump \
            -h"${DB_HOST}" \
            -P"${DB_PORT}" \
            -u"${DB_USER}" \
            -p"${DB_PASS}" \
            --single-transaction \
            --routines \
            --triggers \
            "${DB_NAME}" > "${BACKUP_FILE}"
        
        gzip "${BACKUP_FILE}"
        
        FILE_SIZE=$(du -h "${BACKUP_FILE}.gz" | cut -f1)
        echo "✓ Database backup: ${BACKUP_FILE}.gz (${FILE_SIZE})"
    else
        echo "⚠ mysqldump not found, trying Docker container..."
        
        if docker ps --format '{{.Names}}' | grep -q "chainetl-mysql"; then
            docker exec chainetl-mysql mysqldump \
                -u"${DB_USER}" \
                -p"${DB_PASS}" \
                --single-transaction \
                --routines \
                --triggers \
                "${DB_NAME}" > "${BACKUP_FILE}"
            
            gzip "${BACKUP_FILE}"
            
            FILE_SIZE=$(du -h "${BACKUP_FILE}.gz" | cut -f1)
            echo "✓ Database backup (via Docker): ${BACKUP_FILE}.gz (${FILE_SIZE})"
        else
            echo "✗ No database backup option available"
            return 1
        fi
    fi
}

backup_config() {
    echo "[2/2] Backing up configuration..."
    
    CONFIG_BACKUP="${BACKUP_DIR}/config-backup-${TIMESTAMP}.tar.gz"
    
    tar -czf "${CONFIG_BACKUP}" \
        -C "${PROJECT_DIR}" \
        --exclude="target" \
        --exclude="backups" \
        --exclude=".git" \
        --exclude="node_modules" \
        src/main/resources/config \
        src/main/resources/application*.yml \
        docker-compose.yml \
        .env 2>/dev/null || true
    
    if [ -f "${CONFIG_BACKUP}" ]; then
        FILE_SIZE=$(du -h "${CONFIG_BACKUP}" | cut -f1)
        echo "✓ Config backup: ${CONFIG_BACKUP} (${FILE_SIZE})"
    else
        echo "⚠ No config files to backup"
    fi
}

cleanup_old_backups() {
    echo ""
    echo "Cleaning up old backups (keeping last 7 days)..."
    
    find "${BACKUP_DIR}" -type f -name "*.gz" -mtime +7 -delete
    
    REMAINING=$(find "${BACKUP_DIR}" -type f -name "*.gz" | wc -l)
    echo "✓ Remaining backup files: ${REMAINING}"
}

backup_database
backup_config
cleanup_old_backups

echo ""
echo "=========================================="
echo "  Backup completed successfully!"
echo "  Backup location: ${BACKUP_DIR}"
echo "=========================================="
