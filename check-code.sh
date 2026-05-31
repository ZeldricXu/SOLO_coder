#!/bin/bash

# Run static analysis

echo "======================================"
echo "Running Static Code Analysis"
echo "======================================"

echo "1. Running Checkstyle..."
mvn -B checkstyle:check -Pchecks

echo ""
echo "2. Running PMD..."
mvn -B pmd:check -Pchecks

echo ""
echo "3. Running SpotBugs..."
mvn -B spotbugs:check -Pchecks

echo ""
echo "All checks passed!"
