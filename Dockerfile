FROM eclipse-temurin:17-jre-alpine AS base

LABEL maintainer="Orchestration Team"
LABEL org.opencontainers.image.title="Task Orchestration Platform"
LABEL org.opencontainers.image.description="Enterprise级依赖任务编排平台"
LABEL org.opencontainers.image.vendor="Orchestration Team"

RUN apk add --no-cache tzdata curl && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE=prod

WORKDIR /app

FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /build

COPY pom.xml .
COPY common/pom.xml common/
COPY persistence/pom.xml persistence/
COPY scheduler/pom.xml scheduler/
COPY sla/pom.xml sla/
COPY skillgraph/pom.xml skillgraph/
COPY monitoring/pom.xml monitoring/
COPY approval/pom.xml approval/
COPY flowdesigner/pom.xml flowdesigner/
COPY storage/pom.xml storage/
COPY tenant/pom.xml tenant/
COPY billing/pom.xml billing/
COPY web/pom.xml web/

RUN mvn dependency:go-offline -B -q

COPY . .

RUN mvn clean package -Pprod -DskipTests -q

FROM base AS final

COPY --from=builder /build/web/target/web-*.jar /app/app.jar

RUN addgroup -S appgroup && adduser -S appuser -G appgroup && \
    chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
