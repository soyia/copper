#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
phase="${1:-}"
env_file="${2:-}"
manifest="${3:-}"
output="${4:-}"

[[ "$phase" == "source" || "$phase" == "target" || "$phase" == "replacement" ]] || {
  echo "用法：sudo tests/run-m3-final-x86-acceptance.sh source|target|replacement ENV_FILE TARGET_MANIFEST OUTPUT_DIR" >&2
  exit 2
}
[[ -f "$env_file" && -f "$manifest" && -n "$output" ]] || {
  echo "缺少正式环境文件、目标发布清单或证据输出目录" >&2
  exit 2
}
[[ "${EUID:-$(id -u)}" == "0" ]] || { echo "M3 最终验收必须由 root 执行" >&2; exit 1; }
[[ "$(uname -m)" == "x86_64" ]] || { echo "最终验收主机必须是 x86_64" >&2; exit 1; }
. /etc/os-release
[[ "$ID/$VERSION_ID" == "ubuntu/24.04" ]] || { echo "最终验收主机必须是 Ubuntu 24.04" >&2; exit 1; }
if ss -lnt | awk '{print $4}' | grep -Eq '(^|:|\])8191$'; then
  echo "最终验收禁止占用宿主机 8191" >&2
  exit 1
fi
[[ ! -e "$output" && ! -L "$output" ]] || { echo "证据目录已存在，拒绝覆盖：$output" >&2; exit 1; }

export COPPER_ENV_FILE="$env_file"
source "$ROOT_DIR/scripts/lib.sh"
source "$ROOT_DIR/scripts/verification-sql.sh"
validate_tools
validate_environment
require_supported_platform
"$ROOT_DIR/scripts/release-contract.py" validate-manifest "$manifest" >/dev/null

install -d -o root -g root -m 0700 "$output"
temporary="$(mktemp -d /tmp/copper-m3-05-evidence-XXXXXX)"
trap 'rm -rf --one-file-system -- "$temporary"' EXIT

mysql_query() {
  printf '%s\n' "$1" | compose_cmd exec -T mysql sh -c \
    'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot -D copper_v2 --batch --raw --skip-column-names'
}

for service in mysql backend web; do
  container="$(compose_cmd ps -q "$service")"
  [[ -n "$container" ]] || die "$service 容器不存在"
  state="$(docker inspect --format '{{.State.Status}}/{{.State.Health.Status}}' "$container")"
  [[ "$state" == "running/healthy" ]] || die "$service 未健康：$state"
done

current_version="$(env_value COPPER_RELEASE_VERSION)"
schema="$(mysql_query "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;" | tr -d '[:space:]')"
case "$phase" in
  source)
    [[ "$current_version/$schema" == "0.1.0-m2-rc.2/3" ]] || die "来源站必须是 0.1.0-m2-rc.2/schema-3"
    [[ "$(mysql_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='system_upgrade_audit';" | tr -d '[:space:]')" == "0" ]] \
      || die "来源站不应提前存在 schema-4 升级审计表"
    ;;
  target|replacement)
    [[ "$current_version/$schema" == "0.2.0-rc.1/4" ]] || die "目标站必须是 0.2.0-rc.1/schema-4"
    [[ "$(env_value COPPER_IMAGE_DELIVERY)" == "offline" ]] || die "中国区最终验收必须使用正式离线 READY"
    release_root="$(required_env_value COPPER_VAR_DIR)/releases"
    "$ROOT_DIR/scripts/delivery-contract.py" assert-manifest --manifest "$manifest" --release-root "$release_root" >/dev/null
    ready="$(dirname "$manifest")/ready.json"
    python3 - "$env_file" "$manifest" "$ready" <<'PY'
import json, pathlib, sys
def env(path):
    values = {}
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values
runtime, manifest = env(sys.argv[1]), env(sys.argv[2])
ready = json.loads(pathlib.Path(sys.argv[3]).read_text(encoding="utf-8"))
if ready.get("mode") != "offline" or ready.get("status") != "READY":
    raise SystemExit("目标 READY 不是正式离线可用状态")
