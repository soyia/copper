#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
command_name="${1:-status}"
unit_source="$ROOT_DIR/config/systemd/copper.service"
systemd_dir="${COPPER_SYSTEMD_DIR:-/etc/systemd/system}"
unit_target="$systemd_dir/copper.service"

validate_test_target() {
  if [[ "$systemd_dir" != "/etc/systemd/system" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" && ( "$systemd_dir" == /tmp/copper-m5-* || "$systemd_dir" == /opt/copper-m5-* ) ]] \
      || die "隔离自启动 unit 只允许位于 /tmp/copper-m5-* 或 /opt/copper-m5-*"
  fi
}

install_unit() {
  validate_test_target
  [[ -f "$unit_source" ]] || die "缺少 Copper 主服务 unit：$unit_source"
  install -d -o root -g root -m 0755 "$systemd_dir"
  install -o root -g root -m 0644 "$unit_source" "$unit_target"
  if [[ "$systemd_dir" != "/etc/systemd/system" || "${COPPER_HOST_AUTOSTART_TEST_NO_ENABLE:-0}" == "1" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" ]] || die "禁止在生产模式跳过自启动启用"
    require_command systemd-analyze
    systemd-analyze verify "$unit_target"
    note "隔离测试：主服务 unit 已生成并通过校验，未写入或启用主机 systemd"
    return
  fi
  require_supported_platform
  require_command systemctl
  [[ -x /usr/local/sbin/copperctl ]] || die "缺少 /usr/local/sbin/copperctl，不能安装开机自启动"
  systemctl daemon-reload
  systemctl enable --now copper.service >/dev/null
  systemctl is-enabled --quiet copper.service
  systemctl is-active --quiet copper.service
  note "Copper 主服务已启用开机自启动并处于 active"
}

disable_unit() {
  validate_test_target
  if [[ "$systemd_dir" == "/etc/systemd/system" ]]; then
    require_command systemctl
    systemctl disable --now copper.service >/dev/null 2>&1 || true
    rm -f "$unit_target"
    systemctl daemon-reload
  else
    rm -f "$unit_target"
  fi
  note "已移除 Copper 主服务自启动；数据库、附件、配置和备份保持不变"
}

status_unit() {
  validate_test_target
  if [[ "$systemd_dir" != "/etc/systemd/system" ]]; then
    [[ -f "$unit_target" ]] || die "隔离主服务 unit 不存在"
    systemd-analyze verify "$unit_target"
    return
  fi
  require_command systemctl
  systemctl is-enabled copper.service
  systemctl is-active copper.service
}

case "$command_name" in
  install) [[ $# -eq 1 ]] || die "host autostart install 不接受额外参数"; install_unit ;;
  disable) [[ $# -eq 1 ]] || die "host autostart disable 不接受额外参数"; disable_unit ;;
  status) [[ $# -eq 1 ]] || die "host autostart status 不接受额外参数"; status_unit ;;
  *) die "host autostart 只支持 install、status 或 disable" ;;
esac
