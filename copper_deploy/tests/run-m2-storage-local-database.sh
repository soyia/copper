#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$EUID" -eq 0 ]] || { echo "错误：请在 local-database 使用 sudo 执行" >&2; exit 1; }
[[ "$(uname -m)" == "aarch64" ]] || { echo "错误：此脚本仅用于 local-database ARM 隔离补充验收" >&2; exit 1; }
m206_mode="${COPPER_M2_06_MODE:-0}"
[[ "$m206_mode" == "0" || "$m206_mode" == "1" ]] || { echo "错误：COPPER_M2_06_MODE 只支持 0 或 1" >&2; exit 1; }

run_id="$(date -u +%Y%m%d%H%M%S)-$$"
etc_dir="/tmp/copper-m2-05-etc-$run_id"
var_dir="/tmp/copper-m2-05-var-$run_id"
backup_dir="/tmp/copper-m2-05-backup-$run_id"
restore_root="/tmp/copper-m2-05-restores-$run_id"
nas_source="/tmp/copper-m2-05-nas-source-$run_id"
nas_dir="/tmp/copper-m2-05-nas-$run_id"
s3_data="/tmp/copper-m2-05-s3-$run_id"
identity="/tmp/copper-m2-05-identity-$run_id.txt"
key2_dir="/tmp/copper-m2-06-key2-$run_id"
schedule_dir="/tmp/copper-m2-06-systemd-$run_id"
alert_log="/tmp/copper-m2-06-alerts-$run_id.jsonl"
alert_ready="/tmp/copper-m2-06-alerts-$run_id.ready"
alert_pid=""
env_file="$etc_dir/copper.env"
project="copper-m2-05-$run_id"
minio_container="copper-m2-05-minio-$run_id"
proxy_port=18105
s3_port=19105
s3_user="m205access${run_id//-/}"
s3_secret="M205-secret-${run_id}-only-for-isolated-test"
s3_bucket="copper-m205-test"

web_image="${COPPER_STORAGE_TEST_WEB_IMAGE:-sha256:ae4458638da8e1a91aafffb231c5f8778e964bca650c8a8cb23a7e8ac557aa3c}"
backend_image="${COPPER_STORAGE_TEST_BACKEND_IMAGE:?COPPER_STORAGE_TEST_BACKEND_IMAGE is required}"
mysql_image="${COPPER_STORAGE_TEST_MYSQL_IMAGE:-sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af}"
minio_image="quay.io/minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e"

shared_mysql_before="$(docker inspect --format '{{.Id}}' vm-mysql)"
shared_valkey_before="$(docker inspect --format '{{.Id}}' vm-valkey)"
started="$(date +%s)"

cleanup() {
  local status=$?
  set +e
  if [[ -n "$alert_pid" ]]; then kill -TERM "$alert_pid" >/dev/null 2>&1; wait "$alert_pid" >/dev/null 2>&1; fi
  if [[ -f "$env_file" ]]; then
    env COPPER_TEST_MODE=1 COPPER_ALLOW_MUTABLE_IMAGES=1 "$ROOT_DIR/copperctl" --env-file "$env_file" down >/dev/null 2>&1
  fi
  docker rm -f "$minio_container" >/dev/null 2>&1
  if mountpoint -q "$var_dir/backend"; then umount "$var_dir/backend" >/dev/null 2>&1; fi
  if mountpoint -q "$var_dir/mysql"; then umount "$var_dir/mysql" >/dev/null 2>&1; fi
  if mountpoint -q "$backup_dir"; then umount "$backup_dir" >/dev/null 2>&1; fi
  if mountpoint -q "$nas_dir"; then umount "$nas_dir" >/dev/null 2>&1; fi
  if mountpoint -q "$nas_source"; then umount "$nas_source" >/dev/null 2>&1; fi
  rm -rf --one-file-system -- "$etc_dir" "$var_dir" "$backup_dir" "$restore_root" "$nas_source" "$nas_dir" "$s3_data"
  rm -rf --one-file-system -- "$key2_dir" "$schedule_dir"
  rm -f -- "$identity" "$alert_log" "$alert_ready" \
    "/tmp/copper-m2-05-cookie-$run_id.txt" "/tmp/copper-m2-05-api-$run_id.json" \
    "/tmp/copper-m2-05-write-$run_id.json"
  if [[ "$(docker inspect --format '{{.Id}}' vm-mysql 2>/dev/null)" != "$shared_mysql_before" || \
        "$(docker inspect --format '{{.Id}}' vm-valkey 2>/dev/null)" != "$shared_valkey_before" ]]; then
    echo "严重错误：共享 vm-mysql 或 vm-valkey 容器身份发生变化" >&2
    status=1
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' vm-mysql 2>/dev/null)" != "true" || \
        "$(docker inspect --format '{{.State.Running}}' vm-valkey 2>/dev/null)" != "true" ]]; then
    echo "严重错误：共享 vm-mysql 或 vm-valkey 未保持运行" >&2
    status=1
  fi
  trap - EXIT
  exit "$status"
}
trap cleanup EXIT

