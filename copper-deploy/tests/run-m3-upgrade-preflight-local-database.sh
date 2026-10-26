#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$EUID" -eq 0 ]] || { echo "错误：请在 local-database 使用 sudo 执行" >&2; exit 1; }
[[ "$(uname -m)" == "aarch64" ]] || { echo "错误：此脚本只用于 local-database ARM 隔离补充验收" >&2; exit 1; }

run_id="$(date -u +%Y%m%d%H%M%S)-$$"
test_root="${COPPER_M3_TEST_ROOT:-/opt}"
[[ "$test_root" == /* && "$test_root" != "/" ]] || {
  echo "错误：COPPER_M3_TEST_ROOT 必须是非根目录的绝对路径" >&2
  exit 1
}
install -d -o root -g root -m 0700 "$test_root"
etc_dir="$test_root/copper-m3-02-etc-$run_id"
var_dir="$test_root/copper-m3-02-var-$run_id"
backup_dir="$test_root/copper-m3-02-backup-$run_id"
restore_root="$test_root/copper-m3-02-restores-$run_id"
identity="$test_root/copper-m3-02-identity-$run_id.txt"
receipt="$test_root/copper-m3-02-receipt-$run_id.json"
handover="$test_root/copper-m3-02-handover-$run_id.txt"
image_build_dir="$test_root/copper-m3-02-images-$run_id"
env_file="$etc_dir/copper.env"
release_dir="$etc_dir/test-release"
control_releases="$etc_dir/control-releases"
control_bin="$etc_dir/control-bin"
project="copper-m3-02-source-$run_id"
source_port=18089

web_image="sha256:098ad42a89de5c3890ab78548c966adcf0b2f4ebe98ef3c569ba3539ab3d153c"
backend_image="sha256:06b97662116d74b45604ea7607517e98abb644332658c49f8144d933c11124cc"
mysql_image="sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af"
native_java_image="sha256:1824944ef1bd572d1ff0952afeb2fec7931d77c972c4fbc4dfcdf89f758fb490"
native_caddy_image="sha256:ae4458638da8e1a91aafffb231c5f8778e964bca650c8a8cb23a7e8ac557aa3c"
backend_test_tag="copper-m302-backend-arm64:$run_id"
web_test_tag="copper-m302-web-arm64:$run_id"
target_backend_test_tag="copper-m303-backend-arm64:$run_id"
failed_backend_test_tag="copper-m303-backend-failed-arm64:$run_id"
caddy_base_tag="copper-m302-caddy-base:$run_id"
java_base_tag="copper-m302-java-base:$run_id"
backend_extract="copper-m302-backend-extract-$run_id"
web_extract="copper-m302-web-extract-$run_id"
shared_mysql_before="$(docker inspect --format '{{.Id}}' vm-mysql)"
shared_valkey_before="$(docker inspect --format '{{.Id}}' vm-valkey)"
host_8191_before="$(ss -H -ltn 'sport = :8191' 2>/dev/null || true)"
started="$(date +%s)"

cleanup() {
  local status=$?
  set +e
  if [[ -f "$env_file" ]]; then
    if [[ "$status" -ne 0 ]]; then
      env COPPER_TEST_MODE=1 COPPER_ALLOW_MUTABLE_IMAGES=1 "$ROOT_DIR/copperctl" --env-file "$env_file" status >&2 || true
      env COPPER_TEST_MODE=1 COPPER_ALLOW_MUTABLE_IMAGES=1 "$ROOT_DIR/copperctl" --env-file "$env_file" logs backend >&2 || true
      env COPPER_TEST_MODE=1 COPPER_ALLOW_MUTABLE_IMAGES=1 "$ROOT_DIR/copperctl" --env-file "$env_file" logs web >&2 || true
    fi
    env COPPER_TEST_MODE=1 COPPER_ALLOW_MUTABLE_IMAGES=1 "$ROOT_DIR/copperctl" --env-file "$env_file" down >/dev/null 2>&1
  fi
  while read -r container_id compose_project; do
    [[ "$compose_project" == copper-m3-02-source-* || "$compose_project" == copper-restore-m303-* ]] || continue
    docker rm -f "$container_id" >/dev/null 2>&1
  done < <(docker ps -a --format '{{.ID}} {{.Label "com.docker.compose.project"}}')
  rm -rf --one-file-system -- "$etc_dir" "$var_dir" "$backup_dir" "$restore_root"
  rm -rf --one-file-system -- "$image_build_dir"
  rm -f -- "$identity" "$receipt" "$handover"
  docker rm -f "$backend_extract" "$web_extract" >/dev/null 2>&1 || true
  docker image rm -f "$backend_test_tag" "$web_test_tag" "$target_backend_test_tag" "$failed_backend_test_tag" \
    "$caddy_base_tag" "$java_base_tag" >/dev/null 2>&1 || true
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
  trap - EXIT
  exit "$status"
}
trap cleanup EXIT

for image in "$web_image" "$backend_image" "$mysql_image" "$native_java_image" "$native_caddy_image"; do
  docker image inspect "$image" >/dev/null
done

# local-database is ARM and must not pull from the Internet. Repackage the exact official M2
# application payloads on already-present native runtime bases; these images are test-only and removed on exit.
install -d -o root -g root -m 0700 "$image_build_dir/backend" "$image_build_dir/web/srv"
docker create --name "$backend_extract" "$backend_image" >/dev/null
docker cp "$backend_extract:/app/copper.jar" "$image_build_dir/backend/copper.jar"
docker rm "$backend_extract" >/dev/null
docker create --name "$web_extract" "$web_image" >/dev/null
docker cp "$web_extract:/srv/." "$image_build_dir/web/srv"
docker rm "$web_extract" >/dev/null
docker tag "$native_caddy_image" "$caddy_base_tag"
docker tag "$native_java_image" "$java_base_tag"
cat > "$image_build_dir/backend/Dockerfile" <<EOF
FROM $java_base_tag
RUN groupadd --gid 10001 copper && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin copper
WORKDIR /app
COPY --chown=10001:10001 copper.jar /app/copper.jar
USER 10001:10001
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-Djava.io.tmpdir=/app/data/tmp","-Djava.security.egd=file:/dev/urandom","-jar","/app/copper.jar"]
EOF
cat > "$image_build_dir/web/Dockerfile" <<EOF
FROM $caddy_base_tag
COPY srv/ /srv/
EOF
docker build --pull=false --network=none -t "$backend_test_tag" "$image_build_dir/backend" >/dev/null
docker build --pull=false --network=none -t "$web_test_tag" "$image_build_dir/web" >/dev/null
backend_image="$(docker image inspect --format '{{.Id}}' "$backend_test_tag")"
web_image="$(docker image inspect --format '{{.Id}}' "$web_test_tag")"
[[ "$(docker image inspect --format '{{.Architecture}}' "$backend_image")" == "arm64" ]] || { echo "错误：测试 Backend 不是 ARM64" >&2; exit 1; }
[[ "$(docker image inspect --format '{{.Architecture}}' "$web_image")" == "arm64" ]] || { echo "错误：测试 Web 不是 ARM64" >&2; exit 1; }
install -d -o root -g root -m 0750 "$etc_dir"
install -o root -g root -m 0600 "$ROOT_DIR/config/copper.env.example" "$env_file"
age-keygen -o "$identity" >/dev/null 2>&1
chmod 0600 "$identity"
recipient="$(age-keygen -y "$identity")"

python3 - "$env_file" "$project" "$web_image" "$backend_image" "$mysql_image" "$etc_dir" "$var_dir" "$backup_dir" "$restore_root" "$source_port" "$recipient" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
values = {
    "COPPER_SITE_ID": "copper-m302-test",
    "COPPER_PROJECT_NAME": sys.argv[2],
    "COPPER_RELEASE_VERSION": "0.1.0-m2-rc.2",
    "COPPER_DEPLOY_MODE": "external-proxy",
    "COPPER_DOMAIN": "m302.test.internal",
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
    "COPPER_RESTORE_PROXY_PORT": "18090",
    "COPPER_BACKUP_AGE_RECIPIENT": sys.argv[11],
    "COPPER_BACKUP_TARGETS": "local",
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

export COPPER_TEST_MODE=1
export COPPER_ALLOW_MUTABLE_IMAGES=1
export COPPER_UPGRADE_CONTROL_RELEASES_DIR="$control_releases"
export COPPER_UPGRADE_CONTROL_BIN_DIR="$control_bin"
export COMPOSE_PROGRESS=plain
"$ROOT_DIR/copperctl" --env-file "$env_file" install
export COPPER_ENV_FILE="$env_file"
source "$ROOT_DIR/scripts/lib.sh"

require_healthy() {
  local service id state health
  for service in mysql backend web; do
    id="$(compose_cmd ps -q "$service")"
    state="$(docker inspect --format '{{.State.Status}}' "$id")"
    health="$(docker inspect --format '{{.State.Health.Status}}' "$id")"
    [[ "$state/$health" == "running/healthy" ]] || die "$service 未健康：$state/$health"
  done
}

archive_count() {
  find "$backup_dir" -maxdepth 1 -type f -name '*.copperbak' -print | wc -l
}

expect_preflight_failure() {
  local label="$1" manifest="$2"
  shift 2
  local before
  before="$(archive_count)"
  if env "$@" "$ROOT_DIR/copperctl" upgrade preflight "$manifest" \
    >"$etc_dir/$label.out" 2>"$etc_dir/$label.err"; then
    die "$label 预检意外成功"
  fi
  [[ "$(archive_count)" == "$before" ]] || die "$label 预检错误创建了备份"
  [[ ! -e "$var_dir/upgrades/prepared.json" ]] || die "$label 预检错误写入 PREPARED"
  require_healthy
  printf '%s=passed\n' "$label"
}

credential_file="$etc_dir/credentials/initial-admin.txt"
username="$(awk -F= '$1 == "username" {print $2; exit}' "$credential_file")"
initial_password="$(awk -F= '$1 == "password" {print $2; exit}' "$credential_file")"
new_password="M302Runtime$(openssl rand -hex 12)"
cookie="$etc_dir/source-cookie.txt"
curl --fail --silent --show-error -c "$cookie" -H 'Content-Type: application/json' \
  --data "{\"username\":\"$username\",\"password\":\"$initial_password\"}" \
  "http://127.0.0.1:$source_port/api/auth/login" >/dev/null
curl --fail --silent --show-error -b "$cookie" -H 'Content-Type: application/json' \
  --data "{\"currentPassword\":\"$initial_password\",\"newPassword\":\"$new_password\"}" \
  "http://127.0.0.1:$source_port/api/auth/change-password" >/dev/null
[[ ! -e "$credential_file" ]] || die "首次改密后一次性凭据未销毁"
unset initial_password new_password

schema="$(printf '%s\n' "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1;" \
  | compose_cmd exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -D copper_v2 --batch --raw --skip-column-names' \
  | tr -d '[:space:]')"
[[ "$schema" =~ ^[0-9]+$ ]] || die "无法读取测试源 schema"
target_schema="$schema"
if [[ "${COPPER_M3_03_RUNTIME:-0}" == "1" ]]; then
  target_schema="$((schema + 1))"
  install -d -o root -g root -m 0700 "$image_build_dir/target-backend" "$image_build_dir/failed-backend"
  install -o root -g root -m 0600 "$image_build_dir/backend/copper.jar" "$image_build_dir/target-backend/copper.jar"
  install -o root -g root -m 0600 "$image_build_dir/backend/copper.jar" "$image_build_dir/failed-backend/copper.jar"
  python3 - "$image_build_dir/target-backend/copper.jar" "$image_build_dir/failed-backend/copper.jar" "$target_schema" <<'PY'
import pathlib
import sys
import zipfile

good = pathlib.Path(sys.argv[1])
bad = pathlib.Path(sys.argv[2])
version = sys.argv[3]
entry = f"BOOT-INF/classes/db/migration/V{version}__m3_upgrade_probe.sql"
with zipfile.ZipFile(good, "a", compression=zipfile.ZIP_DEFLATED) as archive:
    archive.writestr(entry, """CREATE TABLE system_upgrade_audit (
  id char(36) NOT NULL PRIMARY KEY,
  upgrade_id varchar(48) NOT NULL UNIQUE,
  source_version varchar(64) NOT NULL,
  target_version varchar(64) NOT NULL,
  source_schema varchar(32) NOT NULL,
  target_schema varchar(32) NOT NULL,
  status varchar(32) NOT NULL,
  backup_id char(32) NOT NULL,
  release_manifest_sha256 char(64) NOT NULL,
  started_at timestamp NOT NULL,
  migration_validated_at timestamp NULL,
  switched_at timestamp NULL,
  completed_at timestamp NULL,
  failure_phase varchar(64) NOT NULL DEFAULT '',
  failure_message varchar(500) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE m3_upgrade_probe (id BIGINT NOT NULL PRIMARY KEY, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
""")
with zipfile.ZipFile(bad, "a", compression=zipfile.ZIP_DEFLATED) as archive:
    archive.writestr(entry, "CREATE TABL m3_upgrade_probe_broken (id BIGINT NOT NULL);\n")
PY
  for directory in target-backend failed-backend; do
    cp "$image_build_dir/backend/Dockerfile" "$image_build_dir/$directory/Dockerfile"
  done
  docker build --pull=false --network=none -t "$target_backend_test_tag" "$image_build_dir/target-backend" >/dev/null
  docker build --pull=false --network=none -t "$failed_backend_test_tag" "$image_build_dir/failed-backend" >/dev/null
  target_backend_image="$(docker image inspect --format '{{.Id}}' "$target_backend_test_tag")"
  failed_backend_image="$(docker image inspect --format '{{.Id}}' "$failed_backend_test_tag")"
  [[ "$(docker image inspect --format '{{.Architecture}}' "$target_backend_image")" == "arm64" ]] || die "目标 Backend 测试镜像不是 ARM64"
  [[ "$(docker image inspect --format '{{.Architecture}}' "$failed_backend_image")" == "arm64" ]] || die "失败 Backend 测试镜像不是 ARM64"
  seed_relative="inbounds/9303/m3-upgrade.txt"
  install -d -o 10001 -g 10001 -m 0750 "$var_dir/files/inbounds/9303"
  printf 'Copper M3-03 retained-sidecar migration\n' > "$image_build_dir/m3-upgrade.txt"
  install -o 10001 -g 10001 -m 0640 "$image_build_dir/m3-upgrade.txt" "$var_dir/files/$seed_relative"
  seed_size="$(stat -c '%s' "$var_dir/files/$seed_relative")"
  seed_sha="$(sha256sum "$var_dir/files/$seed_relative" | awk '{print $1}')"
  compose_cmd exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot -D copper_v2' <<SQL
INSERT INTO partner_account
  (partner_id, account_type, balance, frozen_amount, credit_limit, opposite_balance, opposite_credit_limit)
VALUES (9303, 'TRADE', 303.03, 0, 0, 0, 0);
INSERT INTO inbound_attachment
  (inbound_id, file_name, content_type, file_size, sha256, storage_path, file_content, uploaded_by)
VALUES (9303, 'm3-upgrade.txt', 'text/plain', $seed_size, '$seed_sha', '$seed_relative', NULL, 0);
SQL
fi
install -d -o root -g root -m 0700 "$release_dir"
notes="$release_dir/release-notes.md"
printf 'M3-02 isolated test fixture; never publish as a release.\n' > "$notes"
notes_sha="$(sha256sum "$notes" | awk '{print $1}')"
deploy_archive="$release_dir/copper-deploy-0.2.0-rc.1.tar.gz"
acme_archive="$release_dir/acme.sh-3.1.3.tar.gz"
install -d -o root -g root -m 0700 \
  "$control_releases/0.1.0-m2-rc.2/scripts" "$control_bin" \
  "$image_build_dir/control-target/copper-deploy-0.2.0-rc.1/scripts"
for entry in copperctl copper-install copper-maintain; do
  install -o root -g root -m 0755 "$ROOT_DIR/$entry" "$control_releases/0.1.0-m2-rc.2/$entry"
  install -o root -g root -m 0755 "$ROOT_DIR/$entry" "$image_build_dir/control-target/copper-deploy-0.2.0-rc.1/$entry"
  ln -s "$control_releases/0.1.0-m2-rc.2/$entry" "$control_bin/$entry"
done
install -o root -g root -m 0644 "$ROOT_DIR/scripts/lib.sh" "$control_releases/0.1.0-m2-rc.2/scripts/lib.sh"
install -o root -g root -m 0644 "$ROOT_DIR/scripts/lib.sh" "$image_build_dir/control-target/copper-deploy-0.2.0-rc.1/scripts/lib.sh"
install -o root -g root -m 0644 "$ROOT_DIR/compose.yaml" "$control_releases/0.1.0-m2-rc.2/compose.yaml"
install -o root -g root -m 0644 "$ROOT_DIR/compose.yaml" "$image_build_dir/control-target/copper-deploy-0.2.0-rc.1/compose.yaml"
tar -C "$image_build_dir/control-target" -czf "$deploy_archive" copper-deploy-0.2.0-rc.1
printf 'M3-02 test-only acme archive\n' > "$acme_archive"
deploy_archive_sha="$(sha256sum "$deploy_archive" | awk '{print $1}')"
acme_archive_sha="$(sha256sum "$acme_archive" | awk '{print $1}')"
matrix="$release_dir/upgrade-matrix.json"
manifest="$release_dir/copper-release-0.2.0-rc.1.env"
values="$release_dir/values.env"
"$ROOT_DIR/scripts/release-contract.py" generate-matrix \
  --target-version 0.2.0-rc.1 --target-schema "$target_schema" \
  --source "0.1.0-m2-rc.2,$schema,retained-sidecar,maintenance-window,retained-sidecar-or-verified-backup" \
  --output "$matrix" >/dev/null
cat > "$values" <<EOF
COPPER_RELEASE_VERSION=0.2.0-rc.1
COPPER_RELEASE_CHANNEL=rc
COPPER_RELEASED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
COPPER_DEPLOY_TAG=v0.2.0-rc.1
COPPER_DEPLOY_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
COPPER_WEB_IMAGE=ghcr.io/soyia/copper_web@sha256:098ad42a89de5c3890ab78548c966adcf0b2f4ebe98ef3c569ba3539ab3d153c
COPPER_WEB_SOURCE_COMMIT=03525f65cbde5f24843566d9c5f14e79dbc6e904
COPPER_WEB_WORKFLOW_RUN=29296462811
COPPER_WEB_PLATFORM=linux/amd64
COPPER_BACKEND_IMAGE=ghcr.io/soyia/copper_backend@sha256:06b97662116d74b45604ea7607517e98abb644332658c49f8144d933c11124cc
COPPER_BACKEND_SOURCE_COMMIT=d099cc7760d568bf0ddd3393c341203205b6eef4
COPPER_BACKEND_WORKFLOW_RUN=29296460745
COPPER_BACKEND_PLATFORM=linux/amd64
COPPER_MYSQL_IMAGE=mysql:8.4.10@sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af
COPPER_MYSQL_VERSION=8.4.10
COPPER_MYSQL_PLATFORM=linux/amd64
COPPER_SUPPORTED_OS=ubuntu-24.04
COPPER_SUPPORTED_ARCH=amd64
COPPER_MIN_CPU=1
COPPER_MIN_MEMORY_GIB=1
COPPER_MIN_FREE_DISK_GIB=1
COPPER_DATABASE_SCHEMA_VERSION=$target_schema
COPPER_MIGRATION_SET_SHA256=$(sha256sum "$ROOT_DIR/compose.yaml" | awk '{print $1}')
COPPER_BACKUP_FORMAT=copper-backup/v1
COPPER_EXPORT_FORMAT=copper-export/v1
COPPER_VALIDATION_LEVEL=L4
COPPER_RELEASE_NOTES_SHA256=$notes_sha
COPPER_DEPLOY_ARCHIVE=$(basename "$deploy_archive")
COPPER_DEPLOY_ARCHIVE_SHA256=$deploy_archive_sha
COPPER_RELEASE_NOTES_FILE=$(basename "$notes")
COPPER_ACME_ARCHIVE=$(basename "$acme_archive")
COPPER_ACME_ARCHIVE_SHA256=$acme_archive_sha
EOF
chmod 0600 "$values" "$notes" "$matrix"
"$ROOT_DIR/scripts/release-contract.py" generate-manifest \
  --values "$values" --matrix "$matrix" --output "$manifest" >/dev/null
chmod 0600 "$manifest"
# PREPARED must retain the staged delivery receipt even in image-override runtime tests.
printf '%s\n' '{"formatVersion":"copper-delivery-ready/v1","testFixture":true}' > "$(dirname "$manifest")/ready.json"
chmod 0600 "$(dirname "$manifest")/ready.json"
"$ROOT_DIR/copperctl" release validate "$manifest" >/dev/null

expect_preflight_failure missing_key_restore_proof "$manifest"

printf 'Copper M3-02 isolated key handover\n' > "$handover"
chmod 0600 "$handover"
handover_sha="$(sha256sum "$handover" | awk '{print $1}')"
"$ROOT_DIR/copperctl" backup key register \
  --handover-sha256 "$handover_sha" \
  --customer-confirmed-by "M3 test customer" \
  --witness-confirmed-by "M3 test witness" >/dev/null
"$ROOT_DIR/copperctl" backup create >/dev/null
first_archive_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["archive"])' "$backup_dir/last-success.json")"
"$ROOT_DIR/copper-key-offline" verify-backup \
  --identity "$identity" --archive "$backup_dir/$first_archive_name" --receipt "$receipt" >/dev/null
chmod 0600 "$receipt"
"$ROOT_DIR/copperctl" backup key record-restore-test "$receipt" >/dev/null
"$ROOT_DIR/copperctl" backup key audit >/dev/null

"$ROOT_DIR/copperctl" upgrade preflight "$manifest" --json > "$etc_dir/preflight.json"
python3 - "$etc_dir/preflight.json" <<'PY'
import json, pathlib, sys
preflight_path = pathlib.Path(sys.argv[1])
raw_preflight = preflight_path.read_text(encoding="utf-8")
try:
    document = json.loads(raw_preflight)
except json.JSONDecodeError as exc:
    raise AssertionError(f"upgrade preflight --json returned non-JSON: {raw_preflight!r}") from exc
assert document["status"] == "PREFLIGHT_PASSED"
assert document["checks"]["offlineRecoveryKeyRestoreTest"] == "PASSED"
assert document["checks"]["diskCapacity"] == "PASSED"
PY
echo "preflight_success=passed"

expect_preflight_failure low_disk "$manifest" COPPER_UPGRADE_TEST_AVAILABLE_BYTES=0
install -o root -g root -m 0600 /dev/null "$credential_file"
expect_preflight_failure pending_initial_credential "$manifest"
rm -f -- "$credential_file"

original_ids="$(for service in mysql backend web; do compose_cmd ps -q "$service"; done)"
before_failed_prepare_sha="$(sha256sum "$backup_dir/last-success.json" | awk '{print $1}')"
if env COPPER_BACKUP_TEST_FAIL_AT=after-stop "$ROOT_DIR/copperctl" upgrade prepare "$manifest" \
  >"$etc_dir/failed-prepare.out" 2>"$etc_dir/failed-prepare.err"; then
  die "备份故障注入下 upgrade prepare 意外成功"
fi
require_healthy
[[ "$(sha256sum "$backup_dir/last-success.json" | awk '{print $1}')" == "$before_failed_prepare_sha" ]] \
  || die "失败准备改变了 last-success 备份身份"
[[ ! -e "$var_dir/upgrades/prepared.json" ]] || die "失败准备写入了 PREPARED"
echo "prepare_failure_no_ready_state=passed"

# A failed backup status is itself a gate. Repair it with one normal successful backup before retrying preparation.
if "$ROOT_DIR/copperctl" upgrade preflight "$manifest" >/dev/null 2>"$etc_dir/failed-status.err"; then
  die "最近备份失败后预检不应通过"
fi
"$ROOT_DIR/copperctl" backup create >/dev/null
before_success_prepare_sha="$(sha256sum "$backup_dir/last-success.json" | awk '{print $1}')"
before_success_backup_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["backupId"])' "$backup_dir/last-success.json")"
"$ROOT_DIR/copperctl" upgrade prepare "$manifest" > "$etc_dir/prepare.out"
after_success_prepare_sha="$(sha256sum "$backup_dir/last-success.json" | awk '{print $1}')"
after_success_backup_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["backupId"])' "$backup_dir/last-success.json")"
[[ "$after_success_prepare_sha" != "$before_success_prepare_sha" && "$after_success_backup_id" != "$before_success_backup_id" ]] \
  || die "成功准备没有发布新的 last-success 备份身份"
"$ROOT_DIR/copperctl" upgrade status --json > "$etc_dir/status.json"
python3 - "$etc_dir/status.json" "$manifest" "$matrix" <<'PY'
import hashlib, json, pathlib, sys
status_path = pathlib.Path(sys.argv[1])
raw_status = status_path.read_text(encoding="utf-8")
try:
    state = json.loads(raw_status)
except json.JSONDecodeError as exc:
    raise AssertionError(f"upgrade status --json returned non-JSON: {raw_status!r}") from exc
manifest = pathlib.Path(sys.argv[2])
matrix = pathlib.Path(sys.argv[3])
assert state["formatVersion"] == "copper-upgrade-preparation/v1"
assert state["status"] == "PREPARED"
assert state["targetVersion"] == "0.2.0-rc.1"
assert state["manifestSha256"] == hashlib.sha256(manifest.read_bytes()).hexdigest()
assert state["matrixSha256"] == hashlib.sha256(matrix.read_bytes()).hexdigest()
assert state["backup"]["outerVerification"] == "PASSED"
archive = pathlib.Path(state["backup"]["archive"])
assert archive.is_file()
assert state["backup"]["sha256"] == hashlib.sha256(archive.read_bytes()).hexdigest()
PY
[[ "$(stat -c '%U:%G %a' "$var_dir/upgrades/prepared.json")" == "root:root 400" ]] \
  || die "升级准备记录权限不是 root:root 400"
after_prepared_last_success_sha="$(sha256sum "$backup_dir/last-success.json" | awk '{print $1}')"
if "$ROOT_DIR/copperctl" upgrade prepare "$manifest" >/dev/null 2>"$etc_dir/duplicate.err"; then
  die "未消费 PREPARED 时重复 prepare 不应成功"
fi
[[ "$(sha256sum "$backup_dir/last-success.json" | awk '{print $1}')" == "$after_prepared_last_success_sha" ]] \
  || die "重复 prepare 错误改变了 last-success 备份身份"
require_healthy
[[ "$(for service in mysql backend web; do compose_cmd ps -q "$service"; done)" == "$original_ids" ]] \
  || die "M3-02 预检/准备不应替换当前容器"
schema_after="$(printf '%s\n' "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1;" \
  | compose_cmd exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -D copper_v2 --batch --raw --skip-column-names' \
  | tr -d '[:space:]')"
[[ "$schema_after" == "$schema" ]] || die "M3-02 不应改变 schema"

if [[ "${COPPER_M3_03_RUNTIME:-0}" == "1" ]]; then
  export COPPER_RESTORE_TEST_CONFIRM=1
  upgrade_status() {
    python3 - "$var_dir/upgrades/runs/$1/state.json" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["status"])
PY
  }
  restore_status() {
    python3 - "$restore_root/$1/state.json" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["status"])
PY
  }
  current_schema() {
    printf '%s\n' "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1;" \
      | compose_cmd exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -D copper_v2 --batch --raw --skip-column-names' \
      | tr -d '[:space:]'
  }
  assert_source_site() {
    require_healthy
    [[ "$(current_schema)" == "$schema" ]] || die "正式站点未保持来源 schema-$schema"
    [[ "$(awk -F= '$1 == "COPPER_RELEASE_VERSION" {print $2; exit}' "$env_file")" == "0.1.0-m2-rc.2" ]] \
      || die "正式运行版本配置未恢复来源版本"
    [[ "$(printf '%s\n' 'SELECT COUNT(*) FROM partner_account WHERE partner_id = 9303 AND balance = 303.03;' \
      | compose_cmd exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -D copper_v2 -N' | tr -d '[:space:]')" == "1" ]] \
      || die "来源经营数据未保持"
    [[ "$(sha256sum "$var_dir/files/$seed_relative" | awk '{print $1}')" == "$seed_sha" ]] || die "来源附件未保持"
  }
  prepare_again() {
    "$ROOT_DIR/copperctl" upgrade prepare "$manifest" >/dev/null
    [[ -f "$var_dir/upgrades/prepared.json" ]] || die "未生成下一次 PREPARED"
  }
  migrate_with() {
    local upgrade_id="$1" target_backend="$2"
    env COPPER_UPGRADE_TEST_TARGET_WEB_IMAGE="$web_image" \
      COPPER_UPGRADE_TEST_TARGET_BACKEND_IMAGE="$target_backend" \
      COPPER_UPGRADE_TEST_TARGET_MYSQL_IMAGE="$mysql_image" \
      "$ROOT_DIR/copperctl" upgrade migrate --identity "$identity" --upgrade-id "$upgrade_id" --port 18090
  }

  if migrate_with m303-migration-failure "$failed_backend_image" >"$etc_dir/migration-failure.out" 2>"$etc_dir/migration-failure.err"; then
    die "实际 Flyway 失败场景意外成功"
  fi
  [[ "$(upgrade_status m303-migration-failure)" == "MIGRATION_FAILED" ]] || die "失败迁移未进入 MIGRATION_FAILED"
  [[ "$(restore_status m303-migration-failure)" == "MIGRATION_FAILED" ]] || die "失败旁路未保留 MIGRATION_FAILED"
  [[ -s "$restore_root/m303-migration-failure/diagnostics/migration-backend.log" ]] || die "失败迁移未保留 Backend/Flyway 诊断"
  assert_source_site
  "$ROOT_DIR/copperctl" upgrade discard m303-migration-failure >/dev/null
  [[ ! -e "$var_dir/upgrades/prepared.json" && ! -e "$var_dir/upgrades/prepared.claim.json" ]] \
    || die "失败升级归档后未释放下一次准备"
  echo "migration_failure_current_unchanged=passed"

  prepare_again
  migrate_with m303-dirty-sidecar "$target_backend_image" >/dev/null
  [[ "$(upgrade_status m303-dirty-sidecar)" == "MIGRATION_VALIDATED" ]] || die "正常隔离迁移未进入 MIGRATION_VALIDATED"
  docker compose --env-file "$restore_root/m303-dirty-sidecar/copper.env" \
    -f "$ROOT_DIR/compose.yaml" -f "$ROOT_DIR/compose.external-proxy.yaml" \
    -p copper-restore-m303-dirty-sidecar exec -T mysql sh -c \
    'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -D copper_v2 -e "INSERT INTO m3_upgrade_probe (id) VALUES (303);"'
  if "$ROOT_DIR/copperctl" upgrade switch m303-dirty-sidecar >"$etc_dir/dirty.out" 2>"$etc_dir/dirty.err"; then
    die "发生旁路写入后不应允许升级切换"
  fi
  dirty_upgrade_status="$(upgrade_status m303-dirty-sidecar)"
  dirty_restore_status="$(restore_status m303-dirty-sidecar)"
  if [[ "$dirty_upgrade_status" != "DIRTY" || "$dirty_restore_status" != "DIRTY" ]]; then
    sed -n '1,160p' "$etc_dir/dirty.err" >&2
    die "写脏旁路状态不正确：upgrade=$dirty_upgrade_status restore=$dirty_restore_status"
  fi
  assert_source_site
  "$ROOT_DIR/copperctl" upgrade discard m303-dirty-sidecar >/dev/null
  echo "dirty_sidecar_switch_block=passed"

  prepare_again
  migrate_with m303-final-upgrade "$target_backend_image" >/dev/null
  [[ "$(upgrade_status m303-final-upgrade)" == "MIGRATION_VALIDATED" ]] || die "最终旁路未达到 MIGRATION_VALIDATED"
  if env COPPER_RESTORE_TEST_FAIL_AT=after-switch-audit \
    "$ROOT_DIR/copperctl" upgrade switch m303-final-upgrade >"$etc_dir/switch-failure.out" 2>"$etc_dir/switch-failure.err"; then
    die "升级切换故障注入意外成功"
  fi
  [[ "$(upgrade_status m303-final-upgrade)" == "MIGRATION_VALIDATED" ]] || die "切换失败后未回到 MIGRATION_VALIDATED"
  [[ "$(restore_status m303-final-upgrade)" == "MIGRATION_VALIDATED" ]] || die "切换失败后旁路状态不正确"
  assert_source_site
  [[ "$(docker compose --env-file "$restore_root/m303-final-upgrade/copper.env" \
    -f "$ROOT_DIR/compose.yaml" -f "$ROOT_DIR/compose.external-proxy.yaml" \
    -p copper-restore-m303-final-upgrade exec -T mysql sh -c \
    'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -D copper_v2 -Nse "SELECT COUNT(*) FROM system_upgrade_audit WHERE upgrade_id=\"m303-final-upgrade\" AND status=\"MIGRATION_VALIDATED\" AND switched_at IS NULL AND completed_at IS NULL;"' \
    | tr -d '[:space:]')" == "1" ]] || die "切换失败后目标升级审计未恢复到 MIGRATION_VALIDATED"
  echo "switch_failure_source_recovered=passed"

  "$ROOT_DIR/copperctl" upgrade switch m303-final-upgrade >/dev/null
  [[ "$(upgrade_status m303-final-upgrade)" == "SWITCHED" ]] || die "正常升级未进入 SWITCHED"
  require_healthy
  [[ "$(current_schema)" == "$target_schema" ]] || die "切换后 schema 不是目标版本"
  [[ "$(awk -F= '$1 == "COPPER_RELEASE_VERSION" {print $2; exit}' "$env_file")" == "0.2.0-rc.1" ]] \
    || die "切换后运行配置不是目标版本"
  for entry in copperctl copper-install copper-maintain; do
    [[ "$(readlink -f "$control_bin/$entry")" == "$control_releases/0.2.0-rc.1/$entry" ]] \
      || die "切换后维护程序入口未指向目标版本：$entry"
  done
  "$control_bin/copperctl" version >/dev/null
  [[ "$(docker inspect --format '{{.Image}}' "$(compose_cmd ps -q backend)")" == "$target_backend_image" ]] \
    || die "切换后 Backend 不是目标镜像"
  [[ "$(printf '%s\n' 'SELECT COUNT(*) FROM m3_upgrade_probe;' \
    | compose_cmd exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -D copper_v2 -N' | tr -d '[:space:]')" == "0" ]] \
    || die "目标迁移探针表数据异常"
  [[ "$(printf '%s\n' "SELECT COUNT(*) FROM system_upgrade_audit WHERE upgrade_id='m303-final-upgrade' AND status='SWITCHED' AND backup_id REGEXP '^[0-9a-f]{32}$' AND release_manifest_sha256 REGEXP '^[0-9a-f]{64}$';" \
    | compose_cmd exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -D copper_v2 -N' | tr -d '[:space:]')" == "1" ]] \
    || die "升级审计未记录唯一 SWITCHED 证据"
  [[ "$(sha256sum "$var_dir/files/$seed_relative" | awk '{print $1}')" == "$seed_sha" ]] || die "升级后附件不一致"
  echo "upgrade_switch_target_verified=passed"

  "$ROOT_DIR/copperctl" upgrade rollback m303-final-upgrade >/dev/null
  [[ "$(upgrade_status m303-final-upgrade)" == "ROLLED_BACK" ]] || die "升级回滚未进入 ROLLED_BACK"
  assert_source_site
  for entry in copperctl copper-install copper-maintain; do
    [[ "$(readlink -f "$control_bin/$entry")" == "$control_releases/0.1.0-m2-rc.2/$entry" ]] \
      || die "回滚后维护程序入口未恢复来源版本：$entry"
  done
  "$control_bin/copperctl" version >/dev/null
  if printf '%s\n' "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'm3_upgrade_probe';" \
    | compose_cmd exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -D copper_v2 -N' \
    | grep -qx '1'; then
    die "回滚后仍存在目标 schema 表"
  fi
  "$ROOT_DIR/copperctl" upgrade discard m303-final-upgrade >/dev/null
  [[ -z "$(find "$restore_root" -mindepth 1 -maxdepth 1 -print -quit)" ]] || die "M3-03 验收结束仍有旁路数据"
  [[ ! -e "$var_dir/upgrades/prepared.json" && ! -e "$var_dir/upgrades/prepared.claim.json" ]] \
    || die "M3-03 验收结束仍锁定 PREPARED"
  echo "upgrade_rollback_source_verified=passed"
  echo "m3_upgrade_runtime=passed"
fi

mysql_container="$(compose_cmd ps -q mysql)"
mysql_version="$(docker exec "$mysql_container" sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -Nse "SELECT VERSION();"' 2>/dev/null)"
elapsed="$(($(date +%s) - started))"
echo "release_contract=passed"
echo "backup_preparation_binding=passed"
echo "duplicate_prepare_guard=passed"
echo "schema_unchanged=$schema_after"
echo "mysql_version=$mysql_version"
echo "host_8191_unchanged=passed"
echo "shared_vm_mysql_unchanged=passed"
echo "shared_vm_valkey_unchanged=passed"
echo "runtime_seconds=$elapsed"
