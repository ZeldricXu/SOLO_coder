.PHONY: help build test run clean quality checkstyle pmd spotbugs coverage docker up down install-hooks

help:
	@echo "MeshControl Makefile"
	@echo ""
	@echo "Available targets:"
	@echo "  build          - Build project (dev profile)"
	@echo "  build-fast     - Fast build (skip tests and checks)"
	@echo "  build-prod     - Production build"
	@echo "  test           - Run all tests"
	@echo "  test-unit      - Run unit tests only"
	@echo "  test-integration - Run integration tests only"
	@echo "  test-coverage  - Run tests with coverage"
	@echo "  run            - Run application (dev profile)"
	@echo "  clean          - Clean project"
	@echo "  quality        - Run all quality checks"
	@echo "  checkstyle     - Run Checkstyle"
	@echo "  pmd            - Run PMD"
	@echo "  spotbugs       - Run SpotBugs"
	@echo "  coverage       - Generate coverage report"
	@echo "  docker-build   - Build Docker image"
	@echo "  docker-push    - Push Docker image"
	@echo "  up             - Start all services via docker-compose"
	@echo "  down           - Stop all services"
	@echo "  install-hooks  - Install Git pre-commit hooks"
	@echo "  upgrade        - Upgrade Maven dependencies"

build:
	./scripts/build.sh dev

build-fast:
	./scripts/build.sh fast

build-prod:
	./scripts/build.sh prod

test:
	./scripts/test.sh all

test-unit:
	./scripts/test.sh unit

test-integration:
	./scripts/test.sh integration

test-coverage:
	./scripts/test.sh coverage

run:
	./scripts/run.sh dev

clean:
	mvn clean

quality:
	./scripts/quality-check.sh all

checkstyle:
	./scripts/quality-check.sh checkstyle

pmd:
	./scripts/quality-check.sh pmd

spotbugs:
	./scripts/quality-check.sh spotbugs

coverage:
	./scripts/quality-check.sh coverage

docker-build:
	docker build -t meshcontrol:latest .

docker-push:
	docker tag meshcontrol:latest ghcr.io/meshcontrol/meshcontrol:latest
	docker push ghcr.io/meshcontrol/meshcontrol:latest

up:
	docker-compose up -d

down:
	docker-compose down

install-hooks:
	./scripts/setup-hooks.sh

upgrade:
	mvn versions:display-dependency-updates
	mvn versions:display-plugin-updates
