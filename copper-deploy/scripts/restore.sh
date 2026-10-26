#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib.sh"
source "$SCRIPT_DIR/verification-sql.sh"

require_root
restore_command="${1:-}"
if [[ "$restore_command" == "status" ]]; then
  [[ -f "$ENV_FILE" ]] || die "未找到 $ENV_FILE"
  assert_safe_paths
else
  validate_tools
  validate_environment
fi

etc_dir="$(required_env_value COPPER_ETC_DIR)"
var_dir="$(required_env_value COPPER_VAR_DIR)"
backup_dir="$(backup_dir_value)"
restore_root="$(restore_root_value)"
site_id="$(required_env_value COPPER_SITE_ID)"
metadata_tool="$SCRIPT_DIR/backup-metadata.py"
chain_tool="$SCRIPT_DIR/backup-chain.py"
restore_tool="$SCRIPT_DIR/restore-metadata.py"
control_plane_tool="$SCRIPT_DIR/upgrade-control-plane.py"

# EXIT traps run after Bash has unwound function-local variables. Transaction
# cleanup therefore uses explicit process-global context and never secrets.
STAGE_SUCCESS=1
STAGE_ENV=""
STAGE_WORK=""
STAGE_ETC=""
STAGE_VAR=""
STAGE_CONTRACT=""
STAGE_STATE=""
STAGE_DIR=""
STAGE_ID=""
SWITCH_COMPLETE=1
ROLLBACK_COMPLETE=1
SWITCH_UPGRADE_MODE=0
SWITCH_ENV_SWAPPED=0
SWITCH_AUDIT_UPDATED=0
SWITCH_CONTROL_SWAPPED=0
ROLLBACK_UPGRADE_MODE=0
ROLLBACK_ENV_SWAPPED=0
ROLLBACK_CONTROL_SWAPPED=0

require_restore_root() {
  require_command python3
  [[ -x "$restore_tool" ]] || die "恢复元数据工具不存在或不可执行"
  [[ -d "$restore_root" && ! -L "$restore_root" ]] || die "旁路恢复根目录不存在或不安全：$restore_root；请先重新运行 copperctl install"
  [[ "$(stat -c '%u:%g %a' "$restore_root")" == "0:0 700" ]] || die "旁路恢复根目录必须是 root:root 700：$restore_root"
  if [[ "${COPPER_TEST_MODE:-0}" == "1" ]]; then
    if [[ "$restore_root" == "/var/lib/copper-restores" ]]; then
      [[ "${COPPER_PERSISTENT_REBOOT_TEST:-0}" == "1" ]] \
        || die "测试模式使用正式旁路目录时必须显式设置 COPPER_PERSISTENT_REBOOT_TEST=1"
    else
      [[ "$restore_root" == /tmp/copper-* || "$restore_root" == /opt/copper-* ]] \
        || die "隔离恢复测试必须显式使用 /tmp/copper-*、/opt/copper-*，或完整正式目录组合的 COPPER_RESTORE_ROOT"
    fi
  else
    [[ "$restore_root" == "/var/lib/copper-restores" ]] || die "生产旁路恢复根目录必须是 /var/lib/copper-restores"
  fi
}

require_restore_tools() {
  local command_name
  for command_name in age awk cmp cp curl df docker du find flock grep install mv nproc od openssl python3 readlink rm seq sha256sum sort stat tar tr zstd; do
    require_command "$command_name"
  done
  [[ -x "$metadata_tool" ]] || die "备份元数据工具不存在或不可执行"
  [[ -x "$chain_tool" ]] || die "增量备份链工具不存在或不可执行"
  require_restore_root
}

lock_operations() {
  install -d -o root -g root -m 0700 "$backup_dir"
  exec 9>"$backup_dir/.operation.lock"
  flock -n 9 || die "已有备份、校验或恢复操作正在运行，请勿并发执行"
}

validate_restore_id() {
  [[ "$1" =~ ^[a-z0-9][a-z0-9-]{7,47}$ ]] || die "restoreId 必须是 8-48 位小写字母、数字和短横线"
}

restore_directory() {
  validate_restore_id "$1"
  printf '%s/%s' "$restore_root" "$1"
}

state_value() {
  python3 - "$1" "$2" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
value = data.get(sys.argv[2], "")
if isinstance(value, (dict, list)):
    raise SystemExit("状态字段不是标量")
print(value)
PY
}

write_state() {
  local state_path="$1"
  shift
  python3 "$restore_tool" write-state "$state_path" "$@"
}

record_event() {
  local directory="$1" restore_id="$2" action="$3" result="$4" message="$5"
  python3 "$restore_tool" event "$directory/events.jsonl" "$restore_id" "$action" "$result" "$message"
}

stage_compose() {
  local stage_env="$1"
  shift
  # The stage env pins its own project/mode and optional OCR settings. Reuse
  # the same overlay and network checks; teardown never inspects the network.
  ( ENV_FILE="$stage_env"; compose_cmd "$@" )
}

current_mysql_cli() {
  compose_cmd exec -T mysql sh -c \
    'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot -D copper_v2 --batch --raw --skip-column-names'
}

stage_mysql_cli() {
  local stage_env="$1"
  stage_compose "$stage_env" exec -T mysql sh -c \
    'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot -D copper_v2 --batch --raw --skip-column-names'
}

mysql_cli_for() {
  local target="$1" stage_env="$2"
  if [[ "$target" == "current" ]]; then
    current_mysql_cli
  else
    stage_mysql_cli "$stage_env"
  fi
}

require_services_healthy() {
  local target="$1" stage_env="$2"
  shift 2
  local service_name service_id state health
  for service_name in "$@"; do
    if [[ "$target" == "current" ]]; then
      service_id="$(compose_cmd ps -q "$service_name" 2>/dev/null || true)"
    else
      service_id="$(stage_compose "$stage_env" ps -q "$service_name" 2>/dev/null || true)"
    fi
    [[ -n "$service_id" ]] || die "$target 的 $service_name 容器不存在"
    state="$(docker inspect --format '{{.State.Status}}' "$service_id")"
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$service_id")"
    [[ "$state/$health" == "running/healthy" ]] || die "$target 的 $service_name 未健康：$state/$health"
  done
}

wait_for_current_transactions() {
  local attempt active
  for attempt in $(seq 1 30); do
    active="$(printf '%s\n' 'SELECT COUNT(*) FROM information_schema.innodb_trx;' | current_mysql_cli | tr -d '[:space:]')"
    [[ "$active" =~ ^[0-9]+$ ]] || die "无法读取当前 MySQL 活跃事务数量"
    [[ "$active" == "0" ]] && return
    sleep 1
  done
  die "停止 Backend 后仍有 MySQL 活跃事务，拒绝切换数据"
}

capture_attachment_files() {
  local files_root="$1" output_tsv="$2" file relative size digest unexpected
  : > "$output_tsv"
  [[ -d "$files_root" && ! -L "$files_root" ]] || die "附件目录不存在或不安全：$files_root"
  unexpected="$(find "$files_root" -mindepth 1 ! -type d ! -type f -print -quit)"
  [[ -z "$unexpected" ]] || die "附件目录包含符号链接或非常规文件：$unexpected"
  while IFS= read -r -d '' file; do
    relative="${file#"$files_root"/}"
    [[ -n "$relative" && "$relative" != *$'\t'* && "$relative" != *$'\n'* && "$relative" != *$'\r'* ]] || die "附件路径不安全"
    size="$(stat -c '%s' "$file")"
    digest="$(sha256sum "$file" | awk '{print $1}')"
    printf '%s\t%s\t%s\n' "$relative" "$size" "$digest" >> "$output_tsv"
  done < <(find "$files_root" -type f -print0 | LC_ALL=C sort -z)
}

capture_verification() {
  local target="$1" stage_env="$2" files_root="$3" output="$4"
  local work table_names counts totals refs fk_metadata fk_engines fk_sql fk_results database_version schema_version
  install -d -m 0700 "$output"
  work="$(mktemp -d "$output/.capture-XXXXXX")"
  table_names="$work/table-names.tsv"
  counts="$work/record-counts.tsv"
  totals="$work/business-totals.tsv"
  refs="$work/attachment-references.tsv"
  fk_metadata="$work/foreign-key-metadata.tsv"
  fk_engines="$work/table-engines.tsv"
  fk_sql="$work/foreign-key-checks.sql"
  fk_results="$output/foreign-keys.tsv"

  database_version="$(printf '%s\n' 'SELECT VERSION();' | mysql_cli_for "$target" "$stage_env" | tr -d '[:space:]')"
  schema_version="$(printf '%s\n' "SELECT COALESCE((SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1), 'unversioned');" | mysql_cli_for "$target" "$stage_env" | tr -d '[:space:]')"
  printf '%s\n' "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME;" \
    | mysql_cli_for "$target" "$stage_env" > "$table_names"
  write_table_counts_sql "$work/counts.sql"
  mysql_cli_for "$target" "$stage_env" < "$work/counts.sql" > "$counts"
  write_business_totals_sql "$work/totals.sql"
  mysql_cli_for "$target" "$stage_env" < "$work/totals.sql" > "$totals"
  write_attachment_references_sql "$work/references.sql"
  mysql_cli_for "$target" "$stage_env" < "$work/references.sql" > "$refs"
  capture_attachment_files "$files_root" "$work/attachment-files.tsv"

  python3 "$metadata_tool" schema "$output/schema.json" "$database_version" "$schema_version" "$table_names"
  python3 "$metadata_tool" record-counts "$counts" "$output/record-counts.json"
  python3 "$metadata_tool" business-totals "$totals" "$output/business-totals.json"
  python3 "$metadata_tool" attachments "$work/attachment-files.tsv" "$refs" "$output/attachments.json"

  printf '%s\n' "SELECT k.CONSTRAINT_NAME, k.TABLE_NAME, k.COLUMN_NAME, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME, k.ORDINAL_POSITION, r.DELETE_RULE, r.UPDATE_RULE FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.TABLE_NAME = k.TABLE_NAME AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME WHERE k.CONSTRAINT_SCHEMA = DATABASE() AND k.REFERENCED_TABLE_NAME IS NOT NULL ORDER BY k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION;" \
    | mysql_cli_for "$target" "$stage_env" > "$fk_metadata"
  printf '%s\n' "SELECT TABLE_NAME, ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME;" \
    | mysql_cli_for "$target" "$stage_env" > "$fk_engines"
  python3 "$restore_tool" assert-core-foreign-keys "$schema_version" "$fk_metadata" \
    --table-engines "$fk_engines" --database-version "$database_version"
  python3 "$restore_tool" foreign-key-sql "$fk_metadata" "$fk_sql"
  mysql_cli_for "$target" "$stage_env" < "$fk_sql" > "$fk_results"
  printf '%s\n' "SELECT installed_rank, COALESCE(version, ''), REPLACE(REPLACE(description, CHAR(9), ' '), CHAR(10), ' '), type, script, COALESCE(CAST(checksum AS CHAR), ''), success FROM flyway_schema_history ORDER BY installed_rank;" \
    | mysql_cli_for "$target" "$stage_env" > "$output/flyway-history.tsv"
  rm -rf --one-file-system -- "$work"
}

