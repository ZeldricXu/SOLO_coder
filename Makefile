.PHONY: help install install-dev format lint type-check test test-unit test-integration run run-worker run-beat db-migrate db-upgrade db-init clean docker-build docker-up

PROJECT_NAME := gas-estimator
PYTHON := python3.11
POETRY := poetry

help:
	@echo "Available targets:"
	@echo "  install          - Install production dependencies"
	@echo "  install-dev      - Install development dependencies"
	@echo "  format           - Format code with ruff"
	@echo "  lint             - Lint code with ruff"
	@echo "  type-check       - Run type checking with mypy"
	@echo "  test             - Run all tests"
	@echo "  test-unit        - Run unit tests"
	@echo "  test-integration - Run integration tests"
	@echo "  run              - Run the FastAPI server"
	@echo "  run-worker       - Run Celery worker"
	@echo "  run-beat         - Run Celery beat"
	@echo "  db-migrate       - Create new migration"
	@echo "  db-upgrade       - Apply migrations"
	@echo "  db-init          - Initialize database"
	@echo "  clean            - Clean temporary files"
	@echo "  docker-build     - Build Docker image"
	@echo "  docker-up        - Start services with Docker Compose"

install:
	$(POETRY) install --no-dev

install-dev:
	$(POETRY) install

format:
	$(POETRY) run ruff format .
	$(POETRY) run ruff check --fix .

lint:
	$(POETRY) run ruff check .

type-check:
	$(POETRY) run mypy .

test:
	$(POETRY) run pytest

test-unit:
	$(POETRY) run pytest tests/unit/

test-integration:
	$(POETRY) run pytest tests/integration/

run:
	$(POETRY) run uvicorn main:app --host 0.0.0.0 --port 8000 --reload

run-worker:
	$(POETRY) run celery -A app.core.celery worker --loglevel=info --concurrency=4

run-beat:
	$(POETRY) run celery -A app.core.celery beat --loglevel=info

db-migrate:
	@read -p "Enter migration message: " msg; \
	$(POETRY) run alembic revision --autogenerate -m "$$msg"

db-upgrade:
	$(POETRY) run alembic upgrade head

db-init:
	$(POETRY) run python scripts/init_db.py

clean:
	find . -type f -name "*.pyc" -delete
	find . -type d -name "__pycache__" -delete
	rm -rf .coverage .pytest_cache .mypy_cache .ruff_cache htmlcov
	rm -rf dist build *.egg-info

docker-build:
	docker build -t $(PROJECT_NAME):latest .

docker-up:
	docker-compose up -d
