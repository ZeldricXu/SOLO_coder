#!/usr/bin/env bash
set -euo pipefail

echo "==> Verifying go.mod/go.sum consistency"
go mod tidy
go mod verify

if ! git diff --exit-code go.mod go.sum; then
  echo "ERROR: go.mod or go.sum changed after tidy. Commit the updated files."
  exit 1
fi

echo "PASS: dependencies verified and locked"