compare_verification() {
  local expected="$1" actual="$2" result="$3"
  python3 "$restore_tool" compare-verification "$expected" "$actual" "$actual/foreign-keys.tsv" "$result" || return 1
  if [[ -f "$expected/flyway-history.tsv" ]]; then
    cmp -s "$expected/flyway-history.tsv" "$actual/flyway-history.tsv" || die "Flyway 历史在验证后发生变化"
  fi
}

atomic_install_env() {
  local source="$1" destination="$2" temporary
  [[ -f "$source" && ! -L "$source" ]] || die "运行配置来源不存在或不安全：$source"
  temporary="$(dirname "$destination")/.copper.env.upgrade.$$"
  install -o root -g root -m 0600 "$source" "$temporary"
  mv -f "$temporary" "$destination"
}

require_env_images_available() {
  local env_file="$1" key image
  for key in COPPER_WEB_IMAGE COPPER_BACKEND_IMAGE COPPER_MYSQL_IMAGE; do
    image="$(awk -F= -v wanted="$key" '$1 == wanted {print substr($0, index($0, "=") + 1); exit}' "$env_file")"
    [[ -n "$image" ]] || die "目标运行配置缺少 $key"
    docker image inspect "$image" >/dev/null 2>&1 || die "目标镜像尚未交付到本机，M3-03 禁止隐式拉取：$key=$image"
  done
}

write_stage_env() {
  local output="$1" restore_id="$2" stage_etc="$3" stage_var="$4" port="$5"
  local project="copper-restore-${restore_id}"
  umask 077
  {
    printf 'COPPER_SITE_ID=%s\n' "$site_id"
    printf 'COPPER_RELEASE_VERSION=%s\n' "$(required_env_value COPPER_RELEASE_VERSION)"
    printf 'COPPER_PROJECT_NAME=%s\n' "$project"
    printf 'COPPER_DEPLOY_MODE=external-proxy\n'
    printf 'COPPER_DOMAIN=restore.invalid\n'
    printf 'COPPER_TLS_MODE=domain\n'
    printf 'COPPER_PROXY_PORT=%s\n' "$port"
    printf 'COPPER_IMAGE_DELIVERY=%s\n' "$(env_value COPPER_IMAGE_DELIVERY || printf registry)"
    printf 'COPPER_WEB_IMAGE=%s\n' "$(required_env_value COPPER_WEB_IMAGE)"
    printf 'COPPER_BACKEND_IMAGE=%s\n' "$(required_env_value COPPER_BACKEND_IMAGE)"
    printf 'COPPER_MYSQL_IMAGE=%s\n' "$(required_env_value COPPER_MYSQL_IMAGE)"
    printf 'COPPER_TIME_ZONE=%s\n' "$(env_value COPPER_TIME_ZONE || printf Asia/Shanghai)"
    printf 'COPPER_LOG_LEVEL=%s\n' "$(env_value COPPER_LOG_LEVEL || printf INFO)"
    printf 'COPPER_OCR_ENABLED=%s\n' "$(ocr_env_value COPPER_OCR_ENABLED false)"
    printf 'COPPER_OCR_ENDPOINT=%s\n' "$(ocr_env_value COPPER_OCR_ENDPOINT)"
    printf 'COPPER_OCR_NETWORK=%s\n' "$(ocr_env_value COPPER_OCR_NETWORK)"
    printf 'COPPER_OCR_ENGINE=%s\n' "$(ocr_env_value COPPER_OCR_ENGINE PADDLEOCR_LOCAL)"
    printf 'COPPER_OCR_MODEL=%s\n' "$(ocr_env_value COPPER_OCR_MODEL PP-OCRv6-medium)"
    printf 'COPPER_OCR_TIMEOUT_SECONDS=%s\n' "$(ocr_env_value COPPER_OCR_TIMEOUT_SECONDS 120)"
    printf 'COPPER_BOOTSTRAP_USERNAME=%s\n' "$(env_value COPPER_BOOTSTRAP_USERNAME || printf admin)"
    printf 'COPPER_ETC_DIR=%s\n' "$stage_etc"
    printf 'COPPER_VAR_DIR=%s\n' "$stage_var"
    printf 'COPPER_BACKUP_DIR=%s\n' "$backup_dir"
    printf 'COPPER_RESTORE_ROOT=%s\n' "$restore_root"
    printf 'COPPER_RESTORE_PROXY_PORT=%s\n' "$port"
  } > "$output"
  chmod 0600 "$output"
}

prepare_stage_directories() {
  local stage_etc="$1" stage_var="$2"
  install -d -o root -g root -m 0750 "$stage_etc" "$stage_etc/secrets"
  install -d -o 10001 -g 10001 -m 0700 "$stage_etc/credentials"
  install -o 10001 -g 10001 -m 0400 "$etc_dir/secrets/copper_db_password" "$stage_etc/secrets/copper_db_password"
  install -o root -g root -m 0400 "$etc_dir/secrets/mysql_root_password" "$stage_etc/secrets/mysql_root_password"
  install -d -o root -g root -m 0750 "$stage_var"
  install -d -o 999 -g 999 -m 0700 "$stage_var/mysql"
  install -d -o 10001 -g 10001 -m 0750 "$stage_var/backend" "$stage_var/backend/runtime" "$stage_var/files" "$stage_var/logs"
  install -d -o root -g 10001 -m 0750 "$stage_var/time-safety"
  install -d -o root -g root -m 0750 "$stage_var/backups" "$stage_var/caddy"
  install -d -o 10001 -g 10001 -m 0700 "$stage_var/caddy/data" "$stage_var/caddy/config"
}

