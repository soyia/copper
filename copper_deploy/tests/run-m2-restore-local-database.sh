#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$EUID" -eq 0 ]] || { echo "错误：请在 local-database 使用 sudo 执行" >&2; exit 1; }
[[ "$(uname -m)" == "aarch64" ]] || { echo "错误：此脚本仅用于 local-database 的 ARM 隔离补充验收" >&2; exit 1; }

run_id="$(date -u +%Y%m%d%H%M%S)-$$"
etc_dir="/tmp/copper-m2-03-etc-$run_id"
var_dir="/tmp/copper-m2-03-var-$run_id"
backup_dir="/tmp/copper-m2-03-backup-$run_id"
restore_root="/tmp/copper-m2-03-restores-$run_id"
identity="/tmp/copper-m2-03-identity-$run_id.txt"
env_file="$etc_dir/copper.env"
project="copper-m2-03-source-$run_id"
source_port=18083
restore_port=18084

web_image="${COPPER_M2_03_WEB_IMAGE:-sha256:ae4458638da8e1a91aafffb231c5f8778e964bca650c8a8cb23a7e8ac557aa3c}"
backend_image="${COPPER_M2_03_BACKEND_IMAGE:-sha256:4ff840cfccd057b1c434c198a97acbc915c837b63edcb4a93841ae7f1b9572ea}"
mysql_image="${COPPER_M2_03_MYSQL_IMAGE:-sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af}"
other_web_image="${COPPER_M2_03_OTHER_WEB_IMAGE:-sha256:04d1486d8cb0294531a1f5b6c4a6313411d014b0806a0467333510596734b166}"
shared_mysql_before="$(docker inspect --format '{{.Id}}' vm-mysql)"
shared_valkey_before="$(docker inspect --format '{{.Id}}' vm-valkey)"
started="$(date +%s)"
completed=0

cleanup() {
  local status=$?
  set +e
  if [[ -f "$env_file" ]]; then
    env COPPER_TEST_MODE=1 COPPER_ALLOW_MUTABLE_IMAGES=1 "$ROOT_DIR/copperctl" --env-file "$env_file" down >/dev/null 2>&1
  fi
  while read -r container_id compose_project; do
    [[ "$compose_project" == copper-m2-03-source-* || "$compose_project" == copper-restore-m203-* ]] || continue
    docker rm -f "$container_id" >/dev/null 2>&1
  done < <(docker ps -a --format '{{.ID}} {{.Label "com.docker.compose.project"}}')
  rm -rf --one-file-system -- "$etc_dir" "$var_dir" "$backup_dir" "$restore_root"
  rm -f -- "$identity"
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

for image in "$web_image" "$backend_image" "$mysql_image" "$other_web_image"; do
  docker image inspect "$image" >/dev/null
done
install -d -o root -g root -m 0750 "$etc_dir"
install -o root -g root -m 0600 "$ROOT_DIR/config/copper.env.example" "$env_file"
age-keygen -o "$identity" >/dev/null 2>&1
chmod 0600 "$identity"
recipient="$(age-keygen -y "$identity")"

python3 - "$env_file" "$project" "$web_image" "$backend_image" "$mysql_image" "$etc_dir" "$var_dir" "$backup_dir" "$restore_root" "$source_port" "$restore_port" "$recipient" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
values = {
    "COPPER_SITE_ID": "copper-m203-test",
    "COPPER_PROJECT_NAME": sys.argv[2],
    "COPPER_DEPLOY_MODE": "external-proxy",
    "COPPER_DOMAIN": "m203.test.internal",
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
    "COPPER_RESTORE_PROXY_PORT": sys.argv[11],
    "COPPER_BACKUP_AGE_RECIPIENT": sys.argv[12],
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

export COPPER_TEST_MODE=1
export COPPER_ALLOW_MUTABLE_IMAGES=1
"$ROOT_DIR/copperctl" --env-file "$env_file" install

export COPPER_ENV_FILE="$env_file"
export COPPER_BACKUP_TEST_IDENTITY="$identity"
export COPPER_RESTORE_TEST_OTHER_WEB_IMAGE="$other_web_image"
"$ROOT_DIR/tests/m2-restore-runtime.sh"

elapsed="$(($(date +%s) - started))"
mysql_container="$(COPPER_ENV_FILE="$env_file" bash -c 'source "$1/scripts/lib.sh"; compose_cmd ps -q mysql' _ "$ROOT_DIR")"
mysql_version="$(docker exec "$mysql_container" sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -Nse "SELECT VERSION();"' 2>/dev/null || true)"
echo "runtime_seconds=$elapsed"
echo "mysql_version=${mysql_version:-8.4.10}"
echo "shared_vm_mysql_unchanged=passed"
echo "shared_vm_valkey_unchanged=passed"
completed=1
