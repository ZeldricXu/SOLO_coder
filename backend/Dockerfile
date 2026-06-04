FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_OPTS="-Xms1g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENV SPRING_PROFILES_ACTIVE=prod
COPY --from=builder /app/target/protein-viewer-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.profiles.active=$SPRING_PROFILES_ACTIVE -jar app.jar"]
