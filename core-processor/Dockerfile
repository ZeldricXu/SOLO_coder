FROM eclipse-temurin:17-jre-alpine AS base

LABEL org.opencontainers.image.title="core-processor"
LABEL org.opencontainers.image.description="Core Processor Module - Task Scheduling and Execution Management"
LABEL org.opencontainers.image.vendor="Parking Platform"

WORKDIR /app

RUN apk add --no-cache curl tzdata \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

RUN addgroup -S appgroup \
    && adduser -S appuser -G appgroup

USER appuser

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError"
ENV SPRING_PROFILES_ACTIVE=local

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8081/actuator/health || exit 1

COPY target/*.jar app.jar

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
