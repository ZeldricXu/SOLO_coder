FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src

RUN mvn clean package -DskipTests -Pprod -B

FROM eclipse-temurin:17-jre-alpine AS runtime

ENV APP_NAME=iot-platform
ENV APP_VERSION=1.0.0
ENV APP_HOME=/opt/app
ENV JAVA_OPTS=""
ENV SPRING_PROFILES_ACTIVE=prod

RUN apk add --no-cache tzdata curl bash \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && apk del tzdata

RUN addgroup -S appgroup \
    && adduser -S appuser -G appgroup

WORKDIR ${APP_HOME}

COPY --from=builder /app/target/${APP_NAME}-${APP_VERSION}.jar ${APP_HOME}/app.jar
COPY --from=builder /app/target/classes/application*.yml ${APP_HOME}/config/

RUN chown -R appuser:appgroup ${APP_HOME}

USER appuser

EXPOSE 8080 8443

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT [ "sh", "-c" ]
CMD [ "java $JAVA_OPTS -jar app.jar --spring.profiles.active=$SPRING_PROFILES_ACTIVE" ]
