FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src src

RUN ./mvnw clean package -DskipTests -B -q && \
    mv target/battle-platform-*.jar target/app.jar

FROM ubuntu:22.04-slim

LABEL maintainer="battle-platform-team"
LABEL description="跨服争霸赛对战平台 - Battle Platform"

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
ENV APP_HOME=/app
ENV TZ=Asia/Shanghai

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    libssl3 \
    netbase \
    procps \
    tzdata \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/java \
    && curl -SL "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3%2B9/OpenJDK21U-jre_x64_linux_hotspot_21.0.3_9.tar.gz" \
    | tar -xzC /opt/java \
    && mv /opt/java/jdk-21.0.3+9-jre /opt/java/openjdk \
    && java -version

RUN apt-get update && apt-get install -y --no-install-recommends \
    libc6 \
    libgcc-s1 \
    libstdc++6 \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -g 1000 app \
    && useradd -u 1000 -g app -s /bin/sh -m app

RUN mkdir -p ${APP_HOME} /data/logs /data/replay \
    && chown -R app:app ${APP_HOME} /data

WORKDIR ${APP_HOME}

COPY --from=builder /app/target/app.jar ${APP_HOME}/app.jar

ENV JAVA_OPTS="-server \
    -Xms2g \
    -Xmx4g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=100 \
    -XX:+PrintGCDetails \
    -XX:+PrintGCTimeStamps \
    -XX:+PrintGCDateStamps \
    -Xlog:gc*,gc+age=trace,safepoint:file=/dev/stdout:utctime,level,tags \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/data/logs/heapdump.hprof \
    -Djava.net.preferIPv4Stack=true \
    -Dio.netty.epoll.nativeEpoll=true \
    -Dio.netty.native.workdir=/tmp \
    -Djava.security.egd=file:/dev/./urandom \
    -Duser.timezone=Asia/Shanghai \
    -Dspring.profiles.active=prod"

EXPOSE 8080 9090

USER app

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

CMD ["sh", "-c", "java ${JAVA_OPTS} -jar ${APP_HOME}/app.jar"]
