# syntax=docker/dockerfile:1.6

# ===================== Builder Stage =====================
FROM rust:1.78-alpine3.20 AS builder

WORKDIR /app

RUN apk add --no-cache \
    musl-dev \
    perl \
    make \
    openssl-dev \
    pkgconfig \
    ca-certificates \
    curl

RUN rustup target add x86_64-unknown-linux-musl

ENV RUSTFLAGS="-C target-feature=+crt-static"
ENV CARGO_NET_GIT_FETCH_WITH_CLI=true

COPY Cargo.toml Cargo.lock ./
COPY crates ./crates

RUN mkdir -p src && \
    echo "fn main() {}" > src/main.rs && \
    echo "pub fn __placeholder() {}" > src/lib.rs && \
    cargo build --release --target x86_64-unknown-linux-musl --bin code_review_platform 2>&1 || true

COPY src ./src
COPY migrations ./migrations
COPY config ./config

RUN touch src/main.rs src/lib.rs && \
    cargo build --release --target x86_64-unknown-linux-musl --bin code_review_platform

RUN strip /app/target/x86_64-unknown-linux-musl/release/code_review_platform

# ===================== Runtime Stage =====================
FROM alpine:3.20 AS runtime

RUN apk add --no-cache \
    ca-certificates \
    tzdata \
    curl \
    && rm -rf /var/cache/apk/*

ENV TZ=UTC
ENV RUN_MODE=production
ENV APP_ENV=production

WORKDIR /app

COPY --from=builder /app/target/x86_64-unknown-linux-musl/release/code_review_platform /app/code_review_platform
COPY --from=builder /app/migrations /app/migrations
COPY --from=builder /app/config /app/config
COPY static /app/static

RUN chmod +x /app/code_review_platform && \
    addgroup -S app && adduser -S app -G app && \
    chown -R app:app /app

USER app

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["/app/code_review_platform"]
