.PHONY: help install install-dev install-prod lint format type-check test test-cov test-core test-modules test-api build run clean docker-build docker-run docker-push

PROJECT_NAME := streamsql
PYTHON := python3
PIP := pip3
POETRY := poetry
DOCKER_REGISTRY := your-registry.com
IMAGE_TAG := $(shell git describe --tags --always 2>/dev/null || echo "latest")

help:
	@echo "StreamSQL - Stream SQL Computing Engine"
	@echo ""
	@echo "Usage:"
	@echo "  make install        Install production dependencies"
	@echo "  make install-dev    Install development dependencies"
	@echo "  make install-prod   Install production-only dependencies"
	@echo "  make lint           Run linters (ruff, mypy)"
	@echo "  make format         Run code formatters (black, isort)"
	@echo "  make type-check     Run type checker (mypy)"
	@echo "  make test           Run all tests"
	@echo "  make test-cov       Run tests with coverage report"
	@echo "  make test-core      Run core module tests"
	@echo "  make test-modules   Run module tests"
	@echo "  make test-api       Run API tests"
	@echo "  make build          Build distribution packages"
	@echo "  make run            Run development server"
	@echo "  make clean          Clean build artifacts"
	@echo "  make docker-build   Build Docker image"
	@echo "  make docker-run     Run Docker container"
	@echo "  make docker-push    Push Docker image to registry"

install:
	$(PIP) install --upgrade pip
	$(PIP) install -r requirements.txt

install-dev: install
	$(PIP) install -r requirements-dev.txt

install-prod:
	$(PIP) install --upgrade pip
	$(PIP) install -r requirements-prod.txt

lint:
	@echo "Running ruff linter..."
	ruff check streamsql/ tests/
	@echo "Running flake8 (fallback)..."
	-flake8 streamsql/ tests/ --config=.flake8

format:
	@echo "Formatting code with black..."
	black streamsql/ tests/
	@echo "Sorting imports with isort..."
	isort streamsql/ tests/

type-check:
	@echo "Running type checker..."
	mypy streamsql/ --config-file=mypy.ini

test:
	@echo "Running all tests..."
	pytest tests/ -v --tb=short

test-cov:
	@echo "Running tests with coverage..."
	pytest tests/ -v \
		--cov=streamsql \
		--cov-report=term-missing \
		--cov-report=html:htmlcov \
		--cov-report=xml:coverage.xml \
		--cov-fail-under=80

test-core:
	@echo "Running core module tests..."
	pytest tests/test_core/ -v --tb=short

test-modules:
	@echo "Running module tests..."
	pytest tests/test_modules/ -v --tb=short

test-api:
	@echo "Running API tests..."
	pytest tests/test_api/ -v --tb=short

build:
	@echo "Building distribution packages..."
	$(PYTHON) -m build

run:
	@echo "Starting development server..."
	uvicorn streamsql.main:app --reload --host 0.0.0.0 --port 8000

run-prod:
	@echo "Starting production server..."
	gunicorn streamsql.main:app \
		--workers 4 \
		--worker-class uvicorn.workers.UvicornWorker \
		--bind 0.0.0.0:8000 \
		--timeout 120

clean:
	@echo "Cleaning build artifacts..."
	rm -rf build/ dist/ *.egg-info/
	rm -rf .pytest_cache/ .mypy_cache/ .ruff_cache/
	rm -rf htmlcov/ coverage.xml
	find . -type d -name "__pycache__" -exec rm -rf {} +
	find . -type f -name "*.pyc" -delete

docker-build:
	@echo "Building Docker image: $(PROJECT_NAME):$(IMAGE_TAG)"
	docker build -t $(PROJECT_NAME):$(IMAGE_TAG) .
	docker tag $(PROJECT_NAME):$(IMAGE_TAG) $(PROJECT_NAME):latest

docker-run:
	@echo "Running Docker container..."
	docker run -d \
		--name $(PROJECT_NAME) \
		-p 8000:8000 \
		--env-file .env \
		$(PROJECT_NAME):latest

docker-push:
	@echo "Pushing Docker image to registry..."
	docker tag $(PROJECT_NAME):$(IMAGE_TAG) $(DOCKER_REGISTRY)/$(PROJECT_NAME):$(IMAGE_TAG)
	docker tag $(PROJECT_NAME):latest $(DOCKER_REGISTRY)/$(PROJECT_NAME):latest
	docker push $(DOCKER_REGISTRY)/$(PROJECT_NAME):$(IMAGE_TAG)
	docker push $(DOCKER_REGISTRY)/$(PROJECT_NAME):latest

pre-commit: format lint type-check test
	@echo "All pre-commit checks passed!"

ci: lint type-check test-cov
	@echo "CI pipeline completed successfully!"
