FROM golang:1.24-alpine AS builder

WORKDIR /app

RUN apk add --no-cache git ca-certificates tzdata

ENV GO111MODULE=on \
    CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN go build -ldflags="-s -w" -o /app/gateway ./cmd/gateway

FROM alpine:3.20

WORKDIR /app

RUN apk add --no-cache ca-certificates tzdata && \
    addgroup -g 1000 -S appgroup && \
    adduser -u 1000 -S appuser -G appgroup

COPY --from=builder /app/gateway /app/gateway
COPY --from=builder /app/configs /app/configs

RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080 9090

ENV GIN_MODE=release \
    TZ=Asia/Shanghai

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O - http://localhost:9090/health || exit 1

ENTRYPOINT ["/app/gateway"]
CMD ["--config", "/app/configs/config.yaml"]
