# syntax=docker/dockerfile:1
# Build context is the repository root (see docker-compose.yml):  docker build -f inventory-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY resilience-core/pom.xml resilience-core/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline -pl inventory-service -am || true
COPY common common
COPY resilience-core resilience-core
COPY inventory-service inventory-service
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests -pl inventory-service -am package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/inventory-service/target/inventory-service.jar app.jar
ENV SERVER_PORT=8081     JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
