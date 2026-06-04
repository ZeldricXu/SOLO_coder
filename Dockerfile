# Stage 1: Build with sccache
FROM rust:1.78-alpine AS builder
RUN apk add --no-cache musl-dev protoc curl
RUN curl -L https://github.com/mozilla/sccache/releases/download/v0.8.1/sccache-v0.8.1-x86_64-unknown-linux-musl.tar.gz | tar xz && \
    mv sccache-v0.8.1-x86_64-unknown-linux-musl/sccache /usr/local/bin/ && \
    chmod +x /usr/local/bin/sccache
ENV RUSTC_WRAPPER=/usr/local/bin/sccache
ENV SCCACHE_DIR=/sccache
WORKDIR /app
COPY . .
RUN cargo build --release --bin cdn-center --bin cdn-edge

# Stage 2: Runtime
FROM alpine:3.19
RUN apk add --no-cache ca-certificates
COPY --from=builder /app/target/release/cdn-center /usr/local/bin/
COPY --from=builder /app/target/release/cdn-edge /usr/local/bin/
COPY --from=builder /app/migrations /migrations
EXPOSE 8080 9090
CMD ["cdn-center"]