for image in "$web_image" "$backend_image" "$mysql_image" "$minio_image"; do
  docker image inspect "$image" >/dev/null
done

install -d -o root -g root -m 0750 "$etc_dir" "$var_dir" "$var_dir/backend" "$var_dir/mysql" "$backup_dir" "$restore_root" "$nas_source" "$nas_dir" "$s3_data"
mount -t tmpfs -o size=3g,mode=0700 tmpfs "$nas_source"
mount --bind "$nas_source" "$nas_dir"
mount -t tmpfs -o size=128m,mode=0750,uid=10001,gid=10001 tmpfs "$var_dir/backend"
mount -t tmpfs -o size=768m,mode=0750 tmpfs "$var_dir/mysql"
mount -t tmpfs -o size=3g,mode=0700 tmpfs "$backup_dir"

docker run -d --name "$minio_container" \
  -p "127.0.0.1:$s3_port:9000" \
  -e "MINIO_ROOT_USER=$s3_user" \
  -e "MINIO_ROOT_PASSWORD=$s3_secret" \
  -v "$s3_data:/data" \
  "$minio_image" server /data --address :9000 >/dev/null
for _ in $(seq 1 40); do
  if curl --fail --silent --max-time 2 "http://127.0.0.1:$s3_port/minio/health/ready" >/dev/null; then break; fi
  sleep 1
done
curl --fail --silent --max-time 2 "http://127.0.0.1:$s3_port/minio/health/ready" >/dev/null
docker exec "$minio_container" mc alias set m205 http://127.0.0.1:9000 "$s3_user" "$s3_secret" >/dev/null
docker exec "$minio_container" mc mb --ignore-existing "m205/$s3_bucket" >/dev/null

install -o root -g root -m 0600 "$ROOT_DIR/config/copper.env.example" "$env_file"
age-keygen -o "$identity" >/dev/null 2>&1
chmod 0600 "$identity"
recipient="$(age-keygen -y "$identity")"

