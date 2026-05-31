.PHONY: build run test clean deps

APP_NAME := session147
CMD_PATH := ./cmd/api

build:
	go build -o bin/$(APP_NAME) $(CMD_PATH)

run:
	go run $(CMD_PATH)

test:
	go test -v ./...

deps:
	go mod download
	go mod tidy

clean:
	rm -rf bin/

docker-build:
	docker build -t $(APP_NAME):latest .

lint:
	golangci-lint run ./...
