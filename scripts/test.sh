#!/usr/bin/env bash
set -euo pipefail

COVERAGE_MIN="${COVERAGE_MIN:-60}"
COVERAGE_DIR="${COVERAGE_DIR:-.coverage}"
PACKAGES="${PACKAGES:-./...}"
TIMEOUT="${TIMEOUT:-120s}"

mkdir -p "$COVERAGE_DIR"

echo "==> Running tests (coverage_min=${COVERAGE_MIN}%)"
go test -count=1 -timeout "$TIMEOUT" \
  -coverprofile="${COVERAGE_DIR}/coverage.out" \
  -covermode=atomic \
  "$PACKAGES"

echo ""
echo "==> Coverage by package:"
go tool cover -func="${COVERAGE_DIR}/coverage.out"

TOTAL_LINE=$(go tool cover -func="${COVERAGE_DIR}/coverage.out" | grep "^total:")
TOTAL_PCT=$(echo "$TOTAL_LINE" | awk '{print $NF}' | sed 's/%//')
echo ""
echo "Total coverage: ${TOTAL_PCT}%"

if command -v bc &>/dev/null; then
  if echo "${TOTAL_PCT} < ${COVERAGE_MIN}" | bc -l | grep -q 1; then
    echo "FAIL: coverage ${TOTAL_PCT}% < ${COVERAGE_MIN}%"
    exit 1
  fi
else
  INT_TOTAL=$(echo "${TOTAL_PCT}" | cut -d. -f1)
  if [ "$INT_TOTAL" -lt "$COVERAGE_MIN" ]; then
    echo "FAIL: coverage ${TOTAL_PCT}% < ${COVERAGE_MIN}%"
    exit 1
  fi
fi

echo "PASS: coverage ${TOTAL_PCT}% >= ${COVERAGE_MIN}%"

if [ "${1:-}" = "--html" ]; then
  go tool cover -html="${COVERAGE_DIR}/coverage.out" -o "${COVERAGE_DIR}/coverage.html"
  echo "HTML report: ${COVERAGE_DIR}/coverage.html"
fi
