#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_root
require_command python3
require_command sha256sum
require_command flock
require_command df
require_command du
require_command nproc

contract_tool="$SCRIPT_DIR/release-contract.py"
state_tool="$SCRIPT_DIR/upgrade-state.py"
run_tool="$SCRIPT_DIR/upgrade-run.py"
env_tool="$SCRIPT_DIR/upgrade-env.py"
delivery_tool="$SCRIPT_DIR/delivery-contract.py"
control_plane_tool="$SCRIPT_DIR/upgrade-control-plane.py"
[[ -x "$contract_tool" && -x "$state_tool" && -x "$run_tool" && -x "$env_tool" && -x "$delivery_tool" && -x "$control_plane_tool" ]] \
  || die "缺少 M3 升级、交付契约、运行配置或状态工具"

var_dir="$(required_env_value COPPER_VAR_DIR)"
etc_dir="$(required_env_value COPPER_ETC_DIR)"
backup_dir="$(backup_dir_value)"
upgrade_dir="$var_dir/upgrades"
prepared_state="$upgrade_dir/prepared.json"
restore_root="$(restore_root_value)"

temporary=""
usage_error() {
  echo "错误：$*" >&2
  exit 2
}
cleanup() {
  local status=$?
  [[ -z "$temporary" ]] || rm -rf --one-file-system -- "$temporary"
  trap - EXIT
  exit "$status"
}
trap cleanup EXIT

require_healthy_site() {
  local service_name service_id state health
  for service_name in mysql backend web; do
    service_id="$(compose_cmd ps -q "$service_name" 2>/dev/null || true)"
    [[ -n "$service_id" ]] || die "$service_name 容器不存在；升级预检前必须恢复三层服务"
    state="$(docker inspect --format '{{.State.Status}}' "$service_id")"
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$service_id")"
    [[ "$state/$health" == "running/healthy" ]] || die "$service_name 当前为 $state/$health；拒绝进入升级准备"
  done
}

mysql_query() {
  printf '%s\n' "$1" | compose_cmd exec -T mysql sh -c \
    'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot -D copper_v2 --batch --raw --skip-column-names'
}

json_value() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))[sys.argv[2]])' "$1" "$2"
}

json_path_value() {
  python3 - "$1" "$2" <<'PY'
import json
import sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
for part in sys.argv[2].split("."):
    value = value[part]
if isinstance(value, (dict, list)):
    raise SystemExit("JSON 路径不是标量")
print(value)
PY
}

upgrade_run_state() {
  printf '%s/runs/%s/state.json' "$upgrade_dir" "$1"
}

validate_upgrade_id() {
  [[ "$1" =~ ^[a-z0-9][a-z0-9-]{7,47}$ ]] || die "upgradeId 必须是 8-48 位小写字母、数字和短横线"
}

upgrade_lock() {
  install -d -o root -g root -m 0700 "$upgrade_dir"
  exec 7>"$upgrade_dir/.lifecycle.lock"
  flock -n 7 || die "已有升级迁移、切换或回滚操作正在运行，请勿并发执行"
}

run_transition() {
  local upgrade_id="$1" status="$2" phase="$3" action="$4" result="$5" message="$6" error="${7:-}"
  local arguments=(transition --state "$(upgrade_run_state "$upgrade_id")" --status "$status" --phase "$phase" \
    --action "$action" --result "$result" --message "$message")
  [[ -z "$error" ]] || arguments+=(--error "$error")
  "$run_tool" "${arguments[@]}" >/dev/null
}

current_release_version() {
  local version
  version="$(env_value COPPER_RELEASE_VERSION || true)"
  [[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(rc\.[1-9][0-9]*|m[1-9][0-9]*-rc\.[1-9][0-9]*))?$ ]] \
    || die "$ENV_FILE 缺少可信的 COPPER_RELEASE_VERSION；必须填写当前实际安装的 Deploy 产品版本，禁止猜测"
  printf '%s' "$version"
}

