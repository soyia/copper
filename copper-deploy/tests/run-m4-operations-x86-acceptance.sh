#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${1:-}" != "--hook" ]]; then
  ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  export COPPER_M4_MONITOR_WEB_IMAGE="${COPPER_M4_OPERATIONS_WEB_IMAGE:?COPPER_M4_OPERATIONS_WEB_IMAGE is required}"
  export COPPER_M4_MONITOR_BACKEND_IMAGE="${COPPER_M4_OPERATIONS_BACKEND_IMAGE:?COPPER_M4_OPERATIONS_BACKEND_IMAGE is required}"
  export COPPER_M4_MONITOR_MYSQL_IMAGE="${COPPER_M4_OPERATIONS_MYSQL_IMAGE:-sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af}"
  export COPPER_M4_MONITOR_TEST_ROOT="${COPPER_M4_OPERATIONS_TEST_ROOT:-/opt/copper-m4-05}"
  export COPPER_M4_MONITOR_PROXY_PORT="${COPPER_M4_OPERATIONS_PROXY_PORT:-18108}"
  export COPPER_M4_MONITOR_ACCEPTANCE_HOOK="$ROOT_DIR/tests/run-m4-operations-x86-acceptance.sh"
  "$ROOT_DIR/tests/run-m4-monitoring-x86-acceptance.sh"
  printf 'm4_05_x86_acceptance=passed\n'
  exit 0
fi

ROOT_DIR="${COPPER_M4_ACCEPTANCE_ROOT_DIR:?missing acceptance root}"
env_file="${COPPER_M4_ACCEPTANCE_ENV_FILE:?missing acceptance env}"
evidence="${COPPER_M4_ACCEPTANCE_EVIDENCE:?missing evidence directory}"
runtime="${COPPER_M4_ACCEPTANCE_RUNTIME:?missing runtime directory}"
run_id="${COPPER_M4_ACCEPTANCE_RUN_ID:?missing run id}"
mysql_image="${COPPER_M4_ACCEPTANCE_MYSQL_IMAGE:?missing mysql image}"
probe=""

cleanup_probe() {
  if [[ -n "$probe" ]]; then
    docker rm -f "$probe" >/dev/null 2>&1 || true
  fi
}
trap cleanup_probe EXIT

export COPPER_ENV_FILE="$env_file"
export COPPER_TEST_MODE=1
export COPPER_ALLOW_MUTABLE_IMAGES=1

"$ROOT_DIR/copperctl" operations status --json > "$evidence/operations-normal.json"
python3 - "$evidence/operations-normal.json" <<'PY'
import json, sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["overallLevel"] == "WARNING", status["overallLevel"]
assert status["logProtection"]["staticEnforcement"]["status"] == "PASS"
assert status["logProtection"]["runtimeEnforcement"]["status"] == "PASS"
assert status["recovery"]["backup"]["localRecoveryPoint"] == "TARGET_MET"
assert status["recovery"]["backup"]["hostLossRecoveryPoint"] == "NOT_SUPPORTED"
assert status["recovery"]["backup"]["offHostTargetEvidenced"] is False
assert status["recovery"]["commercialSla"] is False
assert status["privacy"] == {"businessDetailsCollected": False, "secretsCollected": False}
PY

mysql_id="$(docker compose --env-file "$env_file" -f "$ROOT_DIR/compose.yaml" -f "$ROOT_DIR/compose.external-proxy.yaml" ps -q mysql)"
backend_id="$(docker compose --env-file "$env_file" -f "$ROOT_DIR/compose.yaml" -f "$ROOT_DIR/compose.external-proxy.yaml" ps -q backend)"
[[ -n "$mysql_id" && -n "$backend_id" ]]

docker inspect --format '{{json .HostConfig.LogConfig}}' "$mysql_id" > "$evidence/mysql-log-config.json"
docker inspect --format '{{json .HostConfig.LogConfig}}' "$backend_id" > "$evidence/backend-log-config.json"
python3 - "$evidence/mysql-log-config.json" "$evidence/backend-log-config.json" <<'PY'
import json, sys
for path in sys.argv[1:]:
    config = json.load(open(path, encoding="utf-8"))
    assert config["Type"] == "local"
    assert config["Config"] == {"max-file": "5", "max-size": "20m"}
PY

docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$backend_id" > "$evidence/backend-runtime-environment.txt"
grep -Fxq 'LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE=20MB' "$evidence/backend-runtime-environment.txt"
grep -Fxq 'LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY=7' "$evidence/backend-runtime-environment.txt"
grep -Fxq 'LOGGING_LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP=200MB' "$evidence/backend-runtime-environment.txt"

docker exec -i "$mysql_id" sh -c \
  'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot -D copper_v2 --batch --raw --skip-column-names' \
  > "$evidence/mysql-log-policy.txt" <<'SQL'
