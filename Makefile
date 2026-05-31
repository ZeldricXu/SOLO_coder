.PHONY: help install install-dev install-lint install-all test test-unit test-integration test-cov lint lint-all format type-check security security-bandit security-safety clean build run run-dev run-prod docker-build docker-up docker-down docker-logs migrate migrate-new migrate-up migrate-down db-shell docker-push deploy-staging deploy-prod scaffold scaffold-list scaffold-create docs docs-build docs-serve

# Colors for terminal output
GREEN  := $(shell tput -Txterm setaf 2)
YELLOW := $(shell tput -Txterm setaf 3)
WHITE  := $(shell tput -Txterm setaf 7)
RESET  := $(shell tput -Txterm sgr0)

TARGET_MAX_CHAR_NUM=25

help:
	@echo ''
	@echo 'Usage:'
	@echo '  ${YELLOW}make${RESET} ${GREEN}<target>${RESET}'
	@echo ''
	@echo 'Targets:'
	@awk '/^[a-zA-Z\-\_0-9]+:/ { \
		helpMessage = match(lastLine, /^## (.*)/); \
		if (helpMessage) { \
			helpCommand = substr($$1, 0, index($$1, ":")-1); \
			helpMessage = substr(lastLine, RSTART + 3, RLENGTH); \
			printf "  ${YELLOW}%-$(TARGET_MAX_CHAR_NUM)s${RESET} ${GREEN}%s${RESET}\n", helpCommand, helpMessage; \
		} \
	} \
	{ lastLine = $$0 }' $(MAKEFILE_LIST)
	@echo ''

## Install core dependencies
install:
	@echo "Installing core dependencies..."
	@pip install -r requirements.txt

## Install development dependencies
install-dev: install
	@echo "Installing development dependencies..."
	@pip install -e ".[dev]"

## Install linting and static analysis tools
install-lint: install-dev
	@echo "Installing linting tools..."
	@pip install -e ".[lint]"

## Install all dependencies including docs
install-all: install-lint
	@echo "Installing all dependencies..."
	@pip install -e ".[all]"

## Run all tests
test:
	@echo "Running all tests..."
	@pytest tests/ -v

## Run unit tests only
test-unit:
	@echo "Running unit tests..."
	@pytest tests/ -v -m "not integration"

## Run integration tests only
test-integration:
	@echo "Running integration tests..."
	@pytest tests/ -v -m integration

## Run tests with coverage report
test-cov:
	@echo "Running tests with coverage..."
	@pytest tests/ -v --cov=src --cov-report=term --cov-report=xml --cov-report=html

## Run ruff linter
lint:
	@echo "Running ruff linter..."
	@ruff check .

## Run all linting tools (ruff + mypy + bandit)
lint-all: lint type-check security

## Auto-format code with ruff
format:
	@echo "Auto-formatting code..."
	@ruff format .

## Run mypy type checking
type-check:
	@echo "Running mypy type checking..."
	@mypy src/ --ignore-missing-imports

## Run all security checks
security: security-bandit security-safety

## Run bandit security scan
security-bandit:
	@echo "Running bandit security scan..."
	@bandit -r src/ -f json -o bandit-report.json || true

## Run safety dependency check
security-safety:
	@echo "Running safety dependency check..."
	@safety check --full-report || true

## Clean build artifacts and cache
clean:
	@echo "Cleaning up..."
	@find . -type f -name "*.pyc" -delete
	@find . -type d -name "__pycache__" -delete
	@find . -type d -name "*.egg-info" -exec rm -rf {} + 2>/dev/null || true
	@rm -rf .pytest_cache .mypy_cache .ruff_cache
	@rm -rf build dist
	@rm -rf htmlcov .coverage coverage.xml
	@rm -rf bandit-report.json

## Build the package
build: clean
	@echo "Building package..."
	@python -m build

## Run the application
run:
	@echo "Starting application..."
	@python main.py

## Run with development settings
run-dev:
	@echo "Starting development server..."
	@APP_ENV=development python main.py

## Run with production settings
run-prod:
	@echo "Starting production server..."
	@APP_ENV=production python -m uvicorn main:app --host 0.0.0.0 --port 8000 --workers 4

## Build Docker image
docker-build:
	@echo "Building Docker image..."
	@docker build -t infra-platform:latest .

## Start services with Docker Compose (dev)
docker-up:
	@echo "Starting Docker services..."
	@docker-compose up -d

## Stop Docker services
docker-down:
	@echo "Stopping Docker services..."
	@docker-compose down

## View Docker logs
docker-logs:
	@echo "Viewing Docker logs..."
	@docker-compose logs -f

## Create new database migration
migrate-new:
	@echo "Creating new migration..."
	@read -p "Enter migration message: " msg; \
	alembic revision --autogenerate -m "$$msg"

## Apply all pending migrations
migrate-up:
	@echo "Applying migrations..."
	@alembic upgrade head

## Rollback last migration
migrate-down:
	@echo "Rolling back last migration..."
	@alembic downgrade -1

## Connect to database shell
db-shell:
	@echo "Connecting to database..."
	@psql $(DATABASE_URL)

## Push Docker image to registry
docker-push:
	@echo "Pushing Docker image..."
	@docker tag infra-platform:latest $(REGISTRY)/infra-platform:$(TAG)
	@docker push $(REGISTRY)/infra-platform:$(TAG)

## Deploy to staging environment
deploy-staging: build docker-push
	@echo "Deploying to staging..."
	@kubectl apply -f k8s/staging/
	@kubectl rollout restart deployment/infra-platform -n staging

## Deploy to production environment
deploy-prod: build docker-push
	@echo "Deploying to production..."
	@kubectl apply -f k8s/production/
	@kubectl rollout restart deployment/infra-platform -n production

## List available scaffold templates
scaffold-list:
	@echo "Available scaffold templates:"
	@python -m src.scaffold.cli list

## Create new project with scaffold
scaffold-create:
	@echo "Creating new project..."
	@python -m src.scaffold.cli create

## Build documentation
docs-build:
	@echo "Building documentation..."
	@mkdocs build

## Serve documentation locally
docs-serve:
	@echo "Serving documentation..."
	@mkdocs serve
