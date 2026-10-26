#!/usr/bin/env bash
set -Eeuo pipefail

: "${COPPER_DIRECT_TRADE_DB_URL:?COPPER_DIRECT_TRADE_DB_URL is required}"
: "${COPPER_DIRECT_TRADE_DB_USERNAME:?COPPER_DIRECT_TRADE_DB_USERNAME is required}"
: "${COPPER_DIRECT_TRADE_DB_PASSWORD+x}"

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
runtime="$(mktemp -d "${TMPDIR:-/tmp}/copper-direct-trade.XXXXXX")"

cleanup() { rm -rf "$runtime"; }
trap cleanup EXIT INT TERM

install -d -m 0700 "$runtime/data/files" "$runtime/data/exports" "$runtime/data/tmp" \
  "$runtime/data/logs" "$runtime/data/secrets"

cd "$repo_root"
env \
  COPPER_DIRECT_TRADE_E2E=true \
  COPPER_PORT=8193 \
  COPPER_TIME_ZONE=Asia/Shanghai \
  COPPER_DB_URL="$COPPER_DIRECT_TRADE_DB_URL" \
  COPPER_DB_USERNAME="$COPPER_DIRECT_TRADE_DB_USERNAME" \
  COPPER_DB_PASSWORD="$COPPER_DIRECT_TRADE_DB_PASSWORD" \
  COPPER_FLYWAY_ENABLED=true \
  COPPER_FLYWAY_BASELINE_ON_MIGRATE=false \
  COPPER_DATA_ROOT="$runtime/data" \
  COPPER_FILE_STORAGE_PATH="$runtime/data/files" \
  COPPER_EXPORT_PATH="$runtime/data/exports" \
  COPPER_UPLOAD_TEMP_PATH="$runtime/data/tmp" \
  COPPER_LOG_FILE="$runtime/data/logs/backend.log" \
  COPPER_LOG_LEVEL=INFO \
  COPPER_BOOTSTRAP_USERNAME=admin \
  COPPER_BOOTSTRAP_CREDENTIAL_FILE="$runtime/data/secrets/initial-admin.txt" \
  COPPER_ALLOWED_ORIGINS=http://127.0.0.1:18080 \
  COPPER_COOKIE_SECURE=false \
  COPPER_COOKIE_SAME_SITE=Lax \
  mvn -B -ntp clean -Dtest=DirectTradeMySqlAcceptanceTest test

printf '%s\n' \
  'DIRECT_TRADE_MYSQL=PASS promise=linked dispatch=traceable acceptance=posts_ar_ap return=reversible settlement=bounded inventory=unchanged cleanup=zero_residual'
