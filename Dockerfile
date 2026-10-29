# syntax=docker/dockerfile:1.7
#
# ZenithHub: one container running the manager (web UI + supervisor) and one
# Zenith process per instance.
#
#   docker compose up -d        then open http://<host>:8080
#
# ZENITH_RUNTIME=native (default) compiles Zenith with GraalVM: low memory per
# instance, x86-64-v3 CPUs only. ZENITH_RUNTIME=jar runs Zenith on the JVM:
# works on any CPU (including ARM) but uses more memory per instance.
ARG ZENITH_RUNTIME=native

FROM node:24-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM ghcr.io/graalvm/native-image-community:25 AS java
ARG ZENITH_RUNTIME
RUN microdnf install -y findutils git-core && microdnf clean all
WORKDIR /src
COPY . .
# build also regenerates the command docs, which runs Zenith in ./run
RUN --mount=type=cache,target=/root/.gradle mkdir -p run && \
    if [ "$ZENITH_RUNTIME" = "native" ]; then \
      ./gradlew --no-daemon -x test nativeCompile :manager:shadowJar \
      && cp build/native/nativeCompile/ZenithProxy /tmp/ZenithProxy; \
    else \
      ./gradlew --no-daemon -x test shadowJar :manager:shadowJar \
      && cp build/libs/ZenithProxy.jar /tmp/ZenithProxy.jar; \
    fi \
    && mkdir -p /out/zenith \
    && cp /tmp/ZenithProxy* /out/zenith/ \
    && cp manager/build/libs/zenith-manager.jar /out/manager.jar

FROM eclipse-temurin:25-jre AS runtime-base
RUN groupadd --system zenith && useradd --system --gid zenith --home-dir /data zenith \
    && mkdir -p /data && chown zenith:zenith /data
COPY --from=java /out/ /app/
COPY --from=web /web/build /app/web
ENV ZENITH_DATA_DIR=/data \
    ZENITH_WEB_DIR=/app/web \
    ZENITH_WEB_PORT=8080
VOLUME /data
EXPOSE 8080 25565
USER zenith
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xmx160m", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/manager.jar"]

FROM runtime-base AS runtime-native
ENV ZENITH_COMMAND=/app/zenith/ZenithProxy

FROM runtime-base AS runtime-jar
ENV ZENITH_COMMAND="java -Xmx300m -XX:+UseSerialGC --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -jar /app/zenith/ZenithProxy.jar"

FROM runtime-${ZENITH_RUNTIME}
