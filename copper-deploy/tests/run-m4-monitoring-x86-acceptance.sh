#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$EUID" -eq 0 ]] || { echo "错误：M4-04 x86_64 验收必须使用 sudo" >&2; exit 1; }
[[ "$(uname -m)" == "x86_64" ]] || { echo "错误：M4-04 正式验收只支持 x86_64" >&2; exit 1; }
. /etc/os-release
[[ "$ID" == "ubuntu" && "$VERSION_ID" == "24.04" ]] || { echo "错误：M4-04 正式验收只支持 Ubuntu 24.04" >&2; exit 1; }

web_image="${COPPER_M4_MONITOR_WEB_IMAGE:?COPPER_M4_MONITOR_WEB_IMAGE is required}"
backend_image="${COPPER_M4_MONITOR_BACKEND_IMAGE:?COPPER_M4_MONITOR_BACKEND_IMAGE is required}"
mysql_image="${COPPER_M4_MONITOR_MYSQL_IMAGE:-sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af}"
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
base="${COPPER_M4_MONITOR_TEST_ROOT:-/opt/copper-m4-04}"
runtime="$base/runtime-$run_id"
offline="$base/offline-key-$run_id"
evidence="$base/evidence-$run_id"
etc_dir="$runtime/etc"
var_dir="$runtime/var"
backup_dir="$runtime/backups"
restore_root="$runtime/restores"
systemd_dir="$runtime/systemd"
env_file="$etc_dir/copper.env"
project="copper-m404-$run_id"
domain="m404.test.internal"
proxy_port="${COPPER_M4_MONITOR_PROXY_PORT:-18098}"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

cleanup() {
  local status=$?
  set +e
  if [[ -f "$env_file" ]]; then
    env COPPER_TEST_MODE=1 COPPER_ALLOW_MUTABLE_IMAGES=1 \
      "$ROOT_DIR/copperctl" --env-file "$env_file" down >/dev/null 2>&1
  fi
  rm -rf --one-file-system -- "$runtime" "$offline"
  trap - EXIT
  exit "$status"
}
trap cleanup EXIT

for command in docker openssl age age-keygen zstd python3 curl unshare systemd-analyze chronyc; do
  command -v "$command" >/dev/null 2>&1 || { echo "错误：缺少 $command" >&2; exit 1; }
done
for image in "$web_image" "$backend_image" "$mysql_image"; do
  docker image inspect "$image" >/dev/null
done
[[ "$(docker version --format '{{.Server.Version}}')" == 29.* ]]
[[ "$(docker compose version --short)" == 5.* ]]
[[ "$proxy_port" != "8191" ]]

install -d -o root -g root -m 0700 "$base" "$runtime" "$evidence"
"$ROOT_DIR/copper-key-offline" generate --output-dir "$offline" \
  --site-id copper-m404-test --customer-reference m4-04-x86-acceptance > "$evidence/key-generation.json"
identity="$offline/copper-age-identity.txt"
recipient="$(tr -d '\r\n' < "$offline/recipient.txt")"
handover_sha="$(sha256sum "$offline/handover-record.json" | awk '{print $1}')"

install -d -o root -g root -m 0700 "$etc_dir" "$etc_dir/tls" "$etc_dir/notification-credentials"
install -o root -g root -m 0600 "$ROOT_DIR/config/copper.env.example" "$env_file"
openssl req -x509 -newkey rsa:2048 -nodes -days 90 \
  -keyout "$etc_dir/tls/test-private-key.pem" \
  -out "$etc_dir/tls/external-fullchain.pem" \
  -subj "/CN=$domain" -addext "subjectAltName=DNS:$domain" >/dev/null 2>&1
chmod 0600 "$etc_dir/tls/test-private-key.pem"
chmod 0644 "$etc_dir/tls/external-fullchain.pem"

python3 - "$env_file" "$project" "$web_image" "$backend_image" "$mysql_image" \
  "$etc_dir" "$var_dir" "$backup_dir" "$restore_root" "$proxy_port" "$recipient" "$domain" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
