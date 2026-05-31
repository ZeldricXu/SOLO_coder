SHELL := /bin/bash

APP_NAME := logrotate
APP_VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")
BUILD_TIME := $(shell date -u +"%Y-%m-%dT%H:%M:%SZ")
GIT_COMMIT := $(shell git rev-parse --short HEAD 2>/dev/null || echo "unknown")
GIT_BRANCH := $(shell git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")

GO ?= go
GOPATH := $(shell $(GO) env GOPATH)
GOBIN := $(GOPATH)/bin
GOFMT := gofmt
GOIMPORTS := goimports
GOLANGCI_LINT := golangci-lint
STATICCHECK := staticcheck

OS := $(shell uname -s | tr '[:upper:]' '[:lower:]')
ARCH := $(shell uname -m)
ifeq ($(ARCH),x86_64)
	ARCH := amd64
endif
ifeq ($(ARCH),aarch64)
	ARCH := arm64
endif

LD_FLAGS := -s -w \
	-X main.AppName=$(APP_NAME) \
	-X main.AppVersion=$(APP_VERSION) \
	-X main.BuildTime=$(BUILD_TIME) \
	-X main.GitCommit=$(GIT_COMMIT) \
	-X main.GitBranch=$(GIT_BRANCH)

ENV ?= dev
CONFIG_FILE := configs/config.$(ENV).yaml

DOCKER_REGISTRY ?= registry.example.com
DOCKER_REPO ?= $(APP_NAME)
DOCKER_TAG ?= $(APP_VERSION)
DOCKER_IMAGE := $(DOCKER_REGISTRY)/$(DOCKER_REPO):$(DOCKER_TAG)

.PHONY: all
all: help

.PHONY: help
help:
	@echo "Available targets:"
	@echo "  build             - Build the application"
	@echo "  build-all         - Build for all platforms"
	@echo "  run               - Run the application locally"
	@echo "  test              - Run unit tests"
	@echo "  test-coverage     - Run tests with coverage report"
	@echo "  test-integration  - Run integration tests"
	@echo "  lint              - Run golangci-lint"
	@echo "  lint-fix          - Run golangci-lint with auto-fix"
	@echo "  vet               - Run go vet"
	@echo "  fmt               - Run gofmt"
	@echo "  fmt-check         - Check formatting"
	@echo "  imports           - Run goimports"
	@echo "  staticcheck       - Run staticcheck"
	@echo "  security          - Run security scan (gosec)"
	@echo "  check             - Run all checks (fmt, vet, lint)"
	@echo "  deps              - Download dependencies"
	@echo "  deps-update       - Update dependencies"
	@echo "  tidy              - Run go mod tidy"
	@echo "  clean             - Clean build artifacts"
	@echo "  generate          - Run go generate"
	@echo "  docker-build      - Build Docker image"
	@echo "  docker-push       - Push Docker image"
	@echo "  docker-run        - Run Docker container locally"
	@echo "  compose-up        - Start services with docker-compose"
	@echo "  compose-down      - Stop services with docker-compose"
	@echo "  install-tools     - Install development tools"
	@echo "  docs              - Generate documentation"
	@echo "  version           - Show version info"

.PHONY: build
build:
	@echo "Building $(APP_NAME) $(APP_VERSION) for $(OS)/$(ARCH)..."
	@mkdir -p bin
	CGO_ENABLED=0 GOOS=$(OS) GOARCH=$(ARCH) $(GO) build \
		-ldflags '$(LD_FLAGS)' \
		-o bin/$(APP_NAME) \
		cmd/server/main.go
	@echo "Build complete: bin/$(APP_NAME)"

.PHONY: build-all
build-all:
	@echo "Building for all platforms..."
	@mkdir -p bin
	@for os in linux darwin; do \
		for arch in amd64 arm64; do \
			echo "Building $$os/$$arch..."; \
			CGO_ENABLED=0 GOOS=$$os GOARCH=$$arch $(GO) build \
				-ldflags '$(LD_FLAGS)' \
				-o bin/$(APP_NAME)-$$os-$$arch \
				cmd/server/main.go; \
		done; \
	done
	@echo "All builds complete"

.PHONY: run
run:
	@echo "Running $(APP_NAME) with $(ENV) config..."
	@CONFIG_FILE=$(CONFIG_FILE) $(GO) run \
		-ldflags '$(LD_FLAGS)' \
		cmd/server/main.go

.PHONY: test
test:
	@echo "Running unit tests..."
	@$(GO) test -v -race ./...

.PHONY: test-coverage
test-coverage:
	@echo "Running tests with coverage..."
	@mkdir -p coverage
	@$(GO) test -v -race -coverprofile=coverage/coverage.out -covermode=atomic ./...
	@$(GO) tool cover -html=coverage/coverage.out -o coverage/coverage.html
	@echo "Coverage report: coverage/coverage.html"

.PHONY: test-integration
test-integration:
	@echo "Running integration tests..."
	@$(GO) test -v -race -tags=integration ./...

.PHONY: lint
lint:
	@echo "Running golangci-lint..."
	@$(GOLANGCI_LINT) run ./...

.PHONY: lint-fix
lint-fix:
	@echo "Running golangci-lint with auto-fix..."
	@$(GOLANGCI_LINT) run --fix ./...

.PHONY: vet
vet:
	@echo "Running go vet..."
	@$(GO) vet ./...

.PHONY: fmt
fmt:
	@echo "Running gofmt..."
	@find . -name '*.go' -not -path './vendor/*' -exec $(GOFMT) -w {} +

.PHONY: fmt-check
fmt-check:
	@echo "Checking formatting..."
	@files=$$(find . -name '*.go' -not -path './vendor/*' -exec $(GOFMT) -l {} +); \
	if [ -n "$$files" ]; then \
		echo "Files need formatting:"; \
		echo "$$files"; \
		exit 1; \
	fi
	@echo "Formatting is correct"

.PHONY: imports
imports:
	@echo "Running goimports..."
	@find . -name '*.go' -not -path './vendor/*' -exec $(GOIMPORTS) -w {} +

.PHONY: staticcheck
staticcheck:
	@echo "Running staticcheck..."
	@$(STATICCHECK) ./...

.PHONY: security
security:
	@echo "Running security scan..."
	@gosec ./...

.PHONY: check
check: fmt-check vet lint staticcheck
	@echo "All checks passed!"

.PHONY: deps
deps:
	@echo "Downloading dependencies..."
	@$(GO) mod download

.PHONY: deps-update
deps-update:
	@echo "Updating dependencies..."
	@$(GO) get -u ./...
	@$(GO) mod tidy

.PHONY: tidy
tidy:
	@echo "Running go mod tidy..."
	@$(GO) mod tidy

.PHONY: clean
clean:
	@echo "Cleaning build artifacts..."
	@rm -rf bin/ coverage/ dist/
	@$(GO) clean ./...

.PHONY: generate
generate:
	@echo "Running go generate..."
	@$(GO) generate ./...

.PHONY: docker-build
docker-build:
	@echo "Building Docker image: $(DOCKER_IMAGE)..."
	@docker build \
		--build-arg APP_VERSION=$(APP_VERSION) \
		--build-arg BUILD_TIME=$(BUILD_TIME) \
		--build-arg GIT_COMMIT=$(GIT_COMMIT) \
		-t $(DOCKER_IMAGE) \
		-t $(DOCKER_REGISTRY)/$(DOCKER_REPO):latest \
		.
	@echo "Docker image built: $(DOCKER_IMAGE)"

.PHONY: docker-push
docker-push:
	@echo "Pushing Docker image: $(DOCKER_IMAGE)..."
	@docker push $(DOCKER_IMAGE)
	@docker push $(DOCKER_REGISTRY)/$(DOCKER_REPO):latest
	@echo "Docker image pushed"

.PHONY: docker-run
docker-run:
	@echo "Running Docker container..."
	@docker run -it --rm \
		-p 8080:8080 \
		-v $(PWD)/configs:/app/configs \
		-v $(PWD)/logs:/var/log/logrotate \
		-e ENV=$(ENV) \
		$(DOCKER_IMAGE)

.PHONY: compose-up
compose-up:
	@echo "Starting services with docker-compose..."
	@ENV=$(ENV) docker-compose up -d

.PHONY: compose-down
compose-down:
	@echo "Stopping services with docker-compose..."
	@docker-compose down

.PHONY: install-tools
install-tools:
	@echo "Installing development tools..."
	@$(GO) install github.com/golangci/golangci-lint/cmd/golangci-lint@latest
	@$(GO) install honnef.co
/go/tools/cmd/staticcheck@latest
	@$(GO) install golang.org/x/tools/cmd/goimports@latest
	@$(GO) install github.com/securecodewarrior/gosec/v2/cmd/gosec@latest
	@$(GO) install github.com/go-delve/delve/cmd/dlv@latest
	@echo "Tools installed"

.PHONY: docs
docs:
	@echo "Generating documentation..."
	@mkdir -p docs
	@$(GO) doc -all ./pkg/... > docs/api.md
	@echo "Documentation generated: docs/api.md"

.PHONY: version
version:
	@echo "App Name:    $(APP_NAME)"
	@echo "Version:     $(APP_VERSION)"
	@echo "Build Time:  $(BUILD_TIME)"
	@echo "Git Commit:  $(GIT_COMMIT)"
	@echo "Git Branch:  $(GIT_BRANCH)"
	@echo "Go Version:  $(shell $(GO) version)"
	@echo "OS/Arch:     $(OS)/$(ARCH)"

.PHONY: pre-commit
pre-commit: check test
	@echo "Pre-commit checks passed!"
