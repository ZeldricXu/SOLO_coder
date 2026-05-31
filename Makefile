.PHONY: help build build-prod test clean docker-build docker-up docker-down lint checkstyle pmd spotbugs quality deploy-staging deploy-prod

.DEFAULT_GOAL := help

help:
	@echo "Available targets:"
	@echo "  make build          - Build the project (dev profile)"
	@echo "  make build-prod     - Build the project (prod profile)"
	@echo "  make test           - Run all tests"
	@echo "  make clean          - Clean build artifacts"
	@echo "  make lint           - Run all static code analysis"
	@echo "  make checkstyle     - Run Checkstyle analysis"
	@echo "  make pmd            - Run PMD analysis"
	@echo "  make spotbugs       - Run SpotBugs analysis"
	@echo "  make quality        - Run full quality gate"
	@echo "  make docker-build   - Build Docker image"
	@echo "  make docker-up      - Start services with Docker Compose"
	@echo "  make docker-down    - Stop Docker Compose services"
	@echo "  make run            - Run the application locally"

build:
	@echo "Building project (dev profile)..."
	mvn clean compile -Pdev

build-prod:
	@echo "Building project (prod profile)..."
	mvn clean package -Pprod -DskipTests

test:
	@echo "Running tests..."
	mvn test -Ptest

clean:
	@echo "Cleaning build artifacts..."
	mvn clean

lint: checkstyle pmd spotbugs

checkstyle:
	@echo "Running Checkstyle analysis..."
	mvn checkstyle:check -Ptest

pmd:
	@echo "Running PMD analysis..."
	mvn pmd:pmd pmd:cpd-check -Ptest

spotbugs:
	@echo "Running SpotBugs analysis..."
	mvn spotbugs:spotbugs -Ptest

quality:
	@echo "Running full quality gate..."
	mvn verify -Ptest

docker-build:
	@echo "Building Docker image..."
	docker build -t orchestration-platform:latest .

docker-up:
	@echo "Starting services with Docker Compose..."
	docker-compose up -d

docker-down:
	@echo "Stopping Docker Compose services..."
	docker-compose down

run:
	@echo "Running application locally..."
	mvn spring-boot:run -Pdev -pl web -am

deploy-staging: build-prod
	@echo "Deploying to staging..."
	cd k8s/overlays/staging && kubectl apply -k .

deploy-prod: build-prod
	@echo "Deploying to production..."
	cd k8s/overlays/production && kubectl apply -k .