keys = {"web": "COPPER_WEB_IMAGE", "backend": "COPPER_BACKEND_IMAGE", "mysql": "COPPER_MYSQL_IMAGE"}
for image in ready.get("images", []):
    key = keys.get(image.get("role"))
    if not key or image.get("reference") != manifest.get(key) or runtime.get(key) != image.get("localId"):
        raise SystemExit(f"目标离线运行镜像未同时绑定发布摘要和 READY 内容 ID：{image.get('role')}")
if {item.get("role") for item in ready.get("images", [])} != set(keys):
    raise SystemExit("目标 READY 镜像角色不完整")
PY
    [[ "$(mysql_query "SELECT COUNT(*) FROM system_upgrade_audit WHERE target_version='0.2.0-rc.1' AND target_schema='4' AND status='SWITCHED' AND backup_id REGEXP '^[0-9a-f]{32}$' AND release_manifest_sha256 REGEXP '^[0-9a-f]{64}$';" | tr -d '[:space:]')" == "1" ]] \
      || die "目标站缺少唯一可追溯的 SWITCHED 升级审计"
    ;;
esac

{
  printf 'phase=%s\n' "$phase"
  printf 'captured_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'host_arch=%s\n' "$(uname -m)"
  printf 'os=%s/%s\n' "$ID" "$VERSION_ID"
  printf 'docker=%s\n' "$(docker version --format '{{.Server.Version}}')"
  printf 'compose=%s\n' "$(docker compose version --short)"
  printf 'product_version=%s\n' "$current_version"
  printf 'schema=%s\n' "$schema"
  printf 'host_8191=unused\n'
} > "$output/summary.env"

compose_cmd ps -a > "$output/compose-ps.txt"
for key in COPPER_WEB_IMAGE COPPER_BACKEND_IMAGE COPPER_MYSQL_IMAGE; do
  printf '%s=%s\n' "$key" "$(env_value "$key")"
done > "$output/images.env"
if [[ "$phase" != "source" ]]; then
  python3 - "$(dirname "$manifest")/ready.json" >> "$output/images.env" <<'PY'
import json, pathlib, sys
for image in json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["images"]:
    print(f"PUBLISHED_{image['role'].upper()}_IMAGE={image['reference']}")
PY
fi
mysql_query "SELECT installed_rank, COALESCE(version,''), description, type, script, COALESCE(CAST(checksum AS CHAR),''), success FROM flyway_schema_history ORDER BY installed_rank;" \
  > "$output/flyway-history.tsv"
write_business_totals_sql "$temporary/business-totals.sql"
compose_cmd exec -T mysql sh -c \
  'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot -D copper_v2 --batch --raw --skip-column-names' \
  < "$temporary/business-totals.sql" > "$output/business-totals.tsv"
mysql_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE';" \
  > "$output/table-count.txt"
if [[ "$phase" != "source" ]]; then
  mysql_query "SELECT upgrade_id,source_version,target_version,source_schema,target_schema,status,backup_id,release_manifest_sha256,started_at,migration_validated_at,switched_at,completed_at FROM system_upgrade_audit ORDER BY started_at,upgrade_id;" \
    > "$output/system-upgrade-audit.tsv"
fi
(cd "$(required_env_value COPPER_VAR_DIR)/files" \
  && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum) \
  > "$output/attachments.sha256"
find "$(required_env_value COPPER_VAR_DIR)/upgrades" -type f -maxdepth 4 -print 2>/dev/null \
  | LC_ALL=C sort > "$output/upgrade-evidence-files.txt" || true

find "$output" -type f ! -name SHA256SUMS -printf '%P\0' \
  | LC_ALL=C sort -z \
  | xargs -0 -r -I{} sha256sum "$output/{}" \
  | sed "s#  $output/#  #" > "$output/SHA256SUMS"
chmod 0600 "$output"/*
trap - EXIT
rm -rf --one-file-system -- "$temporary"
echo "m3_final_${phase}_evidence=passed"
echo "evidence_dir=$output"
