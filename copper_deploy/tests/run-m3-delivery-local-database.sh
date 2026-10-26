#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$EUID" -eq 0 ]] || { echo "错误：请在 local-database 使用 sudo 执行" >&2; exit 1; }
[[ "$(uname -m)" == "aarch64" ]] || { echo "错误：此脚本只用于 local-database ARM 隔离补充验收" >&2; exit 1; }

run_id="$(date -u +%Y%m%d%H%M%S)-$$"
test_root="${COPPER_M3_TEST_ROOT:-/opt}/copper-m3-04-$run_id"
fixture="$test_root/fixture"
target_fixture="$test_root/target-fixture"
online_media="$test_root/online-media"
offline_media="$test_root/offline-media"
delta_media="$test_root/delta-media"
bad_media="$test_root/bad-media"
etc_dir="$test_root/etc"
var_online="$test_root/var-online"
var_offline="$test_root/var-offline"
var_failure="$test_root/var-failure"
backup_dir="$test_root/backups"
restore_root="$test_root/restores"
rootfs="$test_root/rootfs"
rootfs_tar="$test_root/rootfs.tar"
env_online="$etc_dir/online.env"
env_offline="$etc_dir/offline.env"
env_failure="$etc_dir/failure.env"
test_tags=(
  "docker.io/library/copper-m304-delivery-web:$run_id"
  "docker.io/library/copper-m304-delivery-backend:$run_id"
  "docker.io/library/copper-m304-delivery-mysql:$run_id"
)
http_port=18104
http_pid=""
image_ids=()
started="$(date +%s)"

shared_mysql_before="$(docker inspect --format '{{.Id}}' vm-mysql)"
shared_valkey_before="$(docker inspect --format '{{.Id}}' vm-valkey)"
host_8191_before="$(ss -H -ltn 'sport = :8191' 2>/dev/null || true)"
production_env_before="missing"
if [[ -f /etc/copper/copper.env ]]; then
  production_env_before="$(sha256sum /etc/copper/copper.env | awk '{print $1}')"
fi

cleanup() {
  local status=$?
  set +e
  [[ -z "$http_pid" ]] || kill "$http_pid" >/dev/null 2>&1 || true
  [[ -z "$http_pid" ]] || wait "$http_pid" >/dev/null 2>&1 || true
  docker image rm -f "${test_tags[@]}" "${image_ids[@]}" >/dev/null 2>&1 || true
  rm -rf --one-file-system -- "$test_root"
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
  if [[ "$(ss -H -ltn 'sport = :8191' 2>/dev/null || true)" != "$host_8191_before" ]]; then
    echo "严重错误：宿主机 8191 监听状态被测试改变" >&2
    status=1
  fi
  production_env_after="missing"
  if [[ -f /etc/copper/copper.env ]]; then
    production_env_after="$(sha256sum /etc/copper/copper.env | awk '{print $1}')"
  fi
  if [[ "$production_env_after" != "$production_env_before" ]]; then
    echo "严重错误：正式 /etc/copper/copper.env 被测试改变" >&2
    status=1
  fi
  trap - EXIT
  exit "$status"
}
trap cleanup EXIT

install -d -o root -g root -m 0700 \
  "$fixture" "$target_fixture" "$etc_dir" "$var_online" "$var_offline" "$var_failure" \
  "$backup_dir" "$restore_root" "$rootfs"
