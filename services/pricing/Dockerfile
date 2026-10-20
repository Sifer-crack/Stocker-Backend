# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew && ./gradlew :services:pricing:bootJar --no-daemon
# Downloads Firefox for the ingest/ module (app.ingest.*) using Gradle's real runtime
# classpath - a Boot jar's BOOT-INF/lib nesting means `java -cp app.jar ...` cannot run
# Playwright's install CLI directly. Firefox, not Chromium: verified against the live sites
# (Chromium hits a Cloudflare challenge / HTTP2 block on all three chains) - see
# ingest/config/PlaywrightConfig javadoc and INGEST_MODULE.md.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright
RUN ./gradlew :services:pricing:installPlaywrightBrowsers --no-daemon

FROM eclipse-temurin:25-jre
# curl: /healthz healthcheck. Remaining packages: headless Firefox runtime deps for the
# ingest/ module (Playwright).
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    libgtk-3-0 libx11-xcb1 libasound2t64 libdbus-glib-1-2 libxt6 libpci3 \
    fonts-liberation libgcc-s1 libc6 libstdc++6 \
    && rm -rf /var/lib/apt/lists/*
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright
# Playwright.create() would otherwise download Chromium and WebKit (~900 MB) on every container start;
# only the Firefox installed in the build stage is used.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY --from=build /opt/ms-playwright /opt/ms-playwright
WORKDIR /app
COPY --from=build /workspace/services/pricing/build/libs/*.jar app.jar
EXPOSE 8084
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8084/healthz || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