SELECT @@log_bin, @@general_log, @@slow_query_log;
SQL
grep -Fxq $'0\t0\t0' "$evidence/mysql-log-policy.txt"

probe="copper-m405-log-$run_id"
docker run --name "$probe" --log-driver local --log-opt max-size=1m --log-opt max-file=2 \
  --entrypoint sh "$mysql_image" -c 'yes COPPER-M4-05-ROTATION-PROBE | head -c 8388608' >/dev/null
log_path="$(docker inspect --format '{{.LogPath}}' "$probe")"
log_dir="$(dirname "$log_path")"
log_bytes="$(du -sb "$log_dir" | awk '{print $1}')"
log_files="$(find "$log_dir" -maxdepth 1 -type f | wc -l | tr -d ' ')"
printf 'probe_bytes=%s\nprobe_files=%s\n' "$log_bytes" "$log_files" > "$evidence/docker-local-rotation.txt"
[[ "$log_bytes" -lt $((4 * 1024 * 1024)) ]]
docker rm "$probe" >/dev/null
probe=""

set +e
env COPPER_OPERATIONS_TEST_AVAILABLE_BYTES="$((512 * 1024**2))" \
  "$ROOT_DIR/copperctl" operations status --json > "$evidence/operations-capacity-blocked.json"
blocked_exit=$?
set -e
[[ "$blocked_exit" -eq 1 ]]
python3 - "$evidence/operations-capacity-blocked.json" <<'PY'
import json, sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
assert status["overallLevel"] == "BLOCKED"
assert status["capacity"]["level"] == "BLOCKED"
assert status["capacity"]["largeJobsBlockedAt"] == "CRITICAL"
assert status["capacity"]["businessWritesBlockedAt"] == "BLOCKED"
PY

for fault in database disk backup certificate time power; do
  "$ROOT_DIR/copperctl" operations checklist "$fault" --json > "$evidence/checklist-$fault.json"
  python3 - "$evidence/checklist-$fault.json" "$fault" <<'PY'
import json, sys
item = json.load(open(sys.argv[1], encoding="utf-8"))
assert item["fault"] == sys.argv[2]
assert item["stopRule"] and item["commands"] and item["recoveryGate"] and item["escalation"]
PY
done

unit_dir="$runtime/operations-systemd"
install -d -o root -g root -m 0700 "$unit_dir"
install -o root -g root -m 0644 "$ROOT_DIR/config/systemd/copper-backup.service" "$unit_dir/copper-backup.service"
install -o root -g root -m 0644 "$ROOT_DIR/config/systemd/copper-backup.timer" "$unit_dir/copper-backup.timer"
install -o root -g root -m 0644 "$ROOT_DIR/config/systemd/copper-monitor.service" "$unit_dir/copper-monitor.service"
install -o root -g root -m 0644 "$ROOT_DIR/config/systemd/copper-monitor.timer" "$unit_dir/copper-monitor.timer"
python3 - "$unit_dir" "$ROOT_DIR/copperctl" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
for path in (root / "copper-backup.service", root / "copper-monitor.service"):
    content = path.read_text(encoding="utf-8")
    content = content.replace("/usr/local/sbin/copperctl", sys.argv[2])
    path.write_text(content, encoding="utf-8")
PY
systemd-analyze verify "$unit_dir/copper-backup.service" "$unit_dir/copper-backup.timer" \
  "$unit_dir/copper-monitor.service" "$unit_dir/copper-monitor.timer" \
  > "$evidence/operations-systemd-verify.txt" 2>&1
grep -q '^FixedRandomDelay=true$' "$ROOT_DIR/config/systemd/copper-backup.timer"
grep -q '^LogRateLimitIntervalSec=30s$' "$ROOT_DIR/config/systemd/copper-backup.service"

python3 - "$evidence/operations-normal.json" "$evidence/operations-capacity-blocked.json" \
  "$evidence/docker-local-rotation.txt" > "$evidence/m4-05-summary.json" <<'PY'
import json, pathlib, sys
normal = json.load(open(sys.argv[1], encoding="utf-8"))
blocked = json.load(open(sys.argv[2], encoding="utf-8"))
rotation = pathlib.Path(sys.argv[3]).read_text(encoding="utf-8").splitlines()
print(json.dumps({
    "formatVersion": "copper-m4-operations-acceptance/v1",
    "result": "PASSED",
    "normalOperationsLevel": normal["overallLevel"],
    "localRecoveryPoint": normal["recovery"]["backup"]["localRecoveryPoint"],
    "hostLossRecoveryPoint": normal["recovery"]["backup"]["hostLossRecoveryPoint"],
    "capacityBlockedLevel": blocked["capacity"]["level"],
    "rotationEvidence": rotation,
    "incidentChecklists": ["database", "disk", "backup", "certificate", "time", "power"],
    "commercialSla": False,
}, ensure_ascii=False, sort_keys=True, indent=2))
PY
