#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"
source "$SCRIPT_DIR/verification-sql.sh"

require_root
validate_tools
validate_environment

etc_dir="$(required_env_value COPPER_ETC_DIR)"
var_dir="$(required_env_value COPPER_VAR_DIR)"
backup_dir="$(backup_dir_value)"
site_id="$(required_env_value COPPER_SITE_ID)"
metadata_tool="$SCRIPT_DIR/backup-metadata.py"
chain_tool="$SCRIPT_DIR/backup-chain.py"
storage_tool="$SCRIPT_DIR/storage-target.py"

require_healthy_site() {
  local service_name service_id state health
  for service_name in mysql backend web; do
    service_id="$(compose_cmd ps -q "$service_name" 2>/dev/null || true)"
    [[ -n "$service_id" ]] || die "$service_name 容器不存在；先运行 copperctl up 和 copperctl doctor"
    state="$(docker inspect --format '{{.State.Status}}' "$service_id")"
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$service_id")"
    [[ "$state" == "running" && "$health" == "healthy" ]] || die "$service_name 当前为 $state/$health；备份前必须先恢复三层健康"
  done
}

wait_for_stable_site_health() {
  local attempt stable_observations=0 service_name service_id state health all_healthy
  note "三层服务已经恢复，继续观察稳定状态后再返回操作菜单"
  for attempt in $(seq 1 15); do
    all_healthy=1
    for service_name in mysql backend web; do
      service_id="$(compose_cmd ps -q "$service_name" 2>/dev/null || true)"
      if [[ -z "$service_id" ]]; then
        all_healthy=0
        break
      fi
      state="$(docker inspect --format '{{.State.Status}}' "$service_id" 2>/dev/null || true)"
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$service_id" 2>/dev/null || true)"
      if [[ "$state/$health" != "running/healthy" ]]; then
        all_healthy=0
        break
      fi
    done
    if [[ "$all_healthy" == "1" ]]; then
      stable_observations=$((stable_observations + 1))
      if [[ "$stable_observations" -ge 3 ]]; then
        note "三层服务已连续通过稳定健康检查"
        return
      fi
    else
      stable_observations=0
    fi
    sleep 3
  done
  echo "错误：备份后服务没有持续稳定；程序不会返回正常菜单，请运行 copperctl doctor 查看原因" >&2
  return 1
}

require_no_pending_initial_admin_credential() {
  local credential_file="$etc_dir/credentials/initial-admin.txt"
  [[ ! -e "$credential_file" && ! -L "$credential_file" ]] \
    || die "首次管理员一次性凭据尚未完成交付和改密；该明文凭据不会进入备份，必须先完成首次改密再创建可恢复备份"
}

require_m2_backup_tools() {
  local command_name
  for command_name in age awk cp df du find flock grep mv od openssl python3 rm seq sha256sum sort stat sync tar tr xargs zstd; do
    require_command "$command_name"
  done
  [[ -x "$metadata_tool" ]] || die "缺少可执行元数据工具 $metadata_tool"
  [[ -x "$chain_tool" ]] || die "缺少可执行增量备份链工具 $chain_tool"
  [[ -x "$storage_tool" ]] || die "缺少可执行存储目标工具 $storage_tool"
}

backup_policy_value() {
  local name="$1" default_value="$2" value
  value="$(env_value "$name" || true)"
  value="${value:-$default_value}"
  [[ "$value" =~ ^[0-9]+$ ]] || die "$name 必须是非负整数"
  printf '%s' "$value"
}

backup_recipient() {
  local recipient
  recipient="$(env_value COPPER_BACKUP_AGE_RECIPIENT || true)"
  [[ "$recipient" =~ ^age1[0-9a-z]{20,}$ ]] || die "COPPER_BACKUP_AGE_RECIPIENT 必须是客户持有私钥对应的 age X25519 公钥"
  printf '' | age --encrypt --recipient "$recipient" --output /dev/null \
    || die "COPPER_BACKUP_AGE_RECIPIENT 不是 age 可接受的有效公钥"
  printf '%s' "$recipient"
}

lock_backup_operations() {
  install -d -o root -g root -m 0700 "$backup_dir"
  exec 9>"$backup_dir/.operation.lock"
  flock -n 9 || die "已有备份、校验或恢复操作正在运行，请勿并发执行"
}

mysql_cli() {
  compose_cmd exec -T mysql sh -c \
    'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot -D copper_v2 --batch --raw --skip-column-names'
}

mysql_query() {
  printf '%s\n' "$1" | mysql_cli
}

mysql_dump() {
  compose_cmd exec -T mysql sh -c \
    'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysqldump -uroot --databases copper_v2 --single-transaction --quick --routines --triggers --events --hex-blob --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4'
}

require_incremental_binlog() {
  local settings
  settings="$(mysql_query 'SELECT @@GLOBAL.log_bin, @@GLOBAL.binlog_format, @@GLOBAL.binlog_row_image, @@GLOBAL.binlog_expire_logs_seconds;' | tr -d '\r')"
  [[ "$settings" == $'1\tROW\tFULL\t2592000' ]] \
    || die "MySQL 增量备份要求 log_bin=ON、binlog_format=ROW、binlog_row_image=FULL、保留 30 天；当前=$settings"
}

mysql_binlog_status() {
  local status file_name position
  status="$(mysql_query 'SHOW BINARY LOG STATUS;' | head -n 1 | tr -d '\r')"
  IFS=$'\t' read -r file_name position _ <<< "$status"
  [[ "$file_name" =~ ^binlog\.[0-9]{6,}$ && "$position" =~ ^[0-9]+$ && "$position" -ge 4 ]] \
    || die "无法读取受支持的 MySQL binlog 边界：$status"
  printf '%s\t%s\n' "$file_name" "$position"
}

rotate_mysql_binlog() {
  mysql_query 'FLUSH BINARY LOGS;' >/dev/null
  mysql_binlog_status
}

snapshot_attachment_files() {
  local files_root="$1" output_tsv="$2" file relative size digest unexpected
  : > "$output_tsv"
  if [[ ! -d "$files_root" ]]; then
    return
  fi
  unexpected="$(find "$files_root" -mindepth 1 ! -type d ! -type f -print -quit)"
  [[ -z "$unexpected" ]] || die "附件目录包含符号链接或非常规文件，拒绝备份：$unexpected"
  while IFS= read -r -d '' file; do
    relative="${file#"$files_root"/}"
    [[ -n "$relative" && "$relative" != *$'\t'* && "$relative" != *$'\n'* && "$relative" != *$'\r'* ]] \
      || die "附件路径为空或包含制表/换行字符"
    size="$(stat -c '%s' "$file")"
    digest="$(sha256sum "$file" | awk '{print $1}')"
    printf '%s\t%s\t%s\n' "$relative" "$size" "$digest" >> "$output_tsv"
  done < <(find "$files_root" -type f -print0 | LC_ALL=C sort -z)
}