minimum_available_bytes() {
  local available
  available="$(df -PB1 "$var_dir" "$backup_dir" | awk 'NR > 1 {value=$(NF-2); if (minimum == "" || value < minimum) minimum=value} END {print minimum}')"
  [[ "$available" =~ ^[0-9]+$ ]] || die "无法读取数据和备份文件系统的剩余空间"
  if [[ -n "${COPPER_UPGRADE_TEST_AVAILABLE_BYTES:-}" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" && "$COPPER_UPGRADE_TEST_AVAILABLE_BYTES" =~ ^[0-9]+$ ]] \
      || die "升级空间故障注入只允许隔离测试模式并使用非负整数"
    available="$COPPER_UPGRADE_TEST_AVAILABLE_BYTES"
  fi
  printf '%s' "$available"
}

run_preflight() {
  local manifest="$1" emit_json="$2"
  local current_version current_schema database_version target_version target_schema min_cpu min_memory_gib min_disk_gib
  local cpu_count memory_bytes required_memory_bytes target_required backup_required required_bytes available_bytes database_bytes attachment_bytes checked_at
  local preflight_json
  local credential_file="$etc_dir/credentials/initial-admin.txt"

  validate_tools
  validate_environment
  if [[ "$emit_json" == "1" ]]; then
    require_supported_platform >&2
  else
    require_supported_platform
  fi
  [[ -d "$var_dir" && -d "$backup_dir" ]] || die "数据或备份目录不存在；先完成安装和备份配置"
  [[ ! -e "$credential_file" && ! -L "$credential_file" ]] \
    || die "首次管理员一次性凭据尚未完成交付和改密；拒绝准备升级"
  require_healthy_site

  current_version="$(current_release_version)"
  current_schema="$(mysql_query "SELECT COALESCE((SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1), 'unversioned');" | tr -d '[:space:]')"
  [[ "$current_schema" =~ ^(0|[1-9][0-9]*)$ ]] || die "当前 Flyway/schema 版本不可识别：$current_schema"
  database_version="$(mysql_query 'SELECT VERSION();' | tr -d '[:space:]')"
  [[ "$database_version" == 8.4.* ]] || die "当前数据库不是 MySQL 8.4 LTS：$database_version"

  temporary="$(mktemp -d /tmp/copper-m3-02-preflight-XXXXXX)"
  contract_json="$temporary/contract.json"
  if [[ "${COPPER_TEST_MODE:-0}" != "1" ]]; then
    "$delivery_tool" assert-manifest --manifest "$manifest" --release-root "$var_dir/releases" >/dev/null
  fi
  "$contract_tool" validate-manifest "$manifest" >/dev/null
  "$contract_tool" find-source "$manifest" \
    --source-version "$current_version" --source-schema "$current_schema" > "$contract_json"
  chmod 0600 "$contract_json"
  target_version="$(json_value "$contract_json" targetVersion)"
  target_schema="$(json_value "$contract_json" targetSchema)"
  [[ "$target_version" != "$current_version" ]] || die "目标版本与当前版本相同；拒绝伪升级"

  min_cpu="$(json_value "$contract_json" minimumCpu)"
  min_memory_gib="$(json_value "$contract_json" minimumMemoryGiB)"
  min_disk_gib="$(json_value "$contract_json" minimumFreeDiskGiB)"
  cpu_count="$(nproc)"
  memory_bytes="$(awk '/^MemTotal:/ {printf "%.0f", $2 * 1024}' /proc/meminfo)"
  # Cloud vendors reserve part of nominal instance memory for firmware and the
  # kernel. Keep the release contract at the advertised GiB value while using
  # the same 950,000 KiB per nominal GiB threshold as m1-07-preflight.sh and
  # local-host-policy.json. A standard AWS t3.medium therefore remains a valid
  # 4 GiB host without accepting a genuinely undersized 2 GiB instance.
  required_memory_bytes=$((min_memory_gib * 950000 * 1024))
  [[ "$cpu_count" -ge "$min_cpu" ]] || die "CPU 不满足目标版本：至少 ${min_cpu} 核，当前 ${cpu_count} 核"
  [[ "$memory_bytes" -ge "$required_memory_bytes" ]] \
    || die "内存不满足目标版本：标称至少 ${min_memory_gib} GiB，系统需可见至少 $((required_memory_bytes / 1048576)) MiB"

  "$SCRIPT_DIR/backup-key.sh" assert-ready --require-restore-test >/dev/null
  "$SCRIPT_DIR/backup-key.sh" audit >/dev/null
  "$state_tool" check-backup-readiness \
    --job-status "$var_dir/backup-jobs/status.json" --last-success "$backup_dir/last-success.json" >/dev/null

  database_bytes="$(mysql_query "SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE();" | tr -d '[:space:]')"
  [[ "$database_bytes" =~ ^[0-9]+$ ]] || die "无法估算数据库大小"
  attachment_bytes="$(du -sb "$var_dir/files" 2>/dev/null | awk '{print $1}' || true)"
  attachment_bytes="${attachment_bytes:-0}"
  [[ "$attachment_bytes" =~ ^[0-9]+$ ]] || die "无法估算附件大小"
  target_required=$((min_disk_gib * 1073741824))
  backup_required=$((database_bytes * 3 + attachment_bytes * 2 + 536870912))
  required_bytes="$target_required"
  [[ "$backup_required" -le "$required_bytes" ]] || required_bytes="$backup_required"
  available_bytes="$(minimum_available_bytes)"
  [[ "$available_bytes" -ge "$required_bytes" ]] \
    || die "升级准备空间不足：需要至少 $((required_bytes / 1048576)) MiB，当前约 $((available_bytes / 1048576)) MiB"

  checked_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  preflight_args=(
    --contract-json "$contract_json"
    --current-version "$current_version"
    --current-schema "$current_schema"
    --available-bytes "$available_bytes"
    --required-bytes "$required_bytes"
    --checked-at "$checked_at"
  )
  if [[ "$emit_json" == "1" ]]; then
    preflight_json="$("$state_tool" preflight "${preflight_args[@]}")"
    [[ -n "$preflight_json" ]] || die "升级预检状态工具未返回 JSON"
    printf '%s\n' "$preflight_json"
  else
    note "升级预检通过：$current_version/schema-$current_schema -> $target_version/schema-$target_schema"
    note "容量门槛通过：可用 $((available_bytes / 1048576)) MiB，需要 $((required_bytes / 1048576)) MiB"
    note "恢复密钥已有离线实证，最近备份状态、配置和三层健康均通过"
  fi
}

preflight_command() {
  local manifest="${1:-}" output_json=0
  [[ -n "$manifest" ]] || usage_error "upgrade preflight 需要目标 copper-release/v1 清单"
  shift
  if [[ "${1:-}" == "--json" && $# -eq 1 ]]; then output_json=1; shift; fi
  [[ $# -eq 0 ]] || usage_error "upgrade preflight 只接受目标清单和可选 --json"
  run_preflight "$manifest" "$output_json"
}

prepare_command() {
  local manifest="${1:-}" before_digest after_digest archive_name archive prepared_at verified_at
  [[ -n "$manifest" && $# -eq 1 ]] || usage_error "upgrade prepare 只接受一个目标 copper-release/v1 清单"
  upgrade_lock
  exec 8>"$upgrade_dir/.prepare.lock"
  flock -n 8 || die "已有升级准备任务正在运行"
  [[ ! -e "$prepared_state" && ! -L "$prepared_state" ]] \
    || die "已有未消费的 PREPARED 记录；M3-03 消费前拒绝覆盖或重复创建升级备份"
  [[ ! -e "$upgrade_dir/prepared.claim.json" && ! -L "$upgrade_dir/prepared.claim.json" ]] \
    || die "已有升级运行持有 PREPARED；完成切换、回滚或 discard 前拒绝再次准备"
  [[ ! -e "$upgrade_dir/prepared-contract" && ! -L "$upgrade_dir/prepared-contract" ]] \
    || die "发现未完成的升级契约暂存目录；拒绝创建新备份，请先保留现场并检查中断原因"

  run_preflight "$manifest" 0
  before_digest="$(sha256sum "$backup_dir/last-success.json" | awk '{print $1}')"
  note "开始生成本次升级专属的完整加密一致性备份"
  "$ROOT_DIR/copperctl" --env-file "$ENV_FILE" backup create --purpose manual
  after_digest="$(sha256sum "$backup_dir/last-success.json" | awk '{print $1}')"
  [[ "$after_digest" != "$before_digest" ]] || die "升级准备未产生新的 last-success.json，拒绝进入 PREPARED"
  archive_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["archive"])' "$backup_dir/last-success.json")"
  archive="$backup_dir/$archive_name"
  "$ROOT_DIR/copperctl" --env-file "$ENV_FILE" backup verify "$archive"
  require_healthy_site
  verified_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  prepared_at="$verified_at"
  "$state_tool" prepare "${preflight_args[@]}" \
    --last-success "$backup_dir/last-success.json" \
    --prepared-at "$prepared_at" --verified-at "$verified_at" \
    --output "$prepared_state" >/dev/null
  note "升级准备完成但尚未升级：目标契约与新备份证据已绑定到 $prepared_state"
  "$state_tool" status --state "$prepared_state"
}

migrate_command() {
  local identity="" upgrade_id="" port="$(env_value COPPER_RESTORE_PROXY_PORT || printf 18080)"
  local run_state restore_state prepared_json archive manifest ready source_version source_schema target_version target_schema prepared_sha
  local backup_id manifest_sha migration_started_at deploy_archive deploy_archive_sha control_plane_state
  local target_stage_env target_current_env stage_env argument
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --identity) [[ -n "${2:-}" ]] || usage_error "--identity 缺少路径"; identity="$2"; shift 2 ;;
      --upgrade-id) [[ -n "${2:-}" ]] || usage_error "--upgrade-id 缺少值"; upgrade_id="$2"; shift 2 ;;
      --port) [[ -n "${2:-}" ]] || usage_error "--port 缺少值"; port="$2"; shift 2 ;;
      *) usage_error "upgrade migrate 不支持参数：$1" ;;
    esac
  done
  [[ -n "$identity" ]] || usage_error "upgrade migrate 必须提供 --identity AGE-KEY.txt"
  upgrade_id="${upgrade_id:-upgrade-$(date -u +%Y%m%dt%H%M%Sz)-$(openssl rand -hex 4)}"
  validate_upgrade_id "$upgrade_id"
  upgrade_lock
  temporary="$(mktemp -d /tmp/copper-m3-03-migrate-XXXXXX)"
  prepared_json="$temporary/prepared.json"
  "$state_tool" status --state "$prepared_state" --json > "$prepared_json"
  "$run_tool" claim --prepared "$prepared_state" --upgrade-root "$upgrade_dir" --upgrade-id "$upgrade_id" >/dev/null
  run_state="$(upgrade_run_state "$upgrade_id")"
  archive="$(json_path_value "$prepared_json" backup.archive)"
  manifest="$(json_path_value "$prepared_json" releaseContract.manifest)"
  ready="$(json_path_value "$prepared_json" releaseContract.ready)"
  source_version="$(json_path_value "$prepared_json" currentVersion)"
  source_schema="$(json_path_value "$prepared_json" currentSchema)"
  target_version="$(json_path_value "$prepared_json" targetVersion)"
  target_schema="$(json_path_value "$prepared_json" targetSchema)"
  backup_id="$(json_path_value "$prepared_json" backup.backupId)"
  manifest_sha="$(json_path_value "$prepared_json" manifestSha256)"
  deploy_archive="$(json_path_value "$prepared_json" releaseContract.artifacts.deployArchive.path)"
  deploy_archive_sha="$(json_path_value "$prepared_json" releaseContract.artifacts.deployArchive.sha256)"
  prepared_sha="$(sha256sum "$prepared_state" | awk '{print $1}')"
  run_transition "$upgrade_id" RESTORING source-backup-restore restore started "restoring exact PREPARED backup to isolated sidecar"
  if ! "$ROOT_DIR/copperctl" --env-file "$ENV_FILE" backup restore stage "$archive" --identity "$identity" --restore-id "$upgrade_id" --port "$port"; then
    run_transition "$upgrade_id" MIGRATION_FAILED source-restore-failed restore failed \
      "exact PREPARED backup did not reach isolated VALIDATED" "source backup restore failed"
    die "升级旁路恢复失败；当前站点和 PREPARED 证据均未改变"
  fi
  restore_state="$restore_root/$upgrade_id/state.json"
  stage_env="$restore_root/$upgrade_id/copper.env"
  if [[ ! -f "$restore_state" || ! -f "$stage_env" ]]; then
    run_transition "$upgrade_id" MIGRATION_FAILED source-restore-evidence-failed restore failed \
      "isolated restore evidence is incomplete" "restore evidence incomplete"
    die "升级旁路恢复证据不完整；当前站点未改变"
  fi
  control_plane_state="$restore_root/$upgrade_id/control-plane.json"
  control_plane_args=(stage --archive "$deploy_archive" --archive-sha256 "$deploy_archive_sha" \
    --source-version "$source_version" --target-version "$target_version" --state "$control_plane_state")
  if [[ "${COPPER_TEST_MODE:-0}" == "1" ]]; then
    [[ -n "${COPPER_UPGRADE_CONTROL_RELEASES_DIR:-}" && -n "${COPPER_UPGRADE_CONTROL_BIN_DIR:-}" ]] \
      || die "测试模式缺少隔离的升级程序目录和系统入口目录"
    control_plane_args+=(--releases-dir "$COPPER_UPGRADE_CONTROL_RELEASES_DIR" --bin-dir "$COPPER_UPGRADE_CONTROL_BIN_DIR")
  fi
  if ! "$control_plane_tool" "${control_plane_args[@]}"; then
    run_transition "$upgrade_id" MIGRATION_FAILED target-control-plane-stage-failed migrate failed \
      "target control plane could not be staged; current site remained live" "target control plane stage failed"
    die "目标控制程序无法安全暂存；当前站点未改变"
  fi
  target_stage_env="$upgrade_dir/runs/$upgrade_id/target-stage.env"
  target_current_env="$upgrade_dir/runs/$upgrade_id/target-current.env"
  target_arguments=(--manifest "$manifest")
  if [[ "${COPPER_TEST_MODE:-0}" != "1" ]]; then
    target_arguments+=(--ready "$ready")
  fi
  [[ -z "${COPPER_UPGRADE_TEST_TARGET_WEB_IMAGE:-}" ]] \
    || target_arguments+=(--test-web-image "$COPPER_UPGRADE_TEST_TARGET_WEB_IMAGE")
  [[ -z "${COPPER_UPGRADE_TEST_TARGET_BACKEND_IMAGE:-}" ]] \
    || target_arguments+=(--test-backend-image "$COPPER_UPGRADE_TEST_TARGET_BACKEND_IMAGE")
  [[ -z "${COPPER_UPGRADE_TEST_TARGET_MYSQL_IMAGE:-}" ]] \
    || target_arguments+=(--test-mysql-image "$COPPER_UPGRADE_TEST_TARGET_MYSQL_IMAGE")
  if ! "$env_tool" --source "$stage_env" --output "$target_stage_env" "${target_arguments[@]}" \
    || ! "$env_tool" --source "$ENV_FILE" --output "$target_current_env" "${target_arguments[@]}"; then
    run_transition "$upgrade_id" MIGRATION_FAILED target-env-failed migrate failed \
      "target runtime configuration could not be derived from PREPARED" "target env generation failed"
    die "无法从 PREPARED 生成目标运行配置；当前站点未改变"
  fi
  migration_started_at="$(date -u '+%Y-%m-%d %H:%M:%S')"
  run_transition "$upgrade_id" MIGRATING target-flyway-migration migrate started "target backend owns Flyway on isolated restored data"
  if ! "$SCRIPT_DIR/restore.sh" upgrade-target "$upgrade_id" "$source_version" "$source_schema" \
    "$target_version" "$target_schema" "$prepared_sha" "$target_stage_env" "$target_current_env" \
    "$backup_id" "$manifest_sha" "$migration_started_at"; then
    run_transition "$upgrade_id" MIGRATION_FAILED target-migration-failed migrate failed \
      "target migration or post-migration contract failed; current site remained live" "target migration verification failed"
    die "隔离迁移失败；当前站点继续运行，禁止切换"
  fi
  "$control_plane_tool" status --state "$control_plane_state" --expect source >/dev/null
  run_transition "$upgrade_id" MIGRATION_VALIDATED ready-for-operator migrate passed \
    "target schema, Flyway, business totals, attachments and foreign keys validated"
  note "升级 $upgrade_id 已达到 MIGRATION_VALIDATED；正式站点尚未切换"
  note "请在可信终端执行 copperctl upgrade switch $upgrade_id，并输入完整 UPGRADE 确认串"
}

switch_command() {
  local upgrade_id="${1:-}" run_state restore_state status
  [[ -n "$upgrade_id" && $# -eq 1 ]] || usage_error "upgrade switch 只接受一个 UPGRADE_ID"
  validate_upgrade_id "$upgrade_id"
  upgrade_lock
  run_state="$(upgrade_run_state "$upgrade_id")"
  status="$(json_path_value "$run_state" status)"
  [[ "$status" == "MIGRATION_VALIDATED" ]] || die "只有 MIGRATION_VALIDATED 可以切换，当前为 $status"
  run_transition "$upgrade_id" SWITCHING operator-switch switch started "trusted terminal upgrade confirmation required"
  if ! "$ROOT_DIR/copperctl" --env-file "$ENV_FILE" backup restore switch "$upgrade_id"; then
    restore_state="$restore_root/$upgrade_id/state.json"
    status="$(json_path_value "$restore_state" status)"
    case "$status" in
      MIGRATION_VALIDATED) run_transition "$upgrade_id" MIGRATION_VALIDATED ready-for-operator switch failed "switch failed; original site verified" "switch failed; original verified" ;;
      MIGRATION_FAILED) run_transition "$upgrade_id" MIGRATION_FAILED target-sidecar-recovery-failed switch failed "original site verified; target sidecar must be discarded" "target sidecar recovery failed" ;;
      DIRTY) run_transition "$upgrade_id" DIRTY restage-required switch failed "sidecar changed after validation" "sidecar dirty" ;;
      RECOVERY_FAILED) run_transition "$upgrade_id" RECOVERY_FAILED operator-intervention-required switch failed "automatic recovery failed; Web remains stopped" "automatic recovery failed" ;;
      *) die "切换失败且旁路状态不可识别：$status；保留现场，禁止放行 Web" ;;
    esac
    die "升级切换未完成；请按状态证据处理"
  fi
  run_transition "$upgrade_id" SWITCHED target-site-live switch passed "target data, images and version verified before Web resumed"
  note "升级切换完成：$upgrade_id；旧数据和旧配置仍保留，可执行 copperctl upgrade rollback $upgrade_id"
}

