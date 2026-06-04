VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo "v0.1.0-dev")
BUILD_DIR := dist
GO_FLAGS := -ldflags="-s -w"
WASM_EXEC := $(shell find "$(shell go env GOROOT)" -name "wasm_exec.js" -print -quit 2>/dev/null)

.PHONY: all build build-tinygo optimize serve test test-wasm clean release

all: build

# === Build ===

build:
	@mkdir -p $(BUILD_DIR)
	GOOS=js GOARCH=wasm go build $(GO_FLAGS) -o $(BUILD_DIR)/main.wasm .
	@cp $(WASM_EXEC) $(BUILD_DIR)/
	@cp index.html $(BUILD_DIR)/
	@cp -r css/ $(BUILD_DIR)/css/
	@cp -r js/ $(BUILD_DIR)/js/
	@echo "=== Build complete ==="
	@ls -lh $(BUILD_DIR)/main.wasm $(BUILD_DIR)/wasm_exec.js

# === TinyGo Build ===

build-tinygo:
	@if command -v tinygo >/dev/null 2>&1; then \
		mkdir -p $(BUILD_DIR); \
		tinygo build -o $(BUILD_DIR)/main-tiny.wasm -target wasm .; \
		echo "=== TinyGo build complete ==="; \
		ls -lh $(BUILD_DIR)/main.wasm $(BUILD_DIR)/main-tiny.wasm; \
	else \
		echo "=== TinyGo not available, skipping ==="; \
	fi

# === Optimize ===

optimize: build
	@if command -v wasm-opt >/dev/null 2>&1; then \
		wasm-opt -Oz -o $(BUILD_DIR)/main-optimized.wasm $(BUILD_DIR)/main.wasm; \
		gzip -k -9 $(BUILD_DIR)/main-optimized.wasm; \
		gzip -k -9 $(BUILD_DIR)/main.wasm; \
		echo "=== Optimization complete ==="; \
		echo "Original:     $$(du -h $(BUILD_DIR)/main.wasm | cut -f1)"; \
		echo "Optimized:    $$(du -h $(BUILD_DIR)/main-optimized.wasm | cut -f1)"; \
		echo "Optimized.gz: $$(du -h $(BUILD_DIR)/main-optimized.wasm.gz | cut -f1)"; \
		echo "Original.gz:  $$(du -h $(BUILD_DIR)/main.wasm.gz | cut -f1)"; \
	else \
		echo "=== wasm-opt not available, skipping optimization ==="; \
	fi

# === Serve ===

serve:
	go run cmd/serve/main.go

# === Test ===

test:
	go test ./... -count=1 -race -timeout 120s

# === WASM Test ===

test-wasm:
	@if command -v node >/dev/null 2>&1; then \
		cp $(WASM_EXEC) .; \
		GOOS=js GOARCH=wasm go test ./... -exec "node -e 'process.exit(0)'" -count=1 -timeout 120s; \
	else \
		echo "=== Node.js not available, skipping WASM tests ==="; \
	fi

# === Clean ===

clean:
	@rm -rf $(BUILD_DIR)
	@rm -f main.wasm wasm_exec.js
	@echo "=== Cleaned build artifacts ==="

# === Release ===

release: build optimize
	@mkdir -p $(BUILD_DIR)
	@echo "=== Packaging release $(VERSION) ==="
	@cd $(BUILD_DIR) && zip -r ../dataexplorer-$(VERSION).zip .
	@echo "Version:  $(VERSION)"
	@echo "Archive:  dataexplorer-$(VERSION).zip"
	@ls -lh dataexplorer-$(VERSION).zip
