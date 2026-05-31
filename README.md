# MeshControl - Service Mesh Sidecar Management Plane

## Overview

MeshControl is a lightweight and efficient service mesh sidecar management plane built with Java 17 and Spring Boot 3.x. It provides comprehensive control over sidecar proxies in a microservices architecture.

## Architecture

The project follows a modular architecture with the following components:

| Module | Description |
|--------|-------------|
| **meshcontrol-common** | Shared utilities, base classes, and common configurations |
| **meshcontrol-eventstore** | Event log storage, snapshot management, projection reconstruction, and time-travel queries |
| **meshcontrol-sidecar** | Sidecar injection policies, hot configuration updates, and resource limit management |
| **meshcontrol-dns** | Multi-upstream DNS management, intelligent resolution policies, and cache acceleration |
| **meshcontrol-traffic** | Canary release, blue-green deployment, traffic mirroring, and circuit breaker configuration |
| **meshcontrol-mtls** | Automatic certificate issuance, rotation policy configuration, and CRL management |
| **meshcontrol-fault** | Fault scenario definition, injection scope control, and automatic rollback |
| **meshcontrol-audit** | CQRS command persistence, audit log association, and compliance reporting |
| **meshcontrol-image** | Image layered pull, P2P distribution acceleration, and cross-registry synchronization |
| **meshcontrol-api** | Main API gateway and application entry point |

## Technology Stack

- **Language**: Java 17
- **Web Framework**: Spring Boot 3.2.x + Spring WebFlux
- **Persistence**: MyBatis-Plus 3.5.5 + Flyway 10.10.0
- **Database**: MySQL 8.0+
- **Caching**: Caffeine (L1) + Redis (L2)
- **Monitoring**: Micrometer + Spring Actuator + Prometheus
- **Security**: Spring Security
- **Utilities**: Lombok, Apache Commons, Google Guava

## Quick Start

### Prerequisites

- JDK 17+
- Maven 3.6+
- MySQL 8.0+
- Redis 6.0+ (optional)

### Build

```bash
# Clone the repository
cd session166

# Build all modules
mvn clean package -DskipTests
```

### Run

```bash
# Start the API gateway
cd meshcontrol-api
mvn spring-boot:run
```

The application will start on port 8080 by default.

### Database Setup

1. Create MySQL database:
```sql
CREATE DATABASE meshcontrol CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

2. Flyway will automatically run the migrations on startup.

## API Endpoints

All APIs are prefixed with `/api/v1`.

### Event Store
- `POST /api/v1/events` - Publish an event
- `GET /api/v1/events` - Query events
- `GET /api/v1/events/stream/{aggregateType}/{aggregateId}` - Get event stream
- `POST /api/v1/events/snapshots/{aggregateType}/{aggregateId}` - Create snapshot
- `POST /api/v1/events/timetravel` - Time-travel query
- `POST /api/v1/events/projection/rebuild` - Rebuild projection

### Sidecar Lifecycle
- `POST /api/v1/sidecars/inject` - Inject sidecar
- `DELETE /api/v1/sidecars/{sidecarId}` - Remove sidecar
- `POST /api/v1/sidecars/{sidecarId}/heartbeat` - Send heartbeat
- `GET /api/v1/sidecars` - List sidecars
- `POST /api/v1/sidecars/policies` - Create injection policy
- `POST /api/v1/sidecars/configs` - Publish configuration

### DNS Proxy
- `POST /api/v1/dns/upstreams` - Add DNS upstream
- `POST /api/v1/dns/zones` - Add DNS zone
- `POST /api/v1/dns/resolve` - Resolve DNS query
- `GET /api/v1/dns/cache/stats` - Get cache statistics

### Traffic Control
- `POST /api/v1/traffic/policies` - Create traffic policy
- `POST /api/v1/traffic/canary` - Start canary release
- `POST /api/v1/traffic/canary/{releaseId}/complete` - Complete canary
- `POST /api/v1/traffic/canary/{releaseId}/rollback` - Rollback canary
- `POST /api/v1/traffic/bluegreen` - Start blue-green deployment

### mTLS Certificate
- `POST /api/v1/mtls/ca/root` - Create root CA
- `POST /api/v1/mtls/certificates` - Issue certificate
- `POST /api/v1/mtls/certificates/revoke` - Revoke certificate
- `POST /api/v1/mtls/certificates/{certId}/rotate` - Rotate certificate
- `GET /api/v1/mtls/crl` - Get certificate revocation list

### Fault Injection
- `POST /api/v1/fault/scenarios` - Create fault scenario
- `POST /api/v1/fault/inject` - Inject fault
- `POST /api/v1/fault/injections/{injectionId}/rollback` - Rollback injection
- `GET /api/v1/fault/stats` - Get injection statistics

### Audit
- `POST /api/v1/audit/commands` - Record command
- `GET /api/v1/audit/commands` - Query commands
- `GET /api/v1/audit/logs` - Query audit logs
- `POST /api/v1/audit/reports/compliance` - Generate compliance report

### Image Distribution
- `POST /api/v1/images/registries` - Add image registry
- `POST /api/v1/images/pull` - Pull image
- `POST /api/v1/images/sync` - Start image sync
- `GET /api/v1/images/stats` - Get distribution statistics

## Configuration

The main configuration file is `application.yml` in the `meshcontrol-api` module. Key configurations:

- Database connection
- Redis connection (for distributed caching)
- Flyway migration settings
- MyBatis-Plus configuration
- Module-specific settings

## License

This project is licensed under the MIT License.
