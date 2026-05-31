APP_NAME := iot-platform
APP_VERSION := 1.0.0
DOCKER_IMAGE := $(APP_NAME)/$(APP_NAME):$(APP_VERSION)

.PHONY: help build test clean package docker-build docker-up docker-down dev run lint quality coverage sonar release

help:
	@echo "Available targets:"
	@echo "  make build          - Compile the project"
	@echo "  make test           - Run all tests"
	@echo "  make clean          - Clean build artifacts"
	@echo "  make package        - Create executable JAR"
	@echo "  make docker-build   - Build Docker image"
	@echo "  make docker-up      - Start all services with Docker Compose"
	@echo "  make docker-down    - Stop all services"
	@echo "  make dev            - Start development environment"
	@echo "  make run            - Run application locally"
	@echo "  make lint           - Run code style checks"
	@echo "  make quality        - Run full static code analysis"
	@echo "  make coverage       - Run tests with coverage report"
	@echo "  make sonar          - Run SonarQube analysis"
	@echo "  make release        - Create release version"

build:
	mvn compile -Pdev -B

test:
	mvn test -Pdev -B

clean:
	mvn clean

package:
	mvn clean package -DskipTests -Pprod -B

docker-build: package
	docker build -t $(DOCKER_IMAGE) .

docker-up:
	docker-compose up -d

docker-down:
	docker-compose down

dev:
	docker-compose -f docker-compose.yml -f docker-compose.override.yml up -d mysql redis minio
	@echo "Waiting for services to be ready..."
	@sleep 10
	mvn spring-boot:run -Dspring-boot.run.profiles=dev

run:
	mvn spring-boot:run -Dspring-boot.run.profiles=dev

lint:
	mvn checkstyle:check pmd:check -Pdev -B

quality:
	mvn checkstyle:check pmd:check spotbugs:check -Pci -B

coverage:
	mvn clean verify -Pci -B
	@echo "Coverage report available at: target/site/jacoco/index.html"

sonar: coverage
	mvn sonar:sonar -Pci -B

release:
	@echo "Usage: make release VERSION=x.y.z"
	@echo "Example: make release VERSION=1.0.0"
	@if [ -z "$(VERSION)" ]; then echo "VERSION is required"; exit 1; fi
	mvn release:prepare release:perform -B -DreleaseVersion=$(VERSION) -DdevelopmentVersion=$(shell echo $(VERSION) | awk -F. '{$$3=sprintf("%d-SNAPSHOT", $$3+1); print $$1"."$$2"."$$3}')
