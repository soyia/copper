#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

candidate=0
if [[ "${1:-}" == "--candidate" ]]; then
  candidate=1
  shift
fi
version="${1:-}"
output_dir="${2:-$ROOT_DIR/dist}"
[[ "$version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,127}$ ]] || {
  echo "用法：scripts/build-release.sh [--candidate] VERSION [OUTPUT_DIR]" >&2
  exit 2
}

for command_name in git tar gzip awk node mktemp; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "缺少命令 $command_name" >&2
    exit 1
  }
done

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    echo "缺少 sha256sum 或 shasum" >&2
    return 1
  fi
}

tag="v$version"
components="releases/$version/components.env"
[[ -f "$components" ]] || { echo "缺少组件清单 $components" >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "工作区不干净，拒绝生成正式发布包" >&2; exit 1; }

# A formal release remains blocked by findings in either the current tree or any
# reachable history.  An INTERNAL_REHEARSAL_ONLY candidate archives HEAD only, so
# it scans that exact current tree here; build-secure-release.sh separately scans
# the generated candidate bytes.  Historical findings remain FORMAL_RELEASE_BLOCKED
# and cannot be bypassed by omitting --candidate.
audit_dir="$(mktemp -d "${TMPDIR:-/tmp}/copper-m6-confidentiality.XXXXXX")"
trap 'rm -rf "$audit_dir"' EXIT
audit_scope="all"
if [[ "$candidate" == "1" ]]; then
  audit_scope="current"
fi
node "$ROOT_DIR/scripts/security/audit-repositories.mjs" \
  --repo copper_deploy="$ROOT_DIR" \
  --config "$ROOT_DIR/config/m6-confidentiality-audit.json" \
  --gate strict \
  --scope "$audit_scope" \
  --report "$audit_dir/copper-deploy-confidentiality-audit.json"

release_ref="$tag"
if [[ "$candidate" == "1" ]]; then
  [[ "$(git rev-parse HEAD)" == "$(git rev-parse refs/remotes/origin/main)" ]] || {
    echo "候选演练包只能从已同步的 GitHub origin/main 精确提交生成" >&2
    exit 1
  }
  [[ -z "$(git tag -l "$tag")" ]] || {
    echo "正式标签 $tag 已存在，禁止再生成候选演练包" >&2
    exit 1
  }
  release_ref="HEAD"
else
  [[ "$(git describe --exact-match --tags HEAD 2>/dev/null || true)" == "$tag" ]] || {
    echo "HEAD 必须带有精确标签 $tag" >&2
    exit 1
  }
fi

component_version="$(awk -F= '$1 == "COPPER_RELEASE_VERSION" {print $2}' "$components")"
[[ "$component_version" == "$version" ]] || { echo "组件清单版本与请求版本不一致" >&2; exit 1; }

mkdir -p "$output_dir"
archive_name="copper-deploy-$version.tar.gz"
archive="$output_dir/$archive_name"
manifest="$output_dir/copper-release-$version.env"
acme_output=""
matrix_output=""
notes_output=""
future_contract=0
if [[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.[1-9][0-9]*)?$ ]]; then
  future_contract=1
fi

git archive --format=tar --prefix="copper-deploy-$version/" "$release_ref" | gzip -n > "$archive"
archive_hash="$(sha256_file "$archive")"
deploy_commit="$(git rev-parse "$release_ref^{commit}")"

acme_name="$(awk -F= '$1 == "COPPER_ACME_ARCHIVE" {print $2}' "$components")"
if [[ -n "$acme_name" ]]; then
  acme_expected_hash="$(awk -F= '$1 == "COPPER_ACME_ARCHIVE_SHA256" {print $2}' "$components")"
  acme_source="${COPPER_ACME_ARCHIVE_SOURCE:-}"
  [[ -f "$acme_source" ]] || { echo "缺少 COPPER_ACME_ARCHIVE_SOURCE 指向的 $acme_name" >&2; exit 1; }
  [[ "$(sha256_file "$acme_source")" == "$acme_expected_hash" ]] || { echo "acme.sh 归档 SHA-256 与组件清单不一致" >&2; exit 1; }
  acme_output="$output_dir/$acme_name"
  cp "$acme_source" "$acme_output"
fi

