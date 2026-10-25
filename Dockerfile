# JAR is built on the host by build.sh before docker build is invoked.
# This keeps Docker free of network dependencies and avoids QEMU/TLS flakiness
# when cross-compiling for linux/amd64 on Apple Silicon.
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

COPY app.jar app.jar

# /data is the external mount point for all persistent files:
#   feed-store.db       — SQLite database
#   route-config.json   — route enable/disable and schedule settings
#   ai-config.json      — AI platforms and the enabled flag
#   feishu-config.json  — Feishu webhook and per-topic delivery rules
#   credential-config.json — saved Bilibili / X cookies
RUN mkdir /data
VOLUME /data

EXPOSE 8080

# JAVA_OPTS: pass extra JVM flags at runtime (e.g. -Xmx512m)
ENV JAVA_OPTS=""

# Use exec so Java becomes PID 1 and receives OS signals cleanly
ENTRYPOINT ["sh", "-c", \
  "exec java $JAVA_OPTS \
   -Dserver.port=8080 \
   -Dfetch-news.db=/data/feed-store.db \
   -Dfetch-news.route-config=/data/route-config.json \
   -Dfetch-news.ai-config=/data/ai-config.json \
   -Dfetch-news.feishu-config=/data/feishu-config.json \
   -Dfetch-news.credential-config=/data/credential-config.json \
   -jar app.jar"]