for image_index in 0 1 2; do
  printf 'm3-04-%s-%s\n' "$run_id" "$image_index" > "$rootfs/identity.txt"
  tar -C "$rootfs" -cf "$rootfs_tar" .
  imported="$(docker import --platform linux/amd64 "$rootfs_tar" "${test_tags[$image_index]}")"
  [[ "$imported" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo "错误：无法创建隔离测试镜像" >&2; exit 1; }
  [[ "$(docker image inspect --format '{{.Architecture}}' "$imported")" == "amd64" ]] \
    || { echo "错误：隔离测试镜像不是目标 amd64" >&2; exit 1; }
  image_ids+=("$imported")
done
web_image_id="${image_ids[0]}"
backend_image_id="${image_ids[1]}"
mysql_image_id="${image_ids[2]}"
image_arch=amd64

write_env() {
  local path="$1" var_dir="$2"
  cat > "$path" <<EOF
COPPER_ETC_DIR=$etc_dir
COPPER_VAR_DIR=$var_dir
COPPER_BACKUP_DIR=$backup_dir
COPPER_RESTORE_ROOT=$restore_root
EOF
  chmod 0600 "$path"
}
write_env "$env_online" "$var_online"
write_env "$env_offline" "$var_offline"
write_env "$env_failure" "$var_failure"
cat >> "$env_offline" <<EOF
COPPER_RELEASE_VERSION=0.2.0-rc.1
COPPER_WEB_IMAGE=$web_image_id
COPPER_BACKEND_IMAGE=$backend_image_id
COPPER_MYSQL_IMAGE=$mysql_image_id
EOF

deploy="$fixture/copper-deploy-0.2.0-rc.1.tar.gz"
notes="$fixture/copper-release-notes-0.2.0-rc.1.md"
acme="$fixture/acme.sh-3.1.3.tar.gz"
matrix="$fixture/copper-upgrade-matrix-0.2.0-rc.1.json"
manifest="$fixture/copper-release-0.2.0-rc.1.env"
values="$fixture/values.env"
printf 'M3-04 test-only deploy archive\n' > "$deploy"
cat > "$notes" <<'EOF'
# Copper 0.2.0-rc.1 M3-04 测试说明

## 功能变化
验证联网和离线不可变介质；不是正式发布。

## 数据库影响
目标示例为 Flyway V4，M3-04 不执行迁移。

## 已知问题
ARM64 镜像仅用于 local-database 交付流程测试，不可发布。

## 升级步骤
先 stage READY，再由 M3-02/M3-03 执行 prepare、migrate 和人工 switch。

## 停机与回退
交付本身不停机；后续升级使用维护窗口和 retained-sidecar 回退。
EOF
printf 'M3-04 test-only acme archive\n' > "$acme"
"$ROOT_DIR/scripts/release-contract.py" generate-matrix \
  --target-version 0.2.0-rc.1 --target-schema 4 \
  --source '0.1.0-m2-rc.2,3,retained-sidecar,maintenance-window,retained-sidecar-or-verified-backup' \
  --output "$matrix" >/dev/null
cat > "$values" <<EOF
COPPER_RELEASE_VERSION=0.2.0-rc.1
COPPER_RELEASE_CHANNEL=rc
COPPER_RELEASED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
COPPER_DEPLOY_TAG=v0.2.0-rc.1
COPPER_DEPLOY_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
COPPER_WEB_IMAGE=ghcr.io/soyia/copper_web@sha256:0000000000000000000000000000000000000000000000000000000000000000
COPPER_WEB_SOURCE_COMMIT=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
COPPER_WEB_WORKFLOW_RUN=1
COPPER_WEB_PLATFORM=linux/amd64
COPPER_BACKEND_IMAGE=ghcr.io/soyia/copper_backend@sha256:1111111111111111111111111111111111111111111111111111111111111111
COPPER_BACKEND_SOURCE_COMMIT=cccccccccccccccccccccccccccccccccccccccc
COPPER_BACKEND_WORKFLOW_RUN=2
COPPER_BACKEND_PLATFORM=linux/amd64
COPPER_MYSQL_IMAGE=mysql:8.4.10@sha256:2222222222222222222222222222222222222222222222222222222222222222
COPPER_MYSQL_VERSION=8.4.10
COPPER_MYSQL_PLATFORM=linux/amd64
COPPER_SUPPORTED_OS=ubuntu-24.04
COPPER_SUPPORTED_ARCH=amd64
COPPER_MIN_CPU=2
COPPER_MIN_MEMORY_GIB=4
COPPER_MIN_FREE_DISK_GIB=20
COPPER_DATABASE_SCHEMA_VERSION=4
COPPER_MIGRATION_SET_SHA256=3333333333333333333333333333333333333333333333333333333333333333
COPPER_BACKUP_FORMAT=copper-backup/v1
COPPER_EXPORT_FORMAT=copper-export/v1
COPPER_VALIDATION_LEVEL=L4
COPPER_RELEASE_NOTES_SHA256=$(sha256sum "$notes" | awk '{print $1}')
COPPER_DEPLOY_ARCHIVE=$(basename "$deploy")
COPPER_DEPLOY_ARCHIVE_SHA256=$(sha256sum "$deploy" | awk '{print $1}')
COPPER_RELEASE_NOTES_FILE=$(basename "$notes")
COPPER_ACME_ARCHIVE=$(basename "$acme")
COPPER_ACME_ARCHIVE_SHA256=$(sha256sum "$acme" | awk '{print $1}')
EOF
"$ROOT_DIR/scripts/release-contract.py" generate-manifest \
  --values "$values" --matrix "$matrix" --output "$manifest" >/dev/null
rm -f "$values"

"$ROOT_DIR/scripts/build-release-delivery.sh" online "$manifest" "$online_media" >/dev/null
env COPPER_TEST_MODE=1 \
  COPPER_DELIVERY_TEST_WEB_IMAGE="${test_tags[0]}" \
  COPPER_DELIVERY_TEST_BACKEND_IMAGE="${test_tags[1]}" \
  COPPER_DELIVERY_TEST_MYSQL_IMAGE="${test_tags[2]}" \
  "$ROOT_DIR/scripts/build-release-delivery.sh" offline "$manifest" "$offline_media" >/dev/null
online_index="$online_media/copper-delivery-online-0.2.0-rc.1.json"
offline_index="$offline_media/copper-delivery-offline-0.2.0-rc.1.json"

target_deploy="$target_fixture/copper-deploy-0.3.0-rc.1.tar.gz"
target_notes="$target_fixture/copper-release-notes-0.3.0-rc.1.md"
target_acme="$target_fixture/acme.sh-3.1.3.tar.gz"
target_matrix="$target_fixture/copper-upgrade-matrix-0.3.0-rc.1.json"
target_manifest="$target_fixture/copper-release-0.3.0-rc.1.env"
target_values="$target_fixture/values.env"
printf 'M3-04 delta test-only deploy archive\n' > "$target_deploy"
cat > "$target_notes" <<'EOF'
# Copper 0.3.0-rc.1 M3-04 差异升级测试说明

## 功能变化
验证只复用未变化的 Web、Backend 和 MySQL 镜像；不是正式发布。

## 数据库影响
本测试不修改数据库数据目录。

## 已知问题
ARM64 镜像仅用于 local-database 交付流程测试，不可发布。

## 升级步骤
先暂存差异 READY，再进入既有隔离升级状态机。

## 停机与回退
交付本身不停机；后续升级继续使用 retained-sidecar 回退。
EOF
cp "$acme" "$target_acme"
"$ROOT_DIR/scripts/release-contract.py" generate-matrix \
  --target-version 0.3.0-rc.1 --target-schema 5 \
  --source '0.2.0-rc.1,4,retained-sidecar,maintenance-window,retained-sidecar-or-verified-backup' \
  --output "$target_matrix" >/dev/null
cat > "$target_values" <<EOF
COPPER_RELEASE_VERSION=0.3.0-rc.1
COPPER_RELEASE_CHANNEL=rc
COPPER_RELEASED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
COPPER_DEPLOY_TAG=v0.3.0-rc.1
COPPER_DEPLOY_COMMIT=dddddddddddddddddddddddddddddddddddddddd
COPPER_WEB_IMAGE=ghcr.io/soyia/copper_web@sha256:4444444444444444444444444444444444444444444444444444444444444444
COPPER_WEB_SOURCE_COMMIT=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
COPPER_WEB_WORKFLOW_RUN=4
COPPER_WEB_PLATFORM=linux/amd64
COPPER_BACKEND_IMAGE=ghcr.io/soyia/copper_backend@sha256:5555555555555555555555555555555555555555555555555555555555555555
COPPER_BACKEND_SOURCE_COMMIT=ffffffffffffffffffffffffffffffffffffffff
COPPER_BACKEND_WORKFLOW_RUN=5
COPPER_BACKEND_PLATFORM=linux/amd64
COPPER_MYSQL_IMAGE=mysql:8.4.10@sha256:6666666666666666666666666666666666666666666666666666666666666666
COPPER_MYSQL_VERSION=8.4.10
COPPER_MYSQL_PLATFORM=linux/amd64
COPPER_SUPPORTED_OS=ubuntu-24.04
COPPER_SUPPORTED_ARCH=amd64
COPPER_MIN_CPU=2
COPPER_MIN_MEMORY_GIB=4
COPPER_MIN_FREE_DISK_GIB=20
COPPER_DATABASE_SCHEMA_VERSION=5
COPPER_MIGRATION_SET_SHA256=7777777777777777777777777777777777777777777777777777777777777777
COPPER_BACKUP_FORMAT=copper-backup/v1
COPPER_EXPORT_FORMAT=copper-export/v1
COPPER_VALIDATION_LEVEL=L4
COPPER_RELEASE_NOTES_SHA256=$(sha256sum "$target_notes" | awk '{print $1}')
COPPER_DEPLOY_ARCHIVE=$(basename "$target_deploy")
COPPER_DEPLOY_ARCHIVE_SHA256=$(sha256sum "$target_deploy" | awk '{print $1}')
COPPER_RELEASE_NOTES_FILE=$(basename "$target_notes")
COPPER_ACME_ARCHIVE=$(basename "$target_acme")
COPPER_ACME_ARCHIVE_SHA256=$(sha256sum "$target_acme" | awk '{print $1}')
EOF
"$ROOT_DIR/scripts/release-contract.py" generate-manifest \
  --values "$target_values" --matrix "$target_matrix" --output "$target_manifest" >/dev/null
rm -f "$target_values"
env COPPER_TEST_MODE=1 \
  COPPER_DELIVERY_TEST_WEB_IMAGE="${test_tags[0]}" \
  COPPER_DELIVERY_TEST_BACKEND_IMAGE="${test_tags[1]}" \
  COPPER_DELIVERY_TEST_MYSQL_IMAGE="${test_tags[2]}" \
  "$ROOT_DIR/scripts/build-release-delivery.sh" offline-delta \
  "$target_manifest" "$delta_media" "$offline_index" >/dev/null
delta_index="$delta_media/copper-upgrade-delivery-offline-delta-0.2.0-rc.1-to-0.3.0-rc.1.json"
online_sha="$(sha256sum "$online_index" | awk '{print $1}')"
offline_sha="$(sha256sum "$offline_index" | awk '{print $1}')"
delta_sha="$(sha256sum "$delta_index" | awk '{print $1}')"

python3 -m http.server "$http_port" --bind 127.0.0.1 --directory "$online_media" \
  >"$test_root/http.log" 2>&1 &
http_pid=$!
for _ in $(seq 1 20); do
  curl --fail --silent "http://127.0.0.1:$http_port/$([ -f "$online_index" ] && basename "$online_index")" >/dev/null && break
  sleep 0.2
done
kill -0 "$http_pid"
online_url="http://127.0.0.1:$http_port/$(basename "$online_index")"

if env COPPER_TEST_MODE=1 COPPER_DELIVERY_TEST_FAIL_PULL=1 \
  "$ROOT_DIR/copperctl" --env-file "$env_failure" release stage online "$online_url" "$online_sha" \
  >"$test_root/pull-failure.out" 2>"$test_root/pull-failure.err"; then
  echo "错误：测试注入的联网拉取失败意外成功" >&2
  exit 1
fi
[[ ! -e "$var_failure/releases/0.2.0-rc.1" ]] || { echo "错误：拉取失败错误产生 READY" >&2; exit 1; }
echo "online_pull_failure_no_ready=passed"

if env COPPER_TEST_MODE=1 \
  "$ROOT_DIR/copperctl" --env-file "$env_failure" release stage online "$online_url" "$(printf 'f%.0s' {1..64})" \
  >"$test_root/hash-failure.out" 2>"$test_root/hash-failure.err"; then
  echo "错误：错误索引摘要意外成功" >&2
  exit 1
fi
[[ ! -e "$var_failure/releases/0.2.0-rc.1" ]] || { echo "错误：摘要失败错误产生 READY" >&2; exit 1; }
echo "online_digest_failure_no_ready=passed"

delivery_env=(COPPER_TEST_MODE=1 COPPER_DELIVERY_TEST_WEB_IMAGE="${test_tags[0]}" \
  COPPER_DELIVERY_TEST_BACKEND_IMAGE="${test_tags[1]}" COPPER_DELIVERY_TEST_MYSQL_IMAGE="${test_tags[2]}")
env "${delivery_env[@]}" "$ROOT_DIR/copperctl" --env-file "$env_online" \
  release stage online "$online_url" "$online_sha" > "$test_root/online-stage.out"
env "${delivery_env[@]}" "$ROOT_DIR/copperctl" --env-file "$env_online" \
  release stage online "$online_url" "$online_sha" > "$test_root/online-repeat.out"
grep -q 'idempotent=true' "$test_root/online-repeat.out"
env COPPER_TEST_MODE=1 "$ROOT_DIR/copperctl" --env-file "$env_online" \
  release status 0.2.0-rc.1 --json > "$test_root/online-status.json"
python3 - "$test_root/online-status.json" <<'PY'
import json, pathlib, sys
ready = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["releases"][0]
assert ready["status"] == "READY"
assert ready["mode"] == "online"
assert len(ready["images"]) == 3
PY
echo "online_stage_and_idempotency=passed"

docker image rm -f "${test_tags[@]}" "${image_ids[@]}" >/dev/null 2>&1 || true
for image_id in "${image_ids[@]}"; do
  [[ -z "$(docker image ls -q "$image_id" 2>/dev/null)" ]] \
    || { echo "错误：未能移除离线导入前测试镜像" >&2; exit 1; }
done
if env COPPER_TEST_MODE=1 "$ROOT_DIR/copperctl" --env-file "$env_online" \
  release status 0.2.0-rc.1 --json >"$test_root/missing-image.out" 2>"$test_root/missing-image.err"; then
  echo "错误：READY 镜像被删除后 status 仍意外成功" >&2
  exit 1
fi
echo "ready_missing_image_detected=passed"
env "${delivery_env[@]}" HTTP_PROXY=http://127.0.0.1:9 HTTPS_PROXY=http://127.0.0.1:9 \
  "$ROOT_DIR/copperctl" --env-file "$env_offline" release stage offline "$offline_index" "$offline_sha" \
  > "$test_root/offline-stage.out"
docker image inspect "$web_image_id" "$backend_image_id" "$mysql_image_id" >/dev/null
env "${delivery_env[@]}" HTTP_PROXY=http://127.0.0.1:9 HTTPS_PROXY=http://127.0.0.1:9 \
  "$ROOT_DIR/copperctl" --env-file "$env_offline" release stage offline "$offline_index" "$offline_sha" \
  > "$test_root/offline-repeat.out"
grep -q 'idempotent=true' "$test_root/offline-repeat.out"
env COPPER_TEST_MODE=1 "$ROOT_DIR/copperctl" --env-file "$env_offline" \
  release status 0.2.0-rc.1 --json > "$test_root/offline-status.json"
python3 - "$test_root/offline-status.json" <<'PY'
import json, pathlib, sys
ready = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["releases"][0]
assert ready["status"] == "READY"
assert ready["mode"] == "offline"
PY
echo "offline_no_registry_stage_and_idempotency=passed"

[[ -z "$("$ROOT_DIR/scripts/delivery-contract.py" image-archive "$delta_index")" ]] \
  || { echo "错误：三项镜像未变化的差异包仍携带镜像归档" >&2; exit 1; }
[[ -z "$(find "$delta_media" -maxdepth 1 -type f -name 'copper-images-*' -print -quit)" ]] \
  || { echo "错误：差异包目录存在未声明的完整镜像" >&2; exit 1; }
env "${delivery_env[@]}" HTTP_PROXY=http://127.0.0.1:9 HTTPS_PROXY=http://127.0.0.1:9 \
  "$ROOT_DIR/copperctl" --env-file "$env_offline" \
  release stage offline-delta "$delta_index" "$delta_sha" > "$test_root/delta-stage.out"
env COPPER_TEST_MODE=1 "$ROOT_DIR/copperctl" --env-file "$env_offline" \
  release status 0.3.0-rc.1 --json > "$test_root/delta-status.json"
python3 - "$delta_index" "$test_root/delta-status.json" <<'PY'
import json, pathlib, sys
index = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
ready = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))["releases"][0]
assert ready["status"] == "READY"
assert ready["mode"] == "offline-delta"
assert {item["role"]: item["delivery"] for item in index["images"]} == {
    "web": "reuse", "backend": "reuse", "mysql": "reuse",
}
assert {item["role"] for item in index["artifacts"]} == {"deploy", "matrix", "notes", "acme"}
PY
echo "offline_delta_reuses_unchanged_mysql_without_archive=passed"

