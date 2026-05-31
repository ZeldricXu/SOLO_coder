#!/bin/bash

cd "$(dirname "$0")"

echo "========================================"
echo "  CDCSync - CDC Synchronization Pipeline"
echo "========================================"

if [ ! -f "target/cdcsync-core-1.0.0.jar" ]; then
    echo "Building project..."
    mvn clean package -DskipTests
    if [ $? -ne 0 ]; then
        echo "Build failed!"
        exit 1
    fi
fi

echo "Starting CDCSync application..."
java -jar target/cdcsync-core-1.0.0.jar \
    --spring.profiles.active=dev \
    --server.port=8080
