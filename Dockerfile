# ============================================
# Stage 1: Cargo Chef - Cache Dependencies
# ============================================
FROM lukemathwalker/cargo-chef:latest-rust-1.75 AS chef
WORKDIR /app
RUN apt update && apt install -y lld clang pkg-config libssl-dev

FROM chef AS planner
COPY . .
RUN cargo chef prepare --recipe-path recipe.json

FROM chef AS builder
COPY --from=planner /app/recipe.json recipe.json
RUN cargo chef cook --release --recipe-path recipe.json

COPY . .
RUN cargo build --release --bin enterprise_platform

# ============================================
# Stage 2: Runtime
# ============================================
FROM debian:bookworm-slim AS runtime

LABEL org.opencontainers.image.title="Enterprise Platform"
LABEL org.opencontainers.image.description="Enterprise-grade platform with monitoring, security, and federated learning capabilities"
LABEL org.opencontainers.image.source="https://github.com/platform/enterprise_platform"
LABEL org.opencontainers.image.licenses="MIT"

WORKDIR /app

RUN apt-get update && apt-get install -y \
    ca-certificates \
    curl \
    tzdata \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/release/enterprise_platform /usr/local/bin/

RUN useradd -m appuser
USER appuser

ENV RUST_LOG=info
ENV APP_ENV=production

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

CMD ["enterprise_platform"]
