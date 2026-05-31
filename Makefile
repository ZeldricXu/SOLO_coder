SHELL := /bin/bash

PROJECT_NAME := nft-indexer
VERSION := $(shell grep -m1 '<version>' pom.xml | sed -E 's/.*<version>(.*)<\/version>.*/\1/')
DOCKER_REGISTRY := ghcr.io/nftindexer
DOCKER_IMAGE := $(DOCKER_REGISTRY)/$(PROJECT_NAME)
COMMIT_SHA := $(shell git rev-parse --short HEAD 2>/dev/null || echo "dev")
BUILD_DATE := $(shell date -u +"%Y-%m-%dT%H:%M:%SZ")

MVNW := ./mvnw
DOCKER := docker
DOCKER_COMPOSE := docker compose
HELM := helm
KUBECTL := kubectl

.DEFAULT_GOAL := help

.PHONY: help
help:
	@echo "NFTIndexer - Cross-Chain NFT Metadata Indexing and Query Service"
	@echo "================================================================="
	@echo ""
	@echo "Available targets:"
	@echo ""
	@echo "Build targets:"
	@echo "  make clean              - Clean build artifacts"
	@echo "  make compile            - Compile source code"
	@echo "  make package            - Package application (skip tests)"
	@echo "  make build              - Full build with tests and quality checks"
	@echo "  make build-fast         - Build without tests and quality checks"
	@echo "  make build-native       - Build native image (GraalVM)"
	@echo ""
	@echo "Quality targets:"
	@echo "  make checkstyle         - Run Checkstyle analysis"
	@echo "  make pmd                - Run PMD static analysis"
	@echo "  make spotbugs           - Run SpotBugs bug detection"
	@echo "  make test               - Run unit tests"
	@echo "  make test-integration   - Run integration tests"
	@echo "  make coverage           - Run tests with JaCoCo coverage report"
	@echo "  make quality            - Run all quality checks (checkstyle, pmd, spotbugs, test, coverage)"
	@echo "  make sonar              - Run SonarQube analysis"
	@echo ""
	@echo "Docker targets:"
	@echo "  make docker-build       - Build Docker image"
	@echo "  make docker-push        - Push Docker image to registry"
	@echo "  make docker-run         - Run Docker container locally"
	@echo "  make docker-scan        - Scan Docker image for vulnerabilities"
	@echo "  make docker-clean       - Remove Docker images and containers"
	@echo ""
	@echo "Docker Compose targets:"
	@echo "  make compose-up         - Start all services with Docker Compose"
	@echo "  make compose-down       - Stop all services"
	@echo "  make compose-logs       - View service logs"
	@echo "  make compose-clean      - Stop and remove all containers, networks, volumes"
	@echo "  make compose-dev        - Start development environment"
	@echo "  make compose-test       - Start test environment and run tests"
	@echo ""
	@echo "Kubernetes / Helm targets:"
	@echo "  make helm-dep-up        - Update Helm dependencies"
	@echo "  make helm-template      - Generate Kubernetes manifests"
	@echo "  make helm-lint          - Lint Helm chart"
	@echo "  make helm-install       - Install/Upgrade release to Kubernetes"
	@echo "  make helm-install-staging - Install to staging environment"
	@echo "  make helm-install-prod  - Install to production environment"
	@echo "  make helm-uninstall     - Uninstall release"
	@echo "  make helm-rollback      - Rollback release"
	@echo "  make kube-deploy        - Deploy to Kubernetes"
	@echo ""
	@echo "Development targets:"
	@echo "  make run                - Run application locally"
	@echo "  make run-dev            - Run with dev profile and hot reload"
	@echo "  make run-debug          - Run with debug mode (port 5005)"
	@echo "  make format             - Format code using Spotless"
	@echo "  make generate-docs      - Generate API documentation"
	@echo ""
	@echo "CI/CD targets:"
	@echo "  make ci-build           - CI build pipeline"
	@echo "  make ci-quality         - CI quality checks"
	@echo "  make ci-security        - CI security scan"
	@echo "  make ci-release         - CI release pipeline"
	@echo ""
	@echo "Utility targets:"
	@echo "  make version            - Show project version"
	@echo "  make info               - Show project information"
	@echo "  make tree               - Show dependency tree"
	@echo "  make outdated           - Check for outdated dependencies"
	@echo "  make upgrade            - Upgrade dependencies"
	@echo ""
	@echo "Environment variables:"
	@echo "  VERSION=$(VERSION)"
	@echo "  COMMIT_SHA=$(COMMIT_SHA)"
	@echo "  DOCKER_IMAGE=$(DOCKER_IMAGE):$(VERSION)"

