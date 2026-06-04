FROM rust:1.78-alpine AS builder

RUN apk add --no-cache musl-dev pkgconf openssl-dev openssl-libs-static

WORKDIR /app

COPY Cargo.toml Cargo.lock ./
COPY shared/Cargo.toml shared/Cargo.toml
COPY backend/common/Cargo.toml backend/common/Cargo.toml
COPY backend/models/Cargo.toml backend/models/Cargo.toml
COPY backend/auction-engine/Cargo.toml backend/auction-engine/Cargo.toml
COPY backend/bid-arbitrator/Cargo.toml backend/bid-arbitrator/Cargo.toml
COPY backend/account-service/Cargo.toml backend/account-service/Cargo.toml
COPY backend/notification-service/Cargo.toml backend/notification-service/Cargo.toml
COPY backend/risk-control/Cargo.toml backend/risk-control/Cargo.toml
COPY backend/fulfillment/Cargo.toml backend/fulfillment/Cargo.toml
COPY backend/api/Cargo.toml backend/api/Cargo.toml

RUN mkdir -p shared/src && echo "" > shared/src/lib.rs && \
    mkdir -p backend/common/src && echo "" > backend/common/src/lib.rs && \
    mkdir -p backend/models/src && echo "" > backend/models/src/lib.rs && \
    mkdir -p backend/auction-engine/src && echo "" > backend/auction-engine/src/lib.rs && \
    mkdir -p backend/bid-arbitrator/src && echo "" > backend/bid-arbitrator/src/lib.rs && \
    mkdir -p backend/account-service/src && echo "" > backend/account-service/src/lib.rs && \
    mkdir -p backend/notification-service/src && echo "" > backend/notification-service/src/lib.rs && \
    mkdir -p backend/risk-control/src && echo "" > backend/risk-control/src/lib.rs && \
    mkdir -p backend/fulfillment/src && echo "" > backend/fulfillment/src/lib.rs && \
    mkdir -p backend/api/src && echo "fn main() {}" > backend/api/src/main.rs

RUN cargo build --release --bin auction-api 2>/dev/null || true

COPY . .

RUN touch shared/src/lib.rs \
    backend/common/src/lib.rs \
    backend/models/src/lib.rs \
    backend/auction-engine/src/lib.rs \
    backend/bid-arbitrator/src/lib.rs \
    backend/account-service/src/lib.rs \
    backend/notification-service/src/lib.rs \
    backend/risk-control/src/lib.rs \
    backend/fulfillment/src/lib.rs \
    backend/api/src/main.rs

RUN cargo build --release --bin auction-api

FROM alpine:3.19

RUN apk add --no-cache ca-certificates openssl

WORKDIR /app

COPY --from=builder /app/target/release/auction-api /app/auction-api
COPY --from=builder /app/migrations /app/migrations

RUN mkdir -p /app/media

ENV SERVER_HOST=0.0.0.0
ENV SERVER_PORT=8080
ENV MEDIA_STORAGE_PATH=/app/media

EXPOSE 8080

CMD ["/app/auction-api"]
