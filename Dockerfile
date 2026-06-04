FROM node:20-alpine AS frontend-build

WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:17-jdk AS app-build

WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jre-alpine AS runtime

RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

COPY --from=app-build /build/target/flow-platform-*.jar app.jar
COPY --from=frontend-build /build/src/main/resources/static ./static

RUN mkdir -p /data/flow-platform/uploads /var/log/flow-platform && \
    chown -R app:app /app /data/flow-platform /var/log/flow-platform

USER app

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-Xms256m", "-Xmx512m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
