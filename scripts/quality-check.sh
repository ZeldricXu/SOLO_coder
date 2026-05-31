#!/usr/bin/env bash

set -e

CHECK="${1:-all}"

echo "Running quality checks: $CHECK"

run_checkstyle() {
    echo "Running Checkstyle..."
    mvn checkstyle:checkstyle -q
    echo "Checkstyle report: ./meshcontrol-api/target/site/checkstyle.html"
}

run_pmd() {
    echo "Running PMD..."
    mvn pmd:pmd -q
    echo "PMD report: ./meshcontrol-api/target/site/pmd.html"
}

run_spotbugs() {
    echo "Running SpotBugs..."
    mvn spotbugs:spotbugs -q
    echo "SpotBugs report: ./meshcontrol-api/target/site/spotbugs.html"
}

run_jacoco() {
    echo "Running JaCoCo coverage..."
    mvn test jacoco:report -q
    echo "Coverage report: ./meshcontrol-api/target/site/jacoco/index.html"
}

case $CHECK in
    checkstyle)
        run_checkstyle
        ;;
    pmd)
        run_pmd
        ;;
    spotbugs)
        run_spotbugs
        ;;
    coverage)
        run_jacoco
        ;;
    all)
        run_checkstyle
        run_pmd
        run_spotbugs
        run_jacoco
        ;;
    *)
        echo "Invalid check. Available: checkstyle, pmd, spotbugs, coverage, all"
        exit 1
        ;;
esac

echo "Quality checks completed!"
