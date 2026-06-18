# syntax=docker/dockerfile:1.6

########################################
# Stage 1: planner - generate Cargo.lock if missing
########################################
FROM rust:1.78-alpine3.20 AS planner
WORKDIR /app
RUN apk add --no-cache git
COPY Cargo.toml Cargo.lock* ./
COPY crates ./crates
COPY src ./src
COPY config ./config
COPY migrations ./migrations
COPY static ./static
RUN if [ ! -f Cargo.lock ]; then cargo generate-lockfile; fi

########################################
# Stage 2: builder - compile release binary
########################################
FROM rust:1.78-alpine3.20 AS builder
WORKDIR /app

RUN apk add --no-cache \
    musl-dev \
    openssl-dev \
    perl \
    make \
    cmake \
    pkgconfig \
    postgresql16-client \
    git

ENV RUSTFLAGS="-C target-feature=+crt-static"
ENV CARGO_NET_GIT_FETCH_WITH_CLI="true"
ENV SQLX_OFFLINE="true"

COPY --from=planner /app/Cargo.toml /app/Cargo.lock ./
COPY crates ./crates
COPY src ./src
COPY config ./config
COPY migrations ./migrations
COPY static ./static
COPY .sqlx* ./.sqlx/ 2>/dev/null || true

RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/app/target \
    cargo build --release --locked -p code_review_platform && \
    cp target/release/code_review_platform /app/code_review_platform

########################################
# Stage 3: sqlx-cli - for migrations in init container
########################################
FROM rust:1.78-alpine3.20 AS sqlx-cli
WORKDIR /app

RUN apk add --no-cache \
    musl-dev \
    openssl-dev \
    postgresql16-dev \
    make \
    cmake \
    pkgconfig \
    postgresql16-client

ENV SQLX_OFFLINE="false"

RUN --mount=type=cache,target=/usr/local/cargo/registry \
    cargo install sqlx-cli --version 0.7.4 --no-default-features --features postgres,rustls && \
    cp /usr/local/cargo/bin/sqlx /app/sqlx

########################################
# Stage 4: migrate-init - init container image for running migrations
########################################
FROM alpine:3.20 AS migrate-init

RUN apk add --no-cache \
    postgresql16-client \
    ca-certificates \
    tzdata

WORKDIR /app

COPY --from=sqlx-cli /app/sqlx /usr/local/bin/sqlx
COPY migrations ./migrations
COPY config ./config

ENV APP_ENV=production

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

ENTRYPOINT ["/bin/sh", "-c", "\
    echo 'Waiting for PostgreSQL...' && \
    until pg_isready -h \"${APP__DATABASE__HOST:-postgres}\" -p \"${APP__DATABASE__PORT:-5432}\" -U \"${APP__DATABASE__USER:-postgres}\"; do sleep 1; done && \
    echo 'PostgreSQL is ready, running migrations...' && \
    if [ -n \"$DATABASE_URL\" ]; then \
      sqlx migrate run --database-url \"$DATABASE_URL\"; \
    else \
      PGPASSWORD=\"${APP__DATABASE__PASSWORD:-}\" sqlx migrate run --database-url \"postgres://${APP__DATABASE__USER:-postgres}:${APP__DATABASE__PASSWORD:-}@${APP__DATABASE__HOST:-postgres}:${APP__DATABASE__PORT:-5432}/${APP__DATABASE__NAME:-postgres}\"; \
    fi && \
    echo 'Migrations completed successfully.'"]

########################################
# Stage 5: runner - minimal runtime image
########################################
FROM alpine:3.20 AS runner

RUN apk add --no-cache \
    ca-certificates \
    tzdata \
    curl \
    postgresql16-client \
    libgcc

ENV APP_ENV=production
ENV TZ=UTC

WORKDIR /app

COPY --from=builder /app/code_review_platform /usr/local/bin/code_review_platform
COPY config ./config
COPY migrations ./migrations
COPY static ./static

RUN mkdir -p /app/tmp && \
    addgroup -S appgroup && adduser -S appuser -G appgroup && \
    chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["/usr/local/bin/code_review_platform"]
