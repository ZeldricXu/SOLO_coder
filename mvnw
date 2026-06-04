#!/bin/sh
# Maven Wrapper script - downloads Maven if not installed
set -e

MAVEN_PROJECTBASEDIR="$(cd "$(dirname "$0")" && pwd)"
MAVEN_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.tar.gz"
MAVEN_HOME="$HOME/.m2/wrapper/dists/apache-maven-3.9.6"

if [ ! -f "$MAVEN_HOME/bin/mvn" ]; then
    echo "Downloading Maven 3.9.6..."
    mkdir -p "$MAVEN_HOME"
    TMPFILE="$(mktemp)"
    curl -sL "$MAVEN_URL" -o "$TMPFILE"
    tar -xzf "$TMPFILE" -C "$HOME/.m2/wrapper/dists/"
    rm -f "$TMPFILE"
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
