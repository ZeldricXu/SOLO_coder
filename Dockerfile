FROM golang:1.21-alpine3.18 AS builder

WORKDIR /app

RUN apk add --no-cache \
    git \
    make \
    ca-certificates \
    tzdata

ENV GO111MODULE=on \
    CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64

COPY go.mod go.sum ./
RUN go mod download

COPY . .

ARG VERSION=dev
ARG COMMIT=none
ARG BUILD_TIME=unknown

RUN go build -ldflags "\
    -s -w \
    -X main.version=${VERSION} \
    -X main.commit=${COMMIT} \
    -X main.buildTime=${BUILD_TIME}" \
    -o bin/llmgateway ./cmd/main.go

FROM alpine:3.18 AS runtime

WORKDIR /app

RUN apk add --no-cache \
    ca-certificates \
    tzdata \
    curl

ENV TZ=Asia/Shanghai

RUN addgroup -g 10001 appgroup && \
    adduser -H -D -u 10001 -G appgroup appuser

COPY --from=builder /app/bin/llmgateway /usr/local/bin/llmgateway
COPY --from=builder /app/configs /app/configs

RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/api/v1/health || exit 1

ENV GIN_MODE=release \
    CONFIG_PATH=/app/configs/config.prod.yaml

ENTRYPOINT ["llmgateway"]
CMD ["${CONFIG_PATH}"]
