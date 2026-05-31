FROM eclipse-temurin:17-jre-alpine AS base

LABEL org.opencontainers.image.title="api-gateway"
LABEL org.opencontainers.image.description="API Gateway Module - Authentication and Rate Limiting"
LABEL org.opencontainers.image.vendor="Parking Platform"

WORKDIR /app

RUN apk add --no-cache curl tzdata \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

RUN addgroup -S appgroup \
    && adduser -S appuser -G appgroup

FROM base AS builder

RUN apk add --no-cache maven

WORKDIR /build

COPY pom.xml .
COPY common/pom.xml ./common/pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml

RUN mvn dependency:go-offline -B -DskipTests --also-make

COPY common/src ./common/src
COPY api-gateway/src ./api-gateway/src

RUN mvn clean package -DskipTests -pl api-gateway -am -B

FROM base

WORKDIR /app

COPY --from=builder /build/api-gateway/target/*.jar app.jar

RUN chown -R appuser:appgroup /app

USER appuser

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError"
ENV SPRING_PROFILES_ACTIVE=local

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
