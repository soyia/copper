#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

command_name="${1:-help}"

if [[ "$command_name" != "help" && "$command_name" != "-h" && "$command_name" != "--help" ]]; then
  require_root
fi

case "$command_name" in
  prepare)
    exec "$SCRIPT_DIR/prepare-host.sh"
    ;;
  config)
    validate_tools
    validate_environment
    compose_cmd config --quiet
    note "Compose 配置有效"
    ;;
  up|start)
    validate_tools
    validate_environment
    "$SCRIPT_DIR/storage-capacity.sh" >/dev/null
    if [[ "$(env_value COPPER_DEPLOY_MODE || true)" == "lan" ]]; then
      "$SCRIPT_DIR/lan-access.py" preflight --env-file "$ENV_FILE" >/dev/null
      "$SCRIPT_DIR/lan-firewall.sh" apply >/dev/null
    fi
    compose_cmd config --quiet
    compose_cmd up -d --remove-orphans --wait --wait-timeout 240
    note "服务已启动并通过 Compose 健康门槛"
    ;;
  restart)
    validate_tools
    validate_environment
    "$SCRIPT_DIR/storage-capacity.sh" >/dev/null
    if [[ "$(env_value COPPER_DEPLOY_MODE || true)" == "lan" ]]; then
      "$SCRIPT_DIR/lan-access.py" preflight --env-file "$ENV_FILE" >/dev/null
      "$SCRIPT_DIR/lan-firewall.sh" apply >/dev/null
    fi
    compose_cmd config --quiet
    compose_cmd restart
    compose_cmd up -d --remove-orphans --wait --wait-timeout 240
    note "服务已重启并重新通过 Compose 健康门槛"
    ;;
  stop)
    validate_tools
    validate_environment
    compose_cmd stop
    note "服务已停止，数据目录保持不变"
    ;;
  down|uninstall)
    validate_tools
    validate_environment
    compose_cmd down --remove-orphans
    note "容器与网络已移除；数据库、附件、配置、凭据和备份均已保留"
    ;;
  status)
    validate_tools
    validate_environment
    compose_cmd ps
    ;;
  logs)
    validate_tools
    validate_environment
    shift
    compose_cmd logs --tail "${COPPER_LOG_TAIL:-200}" "$@" | "$SCRIPT_DIR/log-redact.py"
    ;;
  help|-h|--help)
    cat <<'EOF'
用法：sudo scripts/compose.sh <prepare|config|up|stop|restart|status|logs|uninstall>

普通命令永远不会删除 /etc/copper、/var/lib/copper 或 /var/backups/copper。
永久删除只能使用 scripts/permanently-delete-data.sh。
EOF
    ;;
  *)
    die "未知命令 $command_name"
    ;;
esac