values = {
    "COPPER_SITE_ID": "copper-m404-test",
    "COPPER_PROJECT_NAME": sys.argv[2],
    "COPPER_RELEASE_VERSION": "0.3.0-rc.2-m404-test",
    "COPPER_DEPLOY_MODE": "external-proxy",
    "COPPER_DOMAIN": sys.argv[12],
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
    "COPPER_RESTORE_PROXY_PORT": "18099",
    "COPPER_BACKUP_AGE_RECIPIENT": sys.argv[11],
    "COPPER_BACKUP_TARGETS": "local",
    "COPPER_MONITOR_ALERT_MODE": "local",
    "COPPER_MONITOR_ALERT_REPEAT_HOURS": "24",
    "COPPER_MONITOR_ALERT_TIMEOUT_SECONDS": "1",
    "COPPER_MONITOR_ALERT_WEBHOOK_URL_FILE": f"{sys.argv[6]}/notification-credentials/monitor_webhook_url",
    "COPPER_MONITOR_ALERT_HMAC_KEY_FILE": f"{sys.argv[6]}/notification-credentials/monitor_webhook_hmac_key",
    "COPPER_MONITOR_TLS_CERT_FILE": f"{sys.argv[6]}/tls/external-fullchain.pem",
}
result = []
seen = set()
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
chmod 0600 "$env_file"

export COPPER_TEST_MODE=1
export COPPER_ALLOW_MUTABLE_IMAGES=1
"$ROOT_DIR/copperctl" --env-file "$env_file" install > "$evidence/install.txt"
export COPPER_ENV_FILE="$env_file"

credential_file="$etc_dir/credentials/initial-admin.txt"
[[ -s "$credential_file" ]]
username="$(awk -F= '$1=="username" {print $2}' "$credential_file")"
password="$(awk -F= '$1=="password" {print $2}' "$credential_file")"
new_password="M4!$(openssl rand -hex 18)aZ9"
cookie="$runtime/admin-cookie.txt"
curl --fail --silent --show-error -c "$cookie" -H 'Content-Type: application/json' \
  --data "{\"username\":\"$username\",\"password\":\"$password\"}" \
  "http://127.0.0.1:$proxy_port/api/auth/login" >/dev/null
curl --fail --silent --show-error -b "$cookie" -H 'Content-Type: application/json' \
  --data "{\"currentPassword\":\"$password\",\"newPassword\":\"$new_password\"}" \
  "http://127.0.0.1:$proxy_port/api/auth/change-password" >/dev/null
unset password new_password
rm -f -- "$cookie"
[[ ! -e "$credential_file" ]]
printf 'initial_admin_credential_destroyed=passed\n' > "$evidence/admin-credential.txt"

"$ROOT_DIR/copperctl" backup key register \
  --handover-sha256 "$handover_sha" \
  --customer-confirmed-by "M4 test customer" \
  --witness-confirmed-by "M4 test witness" > "$evidence/key-register.json"
"$ROOT_DIR/copperctl" backup create --purpose manual > "$evidence/backup-create.txt"
archive="$(find "$backup_dir" -maxdepth 1 -type f -name '*.copperbak' -print -quit)"
[[ -n "$archive" && -s "$archive" ]]
receipt="$offline/restore-receipt.json"
"$ROOT_DIR/copper-key-offline" verify-backup --identity "$identity" --archive "$archive" --receipt "$receipt" \
  > "$evidence/offline-verify.json"
"$ROOT_DIR/copperctl" backup key record-restore-test "$receipt" > "$evidence/restore-proof-register.json"

"$ROOT_DIR/copperctl" monitor check --json > "$evidence/normal.json"
python3 - "$evidence/normal.json" <<'PY'
import json, sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["overallLevel"] == "NORMAL", status["overallLevel"]
assert {item["level"] for item in status["layers"].values()} == {"NORMAL"}
assert status["privacy"] == {"businessDetailsCollected": False, "secretsCollected": False}
assert status["notification"]["result"] == "NOT_REQUIRED"
PY

env COPPER_SYSTEMD_DIR="$systemd_dir" COPPER_MONITOR_SCHEDULE_TEST_NO_ENABLE=1 \
  "$ROOT_DIR/copperctl" monitor schedule install > "$evidence/systemd-verify.txt"
grep -q '^OnUnitActiveSec=5m$' "$systemd_dir/copper-monitor.timer"
grep -q '^ExecStart=.*/copperctl monitor check$' "$systemd_dir/copper-monitor.service"

unshare -n "$ROOT_DIR/copperctl" monitor check --json > "$evidence/network-isolated-normal.json"
python3 - "$evidence/network-isolated-normal.json" <<'PY'
import json, sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["overallLevel"] == "NORMAL"
assert status["layers"]["recoverable"]["level"] == "NORMAL"
PY

env COPPER_MONITOR_TEST_AVAILABLE_BYTES="$((8 * 1024**3))" \
  "$ROOT_DIR/copperctl" monitor check --json > "$evidence/warning.json"
python3 - "$evidence/warning.json" <<'PY'
import json, sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["overallLevel"] == "WARNING"
assert any(item["id"] == "storage.dataFilesystem" and item["level"] == "WARNING" for item in status["signals"])
PY

