FROM rust:1.76-bookworm AS chef

RUN cargo install cargo-chef --locked
WORKDIR /app

FROM chef AS planner
COPY . .
RUN cargo chef prepare --recipe-path recipe.json

FROM chef AS builder

RUN apt-get update && apt-get install -y \
    libssl-dev \
    pkg-config \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=planner /app/recipe.json recipe.json

RUN cargo chef cook --release --all-features --recipe-path recipe.json

COPY . .

RUN cargo build --release --all-features --bin edge-scheduler

RUN strip target/release/edge-scheduler

FROM debian:bookworm-slim AS runtime

RUN apt-get update && apt-get install -y \
    ca-certificates \
    curl \
    tini \
    && rm -rf /var/lib/apt/lists/*

RUN adduser --disabled-password --gecos "" --home /nonexistent --no-create-home edge-scheduler

WORKDIR /app

COPY --from=builder /app/target/release/edge-scheduler /usr/local/bin/edge-scheduler

COPY --from=builder /app/config /app/config/

RUN mkdir -p /app/data /app/logs && \
    chown -R edge-scheduler:edge-scheduler /app

USER edge-scheduler

ENV APP_ENV=production
ENV RUST_LOG=info
ENV RUST_BACKTRACE=0

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/v1/health || exit 1

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["edge-scheduler"]

FROM builder AS builder-test

WORKDIR /app
RUN cargo test --all-features --no-run

FROM runtime AS debug

USER root
RUN apt-get update && apt-get install -y \
    gdb \
    strace \
    ltrace \
    && rm -rf /var/lib/apt/lists/*
USER edge-scheduler

ENV RUST_LOG=debug
ENV RUST_BACKTRACE=1

CMD ["edge-scheduler"]