python3 - "$env_file" "$project" "$web_image" "$backend_image" "$mysql_image" "$etc_dir" "$var_dir" "$backup_dir" "$restore_root" "$proxy_port" "$recipient" "$nas_dir" "$s3_port" "$s3_bucket" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
values = {
    "COPPER_SITE_ID": "copper-m205-test",
    "COPPER_PROJECT_NAME": sys.argv[2],
    "COPPER_DEPLOY_MODE": "external-proxy",
    "COPPER_DOMAIN": "m205.test.internal",
    "COPPER_TLS_MODE": "domain",
    "COPPER_PROXY_PORT": sys.argv[10],
    "COPPER_IMAGE_DELIVERY": "offline",
    "COPPER_WEB_IMAGE": sys.argv[3],
    "COPPER_BACKEND_IMAGE": sys.argv[4],
    "COPPER_MYSQL_IMAGE": sys.argv[5],
    "COPPER_ETC_DIR": sys.argv[6],
    "COPPER_VAR_DIR": sys.argv[7],
    "COPPER_BACKUP_DIR": sys.argv[8],
    "COPPER_RESTORE_ROOT": sys.argv[9],
    "COPPER_RESTORE_PROXY_PORT": "18106",
    "COPPER_BACKUP_AGE_RECIPIENT": sys.argv[11],
    "COPPER_BACKUP_TARGETS": "local,nas,s3",
    "COPPER_BACKUP_NAS_DIR": sys.argv[12],
    "COPPER_BACKUP_S3_ENDPOINT": f"http://127.0.0.1:{sys.argv[13]}",
    "COPPER_BACKUP_S3_REGION": "us-east-1",
    "COPPER_BACKUP_S3_BUCKET": sys.argv[14],
    "COPPER_BACKUP_S3_PREFIX": "copper-m205-test",
    "COPPER_BACKUP_S3_ACCESS_KEY_FILE": f"{sys.argv[6]}/storage-credentials/s3_access_key",
    "COPPER_BACKUP_S3_SECRET_KEY_FILE": f"{sys.argv[6]}/storage-credentials/s3_secret_key",
}
seen = set()
result = []
for line in path.read_text(encoding="utf-8").splitlines():
    key = line.split("=", 1)[0] if "=" in line and not line.lstrip().startswith("#") else ""
    if key in values:
        result.append(f"{key}={values[key]}")
        seen.add(key)
    else:
        result.append(line)
for key, value in values.items():
    if key not in seen:
        result.append(f"{key}={value}")
path.write_text("\n".join(result) + "\n", encoding="utf-8")
PY

install -d -o root -g root -m 0700 "$etc_dir/storage-credentials"
printf '%s\n' "$s3_user" | install -o root -g root -m 0600 /dev/stdin "$etc_dir/storage-credentials/s3_access_key"
printf '%s\n' "$s3_secret" | install -o root -g root -m 0600 /dev/stdin "$etc_dir/storage-credentials/s3_secret_key"

export COPPER_TEST_MODE=1
export COPPER_ALLOW_MUTABLE_IMAGES=1
"$ROOT_DIR/scripts/storage-target.py" --env-file "$env_file" enroll nas --confirm "$nas_dir" >/dev/null
"$ROOT_DIR/copperctl" --env-file "$env_file" install >/dev/null
export COPPER_ENV_FILE="$env_file"
handover_record="/tmp/copper-m2-05-handover-$run_id.txt"
printf 'Copper M2-05 isolated runtime key handover\n' > "$handover_record"
chmod 0600 "$handover_record"
handover_sha="$(sha256sum "$handover_record" | awk '{print $1}')"
"$ROOT_DIR/copperctl" backup key register \
  --handover-sha256 "$handover_sha" \
  --customer-confirmed-by "M2 test customer" \
  --witness-confirmed-by "M2 test witness" >/dev/null
rm -f -- "$handover_record"

require_healthy() {
  local service id state health
  for service in mysql backend web; do
    id="$(bash -c 'source "$1/scripts/lib.sh"; compose_cmd ps -q "$2"' _ "$ROOT_DIR" "$service")"
    [[ -n "$id" ]] || { echo "错误：$service 容器不存在" >&2; return 1; }
    state="$(docker inspect --format '{{.State.Status}}' "$id")"
    health="$(docker inspect --format '{{.State.Health.Status}}' "$id")"
    [[ "$state/$health" == "running/healthy" ]] || { echo "错误：$service 未健康：$state/$health" >&2; return 1; }
  done
}

archive_count() {
  find "$backup_dir" -maxdepth 1 -type f -name '*.copperbak' -print | wc -l
}

expect_backup_failure() {
  local label="$1"
  local before after
  before="$(archive_count)"
  if "$ROOT_DIR/copperctl" backup create >"/tmp/$label.out" 2>"/tmp/$label.err"; then
    echo "错误：$label 故障场景意外成功" >&2
    return 1
  fi
  after="$(archive_count)"
  [[ "$before" == "$after" ]] || { echo "错误：$label 失败后发布了新备份" >&2; return 1; }
  require_healthy
  rm -f -- "/tmp/$label.out" "/tmp/$label.err"
  echo "$label=passed"
}

