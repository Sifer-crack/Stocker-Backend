# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew && ./gradlew :services:analytics:bootJar --no-daemon

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/analytics/build/libs/*.jar app.jar
EXPOSE 8086
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8086/healthz || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
