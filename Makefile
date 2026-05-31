# ============================================
# StreamSQL - Stream SQL Computing Engine
# Makefile for development, build and deployment
# ============================================

# --- Project Variables ---
APP_NAME := streamsql
APP_VERSION ?= $(shell git describe --tags --abbrev=0 2>/dev/null || echo "dev")
GIT_COMMIT ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo "none")
BUILD_TIME ?= $(shell date -u +%Y-%m-%dT%H:%M:%SZ)
GO_VERSION := 1.21

# --- Directories ---
ROOT_DIR := $(shell pwd)
CMD_DIR := $(ROOT_DIR)/cmd
INTERNAL_DIR := $(ROOT_DIR)/internal
CONFIG_DIR := $(ROOT_DIR)/config
BIN_DIR := $(ROOT_DIR)/bin
DIST_DIR := $(ROOT_DIR)/dist
TEST_DIR := $(ROOT_DIR)/test
LOG_DIR := $(ROOT_DIR)/logs
DATA_DIR := $(ROOT_DIR)/data

# --- Go Build Flags ---
LDFLAGS := -s -w \
	-X main.Version=$(APP_VERSION) \
	-X main.BuildTime=$(BUILD_TIME) \
	-X main.GitCommit=$(GIT_COMMIT)

# --- Docker Configuration ---
DOCKER_REGISTRY ?= ghcr.io
DOCKER_REPOSITORY ?= streamsql/streamsql
DOCKER_IMAGE ?= $(DOCKER_REGISTRY)/$(DOCKER_REPOSITORY)
DOCKER_TAG ?= $(APP_VERSION)

# --- Tools ---
GOLANGCI_LINT_VERSION := v1.55.2
GOTESTSUM_VERSION := v1.11.0

# --- Environment ---
APP_ENV ?= development

# --- Phony Targets ---
.PHONY: all help install-tools dependencies verify tidy fmt fmt-check vet lint test test-coverage build build-all run run-dev clean docker-build docker-push docker-run deploy-staging deploy-production release

# ============================================
# Default Target
# ============================================

all: help

help: ## Show this help message
	@echo "StreamSQL - Stream SQL Computing Engine"
	@echo "========================================"
	@echo ""
	@echo "Usage: make [target]"
	@echo ""
	@echo "Targets:"
	@awk 'BEGIN {FS = ":.*##"; printf "\n\033[1mDevelopment:\033[0m\n"} /^[a-zA-Z_-]+:.*?##/ { printf "  \033[36m%-25s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@awk 'BEGIN {FS = ":.*##"; printf "\n\033[1mBuild & Test:\033[0m\n"} /^[a-zA-Z_-]+:.*?##/ { if ($$2 ~ /^Build:/ || $$2 ~ /^Test:/ || $$2 ~ /^Code:/) printf "  \033[36m%-25s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@awk 'BEGIN {FS = ":.*##"; printf "\n\033[1mDocker:\033[0m\n"} /^[a-zA-Z_-]+:.*?##/ { if ($$2 ~ /^Docker:/) printf "  \033[36m%-25s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@awk 'BEGIN {FS = ":.*##"; printf "\n\033[1mDeployment:\033[0m\n"} /^[a-zA-Z_-]+:.*?##/ { if ($$2 ~ /^Deploy:/) printf "  \033[36m%-25s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

# ============================================
# Development Setup
# ============================================

install-tools: ## Install development tools
	@echo "Installing development tools..."
	@go install github.com/golangci/golangci-lint/cmd/golangci-lint@$(GOLANGCI_LINT_VERSION)
	@go install gotest.tools/gotestsum@$(GOTESTSUM_VERSION)
	@go install golang.org/x/tools/cmd/goimports@latest
	@go install github.com/securecodewarrior/gosec/v2/cmd/gosec@latest
	@echo "Tools installed successfully."

dependencies: ## Download Go dependencies
	@echo "Downloading dependencies..."
	@go mod download
	@go mod verify
	@echo "Dependencies downloaded and verified."

verify: ## Verify Go modules
	@go mod verify

tidy: ## Tidy Go modules
	@go mod tidy
	@echo "Go modules tidied."

# ============================================
# Code Quality
# ============================================