set_env_values() {
  python3 - "$env_file" "$@" <<'PY'
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
updates = {}
for value in sys.argv[2:]:
    key, separator, item = value.partition("=")
    if not separator or not re.fullmatch(r"COPPER_[A-Z0-9_]+", key):
        raise SystemExit(f"invalid test env update: {value}")
    updates[key] = item
lines = path.read_text(encoding="utf-8").splitlines()
result = []
seen = set()
for line in lines:
    key = line.split("=", 1)[0] if "=" in line and not line.lstrip().startswith("#") else ""
    if key in updates:
        result.append(f"{key}={updates[key]}")
        seen.add(key)
    else:
        result.append(line)
for key, value in updates.items():
    if key not in seen:
        result.append(f"{key}={value}")
path.write_text("\n".join(result) + "\n", encoding="utf-8")
PY
}

run_m2_06_acceptance() {
  local receipt1 receipt2 key2_identity key2_recipient handover2 handover2_sha
  local rotation_json challenge key2_id proof_file status_file planted_private
  local before_count backup_pid backend_stopped current_archive current_name
  local alert_port alert_credentials alert_url_file alert_hmac_file alert_hmac

  receipt1="/tmp/copper-m2-06-receipt1-$run_id.json"
  "$ROOT_DIR/copper-key-offline" verify-backup \
    --identity "$identity" --archive "$archive" --receipt "$receipt1" >/dev/null
  "$ROOT_DIR/copperctl" backup key record-restore-test "$receipt1" >/dev/null
  rm -f -- "$receipt1"
  echo "initial_offline_backup_verification=passed"

  set_env_values "COPPER_BACKUP_DAILY_TIME=04:17" "COPPER_BACKUP_RANDOM_DELAY_MINUTES=11"
  env COPPER_SYSTEMD_DIR="$schedule_dir" COPPER_BACKUP_SCHEDULE_TEST_NO_ENABLE=1 \
    "$ROOT_DIR/copperctl" backup schedule install >/dev/null
  grep -q '^OnCalendar=\*-\*-\* 04:17:00$' "$schedule_dir/copper-backup.timer"
  grep -q '^RandomizedDelaySec=11m$' "$schedule_dir/copper-backup.timer"
  [[ ! -e /etc/systemd/system/copper-m2-06.timer ]]
  echo "configurable_schedule_unit=passed"

  "$ROOT_DIR/copper-key-offline" generate --output-dir "$key2_dir" \
    --site-id copper-m205-test --customer-reference m2-06-runtime >/dev/null
  key2_identity="$key2_dir/copper-age-identity.txt"
  key2_recipient="$(tr -d '\r\n' < "$key2_dir/recipient.txt")"
  handover2="$key2_dir/handover-record.json"
  handover2_sha="$(sha256sum "$handover2" | awk '{print $1}')"
  rotation_json="/tmp/copper-m2-06-rotation-$run_id.json"
  "$ROOT_DIR/copperctl" backup key prepare-rotation \
    --new-recipient "$key2_recipient" \
    --handover-sha256 "$handover2_sha" \
    --customer-confirmed-by "M2 test customer" \
    --witness-confirmed-by "M2 test witness" > "$rotation_json"
  challenge="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["challenge"])' "$rotation_json")"
  key2_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["keyId"])' "$rotation_json")"
  rm -f -- "$rotation_json"

  if "$ROOT_DIR/copper-key-offline" verify-challenge --identity "$identity" \
    --challenge "$challenge" --proof-output "/tmp/copper-m2-06-wrong-proof-$run_id" >/dev/null 2>&1; then
    echo "错误：旧私钥意外通过新公钥轮换挑战" >&2
    return 1
  fi
  chmod 0644 "$key2_identity"
  if "$ROOT_DIR/copper-key-offline" verify-challenge --identity "$key2_identity" \
    --challenge "$challenge" --proof-output "/tmp/copper-m2-06-permission-proof-$run_id" >/dev/null 2>&1; then
    echo "错误：权限过宽的客户私钥意外被接受" >&2
    return 1
  fi
  chmod 0600 "$key2_identity"
  proof_file="/tmp/copper-m2-06-proof-$run_id.txt"
  "$ROOT_DIR/copper-key-offline" verify-challenge --identity "$key2_identity" \
    --challenge "$challenge" --proof-output "$proof_file" >/dev/null
  "$ROOT_DIR/copperctl" backup key activate-rotation \
    --key-id "$key2_id" --proof-file "$proof_file" --confirm "$key2_id" >/dev/null
  rm -f -- "$proof_file"

  "$ROOT_DIR/copperctl" backup key status > "/tmp/copper-m2-06-key-status-$run_id.json"
  python3 - "/tmp/copper-m2-06-key-status-$run_id.json" "$key2_id" "$key2_recipient" "$env_file" <<'PY'
