ARG GO_VERSION=1.21

FROM golang:${GO_VERSION}-alpine AS builder

RUN apk add --no-cache git ca-certificates tzdata

ARG VERSION=0.0.1-dev
ARG GIT_COMMIT=unknown
ARG BUILD_TIME=unknown

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download && go mod verify

COPY . .

RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
    -trimpath \
    -ldflags "-s -w \
      -X main.version=${VERSION} \
      -X main.gitCommit=${GIT_COMMIT} \
      -X main.buildTime=${BUILD_TIME} \
      -X main.buildProfile=prod" \
    -o /bin/session287 .

FROM golang:${GO_VERSION}-alpine AS dev

RUN apk add --no-cache git ca-certificates tzdata delve

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN go build -gcflags="all=-N -l" -o /bin/session287 .

EXPOSE 8080 2345

CMD ["/bin/session287"]

FROM gcr.io/distroless/static-debian12:nonroot AS prod

COPY --from=builder /usr/share/zoneinfo /usr/share/zoneinfo
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /bin/session287 /bin/session287

ENV TZ=Asia/Shanghai

USER 65532:65532

ENTRYPOINT ["/bin/session287"]
