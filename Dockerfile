# ─── Build stage ────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

# Copy wrapper and dependency descriptors first — layer cached until these change
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle gradle.properties settings.gradle ./

# Warm up the Gradle dependency cache (failure is OK on first run without sources)
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null; true

# Copy source and build the fat JAR (skip tests — run them in CI separately)
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ─── Runtime stage ───────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

COPY --from=build /build/build/libs/*.jar app.jar

# /data is the external mount point for all persistent files:
#   feed-store.db       — SQLite database
#   route-config.json   — route enable/disable and schedule settings
#   ai-config.json      — Ollama host, model and enabled flag
RUN mkdir /data
VOLUME /data

EXPOSE 8080

# JAVA_OPTS: pass extra JVM flags at runtime (e.g. -Xmx512m)
ENV JAVA_OPTS=""

# Use exec so Java becomes PID 1 and receives OS signals cleanly
ENTRYPOINT ["sh", "-c", \
  "exec java $JAVA_OPTS \
   -Dfetch-news.db=/data/feed-store.db \
   -Dfetch-news.route-config=/data/route-config.json \
   -Dfetch-news.ai-config=/data/ai-config.json \
   -jar app.jar"]