import json
import pathlib
import sys

status = json.load(open(sys.argv[1], encoding="utf-8"))
keys = {item["keyId"]: item for item in status["keys"]}
assert status["activeKeyId"] == sys.argv[2]
assert keys[sys.argv[2]]["status"] == "ACTIVE"
assert sum(item["status"] == "RETIRED" for item in status["keys"]) == 1
assert f"COPPER_BACKUP_AGE_RECIPIENT={sys.argv[3]}" in pathlib.Path(sys.argv[4]).read_text(encoding="utf-8")
PY
  rm -f -- "/tmp/copper-m2-06-key-status-$run_id.json"
  echo "offline_proof_key_rotation=passed"

  "$ROOT_DIR/copperctl" backup create >/dev/null
  current_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["archive"])' "$backup_dir/last-success.json")"
  current_archive="$backup_dir/$current_name"
  receipt2="/tmp/copper-m2-06-receipt2-$run_id.json"
  "$ROOT_DIR/copper-key-offline" verify-backup \
    --identity "$key2_identity" --archive "$current_archive" --receipt "$receipt2" >/dev/null
  if "$ROOT_DIR/copper-key-offline" verify-backup --identity "$identity" \
    --archive "$current_archive" --receipt "/tmp/copper-m2-06-old-key-receipt-$run_id.json" >/dev/null 2>&1; then
    echo "错误：旧私钥意外解密了轮换后的新备份" >&2
    return 1
  fi
  "$ROOT_DIR/copperctl" backup key record-restore-test "$receipt2" >/dev/null
  rm -f -- "$receipt2"
  echo "new_key_backup_and_old_key_history=passed"

  planted_private="$etc_dir/planted-private-key.txt"
  install -o root -g root -m 0600 "$key2_identity" "$planted_private"
  if "$ROOT_DIR/copperctl" backup key audit >/dev/null 2>&1; then
    echo "错误：服务器私钥扫描未阻止测试私钥" >&2
    return 1
  fi
  rm -f -- "$planted_private"
  "$ROOT_DIR/copperctl" backup key audit >/dev/null
  echo "server_private_key_destruction_audit=passed"

  before_count="$(archive_count)"
  env COPPER_BACKUP_TEST_DELAY_AFTER_STOP=30 "$ROOT_DIR/copperctl" backup create \
    >"/tmp/copper-m2-06-interrupt-$run_id.out" 2>"/tmp/copper-m2-06-interrupt-$run_id.err" &
  backup_pid=$!
  backend_stopped=0
  for _ in $(seq 1 40); do
    if [[ -z "$(bash -c 'source "$1/scripts/lib.sh"; compose_cmd ps -q backend' _ "$ROOT_DIR" 2>/dev/null || true)" ]]; then
      backend_stopped=1
      break
    fi
    sleep 1
  done
  if [[ "$backend_stopped" != "1" ]]; then
    kill -TERM "$backup_pid" >/dev/null 2>&1 || true
    echo "错误：未观察到 M2-06 中断测试维护窗口" >&2
    return 1
  fi
  kill -TERM "$backup_pid"
  if wait "$backup_pid"; then
    echo "错误：M2-06 中断备份意外成功" >&2
    return 1
  fi
  [[ "$(archive_count)" == "$before_count" ]]
  require_healthy
  status_file="$var_dir/backup-jobs/status.json"
  python3 - "$status_file" <<'PY'
