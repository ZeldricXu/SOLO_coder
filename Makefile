.PHONY: help install install-dev install-test format lint type-check test test-unit test-integration test-coverage clean build run run-dev docker-build docker-up docker-down docker-logs migrate migrate-up migrate-down migrate-create release

PROJECT_NAME := platform-engineer
PYTHON := python3
PIP := pip
UVICORN := uvicorn
PYTEST := pytest
DOCKER_COMPOSE := docker compose

help:
	@echo "Available targets:"
	@echo "  install          Install production dependencies"
	@echo "  install-dev      Install development dependencies"
	@echo "  install-test     Install test dependencies"
	@echo "  format           Format code with black and isort"
	@echo "  lint             Run flake8 linter"
	@echo "  type-check       Run mypy type checker"
	@echo "  test             Run all tests"
	@echo "  test-unit        Run unit tests"
	@echo "  test-integration Run integration tests"
	@echo "  test-coverage    Run tests with coverage"
	@echo "  clean            Clean up temporary files"
	@echo "  build            Build Python package"
	@echo "  run              Run production server"
	@echo "  run-dev          Run development server with hot reload"
	@echo "  docker-build     Build Docker images"
	@echo "  docker-up        Start Docker containers"
	@echo "  docker-down      Stop Docker containers"
	@echo "  docker-logs      View Docker logs"
	@echo "  migrate-up       Run database migrations"
	@echo "  migrate-down     Rollback database migrations"
	@echo "  release          Create a new release"

install:
	$(PIP) install -r requirements.txt

install-dev:
	$(PIP) install -r requirements/dev.txt
	pre-commit install

install-test:
	$(PIP) install -r requirements/test.txt

format:
	black src/ tests/
	isort src/ tests/

lint:
	flake8 src/ tests/ --max-line-length=100

type-check:
	mypy src/ --config-file pyproject.toml

test:
	$(PYTEST) tests/ -v

test-unit:
	$(PYTEST) tests/ -v -m "unit"

test-integration:
	$(PYTEST) tests/ -v -m "integration"

test-coverage:
	$(PYTEST) tests/ \
		--cov=src/$(PROJECT_NAME) \
		--cov-report=xml \
		--cov-report=html \
		--cov-report=term-missing \
		--cov-fail-under=70

security-scan:
	bandit -c pyproject.toml -r src/

clean:
	find . -type f -name "*.pyc" -delete
	find . -type d -name "__pycache__" -delete
	find . -type d -name ".pytest_cache" -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name ".mypy_cache" -exec rm -rf {} + 2>/dev/null || true
	rm -rf build/ dist/ *.egg-info/ htmlcov/ .coverage coverage.xml

build: clean
	$(PYTHON) -m build

run:
	$(UVICORN) $(PROJECT_NAME).app.main:app \
		--host 0.0.0.0 \
		--port 8000 \
		--workers 2

run-dev:
	PYTHONPATH=src $(UVICORN) $(PROJECT_NAME).app.main:app \
		--host 0.0.0.0 \
		--port 8000 \
		--reload

docker-build:
	$(DOCKER_COMPOSE) build

docker-up:
	$(DOCKER_COMPOSE) up -d

docker-down:
	$(DOCKER_COMPOSE) down

docker-logs:
	$(DOCKER_COMPOSE) logs -f

migrate-up:
	alembic upgrade head

migrate-down:
	alembic downgrade -1

migrate-create:
	@read -p "Enter migration message: " message; \
	alembic revision --autogenerate -m "$$message"

release:
	@read -p "Enter version (e.g., 1.0.0): " version; \
	git tag -a v$$version -m "Release v$$version"; \
	git push origin v$$version

check: format lint type-check test

ci-check: lint type-check test-coverage security-scan
