FROM eclipse-temurin:17-jre-alpine AS base

LABEL org.opencontainers.image.title="Log Analyzer"
LABEL org.opencontainers.image.description="Production log analysis and anomaly detection CLI tool"
LABEL org.opencontainers.image.source="https://github.com/datateam/log-analyzer"
LABEL org.opencontainers.image.licenses="MIT"

RUN apk add --no-cache tzdata curl ca-certificates && \
    rm -rf /var/cache/apk/*

ENV JAVA_OPTS="-Xmx2g -Xms256m"
ENV APP_HOME="/opt/log-analyzer"
ENV CONFIG_DIR="/etc/log-analyzer"
ENV LOG_DIR="/var/log/log-analyzer"
ENV DATA_DIR="/data"

RUN mkdir -p ${APP_HOME} ${CONFIG_DIR} ${LOG_DIR} ${DATA_DIR}

WORKDIR ${APP_HOME}

FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q && \
    mv target/log-analyzer-*-all.jar log-analyzer.jar

FROM base AS final

COPY --from=builder /app/log-analyzer.jar ${APP_HOME}/log-analyzer.jar

COPY config/ ${CONFIG_DIR}/

RUN addgroup -S log-analyzer && \
    adduser -S log-analyzer -G log-analyzer && \
    chown -R log-analyzer:log-analyzer ${APP_HOME} ${CONFIG_DIR} ${LOG_DIR} ${DATA_DIR}

USER log-analyzer

VOLUME ["${CONFIG_DIR}", "${LOG_DIR}", "${DATA_DIR}"]

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar ${APP_HOME}/log-analyzer.jar \"$@\"", "--"]

CMD ["--help"]
