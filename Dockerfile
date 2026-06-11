# syntax=docker/dockerfile:1.5

ARG GO_VERSION=1.22
ARG ALPINE_VERSION=3.20

# ==============================
# Stage 1: build
# ==============================
FROM golang:${GO_VERSION}-alpine AS builder

ARG APP_VERSION=dev
ARG BUILD_TIME
ARG COMMIT_SHA=none

RUN apk add --no-cache ca-certificates tzdata git make

WORKDIR /src

ENV CGO_ENABLED=0
ENV GOOS=linux
ENV GOARCH=amd64

COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download -x

COPY . .

RUN --mount=type=cache,target=/root/.cache/go-build \
    --mount=type=cache,target=/go/pkg/mod \
    go build \
      -trimpath \
      -ldflags=" \
        -s -w \
        -X 'github.com/studio/gameroom/pkg/config.Info.Version=${APP_VERSION}' \
        -X 'github.com/studio/gameroom/pkg/config.Info.BuildTime=${BUILD_TIME:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}' \
        -X 'github.com/studio/gameroom/pkg/config.Info.Commit=${COMMIT_SHA}' \
      " \
      -o /out/gameroom-server \
      ./cmd/server

# ==============================
# Stage 2: run
# ==============================
FROM alpine:${ALPINE_VERSION} AS runtime

RUN apk add --no-cache ca-certificates tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

RUN addgroup -S app \
 && adduser -S app -G app -h /home/app

WORKDIR /app

COPY --from=builder --chown=app:app /out/gameroom-server /app/gameroom-server
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/

RUN chmod +x /app/gameroom-server

USER app

EXPOSE 8080 8081

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/health >/dev/null 2>&1 || exit 1

ENTRYPOINT ["/app/gameroom-server"]
CMD []
