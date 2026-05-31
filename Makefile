.PHONY: help build build-dev build-production test test-coverage lint fmt fmt-check check bench doc doc-open clean run run-dev audit deny udeps miri safety quality ci release docker-build docker-push

PROJECT_NAME := enterprise_platform
DOCKER_REGISTRY ?= ghcr.io
DOCKER_IMAGE ?= $(DOCKER_REGISTRY)/platform/enterprise_platform
DOCKER_TAG ?= latest
GIT_SHA ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo "dev")

help:
	@echo "Available targets:"
	@echo "  build              - Build the project"
	@echo "  build-dev          - Build in development mode"
	@echo "  build-production     - Build for production (size optimized)"
	@echo "  test                - Run tests"
	@echo "  test-coverage       - Run tests with coverage"
	@echo "  lint                 - Run clippy linting"
	@echo "  fmt                  - Format the code"
	@echo "  fmt-check            - Check code formatting"
	@echo "  check                - Check the project"
	@echo "  bench                - Run benchmarks"
	@echo "  doc                  - Generate documentation"
	@echo "  clean                - Clean build artifacts"
	@echo "  run                  - Run the application"
	@echo "  run-dev              - Run in development mode"
	@echo "  audit                - Audit dependencies"
	@echo "  safety               - Run all safety checks"
	@echo "  quality              - Run all quality checks"
	@echo "  ci                   - Run all CI checks"
	@echo "  docker-build         - Build Docker image"
	@echo "  docker-push          - Push Docker image"

build:
	cargo build --release

build-dev:
	cargo build

build-production:
	cargo build --profile production

test:
	cargo test -- --nocapture

test-coverage:
	cargo tarpaulin --out Html --out Xml

lint:
	cargo clippy --all-targets --all-features -- -D warnings

fmt:
	cargo fmt --all

fmt-check:
	cargo fmt --all -- --check

check:
	cargo check --all-targets --all-features

bench:
	cargo bench

doc:
	cargo doc --no-deps --document-private-items

clean:
	cargo clean

run:
	cargo run --release

run-dev:
	cargo run

audit:
	cargo audit --file ./Cargo.lock --stale

deny:
	cargo deny check

udeps:
	cargo udeps --all-targets

miri:
	cargo miri test

safety: audit deny udeps

quality: fmt-check lint check test

ci: quality safety

release: build-production

docker-build:
	docker build -t $(DOCKER_IMAGE):$(GIT_SHA) -t $(DOCKER_IMAGE):$(DOCKER_TAG) .

docker-push:
	docker push $(DOCKER_IMAGE):$(GIT_SHA)
	docker push $(DOCKER_IMAGE):$(DOCKER_TAG)
