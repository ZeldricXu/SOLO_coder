FROM golang:1.21-alpine AS builder

WORKDIR /app

RUN apk add --no-cache git ca-certificates tzdata && \
    update-ca-certificates

ENV GO111MODULE=on \
    CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64

ARG APP_VERSION=1.0.0
ARG BUILD_TIME
ARG GIT_COMMIT
ARG ENV=prod

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN go build -tags=${ENV} \
    -ldflags "-s -w \
    -X main.version=${APP_VERSION} \
    -X main.buildTime=${BUILD_TIME} \
    -X main.gitCommit=${GIT_COMMIT}" \
    -o /app/config-platform \
    ./cmd/server/

FROM alpine:3.19

RUN apk add --no-cache ca-certificates tzdata curl && \
    update-ca-certificates && \
    addgroup -S appgroup && \
    adduser -S appuser -G appgroup

ENV TZ=Asia/Shanghai \
    APP_ENV=prod \
    LOG_LEVEL=info \
    PORT=8080

WORKDIR /app

COPY --from=builder /app/config-platform /app/config-platform

RUN chown -R appuser:appgroup /app && \
    chmod +x /app/config-platform

USER appuser

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:${PORT}/health || exit 1

EXPOSE 8080

STOPSIGNAL SIGTERM

CMD ["/app/config-platform"]