rollback_command() {
  local upgrade_id="${1:-}" run_state restore_state status
  [[ -n "$upgrade_id" && $# -eq 1 ]] || usage_error "upgrade rollback 只接受一个 UPGRADE_ID"
  validate_upgrade_id "$upgrade_id"
  upgrade_lock
  run_state="$(upgrade_run_state "$upgrade_id")"
  status="$(json_path_value "$run_state" status)"
  [[ "$status" == "SWITCHED" ]] || die "只有 SWITCHED 可以回滚，当前为 $status"
  run_transition "$upgrade_id" ROLLING_BACK operator-rollback rollback started "trusted terminal rollback confirmation required"
  if ! "$ROOT_DIR/copperctl" --env-file "$ENV_FILE" backup restore rollback "$upgrade_id"; then
    restore_state="$restore_root/$upgrade_id/state.json"
    status="$(json_path_value "$restore_state" status)"
    case "$status" in
      SWITCHED) run_transition "$upgrade_id" SWITCHED target-site-live rollback failed "rollback failed; target site revalidated" "rollback failed; target verified" ;;
      RECOVERY_FAILED) run_transition "$upgrade_id" RECOVERY_FAILED operator-intervention-required rollback failed "rollback recovery failed; Web remains stopped" "rollback recovery failed" ;;
      *) die "回滚失败且旁路状态不可识别：$status；保留现场，禁止放行 Web" ;;
    esac
    die "升级回滚未完成；请按状态证据处理"
  fi
  run_transition "$upgrade_id" ROLLED_BACK source-site-live rollback passed "source data, images and version verified before Web resumed"
  note "升级已完整回滚：$upgrade_id；确认不再需要目标旁路后可执行 copperctl upgrade discard $upgrade_id"
}

