#!/usr/bin/env bash

set -e

PROFILE="${1:-dev}"

echo "Building project with profile: $PROFILE"

case $PROFILE in
    fast)
        mvn clean package -Pfast -Dmaven.test.skip=true
        ;;
    dev)
        mvn clean package -Pdev
        ;;
    qa)
        mvn clean verify -Pqa
        ;;
    prod)
        mvn clean deploy -Pprod
        ;;
    coverage)
        mvn clean test -Pcoverage
        echo "Coverage report available at: ./meshcontrol-api/target/site/jacoco/index.html"
        ;;
    docker)
        mvn clean package -Pdocker jib:dockerBuild
        ;;
    *)
        echo "Invalid profile. Available: fast, dev, qa, prod, coverage, docker"
        exit 1
        ;;
esac

echo "Build completed successfully!"