capture_stage_failure_diagnostics() {
  local diagnostics service
  [[ -n "$STAGE_DIR" && -f "$STAGE_ENV" ]] || return 0
  diagnostics="$STAGE_DIR/diagnostics"
  install -d -o root -g root -m 0700 "$diagnostics"
  stage_compose "$STAGE_ENV" ps -a > "$diagnostics/compose-ps.txt" 2>&1 || true
  for service in mysql backend web; do
    stage_compose "$STAGE_ENV" logs --no-color --timestamps "$service" > "$diagnostics/$service.log" 2>&1 || true
  done
  chmod 0600 "$diagnostics"/* 2>/dev/null || true
}

copy_payload_attachments() {
  local source="$1" destination="$2"
  (cd "$source" && tar --format=pax --numeric-owner -cf - .) | tar --numeric-owner -xf - -C "$destination"
  chown -R 10001:10001 "$destination"
  find "$destination" -type d -exec chmod 0750 {} +
  find "$destination" -type f -exec chmod 0640 {} +
}

replay_incremental_binlogs() {
  local stage_env="$1" payload="$2" container_id from_position first=1 name source target
  container_id="$(stage_compose "$stage_env" ps -q mysql)"
  [[ -n "$container_id" ]] || die "旁路 MySQL 容器不存在，无法回放增量日志"
  from_position="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["database"]["binlog"]["from"]["position"])' "$payload/manifest.json")"
  [[ "$from_position" =~ ^[0-9]+$ && "$from_position" -ge 4 ]] || die "增量 binlog 起点位置不正确"
  while IFS= read -r name; do
    [[ "$name" =~ ^binlog\.[0-9]{6,}$ ]] || die "增量 binlog 文件名不安全：$name"
    source="$payload/database/binlog/$name"
    [[ -f "$source" && ! -L "$source" ]] || die "增量 binlog 文件不存在或不安全：$name"
    target="/tmp/copper-replay-$name"
    docker cp "$source" "$container_id:$target" >/dev/null
    if [[ "$first" == "1" ]]; then
      stage_compose "$stage_env" exec -T mysql mysqlbinlog --disable-log-bin --start-position="$from_position" "$target" \
        | stage_mysql_cli "$stage_env"
      first=0
    else
      stage_compose "$stage_env" exec -T mysql mysqlbinlog --disable-log-bin "$target" \
        | stage_mysql_cli "$stage_env"
    fi
    stage_compose "$stage_env" exec -T mysql rm -f -- "$target"
  done < <(python3 -c 'import json,sys; [print(item["name"]) for item in json.load(open(sys.argv[1], encoding="utf-8"))["database"]["binlog"]["files"]]' "$payload/manifest.json")
  [[ "$first" == "0" ]] || die "增量备份没有可回放的 binlog"
}

test_fail_at() {
  local point="$1"
  if [[ "${COPPER_RESTORE_TEST_FAIL_AT:-}" == "$point" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" ]] || die "恢复故障注入只允许隔离测试模式"
    die "测试注入失败点：$point"
  fi
}

available_restore_bytes() {
  local value
  value="$(df -PB1 "$restore_root" | awk 'END {print $(NF-2)}')"
  if [[ -n "${COPPER_RESTORE_TEST_AVAILABLE_BYTES:-}" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" && "$COPPER_RESTORE_TEST_AVAILABLE_BYTES" =~ ^[0-9]+$ ]] || die "恢复空间故障注入仅允许测试模式的非负整数"
    value="$COPPER_RESTORE_TEST_AVAILABLE_BYTES"
  fi
  printf '%s' "$value"
}

validate_images_available() {
  local image architecture host_arch
  host_arch="$(uname -m)"
  for image in "$(required_env_value COPPER_WEB_IMAGE)" "$(required_env_value COPPER_BACKEND_IMAGE)" "$(required_env_value COPPER_MYSQL_IMAGE)"; do
    docker image inspect "$image" >/dev/null 2>&1 || die "本机缺少恢复所需镜像：$image；离线主机请先导入同版本镜像包"
    architecture="$(docker image inspect --format '{{.Architecture}}' "$image")"
    if [[ "${COPPER_TEST_MODE:-0}" != "1" ]]; then
      [[ "$host_arch" == "x86_64" && "$architecture" == "amd64" ]] || die "生产恢复只接受 x86_64/amd64 镜像，当前主机=$host_arch 镜像=$architecture"
    fi
  done
}

validate_runtime_capacity() {
  local processors available_kb
  processors="$(nproc)"
  available_kb="$(awk '$1 == "MemAvailable:" {print $2; exit}' /proc/meminfo)"
  [[ "$processors" =~ ^[0-9]+$ && "$available_kb" =~ ^[0-9]+$ ]] || die "无法读取 CPU 或可用内存容量"
  if [[ "${COPPER_TEST_MODE:-0}" != "1" ]]; then
    [[ "$processors" -ge 2 ]] || die "旁路恢复至少需要 2 个可用 CPU，当前为 $processors"
    [[ "$available_kb" -ge 2097152 ]] || die "同时运行当前站点和旁路恢复至少需要 2 GiB 可用内存，当前约 $((available_kb / 1024)) MiB"
  fi
}

validate_port_available() {
  local port="$1" current_port
  [[ "$port" =~ ^[0-9]+$ && "$port" -ge 1024 && "$port" -le 65535 ]] || die "旁路端口必须是 1024-65535"
  [[ "$port" != "8191" ]] || die "旁路恢复禁止占用正式 Backend 端口 8191"
  current_port="$(env_value COPPER_PROXY_PORT || true)"
  [[ -z "$current_port" || "$port" != "$current_port" ]] || die "旁路端口不能与当前站点入口端口相同"
  python3 - "$port" <<'PY'
import socket
import sys

sock = socket.socket()
try:
    sock.bind(("127.0.0.1", int(sys.argv[1])))
except OSError as exc:
    raise SystemExit(f"错误：旁路端口不可用：{exc}")
finally:
    sock.close()
PY
}

validate_identity() {
  local identity="$1" mode
  [[ -f "$identity" && ! -L "$identity" ]] || die "age 私钥文件不存在、是链接或不是普通文件"
  mode="$(stat -c '%a' "$identity")"
  (( (0$mode & 077) == 0 )) || die "age 私钥不能允许组或其他用户读取，请设为 600 或 400"
}

require_current_admin_handover_complete() {
  local credential_file="$etc_dir/credentials/initial-admin.txt"
  [[ ! -e "$credential_file" && ! -L "$credential_file" ]] \
    || die "当前空白站仍有待交付的一次性管理员凭据；请先完成首次登录改密并确认凭据文件已销毁，再开始恢复"
}

stage_restore() {
  local archive="" identity="" restore_id="" port="$(env_value COPPER_RESTORE_PROXY_PORT || printf 18080)"
  local -a incremental_archives=() incremental_payloads=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --identity) [[ -n "${2:-}" ]] || die "--identity 缺少路径"; identity="$2"; shift 2 ;;
      --incremental)
        [[ -n "${2:-}" ]] || die "--incremental 缺少 .copperbak 路径"
        incremental_archives+=("$2")
        shift 2
        ;;
      --restore-id) [[ -n "${2:-}" ]] || die "--restore-id 缺少值"; restore_id="$2"; shift 2 ;;
      --port) [[ -n "${2:-}" ]] || die "--port 缺少值"; port="$2"; shift 2 ;;
      --*) die "未知 stage 选项：$1" ;;
      *) [[ -z "$archive" ]] || die "stage 只能接受一个 .copperbak 文件"; archive="$1"; shift ;;
    esac
  done
  [[ -n "$archive" && -n "$identity" ]] || die "用法：backup restore stage FULL.copperbak [--incremental INC.copperbak ...] --identity AGE-KEY.txt [--restore-id ID] [--port PORT]"
  [[ -f "$archive" && ! -L "$archive" ]] || die "备份文件不存在、是链接或不是普通文件：$archive"
  [[ -f "$identity" && ! -L "$identity" ]] || die "age 私钥不存在、是链接或不是普通文件：$identity"
  archive="$(readlink -f "$archive")"
  identity="$(readlink -f "$identity")"
  [[ "$archive" == *.copperbak ]] || die "旁路恢复基础包只接受 copper-backup/v1 的 .copperbak 文件"
  [[ -f "$archive.sha256" && ! -L "$archive.sha256" ]] || die "缺少外层 SHA-256 文件：$archive.sha256"
  local incremental_index incremental_archive
  for incremental_index in "${!incremental_archives[@]}"; do
    incremental_archive="${incremental_archives[$incremental_index]}"
    [[ -f "$incremental_archive" && ! -L "$incremental_archive" && "$incremental_archive" == *.copperbak ]] \
      || die "增量备份不存在、不安全或扩展名不正确：$incremental_archive"
    incremental_archive="$(readlink -f "$incremental_archive")"
    [[ -f "$incremental_archive.sha256" && ! -L "$incremental_archive.sha256" ]] \
      || die "增量备份缺少外层 SHA-256：$incremental_archive.sha256"
    incremental_archives[$incremental_index]="$incremental_archive"
  done
  restore_id="${restore_id:-restore-$(date -u +%Y%m%dt%H%M%Sz)-$(openssl rand -hex 4)}"
  validate_restore_id "$restore_id"
  validate_identity "$identity"
  require_current_admin_handover_complete
  validate_port_available "$port"
  validate_runtime_capacity
  validate_images_available

  local restore_dir state_path event_log work outer payload increments_root stage_etc stage_var stage_env contract
  local available required expansion_limit archive_bytes attachment_bytes sql_bytes outer_format outer_backup_id outer_site_fingerprint outer_created_at outer_offline_proof_sha256 source_site_id source_site_fingerprint stage_success empty_tables missing_test_file
  restore_dir="$(restore_directory "$restore_id")"
  [[ ! -e "$restore_dir" ]] || die "restoreId 已存在：$restore_id"
  install -d -o root -g root -m 0700 "$restore_dir"
  state_path="$restore_dir/state.json"
  event_log="$restore_dir/events.jsonl"
  work="$restore_dir/work"
  outer="$work/outer"
  payload="$work/payload"
  increments_root="$work/increments"
  stage_etc="$restore_dir/etc"
  stage_var="$restore_dir/var"
  stage_env="$restore_dir/copper.env"
  contract="$restore_dir/contract"
  install -d -m 0700 "$work" "$outer" "$payload" "$increments_root"
  write_state "$state_path" "restoreId=$restore_id" "status=STAGING" "phase=preflight" "siteId=$site_id" "proxyPort=$port" "composeProject=copper-restore-$restore_id" "archiveSha256=$(sha256sum "$archive" | awk '{print $1}')" "lastError="
  record_event "$restore_dir" "$restore_id" stage started "preflight accepted"
  stage_success=0
  STAGE_SUCCESS=0
  STAGE_ENV="$stage_env"
  STAGE_WORK="$work"
  STAGE_ETC="$stage_etc"
  STAGE_VAR="$stage_var"
  STAGE_CONTRACT="$contract"
  STAGE_STATE="$state_path"
  STAGE_DIR="$restore_dir"
  STAGE_ID="$restore_id"

  cleanup_stage() {
    local status=$?
    set +e
    if [[ "$STAGE_SUCCESS" != "1" ]]; then
      capture_stage_failure_diagnostics
      [[ ! -f "$STAGE_ENV" ]] || stage_compose "$STAGE_ENV" down --remove-orphans >/dev/null 2>&1
      rm -rf --one-file-system -- "$STAGE_WORK" "$STAGE_ETC" "$STAGE_VAR" "$STAGE_CONTRACT"
      write_state "$STAGE_STATE" "status=FAILED" "phase=failed" "lastError=stage failed with exit $status" >/dev/null 2>&1
      record_event "$STAGE_DIR" "$STAGE_ID" stage failed "stage failed with exit $status" >/dev/null 2>&1
    fi
    trap - EXIT INT TERM
    exit "$status"
  }
  trap cleanup_stage EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  archive_bytes="$(stat -c '%s' "$archive")"
  for incremental_archive in "${incremental_archives[@]}"; do
    archive_bytes=$((archive_bytes + $(stat -c '%s' "$incremental_archive")))
  done
  available="$(available_restore_bytes)"
  required=$((archive_bytes * 4 + 536870912))
  [[ "$available" -ge "$required" ]] || die "恢复空间不足：初检需要至少约 $((required / 1048576)) MiB，当前约 $((available / 1048576)) MiB"

  note "校验外层 SHA-256 与安全解包 envelope"
  python3 "$restore_tool" extract-outer "$archive" "$archive.sha256" "$outer" > "$work/outer-summary.json"
  outer_format="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["formatVersion"])' "$work/outer-summary.json")"
  [[ "$outer_format" == "copper-backup/v1" ]] || die "增量恢复的基础包必须是 copper-backup/v1 全量备份"
  outer_backup_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["backupId"])' "$work/outer-summary.json")"
  outer_site_fingerprint="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["siteFingerprint"])' "$work/outer-summary.json")"
  outer_created_at="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["createdAt"])' "$work/outer-summary.json")"
  outer_offline_proof_sha256="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("offlineProofSha256", ""))' "$work/outer-summary.json")"
  write_state "$state_path" "backupId=$outer_backup_id"
  write_state "$state_path" "phase=decrypt-and-verify"

  note "使用客户私钥解密，并验证 payload 完整性与同版本约束"
  age --decrypt --identity "$identity" --output "$work/payload.tar.zst" "$outer/payload.tar.zst.age"
  test_fail_at after-decrypt
  zstd -q -t "$work/payload.tar.zst"
  available="$(available_restore_bytes)"
  expansion_limit=$((available - 536870912))
  [[ "$expansion_limit" -gt 0 ]] || die "恢复空间不足，无法安全解压 payload"
  zstd -q -d -c "$work/payload.tar.zst" | python3 "$restore_tool" bounded-copy "$work/payload.tar" "$expansion_limit"
  python3 "$restore_tool" extract-payload "$work/payload.tar" "$payload"
  python3 "$restore_tool" verify-payload "$payload" "$site_id" \
    "$(required_env_value COPPER_WEB_IMAGE)" "$(required_env_value COPPER_BACKEND_IMAGE)" "$(required_env_value COPPER_MYSQL_IMAGE)" \
    "$work/payload-summary.json" --expected-backup-id "$outer_backup_id" \
    --expected-site-fingerprint "$outer_site_fingerprint" --expected-created-at "$outer_created_at" \
    --expected-offline-proof-sha256 "$outer_offline_proof_sha256"
  source_site_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["sourceSiteId"])' "$work/payload-summary.json")"
  source_site_fingerprint="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["sourceSiteFingerprint"])' "$work/payload-summary.json")"
  write_state "$state_path" "sourceSiteId=$source_site_id" "sourceSiteFingerprint=$source_site_fingerprint"

  local inc_dir inc_outer inc_payload final_payload inc_summary inc_format inc_backup_id inc_base_id inc_parent_id inc_sequence inc_created_at inc_fingerprint inc_proof
  for incremental_index in "${!incremental_archives[@]}"; do
    incremental_archive="${incremental_archives[$incremental_index]}"
    inc_dir="$increments_root/$(printf '%04d' $((incremental_index + 1)))"
    inc_outer="$inc_dir/outer"
    inc_payload="$inc_dir/payload"
    install -d -m 0700 "$inc_dir" "$inc_outer" "$inc_payload"
    python3 "$restore_tool" extract-outer "$incremental_archive" "$incremental_archive.sha256" "$inc_outer" > "$inc_dir/outer-summary.json"
    inc_summary="$inc_dir/outer-summary.json"
    inc_format="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["formatVersion"])' "$inc_summary")"
    inc_backup_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["backupId"])' "$inc_summary")"
    inc_base_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["baseBackupId"])' "$inc_summary")"
    inc_parent_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["parentBackupId"])' "$inc_summary")"
    inc_sequence="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["chainSequence"])' "$inc_summary")"
    inc_created_at="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["createdAt"])' "$inc_summary")"
    inc_fingerprint="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["siteFingerprint"])' "$inc_summary")"
    inc_proof="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("offlineProofSha256", ""))' "$inc_summary")"
    [[ "$inc_format" == "copper-backup/v2" ]] || die "--incremental 只接受 copper-backup/v2 包"
    [[ "$inc_fingerprint" == "$source_site_fingerprint" ]] || die "增量包与全量基础包来源站点不一致"
    age --decrypt --identity "$identity" --output "$inc_dir/payload.tar.zst" "$inc_outer/payload.tar.zst.age"
    zstd -q -t "$inc_dir/payload.tar.zst"
    available="$(available_restore_bytes)"
    expansion_limit=$((available - 536870912))
    [[ "$expansion_limit" -gt 0 ]] || die "恢复空间不足，无法安全解压增量 payload"
    zstd -q -d -c "$inc_dir/payload.tar.zst" | python3 "$restore_tool" bounded-copy "$inc_dir/payload.tar" "$expansion_limit"
    python3 "$restore_tool" extract-payload "$inc_dir/payload.tar" "$inc_payload"
    python3 "$chain_tool" verify-incremental "$inc_payload" \
      "$(required_env_value COPPER_WEB_IMAGE)" "$(required_env_value COPPER_BACKEND_IMAGE)" "$(required_env_value COPPER_MYSQL_IMAGE)" \
      "$inc_dir/payload-summary.json" --expected-backup-id "$inc_backup_id" --expected-base-backup-id "$inc_base_id" \
      --expected-parent-backup-id "$inc_parent_id" --expected-sequence "$inc_sequence" \
      --expected-site-fingerprint "$inc_fingerprint" --expected-created-at "$inc_created_at" \
      --expected-offline-proof-sha256 "$inc_proof"
    incremental_payloads+=("$inc_payload")
  done
  if [[ "${#incremental_payloads[@]}" -gt 0 ]]; then
    python3 "$chain_tool" verify-chain "$payload" "${incremental_payloads[@]}" > "$work/chain-summary.json"
    write_state "$state_path" "incrementalCount=${#incremental_payloads[@]}" \
      "backupId=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["lastBackupId"])' "$work/chain-summary.json")"
  fi

  available="$(available_restore_bytes)"
  expansion_limit=$((available - 536870912))
  [[ "$expansion_limit" -gt 0 ]] || die "恢复空间不足，无法安全解压数据库"
  zstd -q -d -c "$payload/database/copper.sql.zst" | python3 "$restore_tool" bounded-copy "$work/copper.sql" "$expansion_limit"
  python3 "$restore_tool" validate-sql "$work/copper.sql" >/dev/null
  write_state "$state_path" "phase=prepare-empty-target"

  attachment_bytes="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["attachmentBytes"])' "$work/payload-summary.json")"
  if [[ "${#incremental_payloads[@]}" -gt 0 ]]; then
    attachment_bytes="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["totalBytes"])' \
      "${incremental_payloads[$((${#incremental_payloads[@]} - 1))]}/verification/attachments.json")"
  fi
  sql_bytes="$(stat -c '%s' "$work/copper.sql")"
  available="$(available_restore_bytes)"
  required=$((sql_bytes * 3 + attachment_bytes * 2 + 536870912))
  [[ "$available" -ge "$required" ]] || die "恢复空间不足：精检需要至少约 $((required / 1048576)) MiB，当前约 $((available / 1048576)) MiB"

  prepare_stage_directories "$stage_etc" "$stage_var"
  copy_payload_attachments "$payload/attachments" "$stage_var/files"
  for inc_payload in "${incremental_payloads[@]}"; do
    python3 "$chain_tool" apply-delta "$inc_payload" "$stage_var/files"
  done
  chown -R 10001:10001 "$stage_var/files"
  find "$stage_var/files" -type d -exec chmod 0750 {} +
  find "$stage_var/files" -type f -exec chmod 0640 {} +
  if [[ "${COPPER_RESTORE_TEST_DELETE_ATTACHMENT:-0}" == "1" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" ]] || die "附件丢失注入只允许隔离测试模式"
    missing_test_file="$(find "$stage_var/files" -type f -print -quit)"
    [[ -z "$missing_test_file" ]] || rm -f -- "$missing_test_file"
  fi
  write_stage_env "$stage_env" "$restore_id" "$stage_etc" "$stage_var" "$port"
  stage_compose "$stage_env" config --quiet
  stage_compose "$stage_env" up -d --remove-orphans --wait --wait-timeout 240 mysql
  require_services_healthy stage "$stage_env" mysql
  test_fail_at after-mysql
  empty_tables="$(printf '%s\n' "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE';" | stage_mysql_cli "$stage_env" | tr -d '[:space:]')"
  [[ "$empty_tables" == "0" ]] || die "旁路 MySQL 目标不是空数据库"
  write_state "$state_path" "phase=database-import"
  stage_mysql_cli "$stage_env" < "$work/copper.sql"
  for inc_payload in "${incremental_payloads[@]}"; do
    replay_incremental_binlogs "$stage_env" "$inc_payload"
  done
  test_fail_at after-import
  if [[ "${COPPER_RESTORE_TEST_DELAY_AFTER_IMPORT:-0}" =~ ^[0-9]+$ && "${COPPER_RESTORE_TEST_DELAY_AFTER_IMPORT:-0}" -gt 0 ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" ]] || die "恢复延迟注入只允许隔离测试模式"
    sleep "$COPPER_RESTORE_TEST_DELAY_AFTER_IMPORT"
  fi

  install -d -m 0700 "$contract" "$contract/expected" "$contract/imported" "$contract/staged"
  write_state "$state_path" "phase=database-and-attachment-verification"
  if [[ "${#incremental_payloads[@]}" -gt 0 ]]; then
    final_payload="${incremental_payloads[$((${#incremental_payloads[@]} - 1))]}"
    cp "$final_payload/verification/schema.json" "$final_payload/verification/record-counts.json" \
      "$final_payload/verification/business-totals.json" "$final_payload/verification/attachments.json" "$contract/expected/"
  else
    cp "$payload/verification/"*.json "$contract/expected/"
  fi
  capture_verification stage "$stage_env" "$stage_var/files" "$contract/imported"
  compare_verification "$contract/expected" "$contract/imported" "$contract/imported-result.json"

  stage_compose "$stage_env" up -d --remove-orphans --wait --wait-timeout 300 backend
  require_services_healthy stage "$stage_env" mysql backend
  capture_verification stage "$stage_env" "$stage_var/files" "$contract/staged"
  compare_verification "$contract/expected" "$contract/staged" "$contract/staged-result.json"
  write_state "$state_path" "phase=sidecar-readiness"
  test_fail_at after-validate
  stage_compose "$stage_env" up -d --remove-orphans --wait --wait-timeout 240 web
  require_services_healthy stage "$stage_env" mysql backend web
  curl --fail --silent --show-error --max-time 8 "http://127.0.0.1:$port/readyz" >/dev/null

  cp "$payload/manifest.json" "$payload/config/runtime-allowlist.json" "$contract/"
  if [[ "${#incremental_payloads[@]}" -gt 0 ]]; then
    install -d -m 0700 "$contract/incrementals"
    for incremental_index in "${!incremental_payloads[@]}"; do
      cp "${incremental_payloads[$incremental_index]}/manifest.json" \
        "$contract/incrementals/$(printf '%04d' $((incremental_index + 1)))-manifest.json"
    done
    cp "$work/chain-summary.json" "$contract/"
  fi
  rm -rf --one-file-system -- "$work"
  write_state "$state_path" "status=VALIDATED" "phase=ready-for-operator" "validatedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" "lastError="
  record_event "$restore_dir" "$restore_id" stage passed "database, balances, foreign keys and attachments matched"
  stage_success=1
  STAGE_SUCCESS=1
  trap - EXIT INT TERM
  note "旁路恢复已验证：restoreId=$restore_id，入口=http://127.0.0.1:$port"
  note "当前生产数据尚未修改；人工验收后执行 copperctl backup restore switch $restore_id"
}

upgrade_target_restore() {
  local restore_id="$1" source_version="$2" source_schema="$3" target_version="$4" target_schema="$5" prepared_sha="$6"
  local target_stage_env="$7" target_current_env="$8" backup_id="$9" manifest_sha="${10}" migration_started_at="${11}"
  local restore_dir state_path stage_env status source_capture target_capture diagnostics audit_count control_plane_state
  restore_dir="$(restore_directory "$restore_id")"
  state_path="$restore_dir/state.json"
  stage_env="$restore_dir/copper.env"
  [[ -f "$state_path" && -f "$stage_env" ]] || die "找不到升级旁路：$restore_id"
  [[ -f "$target_stage_env" && ! -L "$target_stage_env" && -f "$target_current_env" && ! -L "$target_current_env" ]] \
    || die "目标运行配置不存在或不安全"
  status="$(state_value "$state_path" status)"
  [[ "$status" == "VALIDATED" ]] || die "只有刚完成同版本恢复的 VALIDATED 旁路可以进入迁移，当前为 $status"
  [[ "$source_schema" =~ ^[0-9]+$ && "$target_schema" =~ ^[0-9]+$ ]] || die "升级 schema 必须是非负整数"
  [[ "$prepared_sha" =~ ^[0-9a-f]{64}$ ]] || die "PREPARED 摘要格式不正确"
  [[ "$source_version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$ \
    && "$target_version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$ ]] || die "升级产品版本格式不正确"
  [[ "$backup_id" =~ ^[0-9a-f]{32}$ ]] || die "升级备份 ID 格式不正确"
  [[ "$manifest_sha" =~ ^[0-9a-f]{64}$ ]] || die "升级发布清单摘要格式不正确"
  [[ "$migration_started_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}$ ]] \
    || die "升级迁移开始时间格式不正确"
  require_services_healthy current "" mysql backend web
  require_services_healthy stage "$stage_env" mysql backend web
  control_plane_state="$restore_dir/control-plane.json"
  [[ -x "$control_plane_tool" && -f "$control_plane_state" && ! -L "$control_plane_state" ]] \
    || die "升级控制程序暂存证据缺失或不安全"
  "$control_plane_tool" status --state "$control_plane_state" --expect source >/dev/null

  source_capture="$restore_dir/contract/migration-source"
  target_capture="$restore_dir/contract/migration-target"
  diagnostics="$restore_dir/diagnostics"
  rm -rf --one-file-system -- "$source_capture" "$target_capture"
  capture_verification stage "$stage_env" "$restore_dir/var/files" "$source_capture"
  compare_verification "$restore_dir/contract/expected" "$source_capture" "$restore_dir/contract/migration-source-result.json"
  install -o root -g root -m 0600 "$ENV_FILE" "$restore_dir/source-current.env"
  install -o root -g root -m 0600 "$stage_env" "$restore_dir/source-stage.env"
  install -o root -g root -m 0600 "$target_current_env" "$restore_dir/target-current.env"
  require_env_images_available "$target_stage_env"
  write_state "$state_path" "status=MIGRATING" "phase=target-flyway-migration" \
    "upgradeMode=retained-sidecar" "sourceVersion=$source_version" "sourceSchema=$source_schema" \
    "targetVersion=$target_version" "targetSchema=$target_schema" "preparedSha256=$prepared_sha" "lastError="
  record_event "$restore_dir" "$restore_id" migrate started "target backend owns Flyway on isolated restored data"

  if ! stage_compose "$stage_env" stop -t 45 web backend; then
    write_state "$state_path" "status=MIGRATION_FAILED" "phase=source-sidecar-stop-failed" "lastError=source sidecar did not stop cleanly"
    record_event "$restore_dir" "$restore_id" migrate failed "source sidecar did not stop cleanly; current site remained live"
    die "来源旁路未能安全停止；当前站点未改变"
  fi
  if ! atomic_install_env "$target_stage_env" "$stage_env"; then
    write_state "$state_path" "status=MIGRATION_FAILED" "phase=target-env-install-failed" "lastError=target stage env install failed"
    record_event "$restore_dir" "$restore_id" migrate failed "target stage env install failed; current site remained live"
    die "目标旁路运行配置安装失败；当前站点未改变"
  fi
  if ! stage_compose "$stage_env" up -d --pull never --remove-orphans --wait --wait-timeout 300 mysql backend; then
    install -d -o root -g root -m 0700 "$diagnostics"
    stage_compose "$stage_env" logs --no-color --tail 300 backend > "$diagnostics/migration-backend.log" 2>&1 || true
    chmod 0600 "$diagnostics/migration-backend.log" 2>/dev/null || true
    write_state "$state_path" "status=MIGRATION_FAILED" "phase=target-flyway-failed" "lastError=target backend or Flyway did not become healthy"
    record_event "$restore_dir" "$restore_id" migrate failed "target backend or Flyway did not become healthy; current site remained live"
    die "目标 Backend/Flyway 迁移失败；当前站点未停止，诊断位于 $diagnostics/migration-backend.log"
  fi
  if ! require_services_healthy stage "$stage_env" mysql backend; then
    write_state "$state_path" "status=MIGRATION_FAILED" "phase=target-health-failed" "lastError=target mysql/backend unhealthy"
    record_event "$restore_dir" "$restore_id" migrate failed "target mysql/backend unhealthy; current site remained live"
    die "目标旁路 MySQL/Backend 未通过健康门槛；当前站点未停止"
  fi
  if ! audit_count="$(printf '%s\n' \
    "INSERT INTO system_upgrade_audit (id, upgrade_id, source_version, target_version, source_schema, target_schema, status, backup_id, release_manifest_sha256, started_at, migration_validated_at) VALUES (UUID(), '$restore_id', '$source_version', '$target_version', '$source_schema', '$target_schema', 'MIGRATION_VALIDATED', '$backup_id', '$manifest_sha', '$migration_started_at', UTC_TIMESTAMP()); SELECT COUNT(*) FROM system_upgrade_audit WHERE upgrade_id='$restore_id' AND status='MIGRATION_VALIDATED';" \
    | stage_mysql_cli "$stage_env" | awk 'END {print}' | tr -d '[:space:]')"; then
    write_state "$state_path" "status=MIGRATION_FAILED" "phase=target-upgrade-audit-failed" "lastError=target upgrade audit insert failed"
    record_event "$restore_dir" "$restore_id" migrate failed "schema migrated but durable upgrade audit insert failed; current site remained live"
    die "目标迁移审计写入失败；当前站点未停止，禁止切换"
  fi
  [[ "$audit_count" == "1" ]] || {
    write_state "$state_path" "status=MIGRATION_FAILED" "phase=target-upgrade-audit-failed" "lastError=target upgrade audit was not recorded"
    record_event "$restore_dir" "$restore_id" migrate failed "schema migrated but durable upgrade audit was not recorded; current site remained live"
    die "目标迁移审计未能唯一落库；当前站点未停止，禁止切换"
  }
  if ! (capture_verification stage "$stage_env" "$restore_dir/var/files" "$target_capture" \
    && python3 "$restore_tool" compare-upgrade-verification \
      "$source_capture" "$target_capture" "$target_capture/foreign-keys.tsv" "$restore_dir/contract/migration-result.json" \
      --source-schema "$source_schema" --target-schema "$target_schema"); then
    write_state "$state_path" "status=MIGRATION_FAILED" "phase=target-contract-failed" "lastError=target migration verification failed"
    record_event "$restore_dir" "$restore_id" migrate failed "schema, Flyway history, business, attachment or foreign-key contract failed; current site remained live"
    die "目标迁移数据合同未通过；当前站点未停止"
  fi
  if ! stage_compose "$stage_env" up -d --pull never --remove-orphans --wait --wait-timeout 240 web; then
    write_state "$state_path" "status=MIGRATION_FAILED" "phase=target-web-failed" "lastError=target web unhealthy"
    record_event "$restore_dir" "$restore_id" migrate failed "target web unhealthy; current site remained live"
    die "目标旁路 Web 未通过健康门槛；当前站点未停止"
  fi
  if ! require_services_healthy stage "$stage_env" mysql backend web; then
    write_state "$state_path" "status=MIGRATION_FAILED" "phase=target-three-layer-failed" "lastError=target three-layer health failed"
    record_event "$restore_dir" "$restore_id" migrate failed "target three-layer health failed; current site remained live"
    die "目标旁路三层健康门槛失败；当前站点未改变"
  fi
  rm -rf --one-file-system -- "$restore_dir/contract/expected"
  install -d -o root -g root -m 0700 "$restore_dir/contract/expected"
  cp "$target_capture/schema.json" "$target_capture/record-counts.json" \
    "$target_capture/business-totals.json" "$target_capture/attachments.json" \
    "$target_capture/flyway-history.tsv" "$restore_dir/contract/expected/"
  chmod 0600 "$restore_dir/contract/expected/"*.json "$restore_dir/contract/expected/flyway-history.tsv"
  write_state "$state_path" "status=MIGRATION_VALIDATED" "phase=ready-for-upgrade-switch" \
    "validatedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" "lastError="
  record_event "$restore_dir" "$restore_id" migrate passed "target Flyway, schema, business totals, attachments and foreign keys validated"
  note "隔离迁移已验证：restoreId=$restore_id；当前站点仍使用 $source_version/schema-$source_schema"
}

confirm_action() {
  local expected="$1"
  if [[ "${COPPER_TEST_MODE:-0}" == "1" && "${COPPER_RESTORE_TEST_CONFIRM:-0}" == "1" ]]; then
    return
  fi
  [[ -t 0 && -t 1 ]] || die "切换与回滚必须在可信交互终端人工确认"
  local confirmation
  printf '请输入 %s 继续：' "$expected"
  IFS= read -r confirmation
  [[ "$confirmation" == "$expected" ]] || die "二次确认不匹配，未修改当前数据"
}

swap_into_current() {
  local restore_dir="$1" state_path="$2" restore_id="$3" stage_env="$4"
  local stage_var="$restore_dir/var" rollback="$restore_dir/rollback" upgrade_mode audit_count control_plane_state
  SWITCH_RESTORE_DIR="$restore_dir"
  SWITCH_STATE="$state_path"
  SWITCH_ID="$restore_id"
  SWITCH_ENV="$stage_env"
  SWITCH_STAGE_VAR="$stage_var"
  SWITCH_ROLLBACK="$rollback"
  SWITCH_ORIGINAL_MYSQL_MOVED=0
  SWITCH_ORIGINAL_FILES_MOVED=0
  SWITCH_RESTORED_MYSQL_MOVED=0
  SWITCH_RESTORED_FILES_MOVED=0
  SWITCH_ORIGINAL_CAPTURED=0
  SWITCH_UPGRADE_MODE=0
  SWITCH_ENV_SWAPPED=0
  SWITCH_AUDIT_UPDATED=0
  SWITCH_CONTROL_SWAPPED=0
  SWITCH_COMPLETE=0
  install -d -m 0700 "$rollback"
  [[ ! -e "$rollback/mysql" && ! -e "$rollback/files" ]] || die "回滚槽已存在，拒绝覆盖"
  [[ -d "$stage_var/mysql" && -d "$stage_var/files" ]] || die "旁路数据目录不完整"
  [[ "$(stat -c '%d' "$var_dir")" == "$(stat -c '%d' "$restore_root")" ]] || die "当前数据与旁路恢复根目录不在同一文件系统，不能原子目录切换"
  upgrade_mode="$(state_value "$state_path" upgradeMode)"
  if [[ "$upgrade_mode" == "retained-sidecar" ]]; then
    [[ -f "$restore_dir/source-current.env" && -f "$restore_dir/target-current.env" ]] || die "升级运行配置交换槽不完整"
    cmp -s "$ENV_FILE" "$restore_dir/source-current.env" || die "正式运行配置在隔离迁移后发生变化，拒绝切换"
    install -o root -g root -m 0600 "$ENV_FILE" "$rollback/source-current.env"
    control_plane_state="$restore_dir/control-plane.json"
    [[ -x "$control_plane_tool" && -f "$control_plane_state" && ! -L "$control_plane_state" ]] \
      || die "升级控制程序切换证据缺失或不安全"
    "$control_plane_tool" status --state "$control_plane_state" --expect source >/dev/null
    SWITCH_UPGRADE_MODE=1
  elif [[ -n "$upgrade_mode" ]]; then
    die "不支持的升级切换模式：$upgrade_mode"
  fi

  cleanup_switch() {
    local status=$?
    local recovery_ok=1 recovered="$SWITCH_RESTORE_DIR/contract/recovered" target_recovered=0 audit_count=""
    [[ "$SWITCH_COMPLETE" == "1" ]] && return
    trap - EXIT INT TERM
    set +e
    (compose_cmd down --remove-orphans >/dev/null 2>&1) || true
    if [[ "$SWITCH_RESTORED_MYSQL_MOVED" == "1" ]]; then
      if [[ -d "$var_dir/mysql" && ! -e "$SWITCH_STAGE_VAR/mysql" ]]; then
        mv "$var_dir/mysql" "$SWITCH_STAGE_VAR/mysql" || recovery_ok=0
      elif [[ -d "$SWITCH_STAGE_VAR/mysql" && ! -e "$var_dir/mysql" ]]; then
        :
      else
        recovery_ok=0
      fi
    fi
    if [[ "$SWITCH_RESTORED_FILES_MOVED" == "1" ]]; then
      if [[ -d "$var_dir/files" && ! -e "$SWITCH_STAGE_VAR/files" ]]; then
        mv "$var_dir/files" "$SWITCH_STAGE_VAR/files" || recovery_ok=0
      elif [[ -d "$SWITCH_STAGE_VAR/files" && ! -e "$var_dir/files" ]]; then
        :
      else
        recovery_ok=0
      fi
    fi
    if [[ "$SWITCH_ORIGINAL_MYSQL_MOVED" == "1" ]]; then
      if [[ -d "$SWITCH_ROLLBACK/mysql" && ! -e "$var_dir/mysql" ]]; then
        mv "$SWITCH_ROLLBACK/mysql" "$var_dir/mysql" || recovery_ok=0
      elif [[ -d "$var_dir/mysql" && ! -e "$SWITCH_ROLLBACK/mysql" ]]; then
        :
      else
        recovery_ok=0
      fi
    fi
    if [[ "$SWITCH_ORIGINAL_FILES_MOVED" == "1" ]]; then
      if [[ -d "$SWITCH_ROLLBACK/files" && ! -e "$var_dir/files" ]]; then
        mv "$SWITCH_ROLLBACK/files" "$var_dir/files" || recovery_ok=0
      elif [[ -d "$var_dir/files" && ! -e "$SWITCH_ROLLBACK/files" ]]; then
        :
      else
        recovery_ok=0
      fi
    fi
    if [[ "$SWITCH_UPGRADE_MODE" == "1" && "$SWITCH_ENV_SWAPPED" == "1" ]]; then
      atomic_install_env "$SWITCH_ROLLBACK/source-current.env" "$ENV_FILE" || recovery_ok=0
      SWITCH_ENV_SWAPPED=0
    fi
    if [[ "$SWITCH_UPGRADE_MODE" == "1" && "$SWITCH_CONTROL_SWAPPED" == "1" ]]; then
      "$control_plane_tool" activate --state "$SWITCH_RESTORE_DIR/control-plane.json" --side source >/dev/null 2>&1 || recovery_ok=0
      SWITCH_CONTROL_SWAPPED=0
    fi
    if [[ "$recovery_ok" == "1" ]]; then
      (compose_cmd up -d --pull never --remove-orphans --wait --wait-timeout 300 mysql backend >/dev/null 2>&1) || recovery_ok=0
    fi
    if [[ "$recovery_ok" == "1" && "$SWITCH_ORIGINAL_CAPTURED" == "1" ]]; then
      rm -rf --one-file-system -- "$recovered" "$SWITCH_RESTORE_DIR/contract/recovered-result.json"
      (capture_verification current "" "$var_dir/files" "$recovered" >/dev/null 2>&1 \
        && compare_verification "$SWITCH_RESTORE_DIR/contract/original" "$recovered" "$SWITCH_RESTORE_DIR/contract/recovered-result.json" >/dev/null 2>&1) || recovery_ok=0
    fi
    if [[ "$recovery_ok" == "1" ]]; then
      (compose_cmd up -d --pull never --remove-orphans --wait --wait-timeout 240 web >/dev/null 2>&1) || recovery_ok=0
    fi
    if [[ "$recovery_ok" == "1" ]]; then
      if (stage_compose "$SWITCH_ENV" up -d --pull never --remove-orphans --wait --wait-timeout 300 >/dev/null 2>&1); then
        target_recovered=1
        if [[ "$SWITCH_UPGRADE_MODE" == "1" && "$SWITCH_AUDIT_UPDATED" == "1" ]]; then
          audit_count="$(printf '%s\n' \
            "UPDATE system_upgrade_audit SET status='MIGRATION_VALIDATED', switched_at=NULL, completed_at=NULL WHERE upgrade_id='$SWITCH_ID' AND status='SWITCHED'; SELECT ROW_COUNT();" \
            | stage_mysql_cli "$SWITCH_ENV" | awk 'END {print}' | tr -d '[:space:]')" || target_recovered=0
          [[ "$audit_count" == "1" ]] || target_recovered=0
        fi
        if [[ "$target_recovered" != "1" ]]; then
          write_state "$SWITCH_STATE" "status=MIGRATION_FAILED" "phase=target-sidecar-recovery-failed" "lastError=switch failed with exit $status; original verified; target audit recovery failed" >/dev/null 2>&1
          record_event "$SWITCH_RESTORE_DIR" "$SWITCH_ID" switch failed "original site verified; target audit recovery failed and sidecar must be discarded" >/dev/null 2>&1
        elif [[ "$SWITCH_UPGRADE_MODE" == "1" ]]; then
          write_state "$SWITCH_STATE" "status=MIGRATION_VALIDATED" "phase=ready-for-upgrade-switch" "lastError=switch failed with exit $status; original verified" >/dev/null 2>&1
        else
          write_state "$SWITCH_STATE" "status=VALIDATED" "phase=ready-for-operator" "lastError=switch failed with exit $status; original verified" >/dev/null 2>&1
        fi
        [[ "$target_recovered" != "1" ]] || record_event "$SWITCH_RESTORE_DIR" "$SWITCH_ID" switch failed "original data verified before traffic recovery" >/dev/null 2>&1
      else
        if [[ "$SWITCH_UPGRADE_MODE" == "1" ]]; then
          write_state "$SWITCH_STATE" "status=MIGRATION_FAILED" "phase=discard-target-sidecar" "lastError=switch failed with exit $status; original verified; target sidecar restart failed" >/dev/null 2>&1
        else
          write_state "$SWITCH_STATE" "status=FAILED" "phase=discard-sidecar" "lastError=switch failed with exit $status; original verified; sidecar restart failed" >/dev/null 2>&1
        fi
        record_event "$SWITCH_RESTORE_DIR" "$SWITCH_ID" switch failed "original site verified and live; sidecar restart failed" >/dev/null 2>&1
      fi
    else
      write_state "$SWITCH_STATE" "status=RECOVERY_FAILED" "phase=operator-intervention-required" "lastError=switch recovery requires operator intervention" >/dev/null 2>&1
      record_event "$SWITCH_RESTORE_DIR" "$SWITCH_ID" switch failed "automatic recovery did not pass validation; traffic remains stopped" >/dev/null 2>&1
      echo "严重错误：切换失败后的原数据恢复未通过验证，Web 保持停止；禁止手工放行流量" >&2
    fi
    exit "$status"
  }
  trap cleanup_switch EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  write_state "$state_path" "phase=quiescing-current-site"
  compose_cmd stop -t 45 web backend
  wait_for_current_transactions
  rm -rf --one-file-system -- "$restore_dir/contract/original"
  capture_verification current "" "$var_dir/files" "$restore_dir/contract/original"
  SWITCH_ORIGINAL_CAPTURED=1
  compose_cmd stop -t 45 mysql
  stage_compose "$stage_env" down --remove-orphans
  write_state "$state_path" "phase=swapping-data-directories"
  SWITCH_ORIGINAL_MYSQL_MOVED=1
  mv "$var_dir/mysql" "$rollback/mysql"
  SWITCH_ORIGINAL_FILES_MOVED=1
  mv "$var_dir/files" "$rollback/files"
  SWITCH_RESTORED_MYSQL_MOVED=1
  mv "$stage_var/mysql" "$var_dir/mysql"
  SWITCH_RESTORED_FILES_MOVED=1
  mv "$stage_var/files" "$var_dir/files"
  if [[ "$SWITCH_UPGRADE_MODE" == "1" ]]; then
    atomic_install_env "$restore_dir/target-current.env" "$ENV_FILE"
    SWITCH_ENV_SWAPPED=1
    "$control_plane_tool" activate --state "$control_plane_state" --side target
    SWITCH_CONTROL_SWAPPED=1
  fi
  test_fail_at after-switch-swap

  write_state "$state_path" "phase=post-switch-verification"
  compose_cmd up -d --pull never --remove-orphans --wait --wait-timeout 300 mysql backend
  require_services_healthy current "" mysql backend
  rm -rf --one-file-system -- "$restore_dir/contract/switched"
  capture_verification current "" "$var_dir/files" "$restore_dir/contract/switched"
  compare_verification "$restore_dir/contract/expected" "$restore_dir/contract/switched" "$restore_dir/contract/switched-result.json"
  if [[ "$SWITCH_UPGRADE_MODE" == "1" ]]; then
    if ! audit_count="$(printf '%s\n' \
      "UPDATE system_upgrade_audit SET status='SWITCHED', switched_at=UTC_TIMESTAMP(), completed_at=UTC_TIMESTAMP(), failure_phase='', failure_message='' WHERE upgrade_id='$restore_id' AND status='MIGRATION_VALIDATED'; SELECT ROW_COUNT();" \
      | current_mysql_cli | awk 'END {print}' | tr -d '[:space:]')"; then
      die "升级切换审计更新失败；将恢复原站并保持 Web 停止直至验证完成"
    fi
    [[ "$audit_count" == "1" ]] || die "升级切换审计未能唯一更新；将恢复原站并保持 Web 停止直至验证完成"
    SWITCH_AUDIT_UPDATED=1
    test_fail_at after-switch-audit
  fi
  compose_cmd up -d --pull never --remove-orphans --wait --wait-timeout 240 web
  require_services_healthy current "" mysql backend web
  if [[ "$SWITCH_UPGRADE_MODE" == "1" ]]; then
    "$control_plane_tool" status --state "$control_plane_state" --expect target >/dev/null
  fi
  write_state "$state_path" "status=SWITCHED" "phase=restored-site-live" "switchedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" "lastError="
  record_event "$restore_dir" "$restore_id" switch passed "restored data validated before traffic resumed"
  SWITCH_COMPLETE=1
  trap - EXIT INT TERM
  rm -rf --one-file-system -- "$restore_dir/etc"
}

switch_restore() {
  local restore_id="$1" restore_dir state_path stage_env status pre_switch
  restore_dir="$(restore_directory "$restore_id")"
  state_path="$restore_dir/state.json"
  stage_env="$restore_dir/copper.env"
  [[ -f "$state_path" && -f "$stage_env" ]] || die "找不到 restoreId：$restore_id"
  status="$(state_value "$state_path" status)"
  [[ "$status" == "VALIDATED" || "$status" == "MIGRATION_VALIDATED" ]] \
    || die "只有 VALIDATED 恢复或 MIGRATION_VALIDATED 升级旁路可以切换，当前为 $status"
  require_services_healthy stage "$stage_env" mysql backend web
  require_services_healthy current "" mysql backend web
  pre_switch="$restore_dir/contract/pre-switch"
  rm -rf --one-file-system -- "$pre_switch" "$restore_dir/contract/pre-switch-result.json"
  if ! (capture_verification stage "$stage_env" "$restore_dir/var/files" "$pre_switch" \
    && compare_verification "$restore_dir/contract/expected" "$pre_switch" "$restore_dir/contract/pre-switch-result.json"); then
    write_state "$state_path" "status=DIRTY" "phase=restage-required" "lastError=sidecar changed after validation"
    record_event "$restore_dir" "$restore_id" switch failed "sidecar changed after validation; current data was not stopped"
    die "旁路数据在 VALIDATED 后发生写入，当前站点尚未停止；请 discard 后从原备份重新 stage，再直接 switch"
  fi
  write_state "$state_path" "phase=operator-confirmation" "lastError="
  if [[ "$status" == "MIGRATION_VALIDATED" ]]; then
    echo "将把已验证升级 $restore_id 切换为站点 $site_id 的当前数据与目标镜像配置；旧数据和旧配置会完整保留。"
    confirm_action "UPGRADE $site_id $restore_id"
  else
    echo "将把已验证备份 $restore_id 切换为站点 $site_id 的当前数据；原 MySQL 与附件会完整保留用于回滚。"
    confirm_action "SWITCH $site_id $restore_id"
  fi
  swap_into_current "$restore_dir" "$state_path" "$restore_id" "$stage_env"
  note "恢复切换完成并已通过切换后验证；原数据仍保留，可执行 copperctl backup restore rollback $restore_id"
}

rollback_restore() {
  local restore_id="$1" restore_dir state_path stage_var rollback status upgrade_mode control_plane_state
  restore_dir="$(restore_directory "$restore_id")"
  state_path="$restore_dir/state.json"
  stage_var="$restore_dir/var"
  rollback="$restore_dir/rollback"
  ROLLBACK_RESTORE_DIR="$restore_dir"
  ROLLBACK_STATE="$state_path"
  ROLLBACK_ID="$restore_id"
  ROLLBACK_STAGE_VAR="$stage_var"
  ROLLBACK_SLOT="$rollback"
  ROLLBACK_RESTORED_MYSQL_MOVED=0
  ROLLBACK_RESTORED_FILES_MOVED=0
  ROLLBACK_ORIGINAL_MYSQL_MOVED=0
  ROLLBACK_ORIGINAL_FILES_MOVED=0
  ROLLBACK_UPGRADE_MODE=0
  ROLLBACK_ENV_SWAPPED=0
  ROLLBACK_CONTROL_SWAPPED=0
  ROLLBACK_COMPLETE=0
  [[ -f "$state_path" ]] || die "找不到 restoreId：$restore_id"
  status="$(state_value "$state_path" status)"
  [[ "$status" == "SWITCHED" ]] || die "只有 SWITCHED 的恢复可以回滚，当前为 $status"
  [[ -d "$rollback/mysql" && -d "$rollback/files" && ! -e "$stage_var/mysql" && ! -e "$stage_var/files" ]] || die "回滚数据槽不完整或目标不为空"
  upgrade_mode="$(state_value "$state_path" upgradeMode)"
  if [[ "$upgrade_mode" == "retained-sidecar" ]]; then
    [[ -f "$rollback/source-current.env" && -f "$restore_dir/target-current.env" ]] || die "升级回滚配置槽不完整"
    cmp -s "$ENV_FILE" "$restore_dir/target-current.env" || die "正式运行配置在升级后发生变化，拒绝自动回滚"
    control_plane_state="$restore_dir/control-plane.json"
    [[ -x "$control_plane_tool" && -f "$control_plane_state" && ! -L "$control_plane_state" ]] \
      || die "升级控制程序回滚证据缺失或不安全"
    "$control_plane_tool" status --state "$control_plane_state" --expect target >/dev/null
    ROLLBACK_UPGRADE_MODE=1
  elif [[ -n "$upgrade_mode" ]]; then
    die "不支持的升级回滚模式：$upgrade_mode"
  fi
  require_services_healthy current "" mysql backend web
  echo "将恢复切换前完整保留的 MySQL 与附件，并把当前恢复数据移回旁路目录。"
  confirm_action "ROLLBACK $site_id $restore_id"

  cleanup_rollback() {
    local exit_status=$?
    local recovery_ok=1 recovered="$ROLLBACK_RESTORE_DIR/contract/rollback-recovered"
    [[ "$ROLLBACK_COMPLETE" == "1" ]] && return
    trap - EXIT INT TERM
    set +e
    (compose_cmd down --remove-orphans >/dev/null 2>&1) || true
    if [[ "$ROLLBACK_ORIGINAL_MYSQL_MOVED" == "1" ]]; then
      if [[ -d "$var_dir/mysql" && ! -e "$ROLLBACK_SLOT/mysql" ]]; then
        mv "$var_dir/mysql" "$ROLLBACK_SLOT/mysql" || recovery_ok=0
      elif [[ -d "$ROLLBACK_SLOT/mysql" && ! -e "$var_dir/mysql" ]]; then
        :
      else
        recovery_ok=0
      fi
    fi
    if [[ "$ROLLBACK_ORIGINAL_FILES_MOVED" == "1" ]]; then
      if [[ -d "$var_dir/files" && ! -e "$ROLLBACK_SLOT/files" ]]; then
        mv "$var_dir/files" "$ROLLBACK_SLOT/files" || recovery_ok=0
      elif [[ -d "$ROLLBACK_SLOT/files" && ! -e "$var_dir/files" ]]; then
        :
      else
        recovery_ok=0
      fi
    fi
    if [[ "$ROLLBACK_RESTORED_MYSQL_MOVED" == "1" ]]; then
      if [[ -d "$ROLLBACK_STAGE_VAR/mysql" && ! -e "$var_dir/mysql" ]]; then
        mv "$ROLLBACK_STAGE_VAR/mysql" "$var_dir/mysql" || recovery_ok=0
      elif [[ -d "$var_dir/mysql" && ! -e "$ROLLBACK_STAGE_VAR/mysql" ]]; then
        :
      else
        recovery_ok=0
      fi
    fi
    if [[ "$ROLLBACK_RESTORED_FILES_MOVED" == "1" ]]; then
      if [[ -d "$ROLLBACK_STAGE_VAR/files" && ! -e "$var_dir/files" ]]; then
        mv "$ROLLBACK_STAGE_VAR/files" "$var_dir/files" || recovery_ok=0
      elif [[ -d "$var_dir/files" && ! -e "$ROLLBACK_STAGE_VAR/files" ]]; then
        :
      else
        recovery_ok=0
      fi
    fi
    if [[ "$ROLLBACK_UPGRADE_MODE" == "1" && "$ROLLBACK_ENV_SWAPPED" == "1" ]]; then
      atomic_install_env "$ROLLBACK_RESTORE_DIR/target-current.env" "$ENV_FILE" || recovery_ok=0
      ROLLBACK_ENV_SWAPPED=0
    fi
    if [[ "$ROLLBACK_UPGRADE_MODE" == "1" && "$ROLLBACK_CONTROL_SWAPPED" == "1" ]]; then
      "$control_plane_tool" activate --state "$ROLLBACK_RESTORE_DIR/control-plane.json" --side target >/dev/null 2>&1 || recovery_ok=0
      ROLLBACK_CONTROL_SWAPPED=0
    fi
    if [[ "$recovery_ok" == "1" ]]; then
      (compose_cmd up -d --pull never --remove-orphans --wait --wait-timeout 300 mysql backend >/dev/null 2>&1) || recovery_ok=0
    fi
    if [[ "$recovery_ok" == "1" ]]; then
      rm -rf --one-file-system -- "$recovered" "$ROLLBACK_RESTORE_DIR/contract/rollback-recovered-result.json"
      (capture_verification current "" "$var_dir/files" "$recovered" >/dev/null 2>&1 \
        && compare_verification "$ROLLBACK_RESTORE_DIR/contract/expected" "$recovered" "$ROLLBACK_RESTORE_DIR/contract/rollback-recovered-result.json" >/dev/null 2>&1) || recovery_ok=0
    fi
    if [[ "$recovery_ok" == "1" ]]; then
      (compose_cmd up -d --pull never --remove-orphans --wait --wait-timeout 240 web >/dev/null 2>&1) || recovery_ok=0
    fi
    if [[ "$recovery_ok" == "1" ]]; then
      write_state "$ROLLBACK_STATE" "status=SWITCHED" "phase=restored-site-live" "lastError=rollback failed with exit $exit_status; restored data verified" >/dev/null 2>&1
      record_event "$ROLLBACK_RESTORE_DIR" "$ROLLBACK_ID" rollback failed "restored data verified before traffic recovery" >/dev/null 2>&1
    else
      write_state "$ROLLBACK_STATE" "status=RECOVERY_FAILED" "phase=operator-intervention-required" "lastError=rollback recovery requires operator intervention" >/dev/null 2>&1
      record_event "$ROLLBACK_RESTORE_DIR" "$ROLLBACK_ID" rollback failed "automatic recovery did not pass validation; traffic remains stopped" >/dev/null 2>&1
      echo "严重错误：回滚失败后的恢复数据未通过验证，Web 保持停止；禁止手工放行流量" >&2
    fi
    exit "$exit_status"
  }
  trap cleanup_rollback EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  write_state "$state_path" "phase=quiescing-restored-site"
  compose_cmd stop -t 45 web backend
  wait_for_current_transactions
  compose_cmd stop -t 45 mysql
  ROLLBACK_RESTORED_MYSQL_MOVED=1
  mv "$var_dir/mysql" "$stage_var/mysql"
  ROLLBACK_RESTORED_FILES_MOVED=1
  mv "$var_dir/files" "$stage_var/files"
  ROLLBACK_ORIGINAL_MYSQL_MOVED=1
  mv "$rollback/mysql" "$var_dir/mysql"
  ROLLBACK_ORIGINAL_FILES_MOVED=1
  mv "$rollback/files" "$var_dir/files"
  if [[ "$ROLLBACK_UPGRADE_MODE" == "1" ]]; then
    atomic_install_env "$rollback/source-current.env" "$ENV_FILE"
    ROLLBACK_ENV_SWAPPED=1
    "$control_plane_tool" activate --state "$control_plane_state" --side source
    ROLLBACK_CONTROL_SWAPPED=1
  fi
  test_fail_at after-rollback-swap
  write_state "$state_path" "phase=post-rollback-verification"
  compose_cmd up -d --pull never --remove-orphans --wait --wait-timeout 300 mysql backend
  require_services_healthy current "" mysql backend
  rm -rf --one-file-system -- "$restore_dir/contract/rolled-back"
  capture_verification current "" "$var_dir/files" "$restore_dir/contract/rolled-back"
  compare_verification "$restore_dir/contract/original" "$restore_dir/contract/rolled-back" "$restore_dir/contract/rolled-back-result.json"
  compose_cmd up -d --pull never --remove-orphans --wait --wait-timeout 240 web
  require_services_healthy current "" mysql backend web
  if [[ "$ROLLBACK_UPGRADE_MODE" == "1" ]]; then
    "$control_plane_tool" status --state "$control_plane_state" --expect source >/dev/null
  fi
  write_state "$state_path" "status=ROLLED_BACK" "phase=original-site-live" "rolledBackAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" "lastError="
  record_event "$restore_dir" "$restore_id" rollback passed "original data validated before traffic resumed"
  ROLLBACK_COMPLETE=1
  trap - EXIT INT TERM
  note "回滚完成并已验证；恢复出的数据仍在旁路目录，确认无须保留后可 discard"
}

discard_restore() {
  local restore_id="$1" supplied_confirmation="${2:-}" restore_dir state_path stage_env status
  restore_dir="$(restore_directory "$restore_id")"
  state_path="$restore_dir/state.json"
  stage_env="$restore_dir/copper.env"
  [[ -f "$state_path" ]] || die "找不到 restoreId：$restore_id"
  status="$(state_value "$state_path" status)"
  [[ "$status" == "VALIDATED" || "$status" == "DIRTY" || "$status" == "ROLLED_BACK" || "$status" == "FAILED" \
    || "$status" == "MIGRATION_VALIDATED" || "$status" == "MIGRATION_FAILED" ]] \
    || die "状态 $status 仍持有切换前原数据，禁止删除"
  if [[ -n "$supplied_confirmation" ]]; then
    [[ "$supplied_confirmation" == "DISCARD $restore_id" ]] \
      || die "旁路删除确认编号不匹配，未删除任何数据"
  else
    confirm_action "DISCARD $restore_id"
  fi
  if [[ "$status" == "VALIDATED" || "$status" == "DIRTY" || "$status" == "MIGRATION_VALIDATED" || "$status" == "MIGRATION_FAILED" || "$status" == "FAILED" ]]; then
    if [[ -f "$stage_env" ]]; then
      stage_compose "$stage_env" down --remove-orphans
    elif [[ "$status" != "FAILED" ]]; then
      die "旁路 Compose 配置缺失，拒绝在未停容器时删除"
    fi
  fi
  rm -rf --one-file-system -- "$restore_dir"
  note "已删除旁路恢复 $restore_id；当前站点数据未修改"
}

accept_upgrade_restore() {
  local restore_id="$1" supplied_confirmation="${2:-}" restore_dir state_path status upgrade_mode
  restore_dir="$(restore_directory "$restore_id")"
  state_path="$restore_dir/state.json"
  [[ -f "$state_path" ]] || die "找不到 restoreId：$restore_id"
  status="$(state_value "$state_path" status)"
  upgrade_mode="$(state_value "$state_path" upgradeMode)"
  [[ "$status" == "SWITCHED" && "$upgrade_mode" == "retained-sidecar" ]] \
    || die "只有已切换且仍保留旧版本回滚副本的升级可以完成验收"
  [[ -d "$restore_dir/rollback/mysql" && -d "$restore_dir/rollback/files" ]] \
    || die "升级回滚副本不完整；拒绝完成验收或删除现场"
  require_services_healthy current "" mysql backend web
  if [[ -n "$supplied_confirmation" ]]; then
    [[ "$supplied_confirmation" == "ACCEPT UPGRADE $site_id $restore_id" ]] \
      || die "升级验收确认内容不匹配，未删除任何回滚数据"
  else
    echo "将接受站点 $site_id 的升级 $restore_id，并永久删除切换前的旧数据库、旧附件和旧配置副本。"
    confirm_action "ACCEPT UPGRADE $site_id $restore_id"
  fi
  rm -rf --one-file-system -- "$restore_dir"
  note "升级已接受；旧版本回滚副本已删除，当前新版本数据和服务未修改"
}

show_status() {
  local restore_id="${1:-}" restore_dir state_file found=0
  if [[ -n "$restore_id" ]]; then
    restore_dir="$(restore_directory "$restore_id")"
    [[ -f "$restore_dir/state.json" ]] || die "找不到 restoreId：$restore_id"
    python3 "$restore_tool" show-state "$restore_dir/state.json"
    return
  fi
  while IFS= read -r -d '' state_file; do
    found=1
    python3 "$restore_tool" show-state "$state_file"
  done < <(find "$restore_root" -mindepth 2 -maxdepth 2 -type f -name state.json -print0 | LC_ALL=C sort -z)
  [[ "$found" == "1" ]] || note "当前没有旁路恢复任务"
}

if [[ "$restore_command" == "status" ]]; then
  require_restore_root
else
  require_restore_tools
  lock_operations
fi

case "$restore_command" in
  stage)
    shift
    stage_restore "$@"
    ;;
  upgrade-target)
    [[ $# -eq 12 ]] || die "内部用法：backup restore upgrade-target ID SOURCE_VERSION SOURCE_SCHEMA TARGET_VERSION TARGET_SCHEMA PREPARED_SHA TARGET_STAGE_ENV TARGET_CURRENT_ENV BACKUP_ID MANIFEST_SHA MIGRATION_STARTED_AT"
    upgrade_target_restore "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" "${11}" "${12}"
    ;;
  switch)
    [[ $# -eq 2 ]] || die "用法：backup restore switch RESTORE_ID"
    switch_restore "$2"
    ;;
  rollback)
    [[ $# -eq 2 ]] || die "用法：backup restore rollback RESTORE_ID"
    rollback_restore "$2"
    ;;
  discard)
    if [[ $# -eq 2 ]]; then
      discard_restore "$2"
    elif [[ $# -eq 4 && "$3" == "--confirm" ]]; then
      discard_restore "$2" "$4"
    else
      die "用法：backup restore discard RESTORE_ID [--confirm 'DISCARD RESTORE_ID']"
    fi
    ;;
  accept-upgrade)
    if [[ $# -eq 2 ]]; then
      accept_upgrade_restore "$2"
    elif [[ $# -eq 4 && "$3" == "--confirm" ]]; then
      accept_upgrade_restore "$2" "$4"
    else
      die "用法：backup restore accept-upgrade RESTORE_ID [--confirm 'ACCEPT UPGRADE SITE_ID RESTORE_ID']"
    fi
    ;;
  status)
    [[ $# -le 2 ]] || die "用法：backup restore status [RESTORE_ID]"
    show_status "${2:-}"
    ;;
  *)
    die "恢复支持 stage、status、switch、rollback、discard 或 accept-upgrade"
    ;;
esac
