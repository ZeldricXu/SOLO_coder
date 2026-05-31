.PHONY: build test tidy clean run

build:
	go build -o bin/chaoslab ./cmd/chaoslab

test:
	go test -v ./...

tidy:
	go mod tidy

clean:
	rm -rf bin/

run: build
	./bin/chaoslab
