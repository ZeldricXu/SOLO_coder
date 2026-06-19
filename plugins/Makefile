.PHONY: all clean list

GO ?= go
GOFLAGS ?= -trimpath
LDFLAGS ?= -s -w
BUILD_DIR ?= ../bin/plugins

PLUGINS := climate_model.so material_simulation.so

all: $(PLUGINS)

%.so: %.go
	@echo "Building plugin: $@"
	@mkdir -p $(BUILD_DIR)
	$(GO) build $(GOFLAGS) -ldflags "$(LDFLAGS)" -buildmode=plugin -o $(BUILD_DIR)/$@ $<

list:
	@echo "Available plugins:"
	@for plugin in $(PLUGINS); do \
		echo "  - $$plugin"; \
	done

clean:
	@echo "Cleaning plugins..."
	@rm -rf $(BUILD_DIR)/*.so