if [[ "$future_contract" == "1" ]]; then
  matrix_source="releases/$version/upgrade-matrix.json"
  notes_source="releases/$version/RELEASE_NOTES.md"
  migrations_source="releases/$version/migrations.sha256"
  [[ -f "$matrix_source" && -f "$notes_source" && -f "$migrations_source" ]] \
    || { echo "M3 发布缺少升级矩阵、发布说明或迁移集合清单" >&2; exit 1; }
  migrations_expected_hash="$(awk -F= '$1 == "COPPER_MIGRATION_SET_SHA256" {print $2}' "$components")"
  [[ "$(sha256_file "$migrations_source")" == "$migrations_expected_hash" ]] \
    || { echo "迁移集合清单实际 SHA-256 与组件清单不一致" >&2; exit 1; }
  matrix_output="$output_dir/copper-upgrade-matrix-$version.json"
  notes_output="$output_dir/copper-release-notes-$version.md"
  cp "$matrix_source" "$matrix_output"
  cp "$notes_source" "$notes_output"
  for role in WEB BACKEND; do
    provenance_name="$(awk -F= -v key="COPPER_${role}_BUILD_PROVENANCE_FILE" '$1 == key {print $2}' "$components")"
    if [[ -n "$provenance_name" ]]; then
      [[ "$provenance_name" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]{0,191}$ ]] || { echo "本地构建证据文件名不安全" >&2; exit 1; }
      cp "releases/$version/$provenance_name" "$output_dir/$provenance_name"
    fi
  done
  notes_expected_hash="$(awk -F= '$1 == "COPPER_RELEASE_NOTES_SHA256" {print $2}' "$components")"
  [[ "$(sha256_file "$notes_output")" == "$notes_expected_hash" ]] \
    || { echo "发布说明实际 SHA-256 与组件清单不一致" >&2; exit 1; }
  "$ROOT_DIR/scripts/release-contract.py" generate-manifest \
    --values "$components" --matrix "$matrix_output" \
    --set "COPPER_DEPLOY_TAG=$tag" --set "COPPER_DEPLOY_COMMIT=$deploy_commit" \
    --set "COPPER_DEPLOY_ARCHIVE=$archive_name" --set "COPPER_DEPLOY_ARCHIVE_SHA256=$archive_hash" \
    --set "COPPER_RELEASE_NOTES_FILE=$(basename "$notes_output")" \
    --output "$manifest" >/dev/null
  "$ROOT_DIR/scripts/release-contract.py" validate-manifest "$manifest" >/dev/null
else
  {
    printf 'COPPER_DEPLOY_TAG=%s\n' "$tag"
    printf 'COPPER_DEPLOY_COMMIT=%s\n' "$deploy_commit"
    printf 'COPPER_DEPLOY_ARCHIVE=%s\n' "$archive_name"
    printf 'COPPER_DEPLOY_ARCHIVE_SHA256=%s\n' "$archive_hash"
    cat "$components"
  } > "$manifest"
fi

manifest_name="$(basename "$manifest")"
manifest_hash="$(sha256_file "$manifest")"
{
  printf '%s  %s\n' "$archive_hash" "$archive_name"
  printf '%s  %s\n' "$manifest_hash" "$manifest_name"
  if [[ -n "$matrix_output" ]]; then
    printf '%s  %s\n' "$(sha256_file "$matrix_output")" "$(basename "$matrix_output")"
    printf '%s  %s\n' "$(sha256_file "$notes_output")" "$(basename "$notes_output")"
  fi
  if [[ -n "$acme_output" ]]; then
    printf '%s  %s\n' "$(sha256_file "$acme_output")" "$(basename "$acme_output")"
  fi
  for role in WEB BACKEND; do
    provenance_name="$(awk -F= -v key="COPPER_${role}_BUILD_PROVENANCE_FILE" '$1 == key {print $2}' "$components")"
    [[ -z "$provenance_name" ]] || printf '%s  %s\n' "$(sha256_file "$output_dir/$provenance_name")" "$provenance_name"
  done
} > "$output_dir/SHA256SUMS"

if [[ "$candidate" == "1" ]]; then
  cat > "$output_dir/CANDIDATE-NOT-A-RELEASE.json" <<EOF
{"format":"copper-release-candidate/v1","status":"NOT_RELEASED","version":"$version","expectedTag":"$tag","deployCommit":"$deploy_commit","deployArchiveSha256":"$archive_hash","manifestSha256":"$manifest_hash","confidentialityAuditScope":"current-tree","formalHistoryGate":"FORMAL_RELEASE_BLOCKED"}
EOF
  chmod 0444 "$output_dir/CANDIDATE-NOT-A-RELEASE.json"
fi

echo "发布包：$archive"
echo "发布清单：$manifest"
[[ -z "$matrix_output" ]] || echo "升级矩阵：$matrix_output"
[[ -z "$notes_output" ]] || echo "发布说明：$notes_output"
echo "校验文件：$output_dir/SHA256SUMS"
[[ -z "$acme_output" ]] || echo "IP 证书客户端：$acme_output"
[[ "$candidate" == "0" ]] || echo "注意：这是未发布的字节冻结验收候选；只有全部验收通过且同一提交创建 $tag 后才能对外交付"
