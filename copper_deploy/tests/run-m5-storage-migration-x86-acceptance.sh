#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$EUID" -eq 0 ]] || { echo "错误：请在隔离 Ubuntu x86_64 验收机使用 sudo 执行" >&2; exit 1; }
[[ "$(uname -m)" == "x86_64" ]] || { echo "错误：M5-05 工程验收仅允许真实 x86_64 主机" >&2; exit 1; }
[[ "${COPPER_M5_05_ALLOW_EPHEMERAL_MEDIA:-0}" == "1" ]] \
  || { echo "错误：必须显式设置 COPPER_M5_05_ALLOW_EPHEMERAL_MEDIA=1；本脚本会创建并清理临时 loop 和本机 NFS 介质" >&2; exit 1; }

. /etc/os-release
[[ "${ID:-}" == "ubuntu" && "${VERSION_ID:-}" == "24.04" ]] \
  || { echo "错误：仅支持 Ubuntu 24.04 验收机" >&2; exit 1; }

for command_name in docker age zstd losetup mkfs.ext4 mount umount findmnt exportfs mount.nfs openssl curl; do
  command -v "$command_name" >/dev/null || { echo "错误：缺少命令 $command_name" >&2; exit 1; }
done
docker compose version >/dev/null
systemctl is-active --quiet nfs-kernel-server || systemctl is-active --quiet nfs-server \
  || { echo "错误：nfs-kernel-server 未运行；请先由系统管理员安装并启动" >&2; exit 1; }
if ss -lnt | awk '{print $4}' | grep -Eq '(^|:)8191$'; then
  echo "错误：验收机 8191 已被占用；本脚本不得影响正式接口" >&2
  exit 1
fi
[[ "$(df --output=avail -B1 / | tail -1 | tr -d ' ')" -ge 12884901888 ]] \
  || { echo "错误：根盘至少需要 12 GiB 可用空间创建临时介质" >&2; exit 1; }

run_id="$(date -u +%Y%m%d%H%M%S)-$$"
etc_dir="/tmp/copper-m2-03-etc-m505-$run_id"
var_dir="/tmp/copper-m2-03-var-m505-$run_id"
backup_dir="/tmp/copper-m2-03-backup-m505-$run_id"
restore_root="/tmp/copper-m2-03-restores-m505-$run_id"
identity="/tmp/copper-m2-03-identity-m505-$run_id.txt"
fetched_dir="/tmp/copper-m2-03-fetched-m505-$run_id"
env_file="$etc_dir/copper.env"
project="copper-m505-source-$run_id"
source_port=18125
restore_port=18126

external_image="/opt/copper-m5-05-external-$run_id.img"
replacement_image="/opt/copper-m5-05-replacement-$run_id.img"
external_mount="/mnt/copper-m5-05-external-$run_id"
nas_source="/opt/copper-m5-05-nfs-source-$run_id"
nas_mount="/mnt/copper-m5-05-nas-$run_id"
exports_file="/etc/exports.d/copper-m5-05-$run_id.exports"
external_loop=""
replacement_loop=""

web_image="${COPPER_M5_05_WEB_IMAGE:-ghcr.io/soyia/copper_web@sha256:4c7097fe8b75d64e696af8930186d9d9d01b7fe93f20825fffa29481593154c8}"
backend_image="${COPPER_M5_05_BACKEND_IMAGE:-ghcr.io/soyia/copper_backend@sha256:4d2160c37fa095e88dd750f9b1a2315a147a1d6cf0cc84a63d659683a7c3f83e}"
mysql_image="${COPPER_M5_05_MYSQL_IMAGE:-mysql:8.4.10@sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af}"
other_web_image="${COPPER_M5_05_OTHER_WEB_IMAGE:-copper-web:m4-03-test}"

cleanup() {
  local status=$?
  set +e
  if [[ -f "$env_file" ]]; then
    env COPPER_TEST_MODE=1 COPPER_ALLOW_MUTABLE_IMAGES=1 "$ROOT_DIR/copperctl" --env-file "$env_file" down >/dev/null 2>&1
  fi
  while read -r container_id compose_project; do
    [[ "$compose_project" == "$project" || "$compose_project" == copper-restore-m505-* || "$compose_project" == copper-restore-m203-* ]] || continue
    docker rm -f "$container_id" >/dev/null 2>&1
  done < <(docker ps -a --format '{{.ID}} {{.Label "com.docker.compose.project"}}')
  mountpoint -q "$nas_mount" && umount "$nas_mount"
  rm -f -- "$exports_file"
  exportfs -ra >/dev/null 2>&1
  mountpoint -q "$external_mount" && umount "$external_mount"
  [[ -z "$replacement_loop" ]] || losetup -d "$replacement_loop" >/dev/null 2>&1
  [[ -z "$external_loop" ]] || losetup -d "$external_loop" >/dev/null 2>&1
  rm -rf --one-file-system -- "$etc_dir" "$var_dir" "$backup_dir" "$restore_root" "$fetched_dir"
  rm -rf --one-file-system -- "$external_mount" "$nas_mount" "$nas_source"
  rm -f -- "$identity" "$external_image" "$replacement_image"
  if ss -lnt | awk '{print $4}' | grep -Eq '(^|:)8191$'; then
    echo "严重错误：M5-05 验收后出现 8191 监听" >&2
    status=1
  fi
  trap - EXIT
  exit "$status"
}
trap cleanup EXIT

