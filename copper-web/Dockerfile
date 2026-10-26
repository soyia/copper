# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

ARG COPPER_VERSION
ARG COPPER_REVISION
ARG COPPER_BUILD_TIME

FROM node:22.22.3-alpine3.22@sha256:cd7807368cf24826297cbad5dca1a44972ccfd770647db52a8c7589eb4599ac8 AS build

ARG COPPER_VERSION
ARG COPPER_REVISION
ARG COPPER_BUILD_TIME

ENV PNPM_HOME=/pnpm \
    PATH=/pnpm:$PATH \
    COPPER_VERSION=$COPPER_VERSION \
    COPPER_REVISION=$COPPER_REVISION \
    COPPER_BUILD_TIME=$COPPER_BUILD_TIME

WORKDIR /app

RUN corepack enable \
    && corepack prepare pnpm@9.15.7 --activate \
    && test "$(pnpm --version)" = "9.15.7"

COPY package.json pnpm-lock.yaml ./
RUN --mount=type=cache,id=copper-web-pnpm,target=/pnpm/store \
    pnpm config set store-dir /pnpm/store \
    && pnpm install --frozen-lockfile

COPY . .
RUN pnpm build \
    && pnpm build:metadata

# Copper security rebuild of the formal upstream Caddy release. The checked-in
# module graph pins patched dependencies without changing the upstream version.
FROM golang:1.27.3-alpine3.23@sha256:754d5200fc3edae76d3b6598a9a3fac7e74981ee6a5413ab955025f185753ef2 AS caddy-build

ENV GOTOOLCHAIN=local
WORKDIR /build/caddy
COPY docker/caddy/go.mod docker/caddy/go.sum ./
RUN --mount=type=cache,id=copper-caddy-mod,target=/go/pkg/mod \
    test "$(go env GOVERSION)" = "go1.27.3" \
    && go mod download
COPY docker/caddy/main.go ./
RUN --mount=type=cache,id=copper-caddy-mod,target=/go/pkg/mod \
    --mount=type=cache,id=copper-caddy-build,target=/root/.cache/go-build \
    go mod verify \
    && CGO_ENABLED=0 go build -mod=readonly -trimpath -buildvcs=false \
        -ldflags="-s -w -X github.com/caddyserver/caddy/v2.CustomVersion=v2.11.7+copper.security.1" \
        -o /out/caddy .

FROM caddy:2.11.7-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f

ENV CADDY_VERSION=v2.11.7+copper.security.1

ARG COPPER_VERSION
ARG COPPER_REVISION
ARG COPPER_BUILD_TIME
ARG COPPER_UID=10001
ARG COPPER_GID=10001

LABEL org.opencontainers.image.title="Copper Web" \
      org.opencontainers.image.description="Copper Web with Caddy 2.11.7 rebuilt from locked security-patched dependencies" \
      org.opencontainers.image.source="https://github.com/soyia/copper_web" \
      org.opencontainers.image.version=$COPPER_VERSION \
      org.opencontainers.image.revision=$COPPER_REVISION \
      org.opencontainers.image.created=$COPPER_BUILD_TIME

COPY --from=caddy-build /out/caddy /usr/bin/caddy

RUN apk add --no-cache libcrypto3=3.5.9-r0 libssl3=3.5.9-r0 zlib=1.3.2-r1 \
        ca-certificates=20261020-r0 ca-certificates-bundle=20261020-r0 \
    && apk del --no-network curl \
    && addgroup -g "${COPPER_GID}" copper \
    && adduser -D -H -u "${COPPER_UID}" -G copper copper \
    && mkdir -p /config/caddy /data/caddy /srv /usr/share/copper \
    && chown -R copper:copper /config /data /srv /usr/share/copper

COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build --chown=copper:copper /app/dist /srv
COPY --from=build --chown=copper:copper /app/build-metadata /usr/share/copper

USER copper:copper
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=6 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
