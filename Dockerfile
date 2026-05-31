# ============================================
# Stage 1: Build Stage
# ============================================
FROM golang:1.21-alpine AS builder

LABEL maintainer="StreamSQL Team <dev@streamsql.io>"
LABEL description="StreamSQL - Stream SQL Computing Engine"

# Install build dependencies
RUN apk add --no-cache \
    git \
    ca-certificates \
    tzdata \
    && update-ca-certificates

# Set working directory
WORKDIR /app

# Set Go environment variables
ENV GO111MODULE=on \
    CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64 \
    GIT_TERMINAL_PROMPT=1

# Copy go mod and sum files
COPY go.mod go.sum ./

# Download dependencies (cache layer)
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download && go mod verify

# Copy source code
COPY . .

# Build the application
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go build \
    -ldflags="-s -w -X main.Version=${VERSION:-dev} -X main.BuildTime=$(date -u +%Y-%m-%dT%H:%M:%SZ) -X main.GitCommit=$(git rev-parse --short HEAD 2>/dev/null || echo 'none')" \
    -a -installsuffix cgo \
    -o /app/bin/streamsql \
    ./cmd/streamsql/main.go

# Verify the binary
RUN ls -la /app/bin/ && \
    /app/bin/streamsql --version 2>&1 || true

# ============================================
# Stage 2: Production Image
# ============================================
FROM alpine:3.18 AS production

LABEL maintainer="StreamSQL Team <dev@streamsql.io>"
LABEL description="StreamSQL - Stream SQL Computing Engine"
LABEL org.opencontainers.image.title="StreamSQL"
LABEL org.opencontainers.image.description="Lightweight and efficient stream SQL computing engine"
LABEL org.opencontainers.image.source="https://github.com/streamsql/streamsql"

# Install runtime dependencies
RUN apk add --no-cache \
    ca-certificates \
    tzdata \
    curl \
    && update-ca-certificates \
    && rm -rf /var/cache/apk/*

# Create non-root user
RUN addgroup -g 1001 streamsql && \
    adduser -D -u 1001 -G streamsql streamsql

# Set timezone
ENV TZ=Asia/Shanghai

# Set working directory
WORKDIR /app

# Copy binary from builder
COPY --from=builder /app/bin/streamsql /usr/local/bin/streamsql

# Copy configuration files
COPY config/ /app/config/

# Create data directories
RUN mkdir -p /app/data /app/logs /app/data/archive /app/data/vector_indexes /app/data/cdc_recovery && \
    chown -R streamsql:streamsql /app

# Set ownership and permissions
RUN chmod +x /usr/local/bin/streamsql

# Switch to non-root user
USER streamsql

# Expose ports
EXPOSE 8080 9090 8081

# Set environment variables
ENV APP_ENV=production \
    GIN_MODE=release \
    LOG_LEVEL=info

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/v1/health || exit 1

# Entrypoint
ENTRYPOINT ["/usr/local/bin/streamsql"]

# Default command
CMD ["--config", "/app/config/production.json"]