for image in "$web_image" "$backend_image" "$mysql_image" "$other_web_image"; do
  docker image inspect "$image" >/dev/null || { echo "错误：验收镜像未预载：$image" >&2; exit 1; }
done

install -d -o root -g root -m 0750 "$etc_dir" "$var_dir" "$backup_dir" "$restore_root" "$fetched_dir"
install -d -o root -g root -m 0700 "$external_mount" "$nas_source" "$nas_mount"
install -d -o root -g root -m 0755 /etc/exports.d
truncate -s 8G "$external_image"
external_loop="$(losetup --find --show "$external_image")"
mkfs.ext4 -q -F -L COPPER_M505 "$external_loop"
mount -o nodev,nosuid,noexec "$external_loop" "$external_mount"

printf '%s 127.0.0.1(rw,sync,no_subtree_check,no_root_squash)\n' "$nas_source" > "$exports_file"
exportfs -ra
mount -t nfs4 -o vers=4.2,proto=tcp 127.0.0.1:"$nas_source" "$nas_mount"
[[ "$(findmnt -no FSTYPE --target "$external_mount")" == "ext4" ]] || { echo "错误：临时外接目标不是 ext4" >&2; exit 1; }
[[ "$(findmnt -no FSTYPE --target "$nas_mount")" =~ ^nfs ]] || { echo "错误：临时 NAS 目标不是 NFS" >&2; exit 1; }

install -o root -g root -m 0600 "$ROOT_DIR/config/copper.env.example" "$env_file"
age-keygen -o "$identity" >/dev/null 2>&1
chmod 0600 "$identity"
recipient="$(age-keygen -y "$identity")"

python3 - "$env_file" "$project" "$web_image" "$backend_image" "$mysql_image" "$etc_dir" "$var_dir" "$backup_dir" "$restore_root" "$source_port" "$restore_port" "$recipient" "$external_mount" "$nas_mount" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
values = {
    "COPPER_SITE_ID": "copper-m505-test",
    "COPPER_PROJECT_NAME": sys.argv[2],
    "COPPER_DEPLOY_MODE": "external-proxy",
    "COPPER_DOMAIN": "m505.test.internal",
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
    "COPPER_BACKUP_TARGETS": "local,external,nas",
    "COPPER_BACKUP_EXTERNAL_DIR": sys.argv[13],
    "COPPER_BACKUP_NAS_DIR": sys.argv[14],
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
"$ROOT_DIR/scripts/storage-target.py" --env-file "$env_file" enroll external --confirm "$external_mount" >/dev/null
"$ROOT_DIR/scripts/storage-target.py" --env-file "$env_file" enroll nas --confirm "$nas_mount" >/dev/null
"$ROOT_DIR/copperctl" --env-file "$env_file" install >/dev/null

export COPPER_ENV_FILE="$env_file"
export COPPER_BACKUP_TEST_IDENTITY="$identity"
export COPPER_RESTORE_TEST_OTHER_WEB_IMAGE="$other_web_image"
"$ROOT_DIR/tests/m2-restore-runtime.sh"

require_healthy() {
  local service id state health
  for service in mysql backend web; do
    id="$(COPPER_ENV_FILE="$env_file" bash -c 'source "$1/scripts/lib.sh"; compose_cmd ps -q "$2"' _ "$ROOT_DIR" "$service")"
    [[ -n "$id" ]] || { echo "错误：$service 容器不存在" >&2; return 1; }
    state="$(docker inspect --format '{{.State.Status}}' "$id")"
    health="$(docker inspect --format '{{.State.Health.Status}}' "$id")"
    [[ "$state/$health" == "running/healthy" ]] || { echo "错误：$service 未健康：$state/$health" >&2; return 1; }
  done
}

last_success_sha="$(sha256sum "$backup_dir/last-success.json" | awk '{print $1}')"
success_markers_before="$(find "$external_mount" "$nas_mount" -maxdepth 1 -name '*.success.json' -type f | wc -l)"
expect_backup_failure() {
  local label="$1"
  shift
  if env "$@" "$ROOT_DIR/copperctl" backup create >/tmp/"$label".out 2>/tmp/"$label".err; then
    echo "错误：$label 故障场景意外成功" >&2
    return 1
  fi
  require_healthy
  [[ "$(sha256sum "$backup_dir/last-success.json" | awk '{print $1}')" == "$last_success_sha" ]] \
    || { echo "错误：$label 改写了 last-success.json" >&2; return 1; }
  rm -f -- /tmp/"$label".out /tmp/"$label".err
  echo "$label=passed"
}

expect_backup_failure m505-publish-interrupted COPPER_STORAGE_TEST_FAIL_AT=external-after-archive-copy
[[ "$(find "$external_mount" "$nas_mount" -maxdepth 1 -name '*.success.json' -type f | wc -l)" == "$success_markers_before" ]] \
  || { echo "错误：发布中断后出现成功标记" >&2; exit 1; }

umount "$external_mount"
expect_backup_failure m505-external-missing
mount -o nodev,nosuid,noexec "$external_loop" "$external_mount"
"$ROOT_DIR/copperctl" storage status >/dev/null

truncate -s 8G "$replacement_image"
replacement_loop="$(losetup --find --show "$replacement_image")"
mkfs.ext4 -q -F -L COPPER_M505_SWAP "$replacement_loop"
umount "$external_mount"
mount -o nodev,nosuid,noexec "$replacement_loop" "$external_mount"
expect_backup_failure m505-external-swapped
umount "$external_mount"
mount -o nodev,nosuid,noexec "$external_loop" "$external_mount"
"$ROOT_DIR/copperctl" storage status >/dev/null

mount -o remount,ro "$nas_mount"
expect_backup_failure m505-nas-read-only
mount -o remount,rw "$nas_mount"
"$ROOT_DIR/copperctl" storage status >/dev/null

fallocate -l 7G "$external_mount/.m505-capacity-fill"
expect_backup_failure m505-external-full
rm -f -- "$external_mount/.m505-capacity-fill"
"$ROOT_DIR/copperctl" storage status >/dev/null

archive_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["archive"])' "$backup_dir/last-success.json")"
archive_sha="$(awk '{print $1}' "$backup_dir/$archive_name.sha256")"
archive_bytes="$(stat -c '%s' "$backup_dir/$archive_name")"
"$ROOT_DIR/copperctl" storage verify external "$archive_name" "$archive_sha" "$archive_bytes" >/dev/null
"$ROOT_DIR/copperctl" storage verify nas "$archive_name" "$archive_sha" "$archive_bytes" >/dev/null
"$ROOT_DIR/copperctl" storage fetch nas "$archive_name" "$fetched_dir" >/dev/null
cmp "$backup_dir/$archive_name" "$fetched_dir/$archive_name"
cmp "$backup_dir/$archive_name.sha256" "$fetched_dir/$archive_name.sha256"