accept_command() {
  local upgrade_id="${1:-}" run_state status target_version switched_at current_version
  local last_success registry proof backup_id backup_sha completed_at receipt_sha restore_state evidence_dir
  [[ -n "$upgrade_id" && $# -eq 1 ]] || usage_error "upgrade accept 只接受一个 UPGRADE_ID"
  validate_upgrade_id "$upgrade_id"
  upgrade_lock
  run_state="$(upgrade_run_state "$upgrade_id")"
  status="$(json_path_value "$run_state" status)"
  [[ "$status" == "SWITCHED" ]] || die "只有 SWITCHED 可以完成升级验收，当前为 $status"
  require_healthy_site
  target_version="$(json_path_value "$run_state" targetVersion)"
  switched_at="$(json_path_value "$run_state" updatedAt)"
  current_version="$(current_release_version)"
  [[ "$current_version" == "$target_version" ]] \
    || die "当前版本 $current_version 与已切换目标 $target_version 不一致；拒绝释放回滚副本"

  last_success="$backup_dir/last-success.json"
  registry="$var_dir/backup-keys/registry.json"
  [[ -f "$last_success" && ! -L "$last_success" && -f "$registry" && ! -L "$registry" ]] \
    || die "缺少升级后的备份或客户离线验证记录；拒绝释放回滚副本"
  "$SCRIPT_DIR/backup-key.sh" assert-ready --require-restore-test >/dev/null
  "$SCRIPT_DIR/backup-key.sh" audit >/dev/null
  "$state_tool" check-backup-readiness \
    --job-status "$var_dir/backup-jobs/status.json" --last-success "$last_success" >/dev/null
  if ! proof="$(python3 - "$last_success" "$registry" "$switched_at" <<'PY'
import datetime as dt
import hashlib
import json
import pathlib
import re
import sys

last_path = pathlib.Path(sys.argv[1])
registry_path = pathlib.Path(sys.argv[2])
switched_text = sys.argv[3]
last = json.loads(last_path.read_text(encoding="utf-8"))
registry = json.loads(registry_path.read_text(encoding="utf-8"))
backup_id = str(last.get("backupId", ""))
backup_sha = str(last.get("sha256", ""))
completed = str(last.get("completedAt", ""))
if not re.fullmatch(r"[0-9a-f]{32}", backup_id) or not re.fullmatch(r"[0-9a-f]{64}", backup_sha):
    raise SystemExit("最近成功备份记录不完整")
parse = lambda value: dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)
if parse(completed) <= parse(switched_text):
    raise SystemExit("最近成功备份不是升级切换后创建")
archive = last_path.parent / str(last.get("archive", ""))
if archive.is_symlink() or not archive.is_file():
    raise SystemExit("升级后备份文件不存在或不安全")
digest = hashlib.sha256()
with archive.open("rb") as reader:
    for block in iter(lambda: reader.read(1024 * 1024), b""):
        digest.update(block)
if digest.hexdigest() != backup_sha:
    raise SystemExit("升级后备份文件摘要不一致")
active_id = registry.get("activeKeyId")
keys = registry.get("keys") if isinstance(registry.get("keys"), list) else []
active = next((item for item in keys if isinstance(item, dict) and item.get("keyId") == active_id), None)
tests = active.get("restoreTests") if isinstance(active, dict) and isinstance(active.get("restoreTests"), list) else []
matched = next((item for item in tests if isinstance(item, dict) and item.get("backupId") == backup_id and item.get("backupSha256") == backup_sha), None)
if matched is None or not re.fullmatch(r"[0-9a-f]{64}", str(matched.get("receiptSha256", ""))):
    raise SystemExit("升级后备份尚未在客户电脑离线验证并登记")
print("|".join((backup_id, backup_sha, completed, str(matched["receiptSha256"]))))
PY
)"; then
    die "升级后的数据保护门槛未通过"
  fi
  IFS='|' read -r backup_id backup_sha completed_at receipt_sha <<< "$proof"

  echo "新版本已通过健康检查，升级后的整站备份也已在客户电脑离线验证。"
  echo "下一步会永久删除切换前的旧数据库、旧附件和旧配置副本；当前新版本数据不会修改。"
  confirm_action "ACCEPT UPGRADE $(required_env_value COPPER_SITE_ID) $upgrade_id"

  restore_state="$restore_root/$upgrade_id/state.json"
  [[ -f "$restore_state" && ! -L "$restore_state" ]] || die "升级回滚状态不存在或不安全"
  evidence_dir="$upgrade_dir/runs/$upgrade_id/accepted-restore-evidence"
  [[ ! -e "$evidence_dir" && ! -L "$evidence_dir" ]] || die "升级验收证据目录已存在；拒绝覆盖"
  install -d -o root -g root -m 0700 "$evidence_dir"
  cp -a -- "$restore_state" "$evidence_dir/state.json"
  [[ ! -f "$restore_root/$upgrade_id/events.jsonl" ]] \
    || cp -a -- "$restore_root/$upgrade_id/events.jsonl" "$evidence_dir/events.jsonl"
  chmod 0400 "$evidence_dir/state.json"
  [[ ! -f "$evidence_dir/events.jsonl" ]] || chmod 0400 "$evidence_dir/events.jsonl"
  python3 - "$evidence_dir/acceptance.json" "$backup_id" "$backup_sha" "$completed_at" "$receipt_sha" <<'PY'
import datetime as dt
import json
import os
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
document = {
    "formatVersion": "copper-upgrade-acceptance/v1",
    "acceptedAt": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "postUpgradeBackupId": sys.argv[2],
    "postUpgradeBackupSha256": sys.argv[3],
    "postUpgradeBackupCompletedAt": sys.argv[4],
    "offlineVerificationReceiptSha256": sys.argv[5],
}
path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
os.chmod(path, 0o400)
PY
  "$ROOT_DIR/copperctl" --env-file "$ENV_FILE" backup restore accept-upgrade "$upgrade_id" \
    --confirm "ACCEPT UPGRADE $(required_env_value COPPER_SITE_ID) $upgrade_id"
  run_transition "$upgrade_id" COMPLETED accepted-and-protected accept passed \
    "post-upgrade backup verified offline; source rollback copy released"
  "$run_tool" finalize --state "$run_state" >/dev/null
  note "升级验收完成：$upgrade_id；下一次升级已解锁"
}

