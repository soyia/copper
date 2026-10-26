#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
require_command systemctl

service_source="$ROOT_DIR/config/systemd/copper-backup.service"
systemd_dir="${COPPER_SYSTEMD_DIR:-/etc/systemd/system}"
if [[ "$systemd_dir" != "/etc/systemd/system" ]]; then
  [[ "${COPPER_TEST_MODE:-0}" == "1" && "$systemd_dir" == /tmp/copper-m2-* ]] \
    || die "COPPER_SYSTEMD_DIR 只允许在 M2 隔离测试中指向 /tmp/copper-m2-*"
fi
service_target="$systemd_dir/copper-backup.service"
timer_target="$systemd_dir/copper-backup.timer"

schedule_values() {
  local daily_time random_minutes full_weekday
  daily_time="$(env_value COPPER_BACKUP_DAILY_TIME || true)"
  daily_time="${daily_time:-02:30}"
  random_minutes="$(env_value COPPER_BACKUP_RANDOM_DELAY_MINUTES || true)"
  random_minutes="${random_minutes:-30}"
  full_weekday="$(env_value COPPER_BACKUP_FULL_WEEKDAY || true)"
  full_weekday="${full_weekday:-7}"
  [[ "$daily_time" =~ ^([01][0-9]|2[0-3]):([0-5][0-9])$ ]] \
    || die "COPPER_BACKUP_DAILY_TIME 必须为 24 小时制 HH:MM"
  [[ "$random_minutes" =~ ^[0-9]+$ && "$random_minutes" -le 1440 ]] \
    || die "COPPER_BACKUP_RANDOM_DELAY_MINUTES 必须为 0-1440"
  [[ "$full_weekday" =~ ^[1-7]$ ]] || die "COPPER_BACKUP_FULL_WEEKDAY 必须为 1-7（周一到周日）"
  printf '%s\t%s\t%s\n' "$daily_time" "$random_minutes" "$full_weekday"
}

write_timer() {
  local output="$1" daily_time="$2" random_minutes="$3"
  {
    echo '[Unit]'
    echo 'Description=Run Copper weekly-full daily-incremental encrypted backup chain'
    echo
    echo '[Timer]'
    printf 'OnCalendar=*-*-* %s:00\n' "$daily_time"
    printf 'RandomizedDelaySec=%sm\n' "$random_minutes"
    echo 'Persistent=true'
    echo 'Unit=copper-backup.service'
    echo
    echo '[Install]'
    echo 'WantedBy=timers.target'
  } > "$output"
}

install_schedule() {
  local values daily_time random_minutes full_weekday generated_timer
  if [[ "${COPPER_BACKUP_SCHEDULE_TEST_NO_ENABLE:-0}" == "1" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" && "$systemd_dir" == /tmp/copper-m2-* ]] \
      || die "COPPER_BACKUP_SCHEDULE_TEST_NO_ENABLE 只允许用于 M2 隔离测试"
    require_command systemd-analyze
  else
    [[ -x /usr/local/sbin/copperctl ]] || die "未安装 /usr/local/sbin/copperctl；先运行 copperctl install"
  fi
  "$ROOT_DIR/scripts/backup-key.sh" audit >/dev/null
  "$ROOT_DIR/scripts/backup-key.sh" assert-ready --require-restore-test >/dev/null
  [[ -s "$(backup_dir_value)/last-success.json" ]] || die "尚无成功整站备份；先手工创建并完成客户离线验证"
  [[ -s "$(backup_dir_value)/chain-state.json" ]] || die "尚无可续接的全量备份链状态；先运行 copperctl backup create --type full"
  values="$(schedule_values)"
  IFS=$'\t' read -r daily_time random_minutes full_weekday <<< "$values"
  generated_timer="$(mktemp)"
  trap 'rm -f "${generated_timer:-}"' EXIT
  write_timer "$generated_timer" "$daily_time" "$random_minutes"
  install -d -o root -g root -m 0755 "$systemd_dir"
  install -o root -g root -m 0644 "$service_source" "$service_target"
  install -o root -g root -m 0644 "$generated_timer" "$timer_target"
  if [[ "${COPPER_BACKUP_SCHEDULE_TEST_NO_ENABLE:-0}" == "1" ]]; then
    python3 - "$service_target" "$ROOT_DIR/copperctl" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
content = path.read_text(encoding="utf-8")
expected = "ExecStart=/usr/local/sbin/copperctl backup create --purpose scheduled"
replacement = f"ExecStart={sys.argv[2]} backup create --purpose scheduled"
if content.count(expected) != 1:
    raise SystemExit("isolated systemd service does not contain the expected production ExecStart")
path.write_text(content.replace(expected, replacement), encoding="utf-8")
PY
  fi
  rm -f "$generated_timer"
  trap - EXIT
  if [[ "${COPPER_BACKUP_SCHEDULE_TEST_NO_ENABLE:-0}" == "1" ]]; then
    systemd-analyze verify "$service_target" "$timer_target"
    note "隔离测试：systemd unit 已生成并通过校验，未写入或启用系统任务"
    return
  fi
  systemctl daemon-reload
  systemctl enable --now copper-backup.timer
  note "备份链计时器已安装：每日 $daily_time，随机延迟 0-${random_minutes} 分钟；每周第 $full_weekday 天全量，其余日期增量"
  note "私钥不在服务器上；任务只使用已登记并完成离线备份验证的客户 age 公钥"
  systemctl list-timers copper-backup.timer --no-pager
}

disable_schedule() {
  if [[ "$systemd_dir" != "/etc/systemd/system" ]]; then
    rm -f -- "$service_target" "$timer_target"
    note "隔离测试的备份 unit 已移除；未操作主机 systemd"
    return
  fi
  systemctl disable --now copper-backup.timer >/dev/null 2>&1 || true
  rm -f -- "$service_target" "$timer_target"
  systemctl daemon-reload
  note "每日备份计时器已禁用并移除；已有备份未删除"
}

status_schedule() {
  local values daily_time random_minutes full_weekday
  values="$(schedule_values)"
  IFS=$'\t' read -r daily_time random_minutes full_weekday <<< "$values"
  echo "配置：每日 $daily_time，随机延迟 0-${random_minutes} 分钟；每周第 $full_weekday 天全量，其余日期增量"
  if [[ "$systemd_dir" != "/etc/systemd/system" ]]; then
    [[ -s "$service_target" && -s "$timer_target" ]] || die "隔离测试 unit 尚未生成"
    systemd-analyze verify "$service_target" "$timer_target"
    echo "隔离测试 unit 已生成但未启用"
    return
  fi
  systemctl status copper-backup.timer --no-pager
  systemctl list-timers copper-backup.timer --no-pager
  if [[ -f "$(required_env_value COPPER_VAR_DIR)/backup-jobs/status.json" ]]; then
    "$ROOT_DIR/scripts/backup-status.sh"
  fi
}

case "${1:-}" in
  install)
    [[ $# -eq 1 ]] || { echo "错误：backup schedule install 不接受额外参数" >&2; exit 2; }
    install_schedule
    ;;
  disable)
    [[ $# -eq 1 ]] || { echo "错误：backup schedule disable 不接受额外参数" >&2; exit 2; }
    disable_schedule
    ;;
  status)
    [[ $# -eq 1 ]] || { echo "错误：backup schedule status 不接受额外参数" >&2; exit 2; }
    status_schedule
    ;;
  *)
    echo "错误：backup schedule 支持 install、disable 或 status" >&2
    exit 2
    ;;
esac
