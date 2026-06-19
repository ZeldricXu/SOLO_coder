.PHONY: all proto build build-scheduler build-worker build-api build-plugins test lint docker up down clean

PROJECT_NAME := df1-96
MODULE := github.com/df1-96/experiment
BIN_DIR := bin
PROTO_DIR := api/proto
PROTO_OUT := pkg/grpcapi

GO ?= go
GOFLAGS ?= -trimpath
LDFLAGS ?= -s -w
DOCKER ?= docker
DOCKER_COMPOSE ?= docker-compose

VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")
COMMIT ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo "none")
BUILD_TIME ?= $(shell date -u +"%Y-%m-%dT%H:%M:%SZ")

LDFLAGS += -X $(MODULE)/pkg/util.Version=$(VERSION)
LDFLAGS += -X $(MODULE)/pkg/util.Commit=$(COMMIT)
LDFLAGS += -X $(MODULE)/pkg/util.BuildTime=$(BUILD_TIME)

all: build

proto:
	@echo "Generating protobuf code..."
	@cd $(PROTO_DIR) && bash generate.sh

build-scheduler:
	@echo "Building scheduler..."
	@mkdir -p $(BIN_DIR)
	$(GO) build $(GOFLAGS) -ldflags "$(LDFLAGS)" -o $(BIN_DIR)/scheduler ./cmd/scheduler

build-worker:
	@echo "Building worker..."
	@mkdir -p $(BIN_DIR)
	$(GO) build $(GOFLAGS) -ldflags "$(LDFLAGS)" -o $(BIN_DIR)/worker ./cmd/worker

build-api:
	@echo "Building api..."
	@mkdir -p $(BIN_DIR)
	$(GO) build $(GOFLAGS) -ldflags "$(LDFLAGS)" -o $(BIN_DIR)/api ./cmd/api-server

build-plugins:
	@echo "Building plugins..."
	@$(MAKE) -C plugins all

build: build-scheduler build-worker build-api build-plugins
	@echo "Build complete!"

test:
	@echo "Running tests..."
	$(GO) test -v -race -coverprofile=coverage.out ./...
	@$(GO) tool cover -func=coverage.out | tail -1

lint:
	@echo "Running lint..."
	@if command -v golangci-lint >/dev/null 2>&1; then \
		golangci-lint run ./...; \
	else \
		$(GO) vet ./...; \
		echo "golangci-lint not found, using go vet instead"; \
	fi

docker: build-scheduler build-worker build-api
	@echo "Building Docker images..."
	$(DOCKER) build -f deployments/Dockerfile.scheduler -t $(PROJECT_NAME)/scheduler:$(VERSION) .
	$(DOCKER) build -f deployments/Dockerfile.worker -t $(PROJECT_NAME)/worker:$(VERSION) .
	$(DOCKER) build -f deployments/Dockerfile.api -t $(PROJECT_NAME)/api:$(VERSION) .
	@echo "Docker images built successfully!"
	@echo "  - $(PROJECT_NAME)/scheduler:$(VERSION)"
	@echo "  - $(PROJECT_NAME)/worker:$(VERSION)"
	@echo "  - $(PROJECT_NAME)/api:$(VERSION)"

docker-latest: docker
	@echo "Tagging latest..."
	$(DOCKER) tag $(PROJECT_NAME)/scheduler:$(VERSION) $(PROJECT_NAME)/scheduler:latest
	$(DOCKER) tag $(PROJECT_NAME)/worker:$(VERSION) $(PROJECT_NAME)/worker:latest
	$(DOCKER) tag $(PROJECT_NAME)/api:$(VERSION) $(PROJECT_NAME)/api:latest

up:
	@echo "Starting docker-compose..."
	@cd deployments && $(DOCKER_COMPOSE) up -d

down:
	@echo "Stopping docker-compose..."
	@cd deployments && $(DOCKER_COMPOSE) down

logs:
	@cd deployments && $(DOCKER_COMPOSE) logs -f

clean:
	@echo "Cleaning..."
	@rm -rf $(BIN_DIR)
	@rm -f coverage.out
	@$(MAKE) -C plugins clean
	@$(GO) clean ./...

help:
	@echo "Available targets:"
	@echo "  all              - Build all binaries (default)"
	@echo "  proto            - Generate protobuf code"
	@echo "  build            - Build all binaries and plugins"
	@echo "  build-scheduler  - Build scheduler binary"
	@echo "  build-worker     - Build worker binary"
	@echo "  build-api        - Build API binary"
	@echo "  build-plugins    - Build all plugins"
	@echo "  test             - Run tests with coverage"
	@echo "  lint             - Run code linting"
	@echo "  docker           - Build Docker images"
	@echo "  docker-latest    - Build Docker images and tag as latest"
	@echo "  up               - Start docker-compose services"
	@echo "  down             - Stop docker-compose services"
	@echo "  logs             - View docker-compose logs"
	@echo "  clean            - Remove build artifacts"
	@echo "  help             - Show this help message"
