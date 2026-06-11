FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B -q

COPY src ./src

RUN mvn clean package -DskipTests -Pnative,aot -B -q \
    && mvn spring-boot:process-aot -B -q \
    && mvn clean package -DskipTests -B -q

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

RUN java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh -jar app.jar || true

USER appuser

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -XX:SharedArchiveFile=app.jsa"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
