#!/bin/bash
set -e

echo "========================================="
echo "Starting Flyway Database Migration"
echo "========================================="

FLYWAY_URL="${FLYWAY_URL:-jdbc:mysql://mysql:3306/cardgame?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai}"
FLYWAY_USER="${FLYWAY_USER:-root}"
FLYWAY_PASSWORD="${FLYWAY_PASSWORD:-}"
FLYWAY_LOCATIONS="${FLYWAY_LOCATIONS:-filesystem:/sql/migration}"
FLYWAY_BASELINE_ON_MIGRATE="${FLYWAY_BASELINE_ON_MIGRATE:-true}"
FLYWAY_CONNECT_RETRIES="${FLYWAY_CONNECT_RETRIES:-10}"
FLYWAY_CONNECT_RETRY_INTERVAL="${FLYWAY_CONNECT_RETRY_INTERVAL:-5}"

echo "Flyway URL: $FLYWAY_URL"
echo "Flyway User: $FLYWAY_USER"
echo "Flyway Locations: $FLYWAY_LOCATIONS"

for i in $(seq 1 $FLYWAY_CONNECT_RETRIES); do
    echo "Attempt $i/$FLYWAY_CONNECT_RETRIES: Checking database connection..."
    
    if command -v mysql >/dev/null 2>&1; then
        DB_HOST=$(echo $FLYWAY_URL | grep -oP '//\K[^:/]+')
        DB_PORT=$(echo $FLYWAY_URL | grep -oP ':\K[0-9]+(?=/)' || echo "3306")
        
        if mysql -h "$DB_HOST" -P "$DB_PORT" -u "$FLYWAY_USER" -p"$FLYWAY_PASSWORD" -e "SELECT 1" >/dev/null 2>&1; then
            echo "Database connection successful!"
            break
        fi
    else
        echo "mysql client not available, skipping connection check"
        break
    fi
    
    if [ $i -eq $FLYWAY_CONNECT_RETRIES ]; then
        echo "ERROR: Failed to connect to database after $FLYWAY_CONNECT_RETRIES attempts"
        exit 1
    fi
    
    echo "Connection failed, retrying in $FLYWAY_CONNECT_RETRY_INTERVAL seconds..."
    sleep $FLYWAY_CONNECT_RETRY_INTERVAL
done

echo "Running Flyway migrate..."
flyway \
    -url="$FLYWAY_URL" \
    -user="$FLYWAY_USER" \
    -password="$FLYWAY_PASSWORD" \
    -locations="$FLYWAY_LOCATIONS" \
    -baselineOnMigrate="$FLYWAY_BASELINE_ON_MIGRATE" \
    -connectRetries="$FLYWAY_CONNECT_RETRIES" \
    -encoding=UTF-8 \
    -cleanDisabled=true \
    migrate

echo "Flyway migration completed successfully!"
echo "========================================="
