#!/bin/bash

set -e

echo "Installing development tools..."

if ! command -v golangci-lint &> /dev/null; then
    echo "Installing golangci-lint..."
    curl -sSfL https://raw.githubusercontent.com/golangci/golangci-lint/master/install.sh | sh -s -- -b $(go env GOPATH)/bin v1.55.2
else
    echo "golangci-lint already installed: $(golangci-lint --version)"
fi

if ! command -v goimports &> /dev/null; then
    echo "Installing goimports..."
    go install golang.org/x/tools/cmd/goimports@latest
else
    echo "goimports already installed"
fi

if ! command -v gosec &> /dev/null; then
    echo "Installing gosec..."
    go install github.com/securecodewarrior/gosec/v2/cmd/gosec@latest
else
    echo "gosec already installed: $(gosec --version 2>&1 | head -1)"
fi

if ! command -v govulncheck &> /dev/null; then
    echo "Installing govulncheck..."
    go install golang.org/x/vuln/cmd/govulncheck@latest
else
    echo "govulncheck already installed"
fi

echo "All tools installed successfully!"