discard_command() {
  local upgrade_id="${1:-}" run_state status terminal
  [[ -n "$upgrade_id" && $# -eq 1 ]] || usage_error "upgrade discard 只接受一个 UPGRADE_ID"
  validate_upgrade_id "$upgrade_id"
  upgrade_lock
  run_state="$(upgrade_run_state "$upgrade_id")"
  status="$(json_path_value "$run_state" status)"
  if [[ "$status" == "ABORTED" || "$status" == "COMPLETED" ]]; then
    [[ -f "$upgrade_dir/prepared.claim.json" ]] || die "升级 $upgrade_id 已完成证据归档；拒绝重复 discard"
    "$run_tool" finalize --state "$run_state" >/dev/null
    note "升级证据归档完成，已释放下一次 upgrade prepare"
    return
  fi
  case "$status" in
    MIGRATION_FAILED|MIGRATION_VALIDATED|DIRTY) terminal=ABORTED ;;
    ROLLED_BACK) terminal=COMPLETED ;;
    *) die "状态 $status 仍可能持有唯一回滚数据或正在运行，禁止 discard" ;;
  esac
  "$ROOT_DIR/copperctl" --env-file "$ENV_FILE" backup restore discard "$upgrade_id"
  run_transition "$upgrade_id" "$terminal" evidence-retained discard passed "isolated runtime removed; upgrade evidence retained"
  "$run_tool" finalize --state "$run_state" >/dev/null
  note "升级旁路已删除；root-only PREPARED 证据已归档，并已释放下一次 upgrade prepare"
}