export COPPER_RESTORE_TEST_CONFIRM=1
"$ROOT_DIR/copperctl" backup restore stage "$fetched_dir/$archive_name" --identity "$identity" --restore-id m505-nas-fetch --port "$restore_port" >/dev/null
"$ROOT_DIR/copperctl" backup restore switch m505-nas-fetch >/dev/null
mysql_id="$(COPPER_ENV_FILE="$env_file" bash -c 'source "$1/scripts/lib.sh"; compose_cmd ps -q mysql' _ "$ROOT_DIR")"
[[ "$(docker exec "$mysql_id" sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" mysql -uroot -Nse "SELECT COUNT(*) FROM copper_v2.inbound_attachment WHERE storage_path=\"inbounds/990003/backup-snapshot.txt\";"')" == "1" ]] \
  || { echo "错误：NAS 取回备份切换后附件数据库引用丢失" >&2; exit 1; }
[[ -f "$var_dir/files/inbounds/990003/backup-snapshot.txt" ]] || { echo "错误：NAS 取回备份切换后附件原件丢失" >&2; exit 1; }
"$ROOT_DIR/copperctl" backup restore rollback m505-nas-fetch >/dev/null
"$ROOT_DIR/copperctl" backup restore discard m505-nas-fetch >/dev/null
require_healthy

status_json="$fetched_dir/storage-status.json"
"$ROOT_DIR/copperctl" storage status > "$status_json"
python3 - "$status_json" <<'PY'
import json
import sys

document = json.load(open(sys.argv[1], encoding="utf-8"))
targets = {item["target"]: item for item in document["targets"]}
assert targets["local"]["failureDomain"] == "SAME_AS_PRIMARY_DATA"
assert targets["local"]["failureDomainRisk"] == "WARNING"
for name in ("external", "nas"):
    assert targets[name]["available"] is True
    assert targets[name]["identityBound"] is True
    assert targets[name]["failureDomain"] == "SEPARATE_MOUNT"
    assert targets[name]["failureDomainRisk"] == "NORMAL"
print("storage_failure_domains=passed")
PY

echo "engineering_external_media=loop-ext4"
echo "engineering_nas=nfs4-localhost"
echo "physical_external_and_remote_nas_required_for_issue_close=true"
echo "archive_sha256=$archive_sha"
echo "archive_bytes=$archive_bytes"
echo "m5_05_engineering_acceptance=passed"