copy_closed_binlogs() {
  local from_file="$1" to_file="$2" target="$3" output_tsv="$4"
  local list_file found_from=0 found_to=0 include=0 file source destination size digest
  install -d -m 0700 "$target"
  : > "$output_tsv"
  list_file="$(mktemp "$backup_dir/.binlog-list-XXXXXX")"
  mysql_query 'SHOW BINARY LOGS;' > "$list_file"
  while IFS=$'\t' read -r file _; do
    [[ "$file" =~ ^binlog\.[0-9]{6,}$ ]] || { rm -f "$list_file"; die "MySQL 返回了不安全的 binlog 文件名：$file"; }
    if [[ "$file" == "$from_file" ]]; then
      found_from=1
      include=1
    fi
    if [[ "$file" == "$to_file" ]]; then
      found_to=1
      include=0
      break
    fi
    if [[ "$include" == "1" ]]; then
      source="$var_dir/mysql/$file"
      destination="$target/$file"
      [[ -f "$source" && ! -L "$source" ]] || { rm -f "$list_file"; die "增量所需 binlog 已不存在或不安全：$file；必须重新创建全量基础备份"; }
      cp --reflink=auto --preserve=mode,timestamps "$source" "$destination"
      chmod 0600 "$destination"
      size="$(stat -c '%s' "$destination")"
      digest="$(sha256sum "$destination" | awk '{print $1}')"
      printf '%s\t%s\t%s\n' "$file" "$size" "$digest" >> "$output_tsv"
    fi
  done < "$list_file"
  rm -f "$list_file"
  [[ "$found_from" == "1" && "$found_to" == "1" && -s "$output_tsv" ]] \
    || die "binlog 链不连续（from=$from_file to=$to_file）；必须重新创建全量基础备份"
}

can_continue_incremental_chain() {
  local state="$backup_dir/chain-state.json" info from_file archive_name archive_sha archive
  [[ -f "$state" && ! -L "$state" ]] || return 1
  info="$(python3 "$chain_tool" state-info "$state" "$site_id" 2>/dev/null)" || return 1
  IFS=$'\t' read -r _ _ _ from_file _ archive_name archive_sha _ <<< "$info"
  archive="$backup_dir/$archive_name"
  [[ -f "$archive" && ! -L "$archive" && "$(sha256sum "$archive" | awk '{print $1}')" == "$archive_sha" ]] || return 1
  mysql_query 'SHOW BINARY LOGS;' | awk -F'\t' -v wanted="$from_file" '$1 == wanted {found=1} END {exit found ? 0 : 1}'
}

wait_for_transactions_to_drain() {
  local attempt active_transactions
  for attempt in $(seq 1 30); do
    active_transactions="$(mysql_query 'SELECT COUNT(*) FROM information_schema.innodb_trx;' | tr -d '[:space:]')"
    [[ "$active_transactions" =~ ^[0-9]+$ ]] || die "无法读取 MySQL 活跃事务数量"
    if [[ "$active_transactions" == "0" ]]; then
      return
    fi
    sleep 1
  done
  die "停止 Backend 后仍有 MySQL 活跃事务，拒绝生成不一致备份"
}

