FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build

COPY pom.xml .
COPY etc/ etc/

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

COPY src/ src/

RUN mvn clean package -DskipTests -B -q

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd -r edgescheduler && useradd -r -g edgescheduler -d /app -s /sbin/nologin edgescheduler

WORKDIR /app

COPY --from=builder /build/target/edge-scheduler-*.jar app.jar

RUN mkdir -p /app/data/offline /app/models /var/log/edge-scheduler && \
    chown -R edgescheduler:edgescheduler /app /var/log/edge-scheduler

USER edgescheduler

EXPOSE 8080 8081

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
    SPRING_PROFILES_ACTIVE=prod \
    TZ=Asia/Shanghai

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]
