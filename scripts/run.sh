#!/usr/bin/env bash

set -e

PROFILE="${1:-dev}"
JAR_FILE="meshcontrol-api/target/meshcontrol-api-*.jar"

JAVA_OPTS_DEFAULT="-Xms512m -Xmx1024m -XX:+UseG1GC"

if [ ! -f $JAR_FILE ]; then
    echo "JAR file not found. Building first..."
    ./scripts/build.sh fast
fi

JAR=$(ls meshcontrol-api/target/meshcontrol-api-*.jar | head -n 1)

echo "Starting MeshControl with profile: $PROFILE"
echo "JAR file: $JAR"

SPRING_PROFILES_ACTIVE=$PROFILE java $JAVA_OPTS_DEFAULT -jar "$JAR"
