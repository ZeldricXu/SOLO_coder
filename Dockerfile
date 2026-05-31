FROM eclipse-temurin:17-jre-alpine AS builder

WORKDIR /app

RUN apk add --no-cache curl jq

FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="ChainETL Platform"
LABEL org.opencontainers.image.description="链上数据ETL管道平台 - 企业级区块链数据处理平台"
LABEL org.opencontainers.image.vendor="ChainETL"
LABEL org.opencontainers.image.source="https://github.com/chainetl/chainetl-platform"

RUN addgroup -S chainetl && adduser -S chainetl -G chainetl

WORKDIR /app

RUN apk add --no-cache \
    curl \
    tzdata \
    bash \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && apk del tzdata

COPY --chown=chainetl:chainetl target/chainetl-platform-*.jar app.jar

COPY --chown=chainetl:chainetl src/main/docker/entrypoint.sh .
COPY --chown=chainetl:chainetl src/main/docker/healthcheck.sh .

RUN chmod +x entrypoint.sh healthcheck.sh

RUN mkdir -p /app/logs /app/data && \
    chown -R chainetl:chainetl /app

USER chainetl:chainetl

EXPOSE 8080 9090

ENV JVM_OPTS="-Xms512m -Xmx1024m"
ENV SPRING_PROFILES_ACTIVE=prod
ENV TZ=Asia/Shanghai

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD ./healthcheck.sh

ENTRYPOINT ["./entrypoint.sh"]
