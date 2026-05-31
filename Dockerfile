ARG GO_VERSION=1.21
ARG ALPINE_VERSION=3.18

FROM golang:${GO_VERSION}-alpine AS builder

WORKDIR /app

RUN apk add --no-cache ca-certificates git tzdata

ENV CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64

COPY go.mod go.sum ./
RUN go mod download && go mod verify

COPY . .

ARG VERSION=dev
ARG COMMIT=none
ARG BUILD_TIME=unknown

RUN go build -ldflags "-s -w \
    -X main.Version=${VERSION} \
    -X main.Commit=${COMMIT} \
    -X main.BuildTime=${BUILD_TIME}" \
    -o /app/bin/edgevision ./cmd/server

FROM alpine:${ALPINE_VERSION} AS runtime

WORKDIR /app

RUN apk add --no-cache ca-certificates tzdata curl && \
    rm -rf /var/cache/apk/*

RUN addgroup -g 10001 edgevision && \
    adduser -u 10001 -G edgevision -h /app -s /sbin/nologin -D edgevision

RUN mkdir -p /app/data /app/configs && \
    chown -R edgevision:edgevision /app

USER edgevision:edgevision

COPY --from=builder /app/bin/edgevision /app/edgevision
COPY --from=builder /app/configs/config.yaml /app/configs/config.yaml

ENV EDGEVISION_CONFIG=/app/configs/config.yaml \
    EDGEVISION_DATA_DIR=/app/data \
    GIN_MODE=release

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/api/v1/health || exit 1

ENTRYPOINT ["/app/edgevision"]
CMD ["--config", "/app/configs/config.yaml"]
