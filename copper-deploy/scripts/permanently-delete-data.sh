#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
[[ "${1:-}" == "--site-id" && -n "${2:-}" ]] || die "用法：$0 --site-id <站点标识>"
requested_site_id="$2"
configured_site_id="$(required_env_value COPPER_SITE_ID)"
[[ "$requested_site_id" == "$configured_site_id" ]] || die "站点标识不匹配，拒绝删除"

assert_safe_paths
etc_dir="$(required_env_value COPPER_ETC_DIR)"
var_dir="$(required_env_value COPPER_VAR_DIR)"
backup_dir="$(backup_dir_value)"
restore_root="$(restore_root_value)"

echo "将永久删除站点 $configured_site_id 的数据库、附件、日志、证书、配置、凭据、备份、旁路恢复和回滚副本。"
if [[ "$(env_value COPPER_REMOTE_ACCESS_PROVIDER || true)" == "cloudflare" ]]; then
  echo "本机 Cloudflare connector 和 Tunnel token 也会删除；Cloudflare Dashboard 中的 Tunnel、Public Hostname 和 Access 策略不会自动删除。"
fi
echo "该操作不可撤销，普通卸载不需要执行本脚本。"
printf '请输入 DELETE %s 继续：' "$configured_site_id"
IFS= read -r confirmation
[[ "$confirmation" == "DELETE $configured_site_id" ]] || die "二次确认不匹配，未删除任何数据"

validate_tools
validate_environment
cloudflare_cleanup_pending=0
if [[ "$(env_value COPPER_REMOTE_ACCESS_PROVIDER || true)" == "cloudflare" ]]; then
  "$SCRIPT_DIR/remote-access-cloudflare.py" --env-file "$ENV_FILE" --root "$ROOT_DIR" \
    remove --confirm "$configured_site_id:REMOVE-CLOUDFLARE"
  cloudflare_cleanup_pending=1
fi
compose_cmd down --remove-orphans
if [[ "$(env_value COPPER_DEPLOY_MODE || true)" == "lan" ]]; then
  "$SCRIPT_DIR/lan-firewall.sh" remove
fi
if [[ "${COPPER_TEST_MODE:-0}" != "1" ]]; then
  "$SCRIPT_DIR/host-autostart.sh" disable
  "$SCRIPT_DIR/monitor-schedule.sh" disable
  "$SCRIPT_DIR/backup-schedule.sh" disable
  "$SCRIPT_DIR/ip-tls.sh" disable-timer
fi

if [[ -d "$restore_root" ]]; then
  while IFS= read -r -d '' restore_env; do
    restore_directory="$(dirname "$restore_env")"
    restore_project="$(awk -F= '$1 == "COPPER_PROJECT_NAME" {print substr($0, index($0, "=") + 1); exit}' "$restore_env")"
    [[ "$restore_project" =~ ^copper-restore-[a-z0-9][a-z0-9-]{7,47}$ ]] || die "旁路恢复项目名不安全：$restore_project"
    if [[ -s "$restore_directory/etc/secrets/copper_db_password" && -s "$restore_directory/etc/secrets/mysql_root_password" ]]; then
      docker compose --env-file "$restore_env" -f "$ROOT_DIR/compose.yaml" -f "$ROOT_DIR/compose.external-proxy.yaml" \
        -p "$restore_project" down --remove-orphans --timeout 45
    else
      while IFS= read -r container_id; do
        [[ -z "$container_id" ]] || docker rm -f "$container_id"
      done < <(docker ps -aq --filter "label=com.docker.compose.project=$restore_project")
      while IFS= read -r network_id; do
        [[ -z "$network_id" ]] || docker network rm "$network_id"
      done < <(docker network ls -q --filter "label=com.docker.compose.project=$restore_project")
    fi
  done < <(find "$restore_root" -mindepth 2 -maxdepth 2 -type f -name copper.env -print0)
fi

sudo rm -rf --one-file-system -- "$var_dir"
sudo rm -rf --one-file-system -- "$etc_dir"
sudo rm -rf --one-file-system -- "$backup_dir"
sudo rm -rf --one-file-system -- "$restore_root"
note "站点 $configured_site_id 的持久数据和配置已永久删除"
if [[ "$cloudflare_cleanup_pending" == "1" ]]; then
  note "Cloudflare 云端清理仍待客户执行：删除 Tunnel、Public Hostname 和 Access 应用/策略"
fi
