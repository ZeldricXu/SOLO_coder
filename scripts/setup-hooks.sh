#!/usr/bin/env bash

set -e

HOOKS_DIR=".git/hooks"
SCRIPTS_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

mkdir -p "$HOOKS_DIR"

cp "$SCRIPTS_DIR/pre-commit.sh" "$HOOKS_DIR/pre-commit"
chmod +x "$HOOKS_DIR/pre-commit"

echo "Git hooks installed successfully!"
