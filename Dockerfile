FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .
COPY card-game-common/pom.xml ./card-game-common/
COPY card-game-netty/pom.xml ./card-game-netty/
COPY card-game-room/pom.xml ./card-game-room/
COPY card-game-deck/pom.xml ./card-game-deck/
COPY card-game-battle/pom.xml ./card-game-battle/
COPY card-game-map/pom.xml ./card-game-map/
COPY card-game-ai/pom.xml ./card-game-ai/
COPY card-game-save/pom.xml ./card-game-save/
COPY card-game-replay/pom.xml ./card-game-replay/
COPY card-game-rank/pom.xml ./card-game-rank/
COPY card-game-server/pom.xml ./card-game-server/

RUN mvn dependency:go-offline -B

COPY card-game-common/src ./card-game-common/src
COPY card-game-netty/src ./card-game-netty/src
COPY card-game-room/src ./card-game-room/src
COPY card-game-deck/src ./card-game-deck/src
COPY card-game-battle/src ./card-game-battle/src
COPY card-game-map/src ./card-game-map/src
COPY card-game-ai/src ./card-game-ai/src
COPY card-game-save/src ./card-game-save/src
COPY card-game-replay/src ./card-game-replay/src
COPY card-game-rank/src ./card-game-rank/src
COPY card-game-server/src ./card-game-server/src

RUN mvn clean package -DskipTests -pl card-game-server -am -B -q \
    && cp card-game-server/target/card-game-server-1.0.0.jar /app/app.jar

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && apk del tzdata

WORKDIR /app

COPY --from=builder /app/app.jar /app/app.jar

ENV JAVA_OPTS=" \
    -XX:+UseG1GC \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=75.0 \
    -XX:MaxGCPauseMillis=200 \
    -XX:G1HeapRegionSize=16m \
    -XX:+UseStringDeduplication \
    -XX:+OptimizeStringConcat \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/var/log/dumps/ \
    -Xlog:gc*,gc+age=trace,gc+ergo=trace:file=/var/log/gc.log:time,level,tags:filecount=5,filesize=100M \
    -Dcom.sun.management.jmxremote \
    -Dcom.sun.management.jmxremote.port=9010 \
    -Dcom.sun.management.jmxremote.rmi.port=9010 \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.local.only=false \
    -Djava.rmi.server.hostname=127.0.0.1 \
    -Dfile.encoding=UTF-8 \
    -Dsun.net.inetaddr.ttl=60 \
    -Dsun.net.inetaddr.negative.ttl=10 \
    -Djdk.tls.ephemeralDHKeySize=2048 \
    -Djdk.nio.maxCachedBufferSize=262144 \
    -Dio.netty.allocator.type=pooled \
    -Dio.netty.allocator.numDirectArenas=16 \
    -Dio.netty.allocator.numHeapArenas=16 \
    -Dio.netty.allocator.normalCacheSize=512 \
    -Dio.netty.eventLoopThreads=0"

ENV SPRING_PROFILES_ACTIVE=prod

RUN mkdir -p /var/log/dumps /var/log

EXPOSE 8080 9000 9010

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
