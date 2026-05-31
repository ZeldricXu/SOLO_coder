# EdgeVision - Video Stream Edge Analysis Engine

## Overview

EdgeVision is a lightweight, high-performance video stream edge analysis engine designed for AI model deployment at the edge, inference task scheduling, lightweight rule execution, and multi-protocol data conversion.

## Core Modules

| Module | Description |
|--------|-------------|
| **Inference Scheduler** | AI model edge deployment, task scheduling with dynamic configuration |
| **Rule Engine** | Lightweight edge rule execution with pluggable strategies |
| **Protocol Adapter** | Multi-industrial protocol driver loading with async processing |
| **Offline Cache** | Local data caching with automatic cloud sync when online |
| **OTA Manager** | Differential firmware upgrades with gray release and rollback |
| **Data Aggregator** | Edge-side data pre-aggregation to reduce bandwidth |
| **Device Shadow** | Cloud device desired state synchronization |
| **Device Lifecycle** | Device registration, authentication, monitoring |

## Quick Start

### Prerequisites

- Go 1.21+
- Docker (optional, for containerized deployment)
- PostgreSQL 15+ (optional)
- Redis 7+ (optional)

### Local Development

```bash
# Clone the repository
git clone <repository-url>
cd session140

# Install dependencies
make deps

# Run development build
make dev

# Start the server
make run
```

### Using Docker

```bash
# Build and start all services
docker-compose up -d

# View logs
docker-compose logs -f edgevision
```

## Build Targets

| Command | Description |
|---------|-------------|
| `make dev` | Build development binary with debug info |
| `make prod` | Build optimized production binary |
| `make test` | Run unit tests |
| `make lint` | Run golangci-lint |
| `make coverage` | Generate test coverage report |
| `make fmt` | Format code |
| `make docker` | Build Docker image |
| `make clean` | Clean build artifacts |

## API Documentation

The server exposes RESTful APIs on port 8080. See the API module for detailed endpoints.

## Project Structure

```
.
├── api/                    # API layer
├── cmd/                    # Application entrypoints
├── configs/                # Configuration files
├── internal/               # Private application code
│   ├── aggregation/        # Data aggregation module
│   ├── cache/              # Offline cache module
│   ├── common/             # Shared utilities
│   ├── inference/          # Inference scheduler module
│   ├── lifecycle/          # Device lifecycle module
│   ├── models/             # Data models
│   ├── ota/                # OTA upgrade module
│   ├── protocol/           # Protocol adapter module
│   ├── rules/              # Rule engine module
│   └── shadow/             # Device shadow module
├── .github/workflows/      # CI/CD pipelines
├── docker-compose.yml      # Docker compose configuration
├── Dockerfile              # Multi-stage Docker build
├── Makefile                # Build automation
└── go.mod                  # Go module definition
```

## License

See LICENSE file for details.
