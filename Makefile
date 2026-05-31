.PHONY: all build test lint clean deps tidy fmt vet coverage dev prod run help

BINARY_NAME := edgevision
CMD_DIR := ./cmd/server
PKG := ./...
LINT_PKG := github.com/golangci/golangci-lint/cmd/golangci-lint@v1.55.2

VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")
COMMIT ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo "none")
BUILD_TIME ?= $(shell date -u +"%Y-%m-%dT%H:%M:%SZ")

LDFLAGS := -ldflags "-s -w \
	-X main.Version=$(VERSION) \
	-X main.Commit=$(COMMIT) \
	-X main.BuildTime=$(BUILD_TIME)"

GO_BUILD := CGO_ENABLED=0 go build $(LDFLAGS)

all: build

help:
	@echo "EdgeVision Build System"
	@echo "======================"
	@echo ""
	@echo "Targets:"
	@echo "  make dev        - Build for development (with debug info)"
	@echo "  make prod       - Build for production (optimized, stripped)"
	@echo "  make build      - Alias for prod build"
	@echo "  make test       - Run unit tests"
	@echo "  make lint       - Run golangci-lint"
	@echo "  make fmt        - Format code with gofmt"
	@echo "  make vet        - Run go vet"
	@echo "  make coverage   - Generate test coverage report"
	@echo "  make deps       - Download dependencies"
	@echo "  make tidy       - Tidy go.mod"
	@echo "  make run        - Run the server locally"
	@echo "  make clean      - Clean build artifacts"
	@echo "  make docker     - Build docker image"
	@echo ""
	@echo "Profiles:"
	@echo "  make profile=dev  - Development profile (verbose logging)"
	@echo "  make profile=prod - Production profile (optimized)"

dev: LDFLAGS := -ldflags " \
	-X main.Version=$(VERSION) \
	-X main.Commit=$(COMMIT) \
	-X main.BuildTime=$(BUILD_TIME) \
	-X main.Profile=dev"
dev: GO_BUILD := CGO_ENABLED=0 go build $(LDFLAGS)
dev: fmt vet
	@echo "Building development binary..."
	@mkdir -p bin
	$(GO_BUILD) -o bin/$(BINARY_NAME)-dev $(CMD_DIR)
	@echo "✅ Development binary built: bin/$(BINARY_NAME)-dev"

prod: fmt vet lint
	@echo "Building production binary..."
	@mkdir -p bin
	$(GO_BUILD) -o bin/$(BINARY_NAME) $(CMD_DIR)
	@echo "✅ Production binary built: bin/$(BINARY_NAME)"

build: prod

deps:
	@echo "Downloading dependencies..."
	@go mod download
	@echo "✅ Dependencies downloaded"

tidy:
	@echo "Tidying go.mod..."
	@go mod tidy
	@go mod verify
	@echo "✅ Dependencies tidied and verified"

fmt:
	@echo "Formatting code..."
	@gofmt -s -w .
	@echo "✅ Code formatted"

vet:
	@echo "Running go vet..."
	@go vet $(PKG)
	@echo "✅ go vet passed"

lint:
	@echo "Running golangci-lint..."
	@go run $(LINT_PKG) run --timeout=5m ./...
	@echo "✅ golangci-lint passed"

test:
	@echo "Running unit tests..."
	@go test -v -race -count=1 $(PKG)
	@echo "✅ All tests passed"

coverage:
	@echo "Running tests with coverage..."
	@mkdir -p coverage
	@go test -race -covermode=atomic -coverprofile=coverage/coverage.out $(PKG)
	@go tool cover -html=coverage/coverage.out -o coverage/coverage.html
	@go tool cover -func=coverage/coverage.out | tail -1
	@echo "✅ Coverage report generated: coverage/coverage.html"

run:
	@echo "Starting EdgeVision server..."
	@go run $(CMD_DIR)

clean:
	@echo "Cleaning build artifacts..."
	@rm -rf bin/ coverage/
	@go clean
	@echo "✅ Cleaned"

docker:
	@echo "Building docker image..."
	@docker build -t edgevision:$(VERSION) .
	@echo "✅ Docker image built: edgevision:$(VERSION)"

install-lint:
	@echo "Installing golangci-lint..."
	@go install $(LINT_PKG)
	@echo "✅ golangci-lint installed"

ci: tidy lint test coverage
	@echo "✅ CI pipeline passed"