test_fail_at() {
  local point="$1"
  if [[ "${COPPER_BACKUP_TEST_FAIL_AT:-}" == "$point" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" ]] || die "备份故障注入只允许隔离测试模式"
    die "测试注入失败点：$point"
  fi
}

test_delay_after_stop() {
  local delay="${COPPER_BACKUP_TEST_DELAY_AFTER_STOP:-0}"
  [[ "$delay" =~ ^[0-9]+$ ]] || die "COPPER_BACKUP_TEST_DELAY_AFTER_STOP 必须是非负整数"
  if [[ "$delay" -gt 0 ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" ]] || die "备份延迟注入只允许隔离测试模式"
    note "测试模式：停止服务后等待 ${delay} 秒"
    sleep "$delay"
  fi
}

validate_no_blob_only_attachments() {
  local count
  count="$(mysql_query "
    SELECT
      (SELECT COUNT(*) FROM inbound_attachment WHERE (storage_path IS NULL OR storage_path = '') AND file_content IS NOT NULL AND OCTET_LENGTH(file_content) > 0)
      +
      (SELECT COUNT(*) FROM inbound_review_snapshot_attachment WHERE (storage_path IS NULL OR storage_path = '') AND file_content IS NOT NULL AND OCTET_LENGTH(file_content) > 0);
  " | tr -d '[:space:]')"
  [[ "$count" =~ ^[0-9]+$ ]] || die "无法检查旧 BLOB 附件"
  [[ "$count" == "0" ]] || die "仍有 $count 个仅存于数据库 BLOB 的附件；必须先完成文件迁移和校验"
}

capture_attachment_files() {
  local files_root="$1" target_root="$2" output_tsv="$3" file relative size digest unexpected
  install -d -m 0700 "$target_root"
  : > "$output_tsv"
  if [[ ! -d "$files_root" ]]; then
    return
  fi
  unexpected="$(find "$files_root" -mindepth 1 ! -type d ! -type f -print -quit)"
  [[ -z "$unexpected" ]] || die "附件目录包含符号链接或非常规文件，拒绝备份：$unexpected"
  while IFS= read -r -d '' file; do
    relative="${file#"$files_root"/}"
    [[ -n "$relative" && "$relative" != *$'\t'* && "$relative" != *$'\n'* && "$relative" != *$'\r'* ]] \
      || die "附件路径为空或包含制表/换行字符"
    size="$(stat -c '%s' "$file")"
    digest="$(sha256sum "$file" | awk '{print $1}')"
    printf '%s\t%s\t%s\n' "$relative" "$size" "$digest" >> "$output_tsv"
  done < <(find "$files_root" -type f -print0 | sort -z)
  tar --format=pax --numeric-owner -C "$files_root" -cf - . | tar --numeric-owner -C "$target_root" -xf -
}

verify_m2_archive() (
  set -Eeuo pipefail
  local archive="$1" sidecar="${2:-$1.sha256}" archive_name staging members expected_members
  [[ -f "$archive" && ! -L "$archive" ]] || die "备份文件不存在或不是普通文件：$archive"
  [[ -f "$sidecar" && ! -L "$sidecar" ]] || die "缺少备份校验文件：$sidecar"
  archive_name="$(basename "$archive")"
  verify_sha256_sidecar "$archive" "$sidecar" "$archive_name" >/dev/null
  staging="$(mktemp -d "$(dirname "$archive")/.verify-XXXXXX")"
  trap 'rm -rf --one-file-system -- "$staging"' EXIT
  members="$(tar -tf "$archive" | LC_ALL=C sort)"
  expected_members=$'README.txt\nchecksums.sha256\nenvelope.json\npayload.tar.zst.age'
  [[ "$members" == "$expected_members" ]] || die "备份包外层文件不完整或包含未声明内容"
  tar -tvf "$archive" | awk '{type=substr($1,1,1); if (type != "-") bad=1} END {exit bad ? 1 : 0}' \
    || die "备份包外层包含目录、链接或设备文件"
  tar -xf "$archive" -C "$staging"
  (cd "$staging" && sha256sum --strict -c checksums.sha256 >/dev/null)
  python3 "$metadata_tool" verify-envelope "$staging"
)

apply_retention() {
  local dry_run="${1:-0}" daily weekly monthly filename deleted plan
  daily="$(backup_policy_value COPPER_BACKUP_KEEP_DAILY 7)"
  weekly="$(backup_policy_value COPPER_BACKUP_KEEP_WEEKLY 4)"
  monthly="$(backup_policy_value COPPER_BACKUP_KEEP_MONTHLY 6)"
  if ! plan="$(python3 "$chain_tool" retention-plan "$backup_dir" "$site_id" "$daily" "$weekly" "$monthly")"; then
    return 1
  fi
  deleted=0
  while IFS= read -r filename; do
    [[ -n "$filename" ]] || continue
    [[ "$filename" != */* && "$filename" == "$site_id"_*.copperbak ]] || die "保留策略返回了不安全文件名"
    if [[ "$dry_run" == "1" ]]; then
      echo "$filename"
      continue
    fi
    "$storage_tool" --env-file "$ENV_FILE" delete "$filename" >/dev/null
    note "已按保留策略删除旧备份：$filename"
    deleted=$((deleted + 1))
  done <<< "$plan"
  if [[ "$dry_run" == "0" && "$deleted" == "0" ]]; then
    note "保留策略无需删除旧备份"
  fi
}

create_m2_backup() {
  local purpose="$1" recipient recipient_fingerprint timestamp started_at completed_at backup_id archive_name archive final_sidecar
  local staging payload_dir outer_dir database_version schema_version database_bytes attachment_bytes available_bytes required_bytes
  local stopped committed published files_tsv references_tsv table_names_tsv counts_tsv totals_tsv counts_sql totals_sql refs_sql
  local age_version zstd_version last_success_tmp chain_state_tmp offline_proof offline_proof_sha256
  local binlog_status binlog_file binlog_position archive_sha256

  require_m2_backup_tools
  recipient="$(backup_recipient)"
  recipient_fingerprint="$(printf '%s' "$recipient" | sha256sum | awk '{print substr($1, 1, 16)}')"
  lock_backup_operations
  require_healthy_site
  require_no_pending_initial_admin_credential
  require_incremental_binlog
  backup_policy_value COPPER_BACKUP_KEEP_DAILY 7 >/dev/null
  backup_policy_value COPPER_BACKUP_KEEP_WEEKLY 4 >/dev/null
  backup_policy_value COPPER_BACKUP_KEEP_MONTHLY 6 >/dev/null

  database_bytes="$(mysql_query "SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE();" | tr -d '[:space:]')"
  [[ "$database_bytes" =~ ^[0-9]+$ ]] || die "无法估算数据库大小"
  attachment_bytes="$(du -sb "$var_dir/files" 2>/dev/null | awk '{print $1}' || true)"
  attachment_bytes="${attachment_bytes:-0}"
  [[ "$attachment_bytes" =~ ^[0-9]+$ ]] || die "无法估算附件大小"
  available_bytes="$(df -PB1 "$backup_dir" | awk 'END {print $(NF-2)}')"
  if [[ -n "${COPPER_BACKUP_TEST_AVAILABLE_BYTES:-}" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" && "$COPPER_BACKUP_TEST_AVAILABLE_BYTES" =~ ^[0-9]+$ ]] \
      || die "可用空间故障注入只允许隔离测试模式并使用非负整数"
    available_bytes="$COPPER_BACKUP_TEST_AVAILABLE_BYTES"
  fi
  required_bytes=$((database_bytes * 3 + attachment_bytes * 2 + 536870912))
  [[ "$available_bytes" -ge "$required_bytes" ]] \
    || die "备份空间不足：需要至少约 $((required_bytes / 1048576)) MiB，当前约 $((available_bytes / 1048576)) MiB"
  "$SCRIPT_DIR/storage-capacity.sh" >/dev/null 2>&1 || true
  "$storage_tool" --env-file "$ENV_FILE" preflight "$required_bytes" >/dev/null

  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  backup_id="$(openssl rand -hex 16)"
  offline_proof="$(openssl rand -hex 32)"
  offline_proof_sha256="$(printf '%s' "$offline_proof" | sha256sum | awk '{print $1}')"
  archive_name="${site_id}_${timestamp}_${backup_id}_${purpose}.copperbak"
  archive="$backup_dir/$archive_name"
  final_sidecar="$archive.sha256"
  [[ ! -e "$archive" && ! -e "$final_sidecar" ]] || die "备份文件已存在：$archive"

  staging="$(mktemp -d "$backup_dir/.backup-XXXXXX")"
  chmod 0700 "$staging"
  payload_dir="$staging/payload"
  outer_dir="$staging/outer"
  install -d -m 0700 "$payload_dir/database" "$payload_dir/attachments" "$payload_dir/config" "$payload_dir/verification" "$outer_dir"
  files_tsv="$staging/attachment-files.tsv"
  references_tsv="$staging/attachment-references.tsv"
  table_names_tsv="$staging/table-names.tsv"
  counts_tsv="$staging/record-counts.tsv"
  totals_tsv="$staging/business-totals.tsv"
  counts_sql="$staging/record-counts.sql"
  totals_sql="$staging/business-totals.sql"
  refs_sql="$staging/attachment-references.sql"
  stopped=0
  committed=0
  published=0

  cleanup_create() {
    local status=$?
    set +e
    if [[ "$stopped" == "1" ]]; then
      if ! compose_cmd up -d --remove-orphans --wait --wait-timeout 240 >/dev/null 2>&1 \
        || ! wait_for_stable_site_health >/dev/null 2>&1; then
        echo "错误：备份中断后服务未能自动恢复健康；业务不得进入就绪，请立即运行 copperctl doctor" >&2
        status=1
      fi
    fi
    if [[ "$committed" != "1" && "$published" == "1" ]]; then
      "$storage_tool" --env-file "$ENV_FILE" delete "$archive_name" >/dev/null 2>&1 || true
      rm -f -- "$archive" "$final_sidecar" "$archive.success.json"
    fi
    rm -rf --one-file-system -- "$staging"
    "$SCRIPT_DIR/storage-capacity.sh" >/dev/null 2>&1 || true
    trap - EXIT INT TERM
    exit "$status"
  }
  trap cleanup_create EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  note "进入一致性备份窗口：先停止 Web 和 Backend，MySQL 保持内部可用"
  stopped=1
  compose_cmd stop -t 45 web backend
  wait_for_transactions_to_drain
  test_delay_after_stop
  test_fail_at after-stop

  note "轮换 MySQL binlog 并固定全量基础备份边界"
  binlog_status="$(rotate_mysql_binlog)"
  IFS=$'\t' read -r binlog_file binlog_position <<< "$binlog_status"

  database_version="$(mysql_query 'SELECT VERSION();' | tr -d '[:space:]')"
  schema_version="$(mysql_query "SELECT COALESCE((SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1), 'unversioned');" | tr -d '[:space:]')"
  [[ -n "$database_version" && -n "$schema_version" ]] || die "无法读取数据库或结构版本"

  mysql_query "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME;" > "$table_names_tsv"
  write_table_counts_sql "$counts_sql"
  mysql_cli < "$counts_sql" > "$counts_tsv"
  write_business_totals_sql "$totals_sql"
  mysql_cli < "$totals_sql" > "$totals_tsv"
  validate_no_blob_only_attachments
  write_attachment_references_sql "$refs_sql"
  mysql_cli < "$refs_sql" > "$references_tsv"

  note "导出 MySQL 逻辑快照并压缩"
  mysql_dump > "$payload_dir/database/copper.sql"
  [[ -s "$payload_dir/database/copper.sql" ]] || die "MySQL 逻辑备份为空"
  zstd -q -T0 -6 -f "$payload_dir/database/copper.sql" -o "$payload_dir/database/copper.sql.zst"
  rm -f "$payload_dir/database/copper.sql"

  note "复制附件原件并逐项核对数据库元数据、大小和 SHA-256"
  capture_attachment_files "$var_dir/files" "$payload_dir/attachments" "$files_tsv"
  python3 "$metadata_tool" attachments "$files_tsv" "$references_tsv" "$payload_dir/verification/attachments.json"
  python3 "$metadata_tool" record-counts "$counts_tsv" "$payload_dir/verification/record-counts.json"
  python3 "$metadata_tool" business-totals "$totals_tsv" "$payload_dir/verification/business-totals.json"
  python3 "$metadata_tool" schema "$payload_dir/verification/schema.json" "$database_version" "$schema_version" "$table_names_tsv"
  printf '%s\n' "$offline_proof" > "$payload_dir/verification/offline-proof.txt"
  chmod 0600 "$payload_dir/verification/offline-proof.txt"
  python3 "$metadata_tool" runtime-config "$payload_dir/config/runtime-allowlist.json" \
    "siteId=$site_id" \
    "projectName=$(required_env_value COPPER_PROJECT_NAME)" \
    "deployMode=$(required_env_value COPPER_DEPLOY_MODE)" \
    "domain=$(required_env_value COPPER_DOMAIN)" \
    "timeZone=$(env_value COPPER_TIME_ZONE || printf 'Asia/Shanghai')" \
    "logLevel=$(env_value COPPER_LOG_LEVEL || printf 'INFO')" \
    "ocrEnabled=$(ocr_env_value COPPER_OCR_ENABLED false)" \
    "ocrEndpoint=$(ocr_env_value COPPER_OCR_ENDPOINT)" \
    "ocrNetwork=$(ocr_env_value COPPER_OCR_NETWORK)" \
    "ocrEngine=$(ocr_env_value COPPER_OCR_ENGINE PADDLEOCR_LOCAL)" \
    "ocrModel=$(ocr_env_value COPPER_OCR_MODEL PP-OCRv6-medium)" \
    "ocrTimeoutSeconds=$(ocr_env_value COPPER_OCR_TIMEOUT_SECONDS 120)" \
    "bootstrapUsername=$(env_value COPPER_BOOTSTRAP_USERNAME || printf 'admin')" \
    "databaseName=copper_v2" \
    "etcDirectory=$etc_dir" \
    "dataDirectory=$var_dir" \
    "attachmentDirectory=$var_dir/files" \
    "webImage=$(required_env_value COPPER_WEB_IMAGE)" \
    "backendImage=$(required_env_value COPPER_BACKEND_IMAGE)" \
    "mysqlImage=$(required_env_value COPPER_MYSQL_IMAGE)"
  test_fail_at after-snapshot

  note "原始快照已捕获，正在恢复三层服务并等待就绪"
  compose_cmd up -d --remove-orphans --wait --wait-timeout 240
  wait_for_stable_site_health
  stopped=0
  completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  age_version="$(age --version 2>&1)"
  zstd_version="$(zstd --version 2>&1)"
  python3 "$metadata_tool" manifest \
    "$payload_dir/manifest.json" "$backup_id" "$site_id" "$purpose" "$started_at" "$completed_at" \
    "$(env_value COPPER_TIME_ZONE || printf 'Asia/Shanghai')" \
    "$(required_env_value COPPER_WEB_IMAGE)" "$(required_env_value COPPER_BACKEND_IMAGE)" "$(required_env_value COPPER_MYSQL_IMAGE)" \
    "$database_version" "$schema_version" "$payload_dir/database/copper.sql.zst" \
    "$payload_dir/verification/record-counts.json" "$payload_dir/verification/business-totals.json" \
    "$payload_dir/verification/attachments.json" "$payload_dir/config/runtime-allowlist.json" "$payload_dir/verification/schema.json" \
    "${COPPERCTL_VERSION:-0.2.0-m2}" "$age_version" "$zstd_version" \
    --binlog-file "$binlog_file" --binlog-position "$binlog_position"

  (
    cd "$payload_dir"
    find . -type f ! -name checksums.sha256 -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > checksums.sha256
  )

  note "压缩并使用客户 age 公钥加密备份载荷"
  tar --format=pax --numeric-owner -C "$payload_dir" -cf - . | zstd -q -T0 -6 -o "$staging/payload.tar.zst"
  age --encrypt --recipient "$recipient" --output "$outer_dir/payload.tar.zst.age" "$staging/payload.tar.zst"
  rm -f "$staging/payload.tar.zst"
  python3 "$metadata_tool" envelope "$outer_dir/envelope.json" "$backup_id" "$site_id" "$completed_at" \
    "$outer_dir/payload.tar.zst.age" "$recipient_fingerprint" "$offline_proof_sha256"
  cat > "$outer_dir/README.txt" <<'EOF'
Copper backup package format: copper-backup/v1

This outer file is a POSIX pax tar archive. Its encrypted payload uses the
public age format. Verify checksums before decryption. The recovery identity
is held by the customer and is intentionally not included in this package.
EOF
  (
    cd "$outer_dir"
    sha256sum envelope.json payload.tar.zst.age README.txt > checksums.sha256
    tar --format=pax -cf "$staging/$archive_name" envelope.json payload.tar.zst.age checksums.sha256 README.txt
  )
  chmod 0600 "$staging/$archive_name"
  (cd "$staging" && sha256sum "$archive_name" > "$archive_name.sha256")
  chmod 0600 "$staging/$archive_name.sha256"

  if [[ "${COPPER_BACKUP_TEST_CORRUPT_BEFORE_VERIFY:-0}" == "1" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" ]] || die "校验损坏注入只允许隔离测试模式"
    printf 'corrupt' >> "$staging/$archive_name"
  fi
  verify_m2_archive "$staging/$archive_name" "$staging/$archive_name.sha256"
  test_fail_at before-publish

  published=1
  mv "$staging/$archive_name" "$archive"
  test_fail_at during-publish
  mv "$staging/$archive_name.sha256" "$final_sidecar"
  sync -f "$archive" "$final_sidecar"
  local configured_target target_names=""
  while IFS= read -r configured_target; do
    [[ -z "$configured_target" ]] || target_names="${target_names}${target_names:+,}${configured_target}"
  done < <("$storage_tool" --env-file "$ENV_FILE" targets)
  [[ -n "$target_names" ]] || die "没有可用的备份发布目标"
  note "将同一备份包发布到已配置目标：$target_names"
  "$storage_tool" --env-file "$ENV_FILE" publish "$archive" "$final_sidecar" >/dev/null
  archive_sha256="$(sha256sum "$archive" | awk '{print $1}')"
  chain_state_tmp="$staging/chain-state.json"
  python3 "$chain_tool" write-state "$chain_state_tmp" "$site_id" "$backup_id" "$backup_id" "" 0 \
    "$completed_at" "$binlog_file" "$binlog_position" "$archive" "$archive_sha256" \
    "$payload_dir/verification/attachments.json"
  last_success_tmp="$staging/last-success.json"
  python3 "$metadata_tool" last-success "$last_success_tmp" "$backup_id" "$completed_at" "$archive" \
    "$recipient_fingerprint" "$offline_proof_sha256"
  chmod 0600 "$last_success_tmp"
  mv "$chain_state_tmp" "$backup_dir/chain-state.json"
  committed=1
  mv "$last_success_tmp" "$backup_dir/last-success.json"
  sync -f "$backup_dir/chain-state.json" "$backup_dir/last-success.json" "$backup_dir"

  if ! apply_retention 0; then
    echo "提醒：备份已成功，但自动保留策略执行失败；请运行 copperctl backup prune --dry-run 检查" >&2
  fi
  "$SCRIPT_DIR/storage-capacity.sh" >/dev/null 2>&1 || true
  rm -rf --one-file-system -- "$staging"
  trap - EXIT INT TERM
  note "一致性整站备份完成：$archive"
  note "外层 SHA-256：$(sha256sum "$archive" | awk '{print $1}')"
}

create_incremental_backup() {
  local purpose="$1" recipient recipient_fingerprint timestamp started_at completed_at backup_id archive_name archive final_sidecar
  local staging payload_dir outer_dir database_version schema_version binlog_bytes attachment_bytes available_bytes required_bytes
  local stopped committed published files_tsv references_tsv table_names_tsv counts_tsv totals_tsv counts_sql totals_sql refs_sql
  local binlogs_tsv binlog_status from_file from_position to_file to_position
  local base_backup_id parent_backup_id parent_sequence sequence parent_archive_name parent_archive_sha256 parent_completed_at parent_archive
  local age_version zstd_version last_success_tmp chain_state_tmp offline_proof offline_proof_sha256 archive_sha256
  local configured_target target_names="" chain_state="$backup_dir/chain-state.json"

  require_m2_backup_tools
  recipient="$(backup_recipient)"
  recipient_fingerprint="$(printf '%s' "$recipient" | sha256sum | awk '{print substr($1, 1, 16)}')"
  lock_backup_operations
  require_healthy_site
  require_no_pending_initial_admin_credential
  require_incremental_binlog
  [[ -f "$chain_state" && ! -L "$chain_state" ]] || die "没有可用的全量基础备份链状态；请先创建 full 备份"
  IFS=$'\t' read -r base_backup_id parent_backup_id parent_sequence from_file from_position parent_archive_name parent_archive_sha256 parent_completed_at \
    <<< "$(python3 "$chain_tool" state-info "$chain_state" "$site_id")"
  [[ "$parent_sequence" =~ ^[0-9]+$ ]] || die "备份链状态序号不正确"
  sequence=$((parent_sequence + 1))
  parent_archive="$backup_dir/$parent_archive_name"
  [[ -f "$parent_archive" && ! -L "$parent_archive" ]] || die "父备份文件不存在：$parent_archive_name；必须重新创建全量基础备份"
  [[ "$(sha256sum "$parent_archive" | awk '{print $1}')" == "$parent_archive_sha256" ]] \
    || die "父备份文件摘要与链状态不一致；拒绝继续增量链"
  verify_m2_archive "$parent_archive" "$parent_archive.sha256"
  backup_policy_value COPPER_BACKUP_KEEP_DAILY 7 >/dev/null
  backup_policy_value COPPER_BACKUP_KEEP_WEEKLY 4 >/dev/null
  backup_policy_value COPPER_BACKUP_KEEP_MONTHLY 6 >/dev/null

  binlog_bytes="$(mysql_query 'SHOW BINARY LOGS;' | awk -F'\t' -v wanted="$from_file" '$1 == wanted {include=1} include {sum += $2} END {print sum + 0}')"
  attachment_bytes="$(du -sb "$var_dir/files" 2>/dev/null | awk '{print $1}' || true)"
  attachment_bytes="${attachment_bytes:-0}"
  [[ "$binlog_bytes" =~ ^[0-9]+$ && "$binlog_bytes" -gt 0 && "$attachment_bytes" =~ ^[0-9]+$ ]] \
    || die "无法估算增量备份空间或父 binlog 已不存在"
  available_bytes="$(df -PB1 "$backup_dir" | awk 'END {print $(NF-2)}')"
  required_bytes=$((binlog_bytes * 2 + attachment_bytes * 2 + 536870912))
  [[ "$available_bytes" -ge "$required_bytes" ]] \
    || die "增量备份空间不足：需要至少约 $((required_bytes / 1048576)) MiB，当前约 $((available_bytes / 1048576)) MiB"
  "$storage_tool" --env-file "$ENV_FILE" preflight "$required_bytes" >/dev/null

  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  backup_id="$(openssl rand -hex 16)"
  offline_proof="$(openssl rand -hex 32)"
  offline_proof_sha256="$(printf '%s' "$offline_proof" | sha256sum | awk '{print $1}')"
  archive_name="${site_id}_${timestamp}_${backup_id}_${purpose}.copperbak"
  archive="$backup_dir/$archive_name"
  final_sidecar="$archive.sha256"
  [[ ! -e "$archive" && ! -e "$final_sidecar" ]] || die "备份文件已存在：$archive"

  staging="$(mktemp -d "$backup_dir/.backup-incremental-XXXXXX")"
  chmod 0700 "$staging"
  payload_dir="$staging/payload"
  outer_dir="$staging/outer"
  install -d -m 0700 "$payload_dir/database/binlog" "$payload_dir/attachments" "$payload_dir/config" "$payload_dir/verification" "$outer_dir"
  files_tsv="$staging/attachment-files.tsv"
  references_tsv="$staging/attachment-references.tsv"
  table_names_tsv="$staging/table-names.tsv"
  counts_tsv="$staging/record-counts.tsv"
  totals_tsv="$staging/business-totals.tsv"
  counts_sql="$staging/record-counts.sql"
  totals_sql="$staging/business-totals.sql"
  refs_sql="$staging/attachment-references.sql"
  binlogs_tsv="$staging/binlogs.tsv"
  stopped=0
  committed=0
  published=0

  cleanup_incremental() {
    local status=$?
    set +e
    if [[ "$stopped" == "1" ]]; then
      if ! compose_cmd up -d --remove-orphans --wait --wait-timeout 240 >/dev/null 2>&1 \
        || ! wait_for_stable_site_health >/dev/null 2>&1; then
        echo "错误：增量备份中断后服务未恢复健康，请立即运行 copperctl doctor" >&2
        status=1
      fi
    fi
    if [[ "$committed" != "1" && "$published" == "1" ]]; then
      "$storage_tool" --env-file "$ENV_FILE" delete "$archive_name" >/dev/null 2>&1 || true
      rm -f -- "$archive" "$final_sidecar"
    fi
    rm -rf --one-file-system -- "$staging"
    "$SCRIPT_DIR/storage-capacity.sh" >/dev/null 2>&1 || true
    trap - EXIT INT TERM
    exit "$status"
  }
  trap cleanup_incremental EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  note "进入增量一致性窗口：停止 Web/Backend，固定数据库和附件同一业务截点"
  stopped=1
  compose_cmd stop -t 45 web backend
  wait_for_transactions_to_drain
  test_delay_after_stop
  test_fail_at after-stop

  database_version="$(mysql_query 'SELECT VERSION();' | tr -d '[:space:]')"
  schema_version="$(mysql_query "SELECT COALESCE((SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1), 'unversioned');" | tr -d '[:space:]')"
  [[ -n "$database_version" && -n "$schema_version" ]] || die "无法读取数据库或结构版本"
  mysql_query "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME;" > "$table_names_tsv"
  write_table_counts_sql "$counts_sql"
  mysql_cli < "$counts_sql" > "$counts_tsv"
  write_business_totals_sql "$totals_sql"
  mysql_cli < "$totals_sql" > "$totals_tsv"
  validate_no_blob_only_attachments
  write_attachment_references_sql "$refs_sql"
  mysql_cli < "$refs_sql" > "$references_tsv"
  snapshot_attachment_files "$var_dir/files" "$files_tsv"
  python3 "$metadata_tool" attachments "$files_tsv" "$references_tsv" "$payload_dir/verification/attachments.json"
  python3 "$chain_tool" attachment-delta "$chain_state" "$payload_dir/verification/attachments.json" \
    "$var_dir/files" "$payload_dir/attachments" "$payload_dir/verification/attachment-delta.json"
  python3 "$metadata_tool" record-counts "$counts_tsv" "$payload_dir/verification/record-counts.json"
  python3 "$metadata_tool" business-totals "$totals_tsv" "$payload_dir/verification/business-totals.json"
  python3 "$metadata_tool" schema "$payload_dir/verification/schema.json" "$database_version" "$schema_version" "$table_names_tsv"
  printf '%s\n' "$offline_proof" > "$payload_dir/verification/offline-proof.txt"
  chmod 0600 "$payload_dir/verification/offline-proof.txt"
  python3 "$metadata_tool" runtime-config "$payload_dir/config/runtime-allowlist.json" \
    "siteId=$site_id" \
    "projectName=$(required_env_value COPPER_PROJECT_NAME)" \
    "deployMode=$(required_env_value COPPER_DEPLOY_MODE)" \
    "domain=$(required_env_value COPPER_DOMAIN)" \
    "timeZone=$(env_value COPPER_TIME_ZONE || printf 'Asia/Shanghai')" \
    "logLevel=$(env_value COPPER_LOG_LEVEL || printf 'INFO')" \
    "ocrEnabled=$(ocr_env_value COPPER_OCR_ENABLED false)" \
    "ocrEndpoint=$(ocr_env_value COPPER_OCR_ENDPOINT)" \
    "ocrNetwork=$(ocr_env_value COPPER_OCR_NETWORK)" \
    "ocrEngine=$(ocr_env_value COPPER_OCR_ENGINE PADDLEOCR_LOCAL)" \
    "ocrModel=$(ocr_env_value COPPER_OCR_MODEL PP-OCRv6-medium)" \
    "ocrTimeoutSeconds=$(ocr_env_value COPPER_OCR_TIMEOUT_SECONDS 120)" \
    "bootstrapUsername=$(env_value COPPER_BOOTSTRAP_USERNAME || printf 'admin')" \
    "databaseName=copper_v2" \
    "etcDirectory=$etc_dir" \
    "dataDirectory=$var_dir" \
    "attachmentDirectory=$var_dir/files" \
    "webImage=$(required_env_value COPPER_WEB_IMAGE)" \
    "backendImage=$(required_env_value COPPER_BACKEND_IMAGE)" \
    "mysqlImage=$(required_env_value COPPER_MYSQL_IMAGE)"

  note "轮换 MySQL binlog 并结束本次增量边界"
  binlog_status="$(rotate_mysql_binlog)"
  IFS=$'\t' read -r to_file to_position <<< "$binlog_status"
  copy_closed_binlogs "$from_file" "$to_file" "$payload_dir/database/binlog" "$binlogs_tsv"
  test_fail_at after-snapshot

  note "增量快照已捕获，恢复三层服务"
  compose_cmd up -d --remove-orphans --wait --wait-timeout 240
  wait_for_stable_site_health
  stopped=0
  completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  age_version="$(age --version 2>&1)"
  zstd_version="$(zstd --version 2>&1)"
  python3 "$chain_tool" manifest \
    "$payload_dir/manifest.json" "$backup_id" "$base_backup_id" "$parent_backup_id" "$sequence" "$site_id" "$purpose" \
    "$started_at" "$completed_at" "$(env_value COPPER_TIME_ZONE || printf 'Asia/Shanghai')" \
    "$(required_env_value COPPER_WEB_IMAGE)" "$(required_env_value COPPER_BACKEND_IMAGE)" "$(required_env_value COPPER_MYSQL_IMAGE)" \
    "$database_version" "$schema_version" "$from_file" "$from_position" "$to_file" "$to_position" "$binlogs_tsv" \
    "$payload_dir/verification/record-counts.json" "$payload_dir/verification/business-totals.json" \
    "$payload_dir/verification/attachments.json" "$payload_dir/verification/attachment-delta.json" \
    "$payload_dir/config/runtime-allowlist.json" "$payload_dir/verification/schema.json" \
    "${COPPERCTL_VERSION:-0.12.0-m5}" "$age_version" "$zstd_version"
  (
    cd "$payload_dir"
    find . -type f ! -name checksums.sha256 -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > checksums.sha256
  )

  note "压缩并使用客户 age 公钥加密增量载荷"
  tar --format=pax --numeric-owner -C "$payload_dir" -cf - . | zstd -q -T0 -6 -o "$staging/payload.tar.zst"
  age --encrypt --recipient "$recipient" --output "$outer_dir/payload.tar.zst.age" "$staging/payload.tar.zst"
  rm -f "$staging/payload.tar.zst"
  python3 "$chain_tool" envelope "$outer_dir/envelope.json" "$backup_id" "$base_backup_id" "$parent_backup_id" "$sequence" \
    "$site_id" "$completed_at" "$outer_dir/payload.tar.zst.age" "$recipient_fingerprint" "$offline_proof_sha256"
  cat > "$outer_dir/README.txt" <<'EOF'
Copper incremental backup package format: copper-backup/v2

This encrypted package is not independently restorable. Restore the referenced
copper-backup/v1 full base and every v2 incremental parent in sequence. Verify
all outer and inner SHA-256 checksums before database or attachment replay.
EOF
  (
    cd "$outer_dir"
    sha256sum envelope.json payload.tar.zst.age README.txt > checksums.sha256
    tar --format=pax -cf "$staging/$archive_name" envelope.json payload.tar.zst.age checksums.sha256 README.txt
  )
  chmod 0600 "$staging/$archive_name"
  (cd "$staging" && sha256sum "$archive_name" > "$archive_name.sha256")
  chmod 0600 "$staging/$archive_name.sha256"
  verify_m2_archive "$staging/$archive_name" "$staging/$archive_name.sha256"
  test_fail_at before-publish

  published=1
  mv "$staging/$archive_name" "$archive"
  test_fail_at during-publish
  mv "$staging/$archive_name.sha256" "$final_sidecar"
  sync -f "$archive" "$final_sidecar"
  while IFS= read -r configured_target; do
    [[ -z "$configured_target" ]] || target_names="${target_names}${target_names:+,}${configured_target}"
  done < <("$storage_tool" --env-file "$ENV_FILE" targets)
  [[ -n "$target_names" ]] || die "没有可用的备份发布目标"
  note "将增量备份发布到已配置目标：$target_names"
  "$storage_tool" --env-file "$ENV_FILE" publish "$archive" "$final_sidecar" >/dev/null
  archive_sha256="$(sha256sum "$archive" | awk '{print $1}')"
  chain_state_tmp="$staging/chain-state.json"
  python3 "$chain_tool" write-state "$chain_state_tmp" "$site_id" "$backup_id" "$base_backup_id" "$parent_backup_id" "$sequence" \
    "$completed_at" "$to_file" "$to_position" "$archive" "$archive_sha256" "$payload_dir/verification/attachments.json"
  last_success_tmp="$staging/last-success.json"
  python3 "$chain_tool" last-success "$last_success_tmp" "$backup_id" "$base_backup_id" "$parent_backup_id" "$sequence" \
    "$completed_at" "$archive" "$recipient_fingerprint" "$offline_proof_sha256"
  mv "$chain_state_tmp" "$backup_dir/chain-state.json"
  committed=1
  mv "$last_success_tmp" "$backup_dir/last-success.json"
  sync -f "$backup_dir/chain-state.json" "$backup_dir/last-success.json" "$backup_dir"

  if ! apply_retention 0; then
    echo "提醒：增量备份成功，但链式保留策略执行失败；请运行 copperctl backup prune --dry-run" >&2
  fi
  "$SCRIPT_DIR/storage-capacity.sh" >/dev/null 2>&1 || true
  rm -rf --one-file-system -- "$staging"
  trap - EXIT INT TERM
  note "一致性增量备份完成：$archive"
  note "备份链：base=$base_backup_id parent=$parent_backup_id sequence=$sequence"
}

validate_payload_archive() {
  local payload="$1"
  if tar -tf "$payload" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
    die "备份数据包含不安全路径，拒绝使用"
  fi
  tar -tf "$payload" | awk -v etc_path="${etc_dir#/}" -v var_path="${var_dir#/}" '
    $0 != etc_path && index($0, etc_path "/") != 1 && $0 != var_path && index($0, var_path "/") != 1 {bad=1}
    END {exit bad ? 1 : 0}
  ' || die "备份数据包含 Copper 目录之外的内容，拒绝使用"
  tar -tvf "$payload" | awk '{type=substr($1,1,1); if (type != "-" && type != "d") bad=1} END {exit bad ? 1 : 0}' \
    || die "备份数据包含符号链接、硬链接或设备文件，拒绝使用"
}

create_legacy_safety_backup() {
  local purpose="${1:-pre-restore}" timestamp archive archive_name staging stopped
  require_command tar
  require_command sha256sum
  require_healthy_site
  install -d -o root -g root -m 0700 "$backup_dir"
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  archive_name="${site_id}-${timestamp}-${purpose}.tar.gz"
  archive="$backup_dir/$archive_name"
  staging="$(mktemp -d "$backup_dir/.legacy-backup-XXXXXX")"
  chmod 0700 "$staging"
  stopped=0
  cleanup_legacy_create() {
    local status=$?
    rm -rf --one-file-system -- "$staging"
    if [[ "$stopped" == "1" ]]; then
      compose_cmd up -d --remove-orphans --wait --wait-timeout 240 >/dev/null 2>&1 || status=1
    fi
    trap - EXIT
    exit "$status"
  }
  trap cleanup_legacy_create EXIT
  compose_cmd stop
  stopped=1
  {
    printf 'format=COPPER_M1_STOPPED_BACKUP_V1\n'
    printf 'site_id=%s\n' "$site_id"
    printf 'created_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'web_image=%s\n' "$(required_env_value COPPER_WEB_IMAGE)"
    printf 'backend_image=%s\n' "$(required_env_value COPPER_BACKEND_IMAGE)"
    printf 'mysql_image=%s\n' "$(required_env_value COPPER_MYSQL_IMAGE)"
  } > "$staging/manifest.env"
  tar --numeric-owner --acls --xattrs \
    --exclude="${var_dir#/}/backups" --exclude="${var_dir#/}/mysql/mysql.sock" \
    -C / -cf "$staging/payload.tar" "${etc_dir#/}" "${var_dir#/}"
  validate_payload_archive "$staging/payload.tar"
  (cd "$staging" && sha256sum payload.tar > payload.tar.sha256)
  tar -C "$staging" -czf "$archive" manifest.env payload.tar payload.tar.sha256
  chmod 0600 "$archive"
  (cd "$backup_dir" && sha256sum "$archive_name" > "$archive_name.sha256")
  chmod 0600 "$archive.sha256"
  compose_cmd up -d --remove-orphans --wait --wait-timeout 240
  stopped=0
  rm -rf --one-file-system -- "$staging"
  trap - EXIT
  BACKUP_RESULT="$archive"
}

manifest_value() {
  local manifest="$1" key="$2"
  awk -F= -v wanted="$key" '$1 == wanted {sub(/^[^=]*=/, ""); print; exit}' "$manifest"
}

restore_legacy_backup() {
  local archive="${1:-}" archive_name staging manifest payload safety_backup confirmation rollback_dir
  [[ -n "$archive" ]] || { echo "错误：backup restore 缺少备份文件" >&2; exit 2; }
  [[ $# -eq 1 ]] || { echo "错误：backup restore 只接受一个备份文件" >&2; exit 2; }
  [[ "$archive" != *.copperbak ]] || die "copper-backup/v1 的旁路恢复属于 M2-03；M2-02 目前只支持 create 和 verify"
  [[ -e "$archive" ]] || die "备份文件不存在：$archive"
  archive="$(readlink -f "$archive")"
  [[ -f "$archive" && ! -L "$archive" ]] || die "备份文件不存在或不是普通文件：$archive"
  [[ -f "$archive.sha256" && ! -L "$archive.sha256" ]] || die "缺少备份校验文件：$archive.sha256"
  archive_name="$(basename "$archive")"
  verify_sha256_sidecar "$archive" "$archive.sha256" "$archive_name"
  if tar -tzf "$archive" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
    die "备份包包含不安全路径，拒绝恢复"
  fi
  staging="$(mktemp -d "$backup_dir/.restore-XXXXXX")"
  chmod 0700 "$staging"
  trap 'rm -rf --one-file-system -- "$staging"' EXIT
  tar -xzf "$archive" -C "$staging"
  manifest="$staging/manifest.env"
  payload="$staging/payload.tar"
  [[ -f "$manifest" && ! -L "$manifest" && -f "$payload" && ! -L "$payload" ]] || die "备份包结构不完整"
  verify_sha256_sidecar "$payload" "$staging/payload.tar.sha256" payload.tar
  [[ "$(manifest_value "$manifest" format)" == "COPPER_M1_STOPPED_BACKUP_V1" ]] || die "不支持的旧备份格式"
  [[ "$(manifest_value "$manifest" site_id)" == "$site_id" ]] || die "备份站点标识与当前站点不一致"
  [[ "$(manifest_value "$manifest" web_image)" == "$(required_env_value COPPER_WEB_IMAGE)" ]] || die "Web 镜像版本不一致；旧格式只允许同版本恢复"
  [[ "$(manifest_value "$manifest" backend_image)" == "$(required_env_value COPPER_BACKEND_IMAGE)" ]] || die "Backend 镜像版本不一致；旧格式只允许同版本恢复"
  [[ "$(manifest_value "$manifest" mysql_image)" == "$(required_env_value COPPER_MYSQL_IMAGE)" ]] || die "MySQL 镜像版本不一致；旧格式只允许同版本恢复"
  validate_payload_archive "$payload"
  echo "将使用旧 M1 同版本备份覆盖站点 $site_id 的当前数据：$archive"
  printf '请输入 RESTORE %s 继续：' "$site_id"
  IFS= read -r confirmation
  [[ "$confirmation" == "RESTORE $site_id" ]] || die "二次确认不匹配，未修改任何数据"
  note "恢复前创建旧格式安全备份"
  create_legacy_safety_backup pre-restore
  safety_backup="$BACKUP_RESULT"
  compose_cmd stop
  rollback_dir="$(mktemp -d "$backup_dir/.rollback-XXXXXX")"
  chmod 0700 "$rollback_dir"
  rollback_restore() {
    local status=$?
    trap - ERR
    set +e
    compose_cmd down --remove-orphans >/dev/null 2>&1
    [[ ! -d "$rollback_dir/etc-copper" ]] || { rm -rf --one-file-system -- "$etc_dir"; mv "$rollback_dir/etc-copper" "$etc_dir"; }
    [[ ! -d "$rollback_dir/var-lib-copper" ]] || { rm -rf --one-file-system -- "$var_dir"; mv "$rollback_dir/var-lib-copper" "$var_dir"; }
    rmdir "$rollback_dir" 2>/dev/null
    compose_cmd up -d --remove-orphans --wait --wait-timeout 240 >/dev/null 2>&1 \
      || { echo "严重错误：旧备份恢复失败且服务未恢复；安全备份：$safety_backup" >&2; status=1; }
    rm -rf --one-file-system -- "$staging"
    trap - EXIT
    exit "$status"
  }
  trap rollback_restore ERR
  mv "$etc_dir" "$rollback_dir/etc-copper"
  mv "$var_dir" "$rollback_dir/var-lib-copper"
  tar --numeric-owner --acls --xattrs -xf "$payload" -C /
  "$SCRIPT_DIR/prepare-host.sh"
  validate_environment
  compose_cmd up -d --remove-orphans --wait --wait-timeout 240
  trap - ERR
  rm -rf --one-file-system -- "$rollback_dir" "$staging"
  trap - EXIT
  note "旧 M1 同版本恢复完成；恢复前安全备份保留在 $safety_backup"
}

case "${1:-}" in
  create)
    shift
    purpose=manual
    backup_type=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --purpose)
          [[ -n "${2:-}" ]] || { echo "错误：--purpose 缺少值" >&2; exit 2; }
          purpose="$2"
          shift 2
          ;;
        --type)
          [[ -n "${2:-}" ]] || { echo "错误：--type 缺少值" >&2; exit 2; }
          backup_type="$2"
          shift 2
          ;;
        *) echo "错误：backup create 只支持 --purpose 和 --type" >&2; exit 2 ;;
      esac
    done
    [[ "$purpose" == "manual" || "$purpose" == "scheduled" ]] || { echo "错误：备份 purpose 只允许 manual 或 scheduled" >&2; exit 2; }
    backup_type="${backup_type:-$([[ "$purpose" == "scheduled" ]] && printf auto || printf full)}"
    [[ "$backup_type" == "auto" || "$backup_type" == "full" || "$backup_type" == "incremental" ]] \
      || { echo "错误：备份 type 只允许 auto、full 或 incremental" >&2; exit 2; }
    if [[ "$backup_type" == "auto" ]]; then
      full_weekday="$(env_value COPPER_BACKUP_FULL_WEEKDAY || true)"
      full_weekday="${full_weekday:-7}"
      [[ "$full_weekday" =~ ^[1-7]$ ]] || die "COPPER_BACKUP_FULL_WEEKDAY 必须是 1-7（周一到周日）"
      current_weekday="$(date +%u)"
      if [[ "$current_weekday" == "$full_weekday" ]] || ! can_continue_incremental_chain; then
        backup_type=full
      else
        backup_type=incremental
      fi
      note "自动备份策略选择：$backup_type（每周全量日=$full_weekday，今天=$current_weekday）"
    fi
    if [[ "$backup_type" == "full" ]]; then
      create_m2_backup "$purpose"
    else
      create_incremental_backup "$purpose"
    fi
    ;;
  verify)
    shift
    [[ $# -eq 1 ]] || { echo "错误：backup verify 需要一个 .copperbak 文件" >&2; exit 2; }
    require_m2_backup_tools
    lock_backup_operations
    verify_m2_archive "$(readlink -f "$1")"
    note "备份外层、envelope 和密文载荷 SHA-256 校验通过"
    ;;
  prune)
    shift
    dry_run=0
    if [[ "${1:-}" == "--dry-run" ]]; then
      dry_run=1
      shift
    fi
    [[ $# -eq 0 ]] || { echo "错误：backup prune 只支持 --dry-run" >&2; exit 2; }
    require_command python3
    lock_backup_operations
    apply_retention "$dry_run"
    if [[ "$dry_run" == "0" ]]; then
      "$SCRIPT_DIR/storage-capacity.sh" >/dev/null 2>&1 || true
    fi
    ;;
  restore)
    shift
    restore_legacy_backup "$@"
    ;;
  *)
    echo "错误：backup 支持 create、verify、prune 或 restore（旧 M1 格式）" >&2
    exit 2
    ;;
esac
