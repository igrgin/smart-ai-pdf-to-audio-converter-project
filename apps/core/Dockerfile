# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-25-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY apps/core/pom.xml apps/core/pom.xml
RUN ./mvnw --batch-mode --no-transfer-progress -pl apps/core -am dependency:go-offline
COPY apps/core/src apps/core/src
RUN ./mvnw --batch-mode --no-transfer-progress -pl apps/core -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN apk upgrade --no-cache \
    && apk add --no-cache clamav qpdf \
    && freshclam --no-warnings \
    && addgroup -S platform \
    && adduser -S -G platform -u 10001 platform
WORKDIR /app
COPY --from=build --chown=platform:platform /workspace/apps/core/target/platform-core-exec.jar /app/platform-core.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/platform-core.jar"]
