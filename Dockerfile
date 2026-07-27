# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S cleveft && adduser -S cleveft -G cleveft \
 && mkdir -p /var/cleveft/audio && chown -R cleveft:cleveft /var/cleveft
COPY --from=build /build/target/*.jar app.jar
USER cleveft

EXPOSE 8082
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75" \
    AUDIO_STORAGE_PATH=/var/cleveft/audio
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
