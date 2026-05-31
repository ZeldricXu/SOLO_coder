#!/usr/bin/env bash
set -euo pipefail

echo "==> Checking gofmt"
DIFF=$(gofmt -s -d .)
if [ -n "$DIFF" ]; then
  echo "FAIL: gofmt found issues"
  echo "$DIFF"
  exit 1
fi
echo "PASS: gofmt"

echo "==> Checking goimports"
DIFF=$(go run golang.org/x/tools/cmd/goimports@latest -l .)
if [ -n "$DIFF" ]; then
  echo "FAIL: goimports found issues in:"
  echo "$DIFF"
  exit 1
fi
echo "PASS: goimports"

echo "==> Running go vet"
go vet ./...
echo "PASS: go vet"

echo "==> Running golangci-lint"
golangci-lint run --timeout 5m ./...
echo "PASS: golangci-lint"

echo ""
echo "All code gates passed."
