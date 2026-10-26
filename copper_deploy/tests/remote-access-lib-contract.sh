#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_root="$(mktemp -d /tmp/copper-cloudflare-lib.XXXXXX)"
trap 'rm -rf "$test_root"' EXIT

etc_dir="$test_root/etc"
var_dir="$test_root/var"
backup_dir="$test_root/backups"
restore_root="$test_root/restores"
mkdir -p "$etc_dir/secrets" "$etc_dir/remote-access/cloudflare" "$var_dir" "$backup_dir" "$restore_root"

digest_a="$(printf 'a%.0s' {1..64})"
digest_b="$(printf 'b%.0s' {1..64})"
digest_c="$(printf 'c%.0s' {1..64})"
token="$(printf 'eyJ'; printf 'A%.0s' {1..120})"
printf '%s\n' "$token" > "$etc_dir/remote-access/cloudflare/tunnel-token"
chmod 0600 "$etc_dir/remote-access/cloudflare/tunnel-token"
printf '%064d\n' 0 > "$etc_dir/secrets/copper_db_password"
printf '%064d\n' 1 > "$etc_dir/secrets/mysql_root_password"

env_file="$etc_dir/copper.env"
{
  printf 'COPPER_SITE_ID=site-001\n'
  printf 'COPPER_PROJECT_NAME=copper\n'
  printf 'COPPER_DOMAIN=erp.customer.test\n'
  printf 'COPPER_DEPLOY_MODE=lan\n'
  printf 'COPPER_TLS_MODE=lan\n'
  printf 'COPPER_LAN_ADDRESS_RESERVED=yes\n'
  printf 'COPPER_LAN_DOMAIN_CONTROL_CONFIRMED=yes\n'
  printf 'COPPER_LAN_NAME_RESOLUTION=dns\n'
  printf 'COPPER_LAN_BIND_IP=192.168.50.20\n'
  printf 'COPPER_LAN_INTERFACE=ens33\n'
  printf 'COPPER_LAN_DNS_SERVER=192.168.50.1\n'
  printf 'COPPER_LAN_ALLOWED_CIDRS=192.168.50.0/24\n'
  printf 'COPPER_IMAGE_DELIVERY=registry\n'
  printf 'COPPER_WEB_IMAGE=example.invalid/web@sha256:%s\n' "$digest_a"
  printf 'COPPER_BACKEND_IMAGE=example.invalid/backend@sha256:%s\n' "$digest_b"
  printf 'COPPER_MYSQL_IMAGE=example.invalid/mysql@sha256:%s\n' "$digest_c"
  printf 'COPPER_ETC_DIR=%s\n' "$etc_dir"
  printf 'COPPER_VAR_DIR=%s\n' "$var_dir"
  printf 'COPPER_BACKUP_DIR=%s\n' "$backup_dir"
  printf 'COPPER_RESTORE_ROOT=%s\n' "$restore_root"
  printf 'COPPER_RESTORE_PROXY_PORT=18080\n'
  printf 'COPPER_REMOTE_ACCESS_PROVIDER=cloudflare\n'
  printf 'COPPER_REMOTE_ACCESS_ENABLED=yes\n'
  printf 'COPPER_CLOUDFLARED_IMAGE=cloudflare/cloudflared@sha256:4f6655284ab3d252b7f28fedb19fe6c8fc82ee5b1295c20ac74d475e5398a52d\n'
  printf 'COPPER_CLOUDFLARE_HOSTNAME=erp.customer.test\n'
  printf 'COPPER_CLOUDFLARE_TOKEN_FILE=%s\n' "$etc_dir/remote-access/cloudflare/tunnel-token"
  printf 'COPPER_CLOUDFLARE_METRICS_PORT=20241\n'
} > "$env_file"
chmod 0600 "$env_file"

export COPPER_TEST_MODE=1
export COPPER_ENV_FILE="$env_file"
source "$ROOT_DIR/scripts/lib.sh"
validate_environment

docker() {
  printf '%s\n' "$*"
}

enabled_command="$(compose_cmd config --quiet)"
grep -Fq "$ROOT_DIR/compose.remote-access-cloudflare.yaml" <<< "$enabled_command"
grep -Fq -- '--profile remote-access-cloudflare' <<< "$enabled_command"

sed -i.bak 's/COPPER_REMOTE_ACCESS_ENABLED=yes/COPPER_REMOTE_ACCESS_ENABLED=no/' "$env_file"
disabled_command="$(compose_cmd config --quiet)"
grep -Fq "$ROOT_DIR/compose.remote-access-cloudflare.yaml" <<< "$disabled_command"
if grep -Fq -- '--profile remote-access-cloudflare' <<< "$disabled_command"; then
  echo "停用状态不得自动激活 cloudflared profile" >&2
  exit 1
fi

printf '%s\n' "$token" > "$etc_dir/escaped-token"
chmod 0600 "$etc_dir/escaped-token"
sed -i.bak "s#COPPER_CLOUDFLARE_TOKEN_FILE=.*#COPPER_CLOUDFLARE_TOKEN_FILE=$etc_dir/remote-access/cloudflare/../../escaped-token#" "$env_file"
if (validate_environment) >/dev/null 2>&1; then
  echo "Cloudflare token 路径不得使用 .. 逃逸 root-only 目录" >&2
  exit 1
fi

echo "remote_access_lib_contract=passed"
