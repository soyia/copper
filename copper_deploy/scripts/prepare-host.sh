#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
require_command sudo
require_command openssl

if [[ ! -f "$ENV_FILE" ]]; then
  sudo install -d -o root -g root -m 0750 "$(dirname "$ENV_FILE")"
  sudo install -o root -g root -m 0600 "$ROOT_DIR/config/copper.env.example" "$ENV_FILE"
  note "已创建 $ENV_FILE；请先填写正式域名和前后端不可变镜像摘要"
fi

sudo chown root:root "$ENV_FILE"
sudo chmod 0600 "$ENV_FILE"

assert_safe_paths
etc_dir="$(required_env_value COPPER_ETC_DIR)"
var_dir="$(required_env_value COPPER_VAR_DIR)"
backup_dir="$(backup_dir_value)"
restore_root="$(restore_root_value)"

sudo install -d -o root -g root -m 0750 "$etc_dir" "$etc_dir/secrets"
sudo install -d -o root -g root -m 0700 "$etc_dir/acme"
sudo install -d -o root -g 10001 -m 0750 "$etc_dir/tls"
sudo install -d -o root -g root -m 0700 "$etc_dir/tls/archive"
sudo install -d -o 10001 -g 10001 -m 0700 "$etc_dir/credentials"
sudo install -d -o root -g root -m 0700 "$etc_dir/storage-credentials"
sudo install -d -o root -g root -m 0700 "$etc_dir/notification-credentials"
sudo install -d -o root -g root -m 0700 "$etc_dir/remote-access" "$etc_dir/remote-access/cloudflare"
sudo install -d -o root -g root -m 0750 "$var_dir"
sudo install -d -o 999 -g 999 -m 0700 "$var_dir/mysql"
sudo install -d -o 10001 -g 10001 -m 0750 "$var_dir/backend" "$var_dir/files" "$var_dir/logs"
sudo install -d -o 10001 -g 10001 -m 0750 "$var_dir/backend/runtime"
sudo install -d -o root -g root -m 0700 "$var_dir/backup-keys" "$var_dir/backup-keys/challenges" "$var_dir/backup-jobs" "$var_dir/monitor"
sudo install -d -o root -g root -m 0700 "$var_dir/security"
sudo install -d -o root -g root -m 0700 "$var_dir/operations"
sudo install -d -o root -g root -m 0700 "$var_dir/remote-access"
sudo install -d -o root -g 10001 -m 0750 "$var_dir/time-safety"
sudo install -d -o root -g root -m 0750 "$var_dir/backups" "$var_dir/caddy"
sudo install -d -o root -g 10001 -m 0750 "$var_dir/acme-webroot"
sudo install -d -o 10001 -g 10001 -m 0700 "$var_dir/caddy/data" "$var_dir/caddy/config"
sudo install -d -o root -g root -m 0700 "$backup_dir"
sudo install -d -o root -g root -m 0700 "$restore_root"

generate_secret() {
  local path="$1" owner="$2" group="$3"
  if sudo test -s "$path"; then
    note "保留已有密钥 $path"
    return
  fi
  local temporary
  temporary="$(mktemp)"
  trap 'rm -f "${temporary:-}"' RETURN
  umask 077
  openssl rand -hex 32 > "$temporary"
  sudo install -o "$owner" -g "$group" -m 0400 "$temporary" "$path"
  rm -f "$temporary"
  trap - RETURN
  note "已生成随机密钥 $path"
}

generate_secret "$etc_dir/secrets/copper_db_password" 10001 10001
generate_secret "$etc_dir/secrets/mysql_root_password" root root

note "目录与密钥准备完成；重复执行不会覆盖现有密钥或数据"
