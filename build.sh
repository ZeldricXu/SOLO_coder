#!/bin/bash

# Quick build script

set -e

echo "======================================"
echo "Data Management Platform Build Script"
echo "======================================"

ENV=${1:-local}
SKIP_TESTS=${2:-true}

echo "Environment: $ENV"
echo "Skip tests: $SKIP_TESTS"

if [ "$SKIP_TESTS" = true ]; then
    echo "Building without tests..."
    mvn -B clean package -DskipTests -P$ENV
else
    echo "Building with tests and checks..."
    mvn -B clean verify -P$ENV -Pchecks -Pcoverage
fi

echo "Build completed!"