.PHONY: version
version:
	@echo "$(VERSION)"

.PHONY: info
info:
	@echo "Project:    $(PROJECT_NAME)"
	@echo "Version:    $(VERSION)"
	@echo "Commit:     $(COMMIT_SHA)"
	@echo "Build Date: $(BUILD_DATE)"
	@echo "Java:       $(shell java -version 2>&1 | head -n1)"
	@echo "Maven:      $(shell ./mvnw -version | head -n1)"
	@echo "Docker:     $(shell docker --version)"
	@echo "Helm:       $(shell helm version --short 2>/dev/null || echo "not installed")"

.PHONY: clean
clean:
	@echo "==> Cleaning build artifacts..."
	$(MVNW) clean

.PHONY: compile
compile:
	@echo "==> Compiling source code..."
	$(MVNW) compile -DskipTests -B

.PHONY: package
package:
	@echo "==> Packaging application..."
	$(MVNW) package -DskipTests -B

.PHONY: build
build:
	@echo "==> Building application with tests and quality checks..."
	$(MVNW) clean verify -B

.PHONY: build-fast
build-fast:
	@echo "==> Building application (fast mode)..."
	$(MVNW) clean package -DskipTests -B -Pskip-quality

.PHONY: build-native
build-native:
	@echo "==> Building native image..."
	$(MVNW) clean native:compile -Pnative -DskipTests -B

.PHONY: checkstyle
checkstyle:
	@echo "==> Running Checkstyle analysis..."
	$(MVNW) checkstyle:checkstyle -B

.PHONY: pmd
pmd:
	@echo "==> Running PMD static analysis..."
	$(MVNW) pmd:pmd pmd:cpd-check -B

.PHONY: spotbugs
spotbugs:
	@echo "==> Running SpotBugs analysis..."
	$(MVNW) spotbugs:spotbugs spotbugs:check -B

.PHONY: test
test:
	@echo "==> Running unit tests..."
	$(MVNW) test -B

.PHONY: test-integration
test-integration:
	@echo "==> Running integration tests..."
	$(MVNW) verify -DskipUnitTests -B -Pintegration-test

.PHONY: coverage
coverage:
	@echo "==> Running tests with coverage..."
	$(MVNW) clean test jacoco:report jacoco:check -B

.PHONY: quality
quality: checkstyle pmd spotbugs coverage
	@echo "==> All quality checks completed successfully!"