set +e
env COPPER_MONITOR_TEST_DB_UNAVAILABLE=1 \
  "$ROOT_DIR/copperctl" monitor check --json > "$evidence/blocked.json"
blocked_exit=$?
set -e
[[ "$blocked_exit" -eq 1 ]]
python3 - "$evidence/blocked.json" <<'PY'
import json, sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["overallLevel"] == "BLOCKED"
assert status["layers"]["readiness"]["level"] == "BLOCKED"
assert status["layers"]["writable"]["level"] == "BLOCKED"
PY

printf '%s\n' 'http://127.0.0.1:9/copper-monitor' | \
  install -o root -g root -m 0600 /dev/stdin "$etc_dir/notification-credentials/monitor_webhook_url"
openssl rand -hex 32 | install -o root -g root -m 0600 /dev/stdin "$etc_dir/notification-credentials/monitor_webhook_hmac_key"
python3 - "$env_file" webhook <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
lines = path.read_text(encoding="utf-8").splitlines()
path.write_text("\n".join("COPPER_MONITOR_ALERT_MODE=" + sys.argv[2] if line.startswith("COPPER_MONITOR_ALERT_MODE=") else line for line in lines) + "\n", encoding="utf-8")
PY
env COPPER_MONITOR_TEST_CERT_DAYS=20 COPPER_MONITOR_TEST_WEBHOOK_FAIL=1 \
  "$ROOT_DIR/copperctl" monitor check --json > "$evidence/notification-failed.json"
python3 - "$evidence/notification-failed.json" <<'PY'
import json, sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["overallLevel"] == "WARNING"
assert status["notification"]["result"] == "FAILED"
assert status["notification"]["errorCode"] == "DELIVERY_FAILED"
assert any(item["id"] == "notification.delivery" for item in status["signals"])
PY

python3 - "$env_file" local <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
lines = path.read_text(encoding="utf-8").splitlines()
path.write_text("\n".join("COPPER_MONITOR_ALERT_MODE=" + sys.argv[2] if line.startswith("COPPER_MONITOR_ALERT_MODE=") else line for line in lines) + "\n", encoding="utf-8")
PY
"$ROOT_DIR/copperctl" monitor check --json > "$evidence/recovered.json"
python3 - "$evidence/recovered.json" <<'PY'
import json, sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["overallLevel"] == "NORMAL"
assert status["notification"]["result"] == "LOCAL_ONLY"
PY

if [[ -n "${COPPER_M4_MONITOR_ACCEPTANCE_HOOK:-}" ]]; then
  [[ -x "$COPPER_M4_MONITOR_ACCEPTANCE_HOOK" ]] || { echo "错误：M4 验收扩展不是可执行文件" >&2; exit 1; }
  export COPPER_M4_ACCEPTANCE_ROOT_DIR="$ROOT_DIR"
  export COPPER_M4_ACCEPTANCE_ENV_FILE="$env_file"
  export COPPER_M4_ACCEPTANCE_EVIDENCE="$evidence"
  export COPPER_M4_ACCEPTANCE_RUNTIME="$runtime"
  export COPPER_M4_ACCEPTANCE_RUN_ID="$run_id"
  export COPPER_M4_ACCEPTANCE_MYSQL_IMAGE="$mysql_image"
  "$COPPER_M4_MONITOR_ACCEPTANCE_HOOK" --hook
fi

"$ROOT_DIR/copperctl" down > "$evidence/down.txt"
"$ROOT_DIR/copperctl" monitor status --json > "$evidence/status-after-stop.json"
python3 - "$evidence/status-after-stop.json" <<'PY'
import json, sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["overallLevel"] == "NORMAL"
PY

python3 - "$evidence" "$started_at" "$run_id" <<'PY'
import datetime as dt
import hashlib
import json
import pathlib
import platform
import sys

root = pathlib.Path(sys.argv[1])
files = []
for path in sorted(root.iterdir()):
    if path.name == "evidence-manifest.json" or not path.is_file():
        continue
    files.append({"name": path.name, "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
document = {
    "formatVersion": "copper-m4-monitor-acceptance/v1",
    "runId": sys.argv[3],
    "startedAt": sys.argv[2],
    "completedAt": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "platform": {"architecture": platform.machine(), "system": platform.system()},
    "result": "PASSED",
    "scenarios": ["normal", "network-isolated", "warning", "blocked", "notification-failed", "recovered", "status-after-stop", "systemd-verified"],
    "files": files,
}
(root / "evidence-manifest.json").write_text(json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
PY
chmod 0600 "$evidence"/*
printf 'm4_04_x86_acceptance=passed\nevidence=%s\n' "$evidence"
