APP_NAME := llmgateway
VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")
COMMIT ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo "none")
BUILD_TIME ?= $(shell date -u +"%Y-%m-%dT%H:%M:%SZ")

PKG := ./...
CMD_DIR := ./cmd/main.go
BIN_DIR := ./bin
OUTPUT := $(BIN_DIR)/$(APP_NAME)

LDFLAGS := -s -w \
	-X main.version=$(VERSION) \
	-X main.commit=$(COMMIT) \
	-X main.buildTime=$(BUILD_TIME)

GO ?= go
GOLANGCI_LINT ?= golangci-lint
DOCKER ?= docker

ENV ?= dev
CONFIG_PATH ?= ./configs/config.$(ENV).yaml

.PHONY: all
all: tidy fmt lint test build

.PHONY: help
help:
	@echo "Available targets:"
	@echo "  all        - Run tidy, fmt, lint, test, and build"
	@echo "  build      - Build the application"
	@echo "  run        - Run the application (use ENV=dev|staging|prod)"
	@echo "  test       - Run tests with coverage"
	@echo "  lint       - Run golangci-lint"
	@echo "  fmt        - Format Go code"
	@echo "  tidy       - Go mod tidy"
	@echo "  clean      - Clean build artifacts"
	@echo "  docker     - Build Docker image"
	@echo "  docker-push - Push Docker image to registry"
	@echo "  version    - Show version information"
	@echo "  deps       - Install dependencies"
	@echo "  install-lint - Install golangci-lint"

.PHONY: deps
deps:
	@echo "==> Downloading dependencies..."
	$(GO) mod download

.PHONY: install-lint
install-lint:
	@echo "==> Installing golangci-lint..."
	curl -sSfL https://raw.githubusercontent.com/golangci/golangci-lint/master/install.sh | sh -s -- -b $(shell go env GOPATH)/bin v1.55.2

.PHONY: tidy
tidy:
	@echo "==> Running go mod tidy..."
	$(GO) mod tidy

.PHONY: fmt
fmt:
	@echo "==> Formatting code..."
	$(GO) fmt $(PKG)
	goimports -w . 2>/dev/null || echo "goimports not installed, skipping..."

.PHONY: lint
lint:
	@echo "==> Running linter..."
	$(GOLANGCI_LINT) run --timeout=5m --config=.golangci.yml $(PKG)

.PHONY: lint-fix
lint-fix:
	@echo "==> Running linter with fix..."
	$(GOLANGCI_LINT) run --timeout=5m --config=.golangci.yml --fix $(PKG)

.PHONY: test
test:
	@echo "==> Running tests..."
	$(GO) test -v -race -coverprofile=coverage.out -covermode=atomic $(PKG)

.PHONY: test-short
test-short:
	@echo "==> Running short tests..."
	$(GO) test -short -race $(PKG)

.PHONY: coverage
coverage: test
	@echo "==> Generating coverage report..."
	$(GO) tool cover -html=coverage.out -o coverage.html
	@echo "Coverage report generated: coverage.html"

.PHONY: build
build:
	@echo "==> Building $(APP_NAME) $(VERSION)..."
	@mkdir -p $(BIN_DIR)
	CGO_ENABLED=0 $(GO) build -ldflags "$(LDFLAGS)" -o $(OUTPUT) $(CMD_DIR)
	@echo "Build completed: $(OUTPUT)"

.PHONY: build-linux
build-linux:
	@echo "==> Building for Linux AMD64..."
	@mkdir -p $(BIN_DIR)
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 $(GO) build -ldflags "$(LDFLAGS)" -o $(OUTPUT)-linux-amd64 $(CMD_DIR)
	@echo "Build completed: $(OUTPUT)-linux-amd64"

.PHONY: run
run:
	@echo "==> Running $(APP_NAME) with $(ENV) config..."
	CONFIG_PATH=$(CONFIG_PATH) $(GO) run $(CMD_DIR) $(CONFIG_PATH)

.PHONY: clean
clean:
	@echo "==> Cleaning..."
	rm -rf $(BIN_DIR)
	rm -f coverage.out coverage.html
	rm -f *.log

.PHONY: docker
docker:
	@echo "==> Building Docker image $(APP_NAME):$(VERSION)..."
	$(DOCKER) build \
		--build-arg VERSION=$(VERSION) \
		--build-arg COMMIT=$(COMMIT) \
		--build-arg BUILD_TIME=$(BUILD_TIME) \
		-t $(APP_NAME):$(VERSION) \
		-t $(APP_NAME):latest \
		.

.PHONY: docker-push
docker-push: docker
	@echo "==> Pushing Docker image..."
	$(DOCKER) push $(APP_NAME):$(VERSION)
	$(DOCKER) push $(APP_NAME):latest

.PHONY: docker-run
docker-run:
	@echo "==> Running Docker container..."
	$(DOCKER) run --rm -p 8080:8080 \
		--env-file .env.$(ENV) \
		$(APP_NAME):latest

.PHONY: version
version:
	@echo "$(APP_NAME) version: $(VERSION)"
	@echo "Commit: $(COMMIT)"
	@echo "Build time: $(BUILD_TIME)"

.PHONY: pre-commit
pre-commit: tidy fmt lint test-short
	@echo "==> Pre-commit checks passed!"