import json
import sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["current"]["result"] == "FAILED"
assert status["current"]["errorCode"] == "INTERRUPTED"
PY
  rm -f -- "/tmp/copper-m2-06-interrupt-$run_id.out" "/tmp/copper-m2-06-interrupt-$run_id.err"
  "$ROOT_DIR/copperctl" backup create >/dev/null
  echo "interrupted_job_recovery=passed"

  alert_port=19106
  alert_credentials="$etc_dir/notification-credentials"
  alert_url_file="$alert_credentials/backup_webhook_url"
  alert_hmac_file="$alert_credentials/backup_webhook_hmac_key"
  install -d -o root -g root -m 0700 "$alert_credentials"
  printf 'http://127.0.0.1:%s/copper-backup-alert\n' "$alert_port" | \
    install -o root -g root -m 0600 /dev/stdin "$alert_url_file"
  alert_hmac="$(openssl rand -hex 32)"
  printf '%s\n' "$alert_hmac" | install -o root -g root -m 0600 /dev/stdin "$alert_hmac_file"
  set_env_values \
    "COPPER_BACKUP_ALERT_MODE=webhook" \
    "COPPER_BACKUP_ALERT_TIMEOUT_SECONDS=1" \
    "COPPER_BACKUP_ALERT_WEBHOOK_URL_FILE=$alert_url_file" \
    "COPPER_BACKUP_ALERT_HMAC_KEY_FILE=$alert_hmac_file"
  python3 "$ROOT_DIR/tests/backup-alert-receiver.py" --port "$alert_port" \
    --output "$alert_log" --ready-file "$alert_ready" >/dev/null 2>&1 &
  alert_pid=$!
  for _ in $(seq 1 40); do
    [[ -s "$alert_ready" ]] && break
    kill -0 "$alert_pid" 2>/dev/null || { echo "错误：测试 Webhook 未启动" >&2; return 1; }
    sleep 0.25
  done
  [[ -s "$alert_ready" ]] || { echo "错误：测试 Webhook 未就绪" >&2; return 1; }

  before_count="$(archive_count)"
  for failure_index in 1 2; do
    if env COPPER_BACKUP_TEST_AVAILABLE_BYTES=0 "$ROOT_DIR/copperctl" backup create \
      >/dev/null 2>"/tmp/copper-m2-06-alert-failure-$run_id-$failure_index.err"; then
      echo "错误：告警测试的空间不足故障意外成功" >&2
      return 1
    fi
  done
  [[ "$(archive_count)" == "$before_count" ]]
  python3 - "$status_file" <<'PY'
import json
import sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["consecutiveFailures"] == 2
assert status["current"]["errorCode"] == "SPACE_INSUFFICIENT"
assert status["current"]["alertResult"] == "SENT"
PY
  "$ROOT_DIR/copperctl" backup create >/dev/null
  for _ in $(seq 1 40); do
    [[ -f "$alert_log" && "$(wc -l < "$alert_log")" -ge 3 ]] && break
    sleep 0.25
  done
  python3 - "$alert_log" "$alert_hmac_file" <<'PY'
import hashlib
import hmac
import json
import pathlib
import sys

records = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()]
assert len(records) == 3
assert [item["body"]["event"] for item in records] == ["BACKUP_FAILED", "BACKUP_FAILED", "BACKUP_RECOVERED"]
allowed = {
    "formatVersion", "event", "siteFingerprint", "jobId", "purpose", "targets",
    "startedAt", "finishedAt", "result", "errorCode", "consecutiveFailures", "nextActionCode",
}
key = pathlib.Path(sys.argv[2]).read_bytes().strip()
for item in records:
    assert set(item["body"]) == allowed
    raw = item["raw"].encode("utf-8")
    assert item["signature"] == "sha256=" + hmac.new(key, raw, hashlib.sha256).hexdigest()
    lowered = item["raw"].lower()
    for forbidden in ("copper-m205-test", ".copperbak", "recipient", "private", "customer", "business", "filename"):
        assert forbidden not in lowered
