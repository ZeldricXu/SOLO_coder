FROM golang:1.22-alpine AS builder

WORKDIR /app

RUN apk add --no-cache \
    ca-certificates \
    tzdata \
    git \
    && update-ca-certificates

ENV CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64 \
    GOTOOLCHAIN=auto

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN go build -ldflags="-s -w -X main.version=${VERSION:-dev} -X main.commit=${COMMIT:-unknown} -X main.buildTime=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    -o /app/bin/cloudci \
    ./cmd/cloudci

RUN mkdir -p /app/config

FROM scratch AS runtime

COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /usr/share/zoneinfo /usr/share/zoneinfo
COPY --from=builder /app/bin/cloudci /usr/local/bin/cloudci
COPY --from=builder /app/config /etc/cloudci/config

ENV TZ=Asia/Shanghai \
    APP_ENV=production \
    CLOUDCI_CONFIG_PATH=/etc/cloudci/config

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD ["cloudci", "healthcheck"] || exit 1

ENTRYPOINT ["cloudci"]
CMD ["serve"]
