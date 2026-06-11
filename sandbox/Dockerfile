FROM eclipse-temurin:21-jdk-alpine AS runtime

RUN apk add --no-cache docker-cli bash sudo shadow

RUN groupadd -r sandbox && useradd -r -g sandbox -m sandboxuser \
    && echo "sandboxuser ALL=(ALL) NOPASSWD: /usr/bin/docker" >> /etc/sudoers

WORKDIR /sandbox

RUN mkdir -p /sandbox/tmp /sandbox/workspace && chown -R sandboxuser:sandbox /sandbox

COPY --chown=sandboxuser:sandbox src/main/resources/sandbox/* ./scripts/ 2>/dev/null || true

RUN chmod +x ./scripts/*.sh 2>/dev/null || true

USER sandboxuser

EXPOSE 8081

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=60.0 -XX:+UseContainerSupport"
ENV DOCKER_HOST="unix:///var/run/docker.sock"
ENV SANDBOX_TIMEOUT_MS="30000"
ENV SANDBOX_MEMORY_LIMIT_MB="512"

VOLUME ["/sandbox/tmp", "/var/run/docker.sock"]

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp /sandbox com.exam.sandbox.SandboxService"]
