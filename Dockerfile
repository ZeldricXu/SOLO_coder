# syntax=docker/dockerfile:1.6

FROM eclipse-temurin:17-jdk-alpine AS builder
LABEL stage=builder

WORKDIR /app

ENV MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Dmaven.test.skip=false"
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

RUN apk add --no-cache maven=3.9.6-r0

COPY pom.xml .
COPY .mvn .mvn
COPY meshcontrol-common/pom.xml ./meshcontrol-common/
COPY meshcontrol-eventstore/pom.xml ./meshcontrol-eventstore/
COPY meshcontrol-sidecar/pom.xml ./meshcontrol-sidecar/
COPY meshcontrol-dns/pom.xml ./meshcontrol-dns/
COPY meshcontrol-traffic/pom.xml ./meshcontrol-traffic/
COPY meshcontrol-mtls/pom.xml ./meshcontrol-mtls/
COPY meshcontrol-fault/pom.xml ./meshcontrol-fault/
COPY meshcontrol-audit/pom.xml ./meshcontrol-audit/
COPY meshcontrol-image/pom.xml ./meshcontrol-image/
COPY meshcontrol-api/pom.xml ./meshcontrol-api/

RUN mvn dependency:go-offline -B -e -Pprod

COPY checkstyle.xml .
COPY pmd.xml .
COPY spotbugs-exclude.xml .
COPY lombok.config .

COPY meshcontrol-common/src ./meshcontrol-common/src
COPY meshcontrol-eventstore/src ./meshcontrol-eventstore/src
COPY meshcontrol-sidecar/src ./meshcontrol-sidecar/src
COPY meshcontrol-dns/src ./meshcontrol-dns/src
COPY meshcontrol-traffic/src ./meshcontrol-traffic/src
COPY meshcontrol-mtls/src ./meshcontrol-mtls/src
COPY meshcontrol-fault/src ./meshcontrol-fault/src
COPY meshcontrol-audit/src ./meshcontrol-audit/src
COPY meshcontrol-image/src ./meshcontrol-image/src
COPY meshcontrol-api/src ./meshcontrol-api/src

RUN mvn clean package -B -e -Pprod -DskipTests \
    && mvn dependency:copy-dependencies -pl meshcontrol-api -DincludeScope=runtime -DoutputDirectory=target/dependency

FROM eclipse-temurin:17-jre-alpine AS runtime
LABEL org.opencontainers.image.title="MeshControl" \
      org.opencontainers.image.description="Service Mesh Sidecar Management Plane" \
      org.opencontainers.image.vendor="MeshControl" \
      org.opencontainers.image.source="https://github.com/meshcontrol/meshcontrol" \
      org.opencontainers.image.licenses="MIT"

RUN apk add --no-cache \
        tzdata=2024a-r0 \
        curl=8.5.0-r0 \
        ca-certificates=20240226-r0 \
    && rm -rf /var/cache/apk/* \
    && addgroup -S meshcontrol -g 1000 \
    && adduser -S meshcontrol -G meshcontrol -u 1000 -h /home/meshcontrol -s /bin/sh

ENV TZ=Asia/Shanghai \
    JAVA_HOME=/opt/java/openjdk \
    PATH="${JAVA_HOME}/bin:${PATH}" \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof" \
    SPRING_PROFILES_ACTIVE=prod \
    APP_HOME=/app

WORKDIR ${APP_HOME}

COPY --from=builder /app/meshcontrol-api/target/*.jar app.jar
COPY --from=builder /app/meshcontrol-api/target/dependency/ BOOT-INF/lib/

RUN mkdir -p /app/logs /app/config /app/tmp \
    && chown -R meshcontrol:meshcontrol /app \
    && chmod -R 755 /app

USER meshcontrol:meshcontrol

EXPOSE 8080 8081

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT [ "sh", "-c", "java ${JAVA_OPTS} -jar app.jar" ]