PY
  echo "privacy_safe_signed_alert_and_recovery=passed"

  kill -TERM "$alert_pid"
  wait "$alert_pid" >/dev/null 2>&1 || true
  alert_pid=""
  if env COPPER_BACKUP_TEST_AVAILABLE_BYTES=0 "$ROOT_DIR/copperctl" backup create >/dev/null 2>&1; then
    echo "错误：Webhook 停止后的空间故障意外成功" >&2
    return 1
  fi
  python3 - "$status_file" <<'PY'
import json
import sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["current"]["result"] == "FAILED"
assert status["current"]["alertResult"] == "FAILED"
PY
  chmod 0644 "$alert_hmac_file"
  if python3 "$ROOT_DIR/scripts/backup-job.py" --state-dir "$var_dir/backup-jobs" send \
    --site-id copper-m205-test --url-file "$alert_url_file" --hmac-key-file "$alert_hmac_file" \
    --timeout 1 --test-mode >/dev/null 2>&1; then
    echo "错误：权限过宽的 Webhook HMAC 文件意外被接受" >&2
    return 1
  fi
  chmod 0600 "$alert_hmac_file"
  set_env_values "COPPER_BACKUP_ALERT_MODE=local"
  "$ROOT_DIR/copperctl" backup create >/dev/null
  echo "alert_delivery_failure_keeps_local_trace=passed"
  rm -f -- "/tmp/copper-m2-06-alert-failure-$run_id-1.err" "/tmp/copper-m2-06-alert-failure-$run_id-2.err"
}

require_healthy
"$ROOT_DIR/copperctl" backup create >/dev/null
archive_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["archive"])' "$backup_dir/last-success.json")"
archive="$backup_dir/$archive_name"
archive_sha="$(sha256sum "$archive" | awk '{print $1}')"
archive_bytes="$(stat -c '%s' "$archive")"

"$ROOT_DIR/scripts/storage-target.py" --env-file "$env_file" verify "$archive_name" "$archive_sha" "$archive_bytes" >/dev/null
for target in local nas s3; do
  destination="/tmp/copper-m2-05-fetch-$target-$run_id"
  "$ROOT_DIR/copperctl" storage fetch "$target" "$archive_name" "$destination" >/dev/null
  cmp "$archive" "$destination/$archive_name"
  rm -rf --one-file-system -- "$destination"
done
echo "three_target_bytes_and_sha256=passed"

status_json="$var_dir/backend/runtime/storage-capacity.json"
"$ROOT_DIR/copperctl" storage status > /tmp/copper-m2-05-capacity.json
python3 - /tmp/copper-m2-05-capacity.json "$archive_sha" <<'PY'
import json
import sys

status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["formatVersion"] == "copper-capacity/v1"
assert status["policy"]["artificialCopperQuota"] is False
assert status["usage"]["databaseBytes"] > 0
assert status["lastSuccessfulBackup"] is not None
targets = {item["target"]: item for item in status["targets"]}
assert set(targets) == {"local", "nas", "s3"}
assert all(item["available"] for item in targets.values())
assert all(item["backupCount"] == 1 for item in targets.values())
assert targets["s3"]["quotaKnown"] is False
assert targets["s3"]["availableBytes"] is None
print("capacity_snapshot=passed")
PY
rm -f /tmp/copper-m2-05-capacity.json
[[ -s "$status_json" && "$(stat -c '%a' "$status_json")" == "644" ]] || { echo "错误：后端容量快照不存在或权限不正确" >&2; exit 1; }

