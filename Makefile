.PHONY: build run test clean deps lint

BINARY_NAME=gas-estimator
BUILD_DIR=bin

all: build

deps:
	go mod download
	go mod tidy

build: deps
	@mkdir -p $(BUILD_DIR)
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o $(BUILD_DIR)/$(BINARY_NAME)-linux-amd64 ./cmd/api
	CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 go build -ldflags="-s -w" -o $(BUILD_DIR)/$(BINARY_NAME)-darwin-arm64 ./cmd/api

build-local: deps
	@mkdir -p $(BUILD_DIR)
	go build -o $(BUILD_DIR)/$(BINARY_NAME) ./cmd/api

run: build-local
	./$(BUILD_DIR)/$(BINARY_NAME)

test:
	go test -v ./...

lint:
	golangci-lint run ./...

clean:
	rm -rf $(BUILD_DIR)
