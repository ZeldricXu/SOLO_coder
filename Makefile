.PHONY: build run test tidy export-dashboard export-rules up down

build:
	go build -o bin/log-pipeline ./cmd

run:
	go run ./cmd

tidy:
	go mod tidy

test:
	go test -v ./...

export-dashboard:
	go run ./cmd -mode export-dashboard

export-rules:
	go run ./cmd -mode export-rules

up:
	cd deploy && docker-compose up -d

down:
	cd deploy && docker-compose down

deps-up:
	cd deploy && docker-compose up -d clickhouse redis

clean:
	cd deploy && docker-compose down -v
	rm -rf bin/