if [[ "$m206_mode" == "0" ]]; then
  credential_file="$etc_dir/credentials/initial-admin.txt"
  username="$(awk -F= '$1=="username" {print $2}' "$credential_file")"
  password="$(awk -F= '$1=="password" {print $2}' "$credential_file")"
  new_password="M205-Temporary!2026-Strong"
  cookie="/tmp/copper-m2-05-cookie-$run_id.txt"
  api_output="/tmp/copper-m2-05-api-$run_id.json"
  write_output="/tmp/copper-m2-05-write-$run_id.json"
  curl --fail --silent --show-error -c "$cookie" -H 'Content-Type: application/json' \
    --data "{\"username\":\"$username\",\"password\":\"$password\"}" \
    "http://127.0.0.1:$proxy_port/api/auth/login" >/dev/null
  curl --fail --silent --show-error -b "$cookie" -H 'Content-Type: application/json' \
    --data "{\"currentPassword\":\"$password\",\"newPassword\":\"$new_password\"}" \
    "http://127.0.0.1:$proxy_port/api/auth/change-password" >/dev/null
  curl --fail --silent --show-error -c "$cookie" -H 'Content-Type: application/json' \
    --data "{\"username\":\"$username\",\"password\":\"$new_password\"}" \
    "http://127.0.0.1:$proxy_port/api/auth/login" >/dev/null
  curl --fail --silent --show-error -b "$cookie" \
    "http://127.0.0.1:$proxy_port/api/system/storage-capacity" > "$api_output"
  python3 - "$api_output" <<'PY'
import json
import sys

body = json.load(open(sys.argv[1], encoding="utf-8"))
assert body["code"] == 0
assert body["data"]["runtimeRiskLevel"] == "DANGER"
assert body["data"]["policy"]["artificialCopperQuota"] is False
assert len(body["data"]["targets"]) == 3
print("capacity_api=passed")
PY
  write_code="$(curl --silent --show-error -o "$write_output" -w '%{http_code}' -b "$cookie" \
    -H 'Content-Type: application/json' --data '{"confirmFullExport":true}' \
    "http://127.0.0.1:$proxy_port/api/business-exports")"
  [[ "$write_code" == "400" ]] || { echo "错误：危险容量下完整导出未被安全阻止，HTTP $write_code" >&2; exit 1; }
  grep -q '业务写入已受控停止' "$write_output"
  echo "danger_write_guard=passed"
  rm -f -- "$cookie" "$api_output" "$write_output"
else
  echo "m2_05_frontend_capacity_check=skipped_for_m2_06_scope"
fi

umount "$nas_dir"
expect_backup_failure nas_disconnected
mount --bind "$nas_source" "$nas_dir"

mount -o remount,bind,ro "$nas_dir"
expect_backup_failure nas_read_only
mount -o remount,bind,rw "$nas_dir"

umount "$nas_dir"
mount -t tmpfs -o size=64k tmpfs "$nas_dir"
expect_backup_failure target_quota_insufficient
umount "$nas_dir"
mount --bind "$nas_source" "$nas_dir"

chmod 0644 "$etc_dir/storage-credentials/s3_secret_key"
expect_backup_failure s3_credentials_rejected
chmod 0600 "$etc_dir/storage-credentials/s3_secret_key"

docker stop "$minio_container" >/dev/null
expect_backup_failure s3_timeout
docker start "$minio_container" >/dev/null
for _ in $(seq 1 40); do
  if curl --fail --silent --max-time 2 "http://127.0.0.1:$s3_port/minio/health/ready" >/dev/null; then break; fi
  sleep 1
done

if [[ "$m206_mode" == "1" ]]; then
  run_m2_06_acceptance
  archive_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["archive"])' "$backup_dir/last-success.json")"
  archive="$backup_dir/$archive_name"
  archive_sha="$(sha256sum "$archive" | awk '{print $1}')"
  archive_bytes="$(stat -c '%s' "$archive")"
fi

"$ROOT_DIR/scripts/storage-target.py" --env-file "$env_file" verify "$archive_name" "$archive_sha" "$archive_bytes" >/dev/null
require_healthy
elapsed="$(($(date +%s) - started))"
echo "archive=$archive_name"
echo "archive_bytes=$archive_bytes"
echo "archive_sha256=$archive_sha"
echo "runtime_seconds=$elapsed"
echo "shared_vm_mysql_unchanged=passed"
echo "shared_vm_valkey_unchanged=passed"