status_command() {
  local claim upgrade_id=""
  claim="$upgrade_dir/prepared.claim.json"
  if [[ $# -gt 0 && "${1:-}" != "--json" ]]; then
    upgrade_id="$1"
    shift
  elif [[ -f "$claim" ]]; then
    upgrade_id="$(json_path_value "$claim" upgradeId)"
  fi
  if [[ -n "$upgrade_id" ]]; then
    validate_upgrade_id "$upgrade_id"
    if [[ "${1:-}" == "--json" && $# -eq 1 ]]; then
      "$run_tool" status --state "$(upgrade_run_state "$upgrade_id")" --json
    elif [[ $# -eq 0 ]]; then
      "$run_tool" status --state "$(upgrade_run_state "$upgrade_id")"
      [[ ! -f "$restore_root/$upgrade_id/state.json" ]] || python3 "$SCRIPT_DIR/restore-metadata.py" show-state "$restore_root/$upgrade_id/state.json"
    else
      usage_error "upgrade status [UPGRADE_ID] 只接受可选 --json"
    fi
  elif [[ "${1:-}" == "--json" && $# -eq 1 ]]; then
    "$state_tool" status --state "$prepared_state" --json
  elif [[ $# -eq 0 ]]; then
    "$state_tool" status --state "$prepared_state"
  else
    usage_error "upgrade status [UPGRADE_ID] 只接受可选 --json"
  fi
}

case "${1:-}" in
  preflight) shift; preflight_command "$@" ;;
  prepare) shift; prepare_command "$@" ;;
  migrate) shift; migrate_command "$@" ;;
  switch) shift; switch_command "$@" ;;
  rollback) shift; rollback_command "$@" ;;
  accept) shift; accept_command "$@" ;;
  discard) shift; discard_command "$@" ;;
  status) shift; status_command "$@" ;;
  *) usage_error "upgrade 支持 preflight、prepare、migrate、switch、rollback、accept、discard 或 status" ;;
esac
