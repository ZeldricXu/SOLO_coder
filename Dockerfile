#------------------------------------------------------------------------------
# Stage 1: Builder - 完整Rust环境编译
#------------------------------------------------------------------------------
FROM rust:1.75-bookworm AS builder

ENV CARGO_HOME=/usr/local/cargo \
    RUSTUP_HOME=/usr/local/rustup \
    PATH=/usr/local/cargo/bin:$PATH \
    CARGO_REGISTRIES_CRATES_IO_PROTOCOL=sparse

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    git \
    pkg-config \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY Cargo.toml Cargo.lock* ./

RUN mkdir -p src && \
    echo "fn main() {}" > src/main.rs && \
    echo "" > src/lib.rs

RUN cargo fetch

COPY src/ src/

RUN cargo build --release --features "postgres,otel" && \
    cargo build --release --features "postgres,otel" --bin enterprise-middleware

#------------------------------------------------------------------------------
# Stage 2: Runtime - 最小化运行时镜像
#------------------------------------------------------------------------------
FROM debian:bookworm-slim AS runtime

LABEL maintainer="platform-team@company.com" \
      org.opencontainers.image.source="https://github.com/enterprise/middleware" \
      org.opencontainers.image.description="Enterprise Middleware - Core Processing, Storage, CDC, Data Quality, Metadata, Lineage & Notification" \
      org.opencontainers.image.licenses="MIT"

ENV DEBIAN_FRONTEND=noninteractive \
    RUST_LOG=info \
    RUST_ENV=production \
    APP_HOME=/app \
    APP_PORT=8080

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    libssl3 \
    tzdata \
    && rm -rf /var/lib/apt/lists/* && \
    addgroup --system app && \
    adduser --system --no-create-home --group app

WORKDIR /app

COPY --from=builder /app/target/release/enterprise-middleware /usr/local/bin/

COPY config/ ./config/

RUN mkdir -p /app/data /app/logs && \
    chown -R app:app /app && \
    chmod +x /usr/local/bin/enterprise-middleware

USER app

EXPOSE 8080 9090

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

STOPSIGNAL SIGTERM

ENTRYPOINT ["/usr/local/bin/enterprise-middleware"]
CMD ["--config", "/app/config/production.toml"]

#------------------------------------------------------------------------------
# Stage 3: Development - 开发环境
#------------------------------------------------------------------------------
FROM rust:1.75-bookworm AS development

ENV CARGO_HOME=/usr/local/cargo \
    RUSTUP_HOME=/usr/local/rustup \
    PATH=/usr/local/cargo/bin:$PATH \
    RUST_LOG=debug \
    RUST_ENV=development

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    git \
    pkg-config \
    libssl-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

RUN rustup component add rustfmt clippy && \
    cargo install cargo-watch cargo-edit

WORKDIR /app

COPY Cargo.toml Cargo.lock* ./

RUN mkdir -p src && \
    echo "fn main() {}" > src/main.rs && \
    echo "" > src/lib.rs && \
    cargo fetch

COPY src/ src/
COPY config/ config/

EXPOSE 8080 9090

CMD ["cargo", "run", "--features", "postgres,otel"]

#------------------------------------------------------------------------------
# Stage 4: Testing - 测试环境
#------------------------------------------------------------------------------
FROM rust:1.75-bookworm AS testing

ENV CARGO_HOME=/usr/local/cargo \
    RUSTUP_HOME=/usr/local/rustup \
    PATH=/usr/local/cargo/bin:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    git \
    pkg-config \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

RUN rustup component add clippy

WORKDIR /app

COPY Cargo.toml Cargo.lock* ./

RUN mkdir -p src && \
    echo "fn main() {}" > src/main.rs && \
    echo "" > src/lib.rs && \
    cargo fetch

COPY src/ src/
COPY config/ config/

RUN cargo build --tests && \
    cargo clippy --all-targets -- -D warnings

CMD ["cargo", "test", "--", "--test-threads=4"]