fmt: ## Format Go code using gofmt and goimports
	@echo "Formatting code..."
	@find . -name "*.go" -not -path "./vendor/*" -exec gofmt -w {} \;
	@goimports -w -local github.com/streamsql ./...
	@echo "Code formatted."

fmt-check: ## Check if code is properly formatted
	@echo "Checking code format..."
	@gofmt -l . | tee /tmp/gofmt.out
	@if [ -s /tmp/gofmt.out ]; then \
		echo "ERROR: Code is not properly formatted!"; \
		exit 1; \
	fi
	@echo "Code format is correct."

vet: ## Run go vet
	@echo "Running go vet..."
	@go vet ./...
	@echo "go vet passed."

lint: ## Run golangci-lint
	@echo "Running golangci-lint..."
	@golangci-lint run --timeout=10m --config=.golangci.yml ./...
	@echo "Linting passed."

lint-fix: ## Run golangci-lint with auto-fix
	@echo "Running golangci-lint with auto-fix..."
	@golangci-lint run --timeout=10m --config=.golangci.yml --fix ./...
	@echo "Auto-fix complete."

security-scan: ## Run security scan using gosec
	@echo "Running security scan..."
	@gosec -fmt=json -out=security-report.json ./... || true
	@echo "Security scan complete. Report saved to security-report.json"

# ============================================
# Testing
# ============================================

test: ## Run all unit tests
	@echo "Running unit tests..."
	@go test -v -race ./...
	@echo "Tests completed."

test-verbose: ## Run tests with verbose output
	@go test -v -race -count=1 ./...

test-coverage: ## Run tests with coverage report
	@echo "Running tests with coverage..."
	@go test -v -race -coverprofile=coverage.out -covermode=atomic ./...
	@go tool cover -func=coverage.out -o coverage.txt
	@go tool cover -html=coverage.out -o coverage.html
	@echo "Coverage report generated: coverage.html"

test-watch: ## Run tests in watch mode
	@gotestsum --watch --format testname ./...

# ============================================
# Build
# ============================================

build: ## Build the application binary
	@echo "Building StreamSQL $(APP_VERSION)..."
	@mkdir -p $(BIN_DIR)
	@CGO_ENABLED=0 go build \
		-ldflags='$(LDFLAGS)' \
		-a -installsuffix cgo \
		-o $(BIN_DIR)/$(APP_NAME) \
		$(CMD_DIR)/$(APP_NAME)/main.go
	@chmod +x $(BIN_DIR)/$(APP_NAME)
	@echo "Binary built: $(BIN_DIR)/$(APP_NAME)"
	@$(BIN_DIR)/$(APP_NAME) --version 2>&1 || true

build-all: ## Build for all platforms (linux, darwin, amd64, arm64)
	@echo "Building for all platforms..."
	@mkdir -p $(DIST_DIR)
	
	@echo "Building linux/amd64..."
	@GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build \
		-ldflags='$(LDFLAGS)' \
		-o $(DIST_DIR)/$(APP_NAME)-linux-amd64 \
		$(CMD_DIR)/$(APP_NAME)/main.go
	
	@echo "Building linux/arm64..."
	@GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build \
		-ldflags='$(LDFLAGS)' \
		-o $(DIST_DIR)/$(APP_NAME)-linux-arm64 \
		$(CMD_DIR)/$(APP_NAME)/main.go
	
	@echo "Building darwin/amd64..."
	@GOOS=darwin GOARCH=amd64 CGO_ENABLED=0 go build \
		-ldflags='$(LDFLAGS)' \
		-o $(DIST_DIR)/$(APP_NAME)-darwin-amd64 \
		$(CMD_DIR)/$(APP_NAME)/main.go
	
	@echo "Building darwin/arm64..."
	@GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build \
		-ldflags='$(LDFLAGS)' \
		-o $(DIST_DIR)/$(APP_NAME)-darwin-arm64 \
		$(CMD_DIR)/$(APP_NAME)/main.go
	
	@echo "All builds complete. Output in $(DIST_DIR)/"

# ============================================
# Run
# ============================================

run: ## Run the application
	@APP_ENV=$(APP_ENV) $(BIN_DIR)/$(APP_NAME)

