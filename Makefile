BINARY_NAME   := session287
GO            := go
GOFLAGS       :=
LDFLAGS       :=
COVERAGE_DIR  := .coverage
COVERAGE_MIN  := 60

ifdef PROFILE
  ifeq ($(PROFILE),dev)
    LDFLAGS += -X main.buildProfile=dev
    GOFLAGS += -race
  else ifeq ($(PROFILE),staging)
    LDFLAGS += -X main.buildProfile=staging -s -w
  else ifeq ($(PROFILE),prod)
    LDFLAGS += -X main.buildProfile=prod -s -w
    GOFLAGS += -trimpath
  else
    $(error Unknown PROFILE "$(PROFILE)". Use dev, staging, or prod)
  endif
else
  PROFILE  := dev
  LDFLAGS += -X main.buildProfile=dev
  GOFLAGS += -race
endif

BUILD_TIME    := $(shell date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || powershell -Command "Get-Date -Format yyyy-MM-ddTHH:mm:ssZ")
GIT_COMMIT    := $(shell git rev-parse --short HEAD 2>/dev/null || echo "unknown")
GIT_BRANCH    := $(shell git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
VERSION       := $(or $(shell git describe --tags --exact-match 2>/dev/null),0.0.1-dev)

LDFLAGS += -X main.version=$(VERSION) \
           -X main.gitCommit=$(GIT_COMMIT) \
           -X main.buildTime=$(BUILD_TIME)

.PHONY: help build clean test lint vet tidy verify cover docker docker-dev run fmt ci

help:
	@echo "Targets:"
	@echo "  build        Build binary (PROFILE=dev|staging|prod)"
	@echo "  run          Build & run"
	@echo "  test         Run tests with coverage"
	@echo "  cover        Generate HTML coverage report"
	@echo "  lint         Run golangci-lint"
	@echo "  vet          Run go vet"
	@echo "  fmt          Run gofmt + goimports"
	@echo "  tidy         Tidy & verify dependencies"
	@echo "  verify       Verify go.sum matches go.mod"
	@echo "  ci           Full CI gate (fmt+vet+lint+test)"
	@echo "  docker       Build production Docker image"
	@echo "  docker-dev   Build dev Docker image"
	@echo "  clean        Remove build artifacts"
	@echo ""
	@echo "Profiles: PROFILE=dev (default) | staging | prod"

build:
	@echo "==> Building $(BINARY_NAME) [profile=$(PROFILE)] [version=$(VERSION)]"
	$(GO) build $(GOFLAGS) -ldflags "$(LDFLAGS)" -o bin/$(BINARY_NAME) .

run: build
	./bin/$(BINARY_NAME)

test: mkdir-coverage
	@echo "==> Running tests [profile=$(PROFILE), coverage_min=$(COVERAGE_MIN)%]"
	$(GO) test $(GOFLAGS) -count=1 -timeout 120s \
		-coverprofile=$(COVERAGE_DIR)/coverage.out \
		-covermode=atomic ./...
	$(GO) tool cover -func=$(COVERAGE_DIR)/coverage.out | tail -1
	@COVERAGE=$$( $(GO) tool cover -func=$(COVERAGE_DIR)/coverage.out | grep total | awk '{print $$3}' | sed 's/%//' ); \
	if [ "$$(echo "$$COVERAGE < $(COVERAGE_MIN)" | bc -l 2>/dev/null || echo 0)" -eq 1 ]; then \
		echo "FAIL: coverage $$COVERAGE% < $(COVERAGE_MIN)%"; exit 1; \
	else \
		echo "PASS: coverage $$COVERAGE% >= $(COVERAGE_MIN)%"; \
	fi

cover: mkdir-coverage
	$(GO) test -coverprofile=$(COVERAGE_DIR)/coverage.out -covermode=atomic ./...
	$(GO) tool cover -html=$(COVERAGE_DIR)/coverage.out -o $(COVERAGE_DIR)/coverage.html
	@echo "==> Coverage report: $(COVERAGE_DIR)/coverage.html"

lint:
	@echo "==> Running golangci-lint"
	golangci-lint run --timeout 5m ./...

vet:
	@echo "==> Running go vet"
	$(GO) vet ./...

fmt:
	@echo "==> Formatting code"
	gofmt -s -w .
	$(GO) run golang.org/x/tools/cmd/goimports@latest -w .

tidy:
	@echo "==> Tidying dependencies"
	$(GO) mod tidy

verify:
	@echo "==> Verifying dependencies"
	$(GO) mod verify
	$(GO) mod tidy
	@git diff --exit-code go.mod go.sum || (echo "ERROR: go.mod/go.sum out of sync. Run 'make tidy' and commit." && exit 1)

ci: fmt vet lint test
	@echo "==> CI gate passed"

docker:
	@echo "==> Building Docker image [profile=prod]"
	docker build \
		--build-arg VERSION=$(VERSION) \
		--build-arg GIT_COMMIT=$(GIT_COMMIT) \
		--build-arg BUILD_TIME=$(BUILD_TIME) \
		-t $(BINARY_NAME):$(VERSION) \
		-t $(BINARY_NAME):latest \
		-f Dockerfile .

docker-dev:
	@echo "==> Building Docker image [profile=dev]"
	docker build \
		--target dev \
		-t $(BINARY_NAME):dev \
		-f Dockerfile .

mkdir-coverage:
	@mkdir -p $(COVERAGE_DIR)

clean:
	@echo "==> Cleaning"
	rm -rf bin/ $(COVERAGE_DIR)/
	$(GO) clean -testcache
