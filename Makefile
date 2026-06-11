# ============================================================
# Makefile for Game Room Engine
# ============================================================

# --- Meta ---
APP_NAME       := gameroom-server
PKG            := github.com/studio/gameroom
CMD_DIR        := ./cmd/server
BIN_DIR        := ./bin
COVER_FILE     := coverage.out
COVER_HTML     := coverage.html

# --- Build Info ---
VERSION        ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
COMMIT         ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo none)
BUILD_TIME     ?= $(shell date -u +"%Y-%m-%dT%H:%M:%SZ")
GO_VERSION     ?= $(shell go version | awk '{print $$3}')

# --- Docker ---
IMAGE_REPO     ?= harbor.example.com/gaming/gameroom
IMAGE_TAG      ?= $(VERSION)

# --- Go Build Flags ---
LDFLAGS        := -s -w \
  -X '$(PKG)/pkg/config.Info.Version=$(VERSION)' \
  -X '$(PKG)/pkg/config.Info.BuildTime=$(BUILD_TIME)' \
  -X '$(PKG)/pkg/config.Info.Commit=$(COMMIT)' \
  -X '$(PKG)/pkg/config.Info.GoVersion=$(GO_VERSION)'

GOFLAGS        ?=
TEST_FLAGS     ?= -count=1 -race -timeout=180s

.PHONY: all build test vet lint tidy cover clean run docker-build docker-push version help

# --- Default target ---
all: tidy build test

# --- Build ---
build:
	@mkdir -p $(BIN_DIR)
	@echo ">>> Building $(APP_NAME) $(VERSION)..."
	CGO_ENABLED=0 go build $(GOFLAGS) -trimpath \
		-ldflags="$(LDFLAGS)" \
		-o $(BIN_DIR)/$(APP_NAME) \
		$(CMD_DIR)
	@echo ">>> Built $(BIN_DIR)/$(APP_NAME)"

build-linux:
	@mkdir -p $(BIN_DIR)
	@echo ">>> Cross-building $(APP_NAME) for linux/amd64..."
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build $(GOFLAGS) -trimpath \
		-ldflags="$(LDFLAGS)" \
		-o $(BIN_DIR)/$(APP_NAME)-linux-amd64 \
		$(CMD_DIR)
	@echo ">>> Built $(BIN_DIR)/$(APP_NAME)-linux-amd64"

# --- Run locally (assumes .env in project root) ---
run: build
	@echo ">>> Starting $(APP_NAME) locally..."
	@cd .. && ./$(notdir $(CURDIR))/$(BIN_DIR)/$(APP_NAME) --env .env

# --- Testing ---
test:
	@echo ">>> Running unit tests..."
	go test $(TEST_FLAGS) ./pkg/...

integration-test:
	@echo ">>> Running integration tests with docker-compose..."
	docker-compose -f test/docker-compose.yml up -d
	sleep 10
	go test $(TEST_FLAGS) -tags=integration ./test/... ; \
	  EXIT_CODE=$$? ; \
	  docker-compose -f test/docker-compose.yml down ; \
	  exit $$EXIT_CODE

# --- Coverage ---
cover:
	@echo ">>> Running tests with coverage..."
	@go test -coverprofile=$(COVER_FILE) -covermode=atomic ./pkg/...
	@go tool cover -html=$(COVER_FILE) -o $(COVER_HTML)
	@echo ">>> Coverage report: $(COVER_HTML)"

# --- Quality ---
vet:
	@echo ">>> go vet..."
	@go vet ./...

lint:
	@echo ">>> golangci-lint..."
	@golangci-lint run ./... || (echo "install golangci-lint first: https://golangci-lint.run" && false)

tidy:
	@echo ">>> go mod tidy..."
	@GOPROXY=https://goproxy.cn,direct go mod tidy
	@git diff --exit-code go.mod go.sum >/dev/null 2>&1 || (echo "go.mod/go.sum were modified, please commit changes" && false)

# --- Docker ---
docker-build:
	@echo ">>> Building Docker image $(IMAGE_REPO):$(IMAGE_TAG)..."
	docker build \
	  --build-arg APP_VERSION=$(VERSION) \
	  --build-arg COMMIT_SHA=$(COMMIT) \
	  --build-arg BUILD_TIME=$(BUILD_TIME) \
	  -t $(IMAGE_REPO):$(IMAGE_TAG) \
	  -t $(IMAGE_REPO):latest \
	  .

docker-push: docker-build
	@echo ">>> Pushing $(IMAGE_REPO):$(IMAGE_TAG)..."
	docker push $(IMAGE_REPO):$(IMAGE_TAG)
	docker push $(IMAGE_REPO):latest

# --- Utilities ---
version:
	@echo "VERSION    : $(VERSION)"
	@echo "COMMIT     : $(COMMIT)"
	@echo "BUILD_TIME : $(BUILD_TIME)"
	@echo "GO_VERSION : $(GO_VERSION)"

clean:
	@rm -rf $(BIN_DIR) $(COVER_FILE) $(COVER_HTML)
	@echo ">>> Cleaned build artifacts"

help:
	@echo ""
	@echo "Game Room Engine — Makefile targets"
	@echo "==================================="
	@echo "  all             : tidy + build + test (default)"
	@echo "  build           : build server binary (host arch)"
	@echo "  build-linux     : cross-compile linux/amd64 binary"
	@echo "  run             : build + run server locally"
	@echo "  test            : run unit tests with -race"
	@echo "  integration-test: run integration tests under docker-compose"
	@echo "  cover           : run tests and open coverage HTML report"
	@echo "  vet             : go vet ./..."
	@echo "  lint            : golangci-lint run"
	@echo "  tidy            : go mod tidy + check for dirty go.mod"
	@echo "  docker-build    : build Docker image"
	@echo "  docker-push     : build + push to Harbor IMAGE_REPO"
	@echo "  version         : print current version/commit info"
	@echo "  clean           : remove build artifacts"
	@echo "  help            : show this help"
	@echo ""
