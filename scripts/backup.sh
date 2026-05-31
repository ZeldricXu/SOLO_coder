#!/bin/bash

# Backup script for Infrastructure Platform

set -e

BACKUP_DIR="${BACKUP_DIR:-./backups}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="${BACKUP_DIR}/backup_${TIMESTAMP}.tar.gz"

echo "Starting backup at $(date)..."

# Create backup directory if not exists
mkdir -p "$BACKUP_DIR"

# Backup files
FILES_TO_BACKUP=(
    "./data"
    "./storage"
    "./index"
    "./logs"
)

# Filter existing directories
EXISTING_FILES=()
for file in "${FILES_TO_BACKUP[@]}"; do
    if [ -e "$file" ]; then
        EXISTING_FILES+=("$file")
    fi
done

if [ ${#EXISTING_FILES[@]} -eq 0 ]; then
    echo "No files to backup"
    exit 0
fi

# Create archive
tar -czf "$BACKUP_FILE" "${EXISTING_FILES[@]}"

echo "Backup created: ${BACKUP_FILE}"
echo "Backup size: $(du -h "$BACKUP_FILE" | cut -f1)"

# Keep only last 7 backups
ls -t "${BACKUP_DIR}"/backup_*.tar.gz | tail -n +8 | xargs -r rm -f

echo "Old backups cleaned up"
echo "Backup completed successfully at $(date)"
