APP_NAME := config-platform
APP_VERSION := 1.0.0
BUILD_TIME := $(shell date -u +"%Y-%m-%dT%H:%M:%SZ")
GIT_COMMIT := $(shell git rev-parse --short HEAD 2>/dev/null || echo "unknown")

LDFLAGS := -ldflags "-X main.version=$(APP_VERSION) -X main.buildTime=$(BUILD_TIME) -X main.gitCommit=$(GIT_COMMIT)"

.PHONY: all build build-dev build-staging build-prod clean test lint vet fmt run docker-build docker-up docker-down help

all: build

help:
	@echo "Available targets:"
	@echo "  make build-dev      - Build for development environment"
	@echo "  make build-staging  - Build for staging environment"
	@echo "  make build-prod     - Build for production environment"
	@echo "  make test           - Run tests"
	@echo "  make lint           - Run golangci-lint"
	@echo "  make vet            - Run go vet"
	@echo "  make fmt            - Format code"
	@echo "  make run            - Run locally"
	@echo "  make docker-build   - Build Docker image"
	@echo "  make docker-up      - Start services with docker-compose"
	@echo "  make docker-down    - Stop services"
	@echo "  make clean          - Clean build artifacts"

build-dev:
	@echo "Building for development..."
	@CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build $(LDFLAGS) -tags dev -o bin/$(APP_NAME)-dev ./cmd/server/
	@echo "Build complete: bin/$(APP_NAME)-dev"

build-staging:
	@echo "Building for staging..."
	@CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build $(LDFLAGS) -tags staging -o bin/$(APP_NAME)-staging ./cmd/server/
	@echo "Build complete: bin/$(APP_NAME)-staging"

build-prod:
	@echo "Building for production..."
	@CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags "-s -w $(LDFLAGS)" -tags prod -o bin/$(APP_NAME) ./cmd/server/
	@echo "Build complete: bin/$(APP_NAME)"

build: build-prod

test:
	@echo "Running tests..."
	@go test -v -race -coverprofile=coverage.out -covermode=atomic ./...

lint:
	@echo "Running golangci-lint..."
	@golangci-lint run --config .golangci.yml ./...

vet:
	@echo "Running go vet..."
	@go vet ./...

fmt:
	@echo "Formatting code..."
	@gofmt -w .
	@goimports -w . 2>/dev/null || true

run:
	@go run ./cmd/server/

docker-build:
	@docker build -t $(APP_NAME):$(APP_VERSION) .
	@docker tag $(APP_NAME):$(APP_VERSION) $(APP_NAME):latest

docker-up:
	@docker-compose up -d

docker-down:
	@docker-compose down

clean:
	@echo "Cleaning..."
	@rm -rf bin/
	@rm -f coverage.out
