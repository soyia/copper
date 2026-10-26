#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
require_command systemctl

systemd_dir="${COPPER_SYSTEMD_DIR:-/etc/systemd/system}"
if [[ "$systemd_dir" != "/etc/systemd/system" ]]; then
  [[ "${COPPER_TEST_MODE:-0}" == "1" && ( "$systemd_dir" == /tmp/copper-m4-* || "$systemd_dir" == /opt/copper-m4-* ) ]] \
    || die "COPPER_SYSTEMD_DIR 只允许在 M4 隔离测试中指向 /tmp/copper-m4-* 或 /opt/copper-m4-*"
fi
service_source="$ROOT_DIR/config/systemd/copper-monitor.service"
timer_source="$ROOT_DIR/config/systemd/copper-monitor.timer"
service_target="$systemd_dir/copper-monitor.service"
timer_target="$systemd_dir/copper-monitor.timer"

install_schedule() {
  if [[ "${COPPER_MONITOR_SCHEDULE_TEST_NO_ENABLE:-0}" == "1" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" && "$systemd_dir" != "/etc/systemd/system" ]] \
      || die "COPPER_MONITOR_SCHEDULE_TEST_NO_ENABLE 只允许 M4 隔离测试"
    require_command systemd-analyze
  else
    [[ -x /usr/local/sbin/copperctl ]] || die "未安装 /usr/local/sbin/copperctl；先运行 copperctl install"
  fi
  install -d -o root -g root -m 0755 "$systemd_dir"
  install -o root -g root -m 0644 "$service_source" "$service_target"
  install -o root -g root -m 0644 "$timer_source" "$timer_target"
  if [[ "${COPPER_MONITOR_SCHEDULE_TEST_NO_ENABLE:-0}" == "1" ]]; then
    python3 - "$service_target" "$ROOT_DIR/copperctl" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
content = path.read_text(encoding="utf-8")
expected = "ExecStart=/usr/local/sbin/copperctl monitor check"
if content.count(expected) != 1:
    raise SystemExit("isolated monitor service does not contain the expected production ExecStart")
path.write_text(content.replace(expected, f"ExecStart={sys.argv[2]} monitor check"), encoding="utf-8")
PY
    systemd-analyze verify "$service_target" "$timer_target"
    note "隔离测试：监控 systemd unit 已生成并通过校验，未写入或启用主机任务"
    return
  fi
  systemctl daemon-reload
  systemctl enable --now copper-monitor.timer
  note "本地监控计时器已安装：启动后 2 分钟首次检查，此后每 5 分钟检查"
  systemctl list-timers copper-monitor.timer --no-pager
}

disable_schedule() {
  if [[ "$systemd_dir" != "/etc/systemd/system" ]]; then
    rm -f -- "$service_target" "$timer_target"
    note "隔离测试监控 unit 已移除；未操作主机 systemd"
    return
  fi
  systemctl disable --now copper-monitor.timer >/dev/null 2>&1 || true
  rm -f -- "$service_target" "$timer_target"
  systemctl daemon-reload
  note "本地监控计时器已禁用并移除；监控历史未删除"
}

status_schedule() {
  if [[ "$systemd_dir" != "/etc/systemd/system" ]]; then
    [[ -s "$service_target" && -s "$timer_target" ]] || die "隔离测试 unit 尚未生成"
    systemd-analyze verify "$service_target" "$timer_target"
    echo "隔离测试 unit 已生成但未启用"
    return
  fi
  systemctl status copper-monitor.timer --no-pager
  systemctl list-timers copper-monitor.timer --no-pager
  if [[ -f "$(required_env_value COPPER_VAR_DIR)/monitor/status.json" ]]; then
    "$ROOT_DIR/scripts/monitor.sh" status
  fi
}

case "${1:-}" in
  install) [[ $# -eq 1 ]] || die "monitor schedule install 不接受额外参数"; install_schedule ;;
  disable) [[ $# -eq 1 ]] || die "monitor schedule disable 不接受额外参数"; disable_schedule ;;
  status) [[ $# -eq 1 ]] || die "monitor schedule status 不接受额外参数"; status_schedule ;;
  *) die "monitor schedule 支持 install、disable 或 status" ;;
esac
