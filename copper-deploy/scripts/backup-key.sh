#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
require_command python3
require_command age

etc_dir="$(required_env_value COPPER_ETC_DIR)"
var_dir="$(required_env_value COPPER_VAR_DIR)"
backup_dir="$(backup_dir_value)"
site_id="$(required_env_value COPPER_SITE_ID)"
registry="$var_dir/backup-keys/registry.json"
challenge_dir="$var_dir/backup-keys/challenges"
tool="$SCRIPT_DIR/backup-key.py"
[[ -x "$tool" ]] || die "缺少密钥生命周期工具 $tool"

tool_command() {
  python3 "$tool" --registry "$registry" --env-file "$ENV_FILE" --site-id "$site_id" "$@"
}

case "${1:-}" in
  register)
    shift
    handover_sha="" customer="" witness=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --handover-sha256) [[ -n "${2:-}" ]] || die "--handover-sha256 缺少值"; handover_sha="$2"; shift 2 ;;
        --customer-confirmed-by) [[ -n "${2:-}" ]] || die "--customer-confirmed-by 缺少值"; customer="$2"; shift 2 ;;
        --witness-confirmed-by) [[ -n "${2:-}" ]] || die "--witness-confirmed-by 缺少值"; witness="$2"; shift 2 ;;
        *) die "backup key register 未知参数：$1" ;;
      esac
    done
    [[ -n "$handover_sha" && -n "$customer" && -n "$witness" ]] \
      || die "用法：backup key register --handover-sha256 HASH --customer-confirmed-by NAME --witness-confirmed-by NAME"
    tool_command register --handover-sha256 "$handover_sha" \
      --customer-confirmed-by "$customer" --witness-confirmed-by "$witness"
    ;;
  prepare-rotation)
    shift
    recipient="" handover_sha="" customer="" witness=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --new-recipient) [[ -n "${2:-}" ]] || die "--new-recipient 缺少值"; recipient="$2"; shift 2 ;;
        --handover-sha256) [[ -n "${2:-}" ]] || die "--handover-sha256 缺少值"; handover_sha="$2"; shift 2 ;;
        --customer-confirmed-by) [[ -n "${2:-}" ]] || die "--customer-confirmed-by 缺少值"; customer="$2"; shift 2 ;;
        --witness-confirmed-by) [[ -n "${2:-}" ]] || die "--witness-confirmed-by 缺少值"; witness="$2"; shift 2 ;;
        *) die "backup key prepare-rotation 未知参数：$1" ;;
      esac
    done
    [[ -n "$recipient" && -n "$handover_sha" && -n "$customer" && -n "$witness" ]] \
      || die "用法：backup key prepare-rotation --new-recipient AGE1 --handover-sha256 HASH --customer-confirmed-by NAME --witness-confirmed-by NAME"
    tool_command prepare-rotation --new-recipient "$recipient" --handover-sha256 "$handover_sha" \
      --customer-confirmed-by "$customer" --witness-confirmed-by "$witness" --challenge-dir "$challenge_dir"
    ;;
  activate-rotation)
    [[ $# -eq 7 && "$2" == "--key-id" && "$4" == "--proof-file" ]] \
      || die "用法：backup key activate-rotation --key-id KEY_ID --proof-file FILE --confirm KEY_ID"
    key_id="$3" proof_file="$5"
    [[ "$6" == "--confirm" && -n "${7:-}" ]] \
      || die "用法：backup key activate-rotation --key-id KEY_ID --proof-file FILE --confirm KEY_ID"
    tool_command activate-rotation --key-id "$key_id" --proof-file "$proof_file" --confirm "$7"
    ;;
  record-restore-test)
    [[ $# -eq 2 ]] || die "用法：backup key record-restore-test RECEIPT.json"
    tool_command record-restore-test --receipt "$2" --backup-dir "$backup_dir"
    ;;
  audit)
    [[ $# -eq 1 ]] || die "backup key audit 不接受额外参数"
    tool_command audit --scan-root "$etc_dir" --scan-root "$var_dir" --scan-root "$backup_dir"
    ;;
  assert-ready)
    shift
    if [[ "${1:-}" == "--require-restore-test" && $# -eq 1 ]]; then
      tool_command assert-ready --require-restore-test
    elif [[ $# -eq 0 ]]; then
      tool_command assert-ready
    else
      die "backup key assert-ready 只接受 --require-restore-test"
    fi
    ;;
  status)
    [[ $# -eq 1 ]] || die "backup key status 不接受额外参数"
    tool_command status
    ;;
  *)
    die "backup key 支持 register、prepare-rotation、activate-rotation、record-restore-test、audit、assert-ready 或 status"
    ;;
esac
