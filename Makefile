.PHONY: install install-dev test lint format docker-build docker-push run-dev up down clean help

PROJECT_NAME := recommendation-engine
IMAGE_NAME := ghcr.io/$(USER)/$(PROJECT_NAME)
IMAGE_TAG ?= latest

help:
	@echo "Available targets:"
	@echo "  install      - Install package in editable mode"
	@echo "  install-dev  - Install with dev dependencies"
	@echo "  test         - Run pytest with coverage"
	@echo "  lint         - Run ruff lint and mypy"
	@echo "  format       - Run ruff format"
	@echo "  docker-build - Build Docker image"
	@echo "  docker-push  - Push Docker image to GHCR"
	@echo "  run-dev      - Run development server"
	@echo "  up           - Start all services with docker-compose"
	@echo "  down         - Stop all services"
	@echo "  clean        - Clean up temporary files"

install:
	pip install -e .

install-dev:
	pip install -e ".[dev]"

test:
	python -m pytest tests/ -v --tb=short --cov=recommendation_engine --cov-report=term-missing --cov-report=html

test-unit:
	python -m pytest tests/unit/ -v --tb=short

test-concurrent:
	python -m pytest tests/concurrent/ -v --tb=short

test-integration:
	python -m pytest tests/integration/ -v --tb=short

lint:
	ruff check recommendation_engine/ config/ tests/
	mypy recommendation_engine/ config/ tests/ --strict --ignore-missing-imports

format:
	ruff format recommendation_engine/ config/ tests/
	ruff check --fix recommendation_engine/ config/ tests/

docker-build:
	docker build -t $(IMAGE_NAME):$(IMAGE_TAG) .

docker-push:
	docker push $(IMAGE_NAME):$(IMAGE_TAG)

run-dev:
	uvicorn recommendation_engine.main:app --reload --host 0.0.0.0 --port 8000

up:
	docker-compose up -d

up-build:
	docker-compose up -d --build

down:
	docker-compose down

down-v:
	docker-compose down -v

logs:
	docker-compose logs -f

clean:
	rm -rf .pytest_cache .mypy_cache .ruff_cache htmlcov
	rm -rf build dist *.egg-info
	find . -type d -name __pycache__ -exec rm -rf {} +
	find . -type f -name "*.pyc" -delete
