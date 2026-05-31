FROM eclipse-temurin:17-jre-alpine AS base

ENV TZ=Asia/Shanghai
ENV APP_HOME=/app
ENV APP_NAME=smartflow
ENV JAVA_OPTS=""
ENV SPRING_PROFILES_ACTIVE=prod
ENV SERVER_PORT=8080

RUN apk add --no-cache tzdata curl \
    && ln -sf /usr/share/zoneinfo/${TZ} /etc/localtime \
    && echo "${TZ}" > /etc/timezone \
    && mkdir -p ${APP_HOME} \
    && addgroup -S appuser \
    && adduser -S appuser -G appuser

WORKDIR ${APP_HOME}

FROM maven:3.9-eclipse-temurin-17 AS builder

ENV TZ=Asia/Shanghai
WORKDIR /build

COPY pom.xml .
COPY smartflow-common/pom.xml ./smartflow-common/pom.xml
COPY smartflow-persistence/pom.xml ./smartflow-persistence/pom.xml
COPY smartflow-ticket-assignment/pom.xml ./smartflow-ticket-assignment/pom.xml
COPY smartflow-approval-engine/pom.xml ./smartflow-approval-engine/pom.xml
COPY smartflow-metering-billing/pom.xml ./smartflow-metering-billing/pom.xml
COPY smartflow-multitenant/pom.xml ./smartflow-multitenant/pom.xml
COPY smartflow-process-designer/pom.xml ./smartflow-process-designer/pom.xml
COPY smartflow-skill-graph/pom.xml ./smartflow-skill-graph/pom.xml
COPY smartflow-document-compare/pom.xml ./smartflow-document-compare/pom.xml
COPY smartflow-sla-monitor/pom.xml ./smartflow-sla-monitor/pom.xml
COPY smartflow-boot/pom.xml ./smartflow-boot/pom.xml

RUN mvn dependency:go-offline -B

COPY . .

RUN mvn clean package -DskipTests -Pprod -pl smartflow-boot -am

FROM base AS final

WORKDIR ${APP_HOME}

COPY --from=builder /build/smartflow-boot/target/smartflow-boot-*.jar app.jar

RUN chown -R appuser:appuser ${APP_HOME}

USER appuser

EXPOSE ${SERVER_PORT}

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=5 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java \
    ${JAVA_OPTS} \
    -Djava.security.egd=file:/dev/./urandom \
    -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} \
    -Dserver.port=${SERVER_PORT} \
    -jar app.jar"]

STOPSIGNAL SIGTERM
