FROM eclipse-temurin:17-jre-alpine AS base

LABEL org.opencontainers.image.title="Contract Audit Platform" \
      org.opencontainers.image.description="Enterprise-grade Smart Contract Audit Analysis Platform" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.vendor="ContractAudit" \
      org.opencontainers.image.authors="engineering@contraudit.com" \
      org.opencontainers.image.licenses="Commercial"

ENV APP_HOME=/app \
    JAVA_OPTS="-Xmx1024m -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication" \
    SPRING_PROFILES_ACTIVE=prod \
    TZ=UTC

RUN addgroup -S contraudit && adduser -S contraudit -G contraudit && \
    mkdir -p ${APP_HOME}/logs ${APP_HOME}/data && \
    chown -R contraudit:contraudit ${APP_HOME} && \
    apk add --no-cache tzdata curl

WORKDIR ${APP_HOME}

FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -Pprod -DskipTests -B && \
    cp target/contract-audit-platform-*.jar /app.jar

FROM base AS final

COPY --from=builder /app.jar ${APP_HOME}/app.jar

USER contraudit

EXPOSE 8080 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom -jar ${APP_HOME}/app.jar"]
