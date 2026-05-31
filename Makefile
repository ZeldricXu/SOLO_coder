SHELL := /bin/bash
.DEFAULT_GOAL := help

APP_NAME := contract-audit-platform
APP_VERSION := 1.0.0
DOCKER_IMAGE := contraudit/contract-audit-platform
DOCKER_TAG := $(APP_VERSION)

.PHONY: help
help: ## Display this help message
	@awk 'BEGIN {FS = ":.*##"; printf "\nUsage:\n  make \033[36m<target>\033[0m\n"} /^[a-zA-Z_-]+:.*?##/ { printf "  \033[36m%-25s\033[0m %s\n", $$1, $$2 } /^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) } ' $(MAKEFILE_LIST)

##@ Development

.PHONY: clean
clean: ## Clean build artifacts
	@mvn clean

.PHONY: compile
compile: ## Compile the project
	@mvn compile -q

.PHONY: test
test: ## Run unit tests
	@mvn test -Pdev

.PHONY: test-coverage
test-coverage: ## Run tests with coverage report
	@mvn test jacoco:report -Pdev

.PHONY: checkstyle
checkstyle: ## Run Checkstyle analysis
	@mvn checkstyle:check -Pdev

.PHONY: pmd
pmd: ## Run PMD static analysis
	@mvn pmd:pmd -Ptest

.PHONY: spotbugs
spotbugs: ## Run SpotBugs analysis
	@mvn spotbugs:check -Ptest

.PHONY: code-quality
code-quality: checkstyle pmd spotbugs ## Run all code quality checks
	@echo "All code quality checks passed!"

.PHONY: verify
verify: ## Run full verification (test + code quality)
	@mvn verify -Ptest

.PHONY: dev
dev: ## Start development server with hot reload
	@mvn spring-boot:run -Pdev -Dspring-boot.run.profiles=dev

.PHONY: dev-debug
dev-debug: ## Start development server in debug mode
	@mvn spring-boot:run -Pdev -Dspring-boot.run.profiles=dev -Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005"

##@ Build

.PHONY: build
build: clean ## Build application JAR
	@mvn package -Pprod -DskipTests

.PHONY: build-dev
build-dev: clean ## Build development JAR
	@mvn package -Pdev -DskipTests

.PHONY: build-test
build-test: clean ## Build test JAR
	@mvn package -Ptest -DskipTests

.PHONY: build-native
build-native: ## Build native image (GraalVM)
	@mvn native:compile -Pnative -DskipTests

##@ Docker

.PHONY: docker-build
docker-build: ## Build Docker image
	@docker build -t $(DOCKER_IMAGE):$(DOCKER_TAG) .
	@docker tag $(DOCKER_IMAGE):$(DOCKER_TAG) $(DOCKER_IMAGE):latest
	@echo "Built image: $(DOCKER_IMAGE):$(DOCKER_TAG)"

.PHONY: docker-push
docker-push: ## Push Docker image to registry
	@docker push $(DOCKER_IMAGE):$(DOCKER_TAG)
	@docker push $(DOCKER_IMAGE):latest

.PHONY: docker-run
docker-run: ## Run application in Docker container
	@docker run -d --name $(APP_NAME) \
		-p 8080:8080 \
		-e SPRING_PROFILES_ACTIVE=prod \
		$(DOCKER_IMAGE):$(DOCKER_TAG)

.PHONY: docker-logs
docker-logs: ## View Docker container logs
	@docker logs -f $(APP_NAME)

.PHONY: docker-stop
docker-stop: ## Stop and remove Docker container
	@docker stop $(APP_NAME) 2>/dev/null || true
	@docker rm $(APP_NAME) 2>/dev/null || true

.PHONY: docker-clean
docker-clean: ## Clean Docker images and containers
	@docker stop $(APP_NAME) 2>/dev/null || true
	@docker rm $(APP_NAME) 2>/dev/null || true
	@docker rmi $(DOCKER_IMAGE):$(DOCKER_TAG) 2>/dev/null || true
	@docker rmi $(DOCKER_IMAGE):latest 2>/dev/null || true

.PHONY: jib-build
jib-build: ## Build and push Docker image using Jib
	@mvn compile jib:build -Pdocker

.PHONY: jib-daemon
jib-daemon: ## Build Docker image to local daemon using Jib
	@mvn compile jib:dockerBuild -Pdocker

##@ Deployment

.PHONY: up
up: ## Start all services with Docker Compose
	@docker compose up -d

.PHONY: down
down: ## Stop all services
	@docker compose down

.PHONY: restart
restart: ## Restart all services
	@docker compose restart

.PHONY: logs
logs: ## View service logs
	@docker compose logs -f

.PHONY: ps
ps: ## List running services
	@docker compose ps

.PHONY: deploy-dev
deploy-dev: ## Deploy to development environment
	@echo "Deploying to development environment..."
	@scripts/deploy.sh dev

.PHONY: deploy-test
deploy-test: ## Deploy to testing environment
	@echo "Deploying to testing environment..."
	@scripts/deploy.sh test

.PHONY: deploy-prod
deploy-prod: ## Deploy to production environment
	@echo "Deploying to production environment..."
	@scripts/deploy.sh prod

##@ Database

.PHONY: db-migrate
db-migrate: ## Run database migrations
	@mvn flyway:migrate -Pdev

.PHONY: db-info
db-info: ## Show migration info
	@mvn flyway:info -Pdev

.PHONY: db-repair
db-repair: ## Repair migration schema history table
	@mvn flyway:repair -Pdev

##@ Monitoring

.PHONY: prometheus
prometheus: ## Start Prometheus
	@docker run -d --name prometheus \
		-p 9090:9090 \
		-v $(PWD)/monitoring/prometheus.yml:/etc/prometheus/prometheus.yml \
		prom/prometheus:v2.51.1

.PHONY: grafana
grafana: ## Start Grafana
	@docker run -d --name grafana \
		-p 3000:3000 \
		-e GF_SECURITY_ADMIN_PASSWORD=admin \
		grafana/grafana:10.4.2

##@ Utility

.PHONY: version
version: ## Show project version
	@echo "$(APP_NAME) v$(APP_VERSION)"

.PHONY: tree
tree: ## Show project structure tree
	@find . -type f -name "*.java" | head -50 | sort

.PHONY: loc
loc: ## Count lines of code
	@find . -name "*.java" -type f | xargs wc -l | tail -1

.PHONY: update-deps
update-deps: ## Update Maven dependencies
	@mvn versions:display-dependency-updates

.PHONY: outdated
outdated: ## Show outdated dependencies
	@mvn dependency:tree -Dverbose | grep -i version

.PHONY: format
format: ## Format code using Google Java Format (if available)
	@find src/main/java -name "*.java" -exec java -jar google-java-format-1.17.0.jar --replace {} \; 2>/dev/null || echo "Google Java Format not available"

.PHONY: pre-commit
pre-commit: compile test checkstyle ## Run pre-commit checks
	@echo "Pre-commit checks completed successfully!"