.PHONY: sonar
sonar:
	@echo "==> Running SonarQube analysis..."
	$(MVNW) sonar:sonar \
		-Dsonar.projectKey=$(PROJECT_NAME) \
		-Dsonar.organization=nftindexer \
		-Dsonar.host.url=$(SONAR_HOST_URL:-http://localhost:9000) \
		-Dsonar.login=$(SONAR_TOKEN) \
		-B

.PHONY: docker-build
docker-build: build-fast
	@echo "==> Building Docker image: $(DOCKER_IMAGE):$(VERSION)..."
	$(DOCKER) build \
		--build-arg VERSION=$(VERSION) \
		--build-arg COMMIT_SHA=$(COMMIT_SHA) \
		--build-arg BUILD_DATE=$(BUILD_DATE) \
		-t $(DOCKER_IMAGE):$(VERSION) \
		-t $(DOCKER_IMAGE):$(COMMIT_SHA) \
		-t $(DOCKER_IMAGE):latest \
		.

.PHONY: docker-push
docker-push: docker-build
	@echo "==> Pushing Docker image..."
	$(DOCKER) push $(DOCKER_IMAGE):$(VERSION)
	$(DOCKER) push $(DOCKER_IMAGE):$(COMMIT_SHA)
	$(DOCKER) push $(DOCKER_IMAGE):latest

.PHONY: docker-run
docker-run:
	@echo "==> Running Docker container..."
	$(DOCKER) run -d \
		--name $(PROJECT_NAME) \
		-p 8080:8080 \
		-e SPRING_PROFILES_ACTIVE=docker \
		-e SPRING_R2DBC_URL=r2dbc:mysql://mysql:3306/nft_indexer \
		-e SPRING_DATA_REDIS_HOST=redis \
		--network nftindexer_default \
		$(DOCKER_IMAGE):$(VERSION)

.PHONY: docker-scan
docker-scan:
	@echo "==> Scanning Docker image for vulnerabilities..."
	$(DOCKER) scout cves $(DOCKER_IMAGE):$(VERSION) || \
	$(DOCKER) scan $(DOCKER_IMAGE):$(VERSION) || \
	(trivy image $(DOCKER_IMAGE):$(VERSION) || echo "No scanner available")

.PHONY: docker-clean
docker-clean:
	@echo "==> Cleaning Docker resources..."
	-$(DOCKER) rm -f $(PROJECT_NAME) 2>/dev/null || true
	-$(DOCKER) rmi -f $(DOCKER_IMAGE):$(VERSION) 2>/dev/null || true
	-$(DOCKER) rmi -f $(DOCKER_IMAGE):$(COMMIT_SHA) 2>/dev/null || true
	-$(DOCKER) rmi -f $(DOCKER_IMAGE):latest 2>/dev/null || true

.PHONY: compose-up
compose-up:
	@echo "==> Starting all services with Docker Compose..."
	$(DOCKER_COMPOSE) up -d --build
	@echo "==> Waiting for services to be ready..."
	@sleep 30
	@$(DOCKER_COMPOSE) ps

.PHONY: compose-down
compose-down:
	@echo "==> Stopping all services..."
	$(DOCKER_COMPOSE) down

.PHONY: compose-logs
compose-logs:
	$(DOCKER_COMPOSE) logs -f

.PHONY: compose-clean
compose-clean:
	@echo "==> Removing all Docker Compose resources..."
	$(DOCKER_COMPOSE) down -v --remove-orphans

.PHONY: compose-dev
compose-dev:
	@echo "==> Starting development environment..."
	$(DOCKER_COMPOSE) up -d mysql redis
	@echo "==> Waiting for databases to be ready..."
	@sleep 15
	@echo "==> Databases ready! Starting application..."
	make run-dev

.PHONY: compose-test
compose-test:
	@echo "==> Starting test environment..."
	$(DOCKER_COMPOSE) -f docker-compose-test.yml up -d
	@sleep 20
	@echo "==> Running tests..."
	make test-integration
	@echo "==> Cleaning up test environment..."
	$(DOCKER_COMPOSE) -f docker-compose-test.yml down -v

.PHONY: helm-dep-up
helm-dep-up:
	@echo "==> Updating Helm dependencies..."
	cd helm/nft-indexer && $(HELM) dependency update

.PHONY: helm-template
helm-template: helm-dep-up
	@echo "==> Templating Helm chart..."
	$(HELM) template $(PROJECT_NAME) ./helm/nft-indexer \
		--namespace nftindexer \
		--values ./helm/nft-indexer/values.yaml

.PHONY: helm-lint
helm-lint: helm-dep-up
	@echo "==> Linting Helm chart..."
	$(HELM) lint ./helm/nft-indexer \
		--values ./helm/nft-indexer/values.yaml \
		--strict

.PHONY: helm-install
helm-install: helm-dep-up helm-lint
	@echo "==> Installing/Upgrading Helm release..."
	$(HELM) upgrade --install $(PROJECT_NAME) ./helm/nft-indexer \
		--namespace nftindexer \
		--create-namespace \
		--values ./helm/nft-indexer/values.yaml \
		--set image.tag=$(VERSION) \
		--wait \
		--timeout 10m
	@echo "==> Release installed successfully!"
	$(HELM) status $(PROJECT_NAME) --namespace nftindexer

.PHONY: helm-install-staging
helm-install-staging: helm-dep-up helm-lint
	@echo "==> Installing to staging environment..."
	$(HELM) upgrade --install $(PROJECT_NAME) ./helm/nft-indexer \
		--namespace nftindexer-staging \
		--create-namespace \
		--values ./helm/nft-indexer/values.yaml \
		--values ./helm/nft-indexer/values-staging.yaml \
		--set image.tag=staging \
		--wait \
		--timeout 10m
	@echo "==> Staging release installed successfully!"
	$(HELM) status $(PROJECT_NAME) --namespace nftindexer-staging

.PHONY: helm-install-prod
helm-install-prod: helm-dep-up helm-lint
	@echo "==> Installing to production environment..."
	$(HELM) upgrade --install $(PROJECT_NAME) ./helm/nft-indexer \
		--namespace nftindexer-production \
		--create-namespace \
		--values ./helm/nft-indexer/values.yaml \
		--values ./helm/nft-indexer/values-production.yaml \
		--set image.tag=$(VERSION) \
		--wait \
		--timeout 15m \
		--atomic
	@echo "==> Production release installed successfully!"
	$(HELM) status $(PROJECT_NAME) --namespace nftindexer-production

.PHONY: helm-uninstall
helm-uninstall:
	@echo "==> Uninstalling Helm release..."
	-$(HELM) uninstall $(PROJECT_NAME) --namespace nftindexer --wait 2>/dev/null || true

.PHONY: helm-rollback
helm-rollback:
	@echo "==> Rolling back Helm release..."
	$(HELM) rollback $(PROJECT_NAME) --namespace nftindexer --wait

.PHONY: kube-deploy
kube-deploy: docker-push helm-install
	@echo "==> Deployment completed successfully!"

.PHONY: run
run:
	@echo "==> Running application locally..."
	$(MVNW) spring-boot:run -B \
		-Dspring-boot.run.profiles=local \
		-Dspring-boot.run.jvmArguments="-Xms512m -Xmx1024m"

.PHONY: run-dev
run-dev:
	@echo "==> Running application with dev profile..."
	$(MVNW) spring-boot:run -B \
		-Dspring-boot.run.profiles=dev \
		-Dspring-boot.run.jvmArguments="-Xms512m -Xmx1024m -Dspring.devtools.restart.enabled=true -Dspring.devtools.livereload.enabled=true"

.PHONY: run-debug
run-debug:
	@echo "==> Running application in debug mode (port 5005)..."
	$(MVNW) spring-boot:run -B \
		-Dspring-boot.run.profiles=dev \
		-Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005 -Xms512m -Xmx1024m"

.PHONY: format
format:
	@echo "==> Formatting code..."
	$(MVNW) spotless:apply -B

.PHONY: generate-docs
generate-docs:
	@echo "==> Generating API documentation..."
	$(MVNW) springdoc:openapi -B

.PHONY: tree
tree:
	@echo "==> Dependency tree..."
	$(MVNW) dependency:tree -B

.PHONY: outdated
outdated:
	@echo "==> Checking for outdated dependencies..."
	$(MVNW) versions:display-dependency-updates -B
	@echo "==> Checking for outdated plugins..."
	$(MVNW) versions:display-plugin-updates -B

.PHONY: upgrade
upgrade:
	@echo "==> Upgrading dependencies..."
	$(MVNW) versions:use-latest-versions -B
	$(MVNW) versions:update-properties -B

.PHONY: ci-build
ci-build:
	@echo "==> Running CI build pipeline..."
	$(MVNW) clean package -DskipTests -B -Pci

.PHONY: ci-quality
ci-quality:
	@echo "==> Running CI quality checks..."
	$(MVNW) enforcer:enforce checkstyle:check pmd:check spotbugs:check -B -Pci
	$(MVNW) test jacoco:check -B -Pci

.PHONY: ci-security
ci-security: docker-build
	@echo "==> Running CI security scan..."
	trivy fs --severity CRITICAL,HIGH . || true
	trivy image --severity CRITICAL,HIGH $(DOCKER_IMAGE):$(VERSION) || true

.PHONY: ci-release
ci-release: ci-build ci-quality docker-push
	@echo "==> CI release pipeline completed!"
	@echo "Version: $(VERSION)"
	@echo "Image: $(DOCKER_IMAGE):$(VERSION)"

.PHONY: flyway-migrate
flyway-migrate:
	@echo "==> Running Flyway migrations..."
	$(MVNW) flyway:migrate -B

.PHONY: flyway-clean
flyway-clean:
	@echo "==> Cleaning Flyway migrations..."
	$(MVNW) flyway:clean -B

.PHONY: flyway-info
flyway-info:
	@echo "==> Flyway migration info..."
	$(MVNW) flyway:info -B