run-dev: ## Run in development mode with hot reload (requires air)
	@APP_ENV=development air

run-docker: ## Run using Docker Compose
	@docker-compose up -d

# ============================================
# Clean
# ============================================

clean: ## Clean build artifacts
	@echo "Cleaning build artifacts..."
	@rm -rf $(BIN_DIR) $(DIST_DIR) $(LOG_DIR) $(DATA_DIR)
	@rm -f coverage.out coverage.txt coverage.html
	@rm -f security-report.json
	@echo "Clean complete."

# ============================================
# Docker
# ============================================

docker-build: ## Docker: Build image
	@echo "Building Docker image: $(DOCKER_IMAGE):$(DOCKER_TAG)..."
	@docker build \
		--build-arg VERSION=$(APP_VERSION) \
		-t $(DOCKER_IMAGE):$(DOCKER_TAG) \
		-t $(DOCKER_IMAGE):latest \
		.
	@echo "Docker image built."

docker-push: ## Docker: Push image to registry
	@echo "Pushing Docker image: $(DOCKER_IMAGE):$(DOCKER_TAG)..."
	@docker push $(DOCKER_IMAGE):$(DOCKER_TAG)
	@docker push $(DOCKER_IMAGE):latest
	@echo "Docker image pushed."

docker-run: ## Docker: Run container locally
	@docker run -d \
		--name $(APP_NAME) \
		-p 8080:8080 \
		-v $(CONFIG_DIR):/app/config \
		-e APP_ENV=development \
		$(DOCKER_IMAGE):$(DOCKER_TAG)

docker-stop: ## Docker: Stop and remove local container
	@docker stop $(APP_NAME) || true
	@docker rm $(APP_NAME) || true

docker-logs: ## Docker: Show container logs
	@docker logs -f $(APP_NAME)

# ============================================
# Database
# ============================================

db-up: ## Start database dependencies using Docker Compose
	@docker-compose up -d postgres redis

db-down: ## Stop database dependencies
	@docker-compose stop postgres redis

db-migrate: ## Run database migrations
	@echo "Running migrations..."

# ============================================
# Deployment
# ============================================

deploy-staging: ## Deploy: Deploy to staging environment
	@echo "Deploying to staging..."
	@kubectl set image deployment/streamsql streamsql=$(DOCKER_IMAGE):$(DOCKER_TAG) -n streamsql
	@kubectl rollout status deployment/streamsql -n streamsql --timeout=300s
	@echo "Deployment to staging complete."

deploy-production: ## Deploy: Deploy to production environment
	@echo "Deploying to production..."
	@kubectl set image deployment/streamsql streamsql=$(DOCKER_IMAGE):$(DOCKER_TAG) -n streamsql
	@kubectl rollout status deployment/streamsql -n streamsql --timeout=600s
	@echo "Deployment to production complete."

# ============================================
# Release
# ============================================

release: test lint build-all docker-build docker-push ## Create a new release (test, lint, build, push)
	@echo "Release $(APP_VERSION) complete!"

release-patch: ## Create a patch release
	@echo "Creating patch release..."
	@git checkout main
	@git pull origin main
	@bump2version patch
	@git push origin main --tags

release-minor: ## Create a minor release
	@echo "Creating minor release..."
	@git checkout main
	@git pull origin main
	@bump2version minor
	@git push origin main --tags

release-major: ## Create a major release
	@echo "Creating major release..."
	@git checkout main
	@git pull origin main
	@bump2version major
	@git push origin main --tags

# ============================================
# Quality Gate
# ============================================

quality-gate: fmt-check vet lint test-coverage security-scan ## Run full quality gate (format, vet, lint, test, security)
	@echo "Quality gate passed!"

# ============================================
# Info
# ============================================

version: ## Show version information
	@echo "StreamSQL $(APP_VERSION)"
	@echo "Git commit: $(GIT_COMMIT)"
	@echo "Build time: $(BUILD_TIME)"
	@echo "Go version: $(GO_VERSION)"

info: version ## Show project information
	@echo ""
	@echo "Project directory: $(ROOT_DIR)"
	@echo "Binary directory: $(BIN_DIR)"
	@echo "Docker image: $(DOCKER_IMAGE):$(DOCKER_TAG)"
