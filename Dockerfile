FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /app

ENV MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=512m"
ENV MAVEN_CLI_OPTS="-B -DskipTests -Dmaven.test.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Dspotbugs.skip=true"

COPY pom.xml .
COPY checkstyle.xml .
COPY pmd.xml .
COPY spotbugs-exclude.xml .
COPY owasp-suppressions.xml .

COPY common/pom.xml ./common/pom.xml
COPY persistence/pom.xml ./persistence/pom.xml
COPY dal/pom.xml ./dal/pom.xml
COPY config/pom.xml ./config/pom.xml
COPY anomaly/pom.xml ./anomaly/pom.xml
COPY trace/pom.xml ./trace/pom.xml
COPY alert/pom.xml ./alert/pom.xml
COPY profiler/pom.xml ./profiler/pom.xml
COPY metrics/pom.xml ./metrics/pom.xml
COPY logging/pom.xml ./logging/pom.xml
COPY storage/pom.xml ./storage/pom.xml
COPY core/pom.xml ./core/pom.xml
COPY bootstrap/pom.xml ./bootstrap/pom.xml

RUN mvn $MAVEN_CLI_OPTS dependency:go-offline

COPY common/src ./common/src
COPY persistence/src ./persistence/src
COPY dal/src ./dal/src
COPY config/src ./config/src
COPY anomaly/src ./anomaly/src
COPY trace/src ./trace/src
COPY alert/src ./alert/src
COPY profiler/src ./profiler/src
COPY metrics/src ./metrics/src
COPY logging/src ./logging/src
COPY storage/src ./storage/src
COPY core/src ./core/src
COPY bootstrap/src ./bootstrap/src

RUN mvn $MAVEN_CLI_OPTS clean package

FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache curl tzdata ca-certificates && \
    rm -rf /var/cache/apk/*

ENV TZ=UTC
ENV JAVA_OPTS=""
ENV APP_OPTS=""
ENV SPRING_PROFILES_ACTIVE=prod

WORKDIR /app

RUN addgroup -S monitoring && adduser -S monitoring -G monitoring && \
    mkdir -p /app/logs /app/config && \
    chown -R monitoring:monitoring /app

COPY --from=builder /app/bootstrap/target/*.jar /app/monitoring-platform.jar

RUN java -Djarmode=layertools -jar monitoring-platform.jar extract

USER monitoring

EXPOSE 8080 8081

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT [ "sh", "-c", "java $JAVA_OPTS $APP_OPTS \
    -Djava.security.egd=file:/dev/./urandom \
    -Dfile.encoding=UTF-8 \
    -Dspring.profiles.active=$SPRING_PROFILES_ACTIVE \
    -jar /app/monitoring-platform.jar" ]
