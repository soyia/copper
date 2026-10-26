# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre@sha256:e04fa72be13b257a59fdec6dc14af78c8f80f035d3c1ed4e03e45552a231d4c8

ARG COPPER_VERSION
ARG COPPER_REVISION
ARG COPPER_BUILD_TIME
ARG COPPER_UID=10001
ARG COPPER_GID=10001

LABEL org.opencontainers.image.title="Copper Backend" \
      org.opencontainers.image.description="Copper Backend production application" \
      org.opencontainers.image.source="https://github.com/soyia/copper_backend" \
      org.opencontainers.image.version=$COPPER_VERSION \
      org.opencontainers.image.revision=$COPPER_REVISION \
      org.opencontainers.image.created=$COPPER_BUILD_TIME

# The application uses Java directly as PID 1; the inherited Go supervisor is unused.
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade \
        curl libcurl4t64 openssl libssl3t64 openssl-provider-legacy \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' curl)" ge 8.18.0-1ubuntu2.8 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' libcurl4t64)" ge 8.18.0-1ubuntu2.8 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' openssl)" ge 3.5.5-1ubuntu3.6 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' libssl3t64)" ge 3.5.5-1ubuntu3.6 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' openssl-provider-legacy)" ge 3.5.5-1ubuntu3.6 \
    && rm -rf /var/lib/apt/lists/* \
    && rm -f /usr/bin/pebble \
    && test ! -e /usr/bin/pebble \
    && groupadd --gid "${COPPER_GID}" copper \
    && useradd --uid "${COPPER_UID}" --gid copper --no-create-home --home-dir /app --shell /usr/sbin/nologin copper \
    && mkdir -p /app/data/files /app/data/logs /app/data/secrets /app/data/tmp \
    && chown -R copper:copper /app

WORKDIR /app
COPY --from=build --chown=copper:copper /workspace/target/copper-v2-backend-*.jar /app/copper.jar

ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8191
VOLUME ["/app/data"]

USER copper:copper

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 3 "http://127.0.0.1:${COPPER_PORT}/api/actuator/health/liveness" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.io.tmpdir=/app/data/tmp", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/copper.jar"]
