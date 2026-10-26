#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

policy="config/release-policy.env"
manifest_fields="config/release-manifest-v1.fields"
decision="docs/M3_VERSIONING_AND_COMPATIBILITY.md"
[[ -s "$policy" && -s "$manifest_fields" && -s "$decision" ]]

duplicate_keys="$(awk -F= '/^[A-Z0-9_]+=/{count[$1]++} END{for (key in count) if (count[key] != 1) print key}' "$policy")"
[[ -z "$duplicate_keys" ]] || {
  printf '发布策略存在重复字段：%s\n' "$duplicate_keys" >&2
  exit 1
}

# shellcheck disable=SC1090
source "$policy"

[[ "$COPPER_VERSION_POLICY" == "copper-semver-v1" ]]
[[ "$COPPER_RELEASE_MANIFEST_FORMAT" == "copper-release/v1" ]]
[[ "$COPPER_UPGRADE_MATRIX_FORMAT" == "copper-upgrade-matrix/v1" ]]
[[ "$COPPER_DELIVERY_FORMAT" == "copper-delivery/v1" ]]
[[ "$COPPER_DELIVERY_READY_FORMAT" == "copper-delivery-ready/v1" ]]
[[ "$COPPER_NEXT_CANDIDATE_VERSION" == "0.4.0-rc.11" ]]
python3 - "$COPPER_NEXT_CANDIDATE_VERSION" <<'PY'
import json, pathlib, sys
version = sys.argv[1]
plan = json.loads(pathlib.Path("releases", version, "CLOSURE_PLAN.json").read_text())
assert plan["version"] == version
assert plan["scope"] == "INTERNAL_REHEARSAL_ONLY"
assert plan["rc7FreezeUnchanged"] is True
assert pathlib.Path("releases", version, "RELEASE_NOTES.md").is_file()
PY
[[ "$COPPER_FIRST_GA_VERSION" == "1.0.0" ]]
[[ "$COPPER_RELEASE_CHANNELS" == "rc,stable" ]]
[[ "$COPPER_PLANNED_STABLE_MIN_DAYS" == "28" ]]
[[ "$COPPER_DIRECT_STABLE_UPGRADE_DEPTH" == "1" ]]
[[ "$COPPER_DATABASE_MIGRATION_POLICY" == "flyway-append-only" ]]
[[ "$COPPER_BACKUP_FORMAT" == "copper-backup/v1" ]]
[[ "$COPPER_EXPORT_FORMAT" == "copper-export/v1" ]]
[[ "$COPPER_SCHEMA_CHANGE_ROLLBACK" == "verified-backup-or-retained-sidecar" ]]

[[ -z "$(sort "$manifest_fields" | uniq -d)" ]] || {
  echo "发布清单必填字段存在重复" >&2
  exit 1
}
required_manifest_fields=(
  COPPER_RELEASE_MANIFEST_FORMAT COPPER_RELEASE_VERSION COPPER_RELEASE_CHANNEL COPPER_RELEASED_AT
  COPPER_DEPLOY_TAG COPPER_DEPLOY_COMMIT
  COPPER_WEB_IMAGE COPPER_WEB_SOURCE_COMMIT COPPER_WEB_PLATFORM
  COPPER_BACKEND_IMAGE COPPER_BACKEND_SOURCE_COMMIT COPPER_BACKEND_PLATFORM
  COPPER_MYSQL_IMAGE COPPER_MYSQL_VERSION COPPER_MYSQL_PLATFORM
  COPPER_SUPPORTED_OS COPPER_SUPPORTED_ARCH COPPER_MIN_CPU COPPER_MIN_MEMORY_GIB COPPER_MIN_FREE_DISK_GIB
  COPPER_DATABASE_SCHEMA_VERSION COPPER_MIGRATION_SET_SHA256 COPPER_BACKUP_FORMAT COPPER_EXPORT_FORMAT
  COPPER_UPGRADE_MATRIX_FILE COPPER_UPGRADE_MATRIX_SHA256 COPPER_VALIDATION_LEVEL COPPER_RELEASE_NOTES_SHA256
  COPPER_DEPLOY_ARCHIVE COPPER_DEPLOY_ARCHIVE_SHA256 COPPER_RELEASE_NOTES_FILE
  COPPER_ACME_ARCHIVE COPPER_ACME_ARCHIVE_SHA256
)
for field in "${required_manifest_fields[@]}"; do
  grep -qx "$field" "$manifest_fields" || { echo "发布清单缺少必填字段：$field" >&2; exit 1; }
done

valid_future_version() {
  [[ "$1" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.[1-9][0-9]*)?$ ]]
}

for version in 0.2.0-rc.1 0.2.0-rc.12 0.2.0 1.0.0 12.34.56; do
  valid_future_version "$version" || { echo "错误拒绝合法版本：$version" >&2; exit 1; }
done

for version in 0.2 01.2.0 0.02.0 0.2.00 0.2.0-rc.0 0.2.0-m3-rc.1 0.2.0-SNAPSHOT latest v0.2.0; do
  if valid_future_version "$version"; then
    echo "错误接受非法版本：$version" >&2
    exit 1
  fi
done

[[ "$(awk -F= '$1 == "COPPER_RELEASE_VERSION" {print $2}' releases/0.1.0-m1-rc.1/components.env)" == "0.1.0-m1-rc.1" ]]
[[ "$(awk -F= '$1 == "COPPER_RELEASE_VERSION" {print $2}' releases/0.1.0-m2-rc.2/components.env)" == "0.1.0-m2-rc.2" ]]

candidate_components="releases/0.2.0-rc.1/components.env"
candidate_matrix="releases/0.2.0-rc.1/upgrade-matrix.json"
candidate_notes="releases/0.2.0-rc.1/RELEASE_NOTES.md"
candidate_migrations="releases/0.2.0-rc.1/migrations.sha256"
[[ "$(awk -F= '$1 == "COPPER_RELEASE_VERSION" {print $2}' "$candidate_components")" == "0.2.0-rc.1" ]]
[[ "$(awk -F= '$1 == "COPPER_DATABASE_SCHEMA_VERSION" {print $2}' "$candidate_components")" == "4" ]]
[[ "$(sha256sum "$candidate_notes" | awk '{print $1}')" \
  == "$(awk -F= '$1 == "COPPER_RELEASE_NOTES_SHA256" {print $2}' "$candidate_components")" ]]
[[ "$(sha256sum "$candidate_migrations" | awk '{print $1}')" \
  == "$(awk -F= '$1 == "COPPER_MIGRATION_SET_SHA256" {print $2}' "$candidate_components")" ]]
./scripts/release-contract.py validate-matrix "$candidate_matrix" >/dev/null

grep -q '产品版本是组件组合版本' "$decision"
grep -q '未列出即不支持直接升级' "$decision"
grep -q 'copper-upgrade-matrix/v1' "$decision"
grep -q '禁止为追求“自动回滚”编写未经业务验证的逆向 SQL' "$decision"
grep -q '`1.0.0` 只在 M7 正式发布门槛通过后创建' "$decision"
grep -q 'Gitee 不参与当前权威发布链' "$decision"

echo "m3_version_policy=passed"
