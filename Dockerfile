FROM golang:1.21-alpine3.19 AS builder

WORKDIR /app

RUN apk add --no-cache \
    git \
    make \
    ca-certificates \
    tzdata

ENV CGO_ENABLED=0 \
    GO111MODULE=on \
    GOSUMDB=off

COPY go.mod go.sum ./
RUN go mod download

COPY . .

ARG APP_VERSION=dev
ARG BUILD_TIME=unknown
ARG GIT_COMMIT=unknown

RUN go build \
    -ldflags "-s -w \
        -X main.AppName=logrotate \
        -X main.AppVersion=${APP_VERSION} \
        -X main.BuildTime=${BUILD_TIME} \
        -X main.GitCommit=${GIT_COMMIT} \
        -X main.GitBranch=docker" \
    -o logrotate \
    cmd/server/main.go

RUN mkdir -p /app/configs /app/logs

FROM alpine:3.19 AS runtime

RUN apk add --no-cache \
    ca-certificates \
    tzdata \
    curl \
    && rm -rf /var/cache/apk/*

ENV TZ=UTC \
    ENV=production \
    CONFIG_FILE=/app/configs/config.prod.yaml \
    LOG_PATH=/var/log/logrotate/app.log

WORKDIR /app

RUN addgroup -g 10001 appgroup && \
    adduser -u 10001 -G appgroup -h /app -s /sbin/nologin -D appuser

COPY --from=builder /app/logrotate /usr/local/bin/logrotate
COPY --from=builder /app/configs /app/configs

RUN mkdir -p /var/log/logrotate && \
    chown -R appuser:appgroup /app /var/log/logrotate && \
    chmod 755 /usr/local/bin/logrotate

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["/usr/local/bin/logrotate"]