cp -a "$offline_media" "$bad_media"
bad_index="$bad_media/$(basename "$offline_index")"
bad_archive="$bad_media/$("$ROOT_DIR/scripts/delivery-contract.py" image-archive "$bad_index")"
chmod 0600 "$bad_index" "$bad_archive"
rm -f "$bad_index"
printf 'not a docker archive\n' > "$bad_archive"
"$ROOT_DIR/scripts/delivery-contract.py" generate \
  --manifest "$bad_media/$(basename "$manifest")" --mode offline --image-archive "$bad_archive" \
  --image-id "web,$web_image_id" --image-id "backend,$backend_image_id" --image-id "mysql,$mysql_image_id" \
  --output "$bad_index" >/dev/null
bad_sha="$(sha256sum "$bad_index" | awk '{print $1}')"
if env "${delivery_env[@]}" "$ROOT_DIR/copperctl" --env-file "$env_failure" \
  release stage offline "$bad_index" "$bad_sha" >"$test_root/import-failure.out" 2>"$test_root/import-failure.err"; then
  echo "错误：无效 Docker 归档意外成功" >&2
  exit 1
fi
[[ ! -e "$var_failure/releases/0.2.0-rc.1" ]] || { echo "错误：导入失败错误产生 READY" >&2; exit 1; }
echo "offline_import_failure_no_ready=passed"

[[ "$(stat -c '%U:%G %a' "$var_online/releases/0.2.0-rc.1")" == "root:root 700" ]]
[[ "$(stat -c '%U:%G %a' "$var_online/releases/0.2.0-rc.1/ready.json")" == "root:root 400" ]]
[[ "$(stat -c '%U:%G %a' "$var_offline/releases/0.2.0-rc.1")" == "root:root 700" ]]
[[ "$(stat -c '%U:%G %a' "$var_offline/releases/0.2.0-rc.1/ready.json")" == "root:root 400" ]]

elapsed="$(($(date +%s) - started))"
echo "m3_delivery_runtime=passed"
echo "host_architecture=$(uname -m)"
echo "test_image_architecture=$image_arch"
echo "runtime_seconds=$elapsed"
echo "host_8191_unchanged=passed"
echo "shared_vm_mysql_unchanged=passed"
echo "shared_vm_valkey_unchanged=passed"
echo "production_env_unchanged=passed"
