#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
require_command python3
require_command openssl
require_command flock

[[ "${1:-}" == "create" ]] || die "backup-job 只支持 create"
shift
purpose="manual"
backup_type=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --purpose)
      [[ "${2:-}" == "manual" || "${2:-}" == "scheduled" ]] || die "--purpose 只支持 manual 或 scheduled"
      purpose="$2"
      shift 2
      ;;
    --type)
      [[ "${2:-}" == "auto" || "${2:-}" == "full" || "${2:-}" == "incremental" ]] || die "--type 只支持 auto、full 或 incremental"
      backup_type="$2"
      shift 2
      ;;
    *) die "backup create 只接受 --purpose manual|scheduled 和 --type auto|full|incremental" ;;
  esac
done

var_dir="$(required_env_value COPPER_VAR_DIR)"
backup_dir="$(backup_dir_value)"
site_id="$(required_env_value COPPER_SITE_ID)"
state_dir="$var_dir/backup-jobs"
job_tool="$SCRIPT_DIR/backup-job.py"
[[ -x "$job_tool" ]] || die "缺少备份任务状态工具"

install -d -o root -g root -m 0700 "$state_dir"
exec 8>"$state_dir/.job.lock"
flock -n 8 || die "已有备份任务正在运行，请使用 copperctl backup status 查看"

targets="$(env_value COPPER_BACKUP_TARGETS || true)"
targets="${targets:-local}"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
job_id="$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 6)"
stderr_file="$state_dir/.stderr-$job_id"
install -o root -g root -m 0600 /dev/null "$stderr_file"
python3 "$job_tool" --state-dir "$state_dir" start \
  --job-id "$job_id" --purpose "$purpose" --targets "$targets" --started-at "$started_at"

child_pid=""
requested_exit=0
forward_signal() {
  requested_exit="$1"
  if [[ -n "$child_pid" ]]; then
    kill -TERM "$child_pid" >/dev/null 2>&1 || true
  fi
}
trap 'forward_signal 130' INT
trap 'forward_signal 143' TERM

set +e
(
  set -Eeuo pipefail
  "$SCRIPT_DIR/backup-key.sh" assert-ready >/dev/null
  backup_args=(create --purpose "$purpose")
  [[ -z "$backup_type" ]] || backup_args+=(--type "$backup_type")
  "$SCRIPT_DIR/backup.sh" "${backup_args[@]}"
) 2>"$stderr_file" &
child_pid=$!
wait "$child_pid"
backup_status=$?
if [[ "$requested_exit" -ne 0 ]]; then
  wait "$child_pid" >/dev/null 2>&1 || true
  backup_status="$requested_exit"
fi
set -e
child_pid=""
trap - INT TERM
cat "$stderr_file" >&2

finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [[ "$backup_status" -eq 0 ]]; then
  notification_event="$(python3 "$job_tool" --state-dir "$state_dir" finish \
    --job-id "$job_id" --result SUCCESS --finished-at "$finished_at" \
    --last-success "$backup_dir/last-success.json")"
else
  error_code="$(python3 "$job_tool" --state-dir "$state_dir" classify \
    --stderr-file "$stderr_file" --exit-code "$backup_status")"
  notification_event="$(python3 "$job_tool" --state-dir "$state_dir" finish \
    --job-id "$job_id" --result FAILED --error-code "$error_code" --finished-at "$finished_at")"
fi
rm -f -- "$stderr_file"

alert_result="NOT_REQUIRED"
if [[ -n "$notification_event" ]]; then
  alert_mode="$(env_value COPPER_BACKUP_ALERT_MODE || true)"
  alert_mode="${alert_mode:-local}"
  case "$alert_mode" in
    local)
      alert_result="LOCAL_ONLY"
      echo "提醒：备份事件 $notification_event 已写入本地受限状态；未配置外部 Webhook" >&2
      ;;
    webhook)
      set +e
      (
        set -Eeuo pipefail
        timeout="$(env_value COPPER_BACKUP_ALERT_TIMEOUT_SECONDS || true)"
        timeout="${timeout:-10}"
        [[ "$timeout" =~ ^[0-9]+$ && "$timeout" -ge 1 && "$timeout" -le 30 ]] \
          || die "COPPER_BACKUP_ALERT_TIMEOUT_SECONDS 必须为 1-30"
        alert_args=(
          --state-dir "$state_dir" send --site-id "$site_id"
          --url-file "$(required_env_value COPPER_BACKUP_ALERT_WEBHOOK_URL_FILE)"
          --hmac-key-file "$(required_env_value COPPER_BACKUP_ALERT_HMAC_KEY_FILE)"
          --timeout "$timeout"
        )
        if [[ "${COPPER_TEST_MODE:-0}" == "1" ]]; then alert_args+=(--test-mode); fi
        python3 "$job_tool" "${alert_args[@]}" >/dev/null
      )
      send_status=$?
      set -e
      if [[ "$send_status" -eq 0 ]]; then
        alert_result="SENT"
      else
        alert_result="FAILED"
        echo "提醒：备份结果已安全留痕，但外部 Webhook 告警发送失败；运行 copperctl backup status 检查" >&2
      fi
      ;;
    *)
      alert_result="FAILED"
      echo "提醒：COPPER_BACKUP_ALERT_MODE 无效；备份结果已留痕，但外部告警未发送" >&2
      ;;
  esac
fi
if ! python3 "$job_tool" --state-dir "$state_dir" record-alert --job-id "$job_id" --result "$alert_result"; then
  echo "提醒：备份结果已生成，但告警结果状态写入失败；请立即运行 copperctl backup status" >&2
fi

exit "$backup_status"
