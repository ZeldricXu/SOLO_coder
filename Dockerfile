FROM golang:1.22-alpine AS builder

RUN apk add --no-cache git ca-certificates tzdata

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
    -ldflags='-w -s -extldflags "-static"' \
    -o /bin/loganalyzer \
    ./cmd/loganalyzer

FROM scratch

COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /usr/share/zoneinfo /usr/share/zoneinfo
COPY --from=builder /bin/loganalyzer /bin/loganalyzer
COPY --from=builder /src/config /config

ENV TZ=UTC \
    APP_ENV=production \
    CONFIG_PATH=/config

EXPOSE 8080 9090 9091

ENTRYPOINT ["/bin/loganalyzer"]
