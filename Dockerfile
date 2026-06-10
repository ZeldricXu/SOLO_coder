FROM rust:1.78-alpine3.20 AS builder

WORKDIR /app

RUN apk add --no-cache \
    musl-dev \
    perl \
    make \
    gcc \
    g++ \
    zlib-dev \
    bzip2-dev \
    xz-dev \
    libgomp

RUN rustup target add x86_64-unknown-linux-musl

COPY Cargo.toml Cargo.lock ./
COPY .cargo ./.cargo
COPY src ./src

RUN cargo build --release --target x86_64-unknown-linux-musl --features parallel,simd

FROM alpine:3.20

LABEL org.opencontainers.image.title="genome_pipeline" \
      org.opencontainers.image.description="High-performance genome sequence analysis pipeline" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.source="https://github.com/your-org/genome_pipeline" \
      org.opencontainers.image.licenses="MIT" \
      maintainer="Bioinformatics Pipeline Team"

RUN apk add --no-cache \
    libgomp \
    zlib \
    bzip2 \
    xz-libs \
    ca-certificates

COPY --from=builder /app/target/x86_64-unknown-linux-musl/release/genome_pipeline /usr/local/bin/genome_pipeline

RUN ln -s /usr/local/bin/genome_pipeline /usr/local/bin/gp

ENV GENOME_REFERENCE_DIR="/reference"

WORKDIR /data

ENTRYPOINT ["/usr/local/bin/genome_pipeline"]

CMD ["--help"]
