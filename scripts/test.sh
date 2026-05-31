#!/usr/bin/env bash

set -e

SCOPE="${1:-all}"

echo "Running tests: $SCOPE"

case $SCOPE in
    unit)
        mvn test -Pdev -Dtest="*Test" -DfailIfNoTests=false
        ;;
    integration)
        mvn test -Pdev -Dtest="*IT,*IntegrationTest" -DfailIfNoTests=false
        ;;
    all)
        mvn test -Pdev
        ;;
    module)
        if [ -z "$2" ]; then
            echo "Usage: ./scripts/test.sh module <module-name>"
            exit 1
        fi
        mvn test -Pdev -pl "$2"
        ;;
    coverage)
        mvn clean test -Pcoverage
        echo "Coverage report available at: ./meshcontrol-api/target/site/jacoco/index.html"
        ;;
    *)
        echo "Invalid scope. Available: unit, integration, all, module <name>, coverage"
        exit 1
        ;;
esac

echo "Tests completed!"
