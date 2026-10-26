#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

manifest="${1:-}"
web_repo="${2:-}"
backend_repo="${3:-}"
deploy_repo="${4:-}"
output_dir="${5:-}"
exceptions="${6:-}"
[[ -n "$manifest" && -n "$web_repo" && -n "$backend_repo" && -n "$deploy_repo" && -n "$output_dir" ]] || {
  echo "用法：scripts/security-scan.sh RELEASE_MANIFEST WEB_REPO BACKEND_REPO DEPLOY_REPO OUTPUT_DIR [EXCEPTIONS_JSON]" >&2
  exit 2
}

for command_name in docker git tar python3 sha256sum mktemp; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "错误：缺少命令 $command_name" >&2; exit 1; }
done
[[ -f "$manifest" && ! -L "$manifest" ]] || { echo "错误：发布清单必须是普通文件且不能是符号链接" >&2; exit 1; }
manifest="$(cd "$(dirname "$manifest")" && pwd)/$(basename "$manifest")"
[[ ! -e "$output_dir" && ! -L "$output_dir" ]] || { echo "错误：输出路径已存在，拒绝覆盖：$output_dir" >&2; exit 1; }
if [[ -n "$exceptions" ]]; then
  [[ -f "$exceptions" && ! -L "$exceptions" ]] || { echo "错误：漏洞例外必须是普通文件且不能是符号链接" >&2; exit 1; }
  exceptions="$(cd "$(dirname "$exceptions")" && pwd)/$(basename "$exceptions")"
fi

policy="$ROOT_DIR/config/security-policy.env"
[[ -f "$policy" && ! -L "$policy" ]] || { echo "错误：缺少安全策略 $policy" >&2; exit 1; }
gitleaks_ignore="$ROOT_DIR/config/gitleaksignore"
[[ -f "$gitleaks_ignore" && ! -L "$gitleaks_ignore" ]] || { echo "错误：缺少精确 Gitleaks 忽略清单 $gitleaks_ignore" >&2; exit 1; }
# shellcheck disable=SC1090
source "$policy"

manifest_value() {
  awk -F= -v wanted="$1" '$1 == wanted {print substr($0, index($0, "=") + 1); exit}' "$manifest"
}

version="$(manifest_value COPPER_RELEASE_VERSION)"
channel="$(manifest_value COPPER_RELEASE_CHANNEL)"
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.[1-9][0-9]*)?$ ]] \
  || { echo "错误：发布版本不受支持" >&2; exit 1; }
[[ "$channel" == "rc" || "$channel" == "stable" ]] || { echo "错误：发布渠道不受支持" >&2; exit 1; }

assert_source() {
  local label="$1" directory="$2" expected_commit="$3"
  [[ -d "$directory/.git" && ! -L "$directory/.git" ]] || {
    echo "错误：$label 必须是独立 Git checkout；不接受缺少完整 .git 目录的工作树" >&2
    exit 1
  }
  [[ -z "$(docker run --rm -v "$directory:/source:ro" --entrypoint git "$COPPER_GITLEAKS_IMAGE" \
    -c safe.directory=/source -C /source status --porcelain)" ]] || { echo "错误：$label 工作区不干净" >&2; exit 1; }
  [[ "$(docker run --rm -v "$directory:/source:ro" --entrypoint git "$COPPER_GITLEAKS_IMAGE" \
    -c safe.directory=/source -C /source rev-parse HEAD)" == "$expected_commit" ]] || {
    echo "错误：$label HEAD 未绑定发布清单提交" >&2
    exit 1
  }
}

tool_images=("$COPPER_SYFT_IMAGE" "$COPPER_GRYPE_IMAGE" "$COPPER_GITLEAKS_IMAGE")
published_images=(
  "$(manifest_value COPPER_WEB_IMAGE)"
  "$(manifest_value COPPER_BACKEND_IMAGE)"
  "$(manifest_value COPPER_MYSQL_IMAGE)"
)
runtime_images=("${published_images[@]}")
image_sources=(registry registry registry)
image_source_evidence=(- - -)
for image in "${tool_images[@]}"; do
  [[ "$image" =~ @sha256:[0-9a-f]{64}$ ]] || { echo "错误：扫描工具必须使用不可变镜像摘要" >&2; exit 1; }
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    [[ "${COPPER_SECURITY_ALLOW_PULL:-0}" == "1" ]] || {
      echo "错误：缺少扫描工具镜像 $image；联网发布工厂需显式设置 COPPER_SECURITY_ALLOW_PULL=1" >&2
      exit 1
    }
    docker pull "$image" >/dev/null
  fi
done
assert_source Web "$web_repo" "$(manifest_value COPPER_WEB_SOURCE_COMMIT)"
assert_source Backend "$backend_repo" "$(manifest_value COPPER_BACKEND_SOURCE_COMMIT)"
assert_source Deploy "$deploy_repo" "$(manifest_value COPPER_DEPLOY_COMMIT)"
configure_preloaded_image() {
  local index="$1" role="$2" role_upper="$3"
  local image_var="COPPER_SECURITY_PRELOADED_${role_upper}_IMAGE"
  local archive_var="COPPER_SECURITY_PRELOADED_${role_upper}_ARCHIVE"
  local evidence_var="COPPER_SECURITY_PRELOADED_${role_upper}_EVIDENCE"
  local source_var="COPPER_SECURITY_PRELOADED_${role_upper}_SOURCE"
  local preloaded_image="${!image_var:-}"
  local preloaded_archive="${!archive_var:-}"
  local preloaded_evidence="${!evidence_var:-}"
  local preloaded_source="${!source_var:-ci-preloaded}"
  local expected_build_image_id
  if [[ -z "$preloaded_image$preloaded_archive$preloaded_evidence" ]]; then
    return
  fi
  [[ "$preloaded_source" == "ci-preloaded" || "$preloaded_source" == "verified-preloaded" ]] \
    || { echo "错误：$role 预加载来源必须是 ci-preloaded 或 verified-preloaded" >&2; exit 1; }
  [[ -n "$preloaded_image" && -f "$preloaded_archive" && ! -L "$preloaded_archive" \
     && -f "$preloaded_evidence" && ! -L "$preloaded_evidence" ]] \
    || { echo "错误：$role 预加载扫描必须同时提供 IMAGE、ARCHIVE、EVIDENCE" >&2; exit 1; }
  expected_build_image_id="$(python3 - "$preloaded_evidence" "$preloaded_archive" "$manifest" "$role_upper" "$ROOT_DIR/scripts" <<'PY'
import hashlib, json, pathlib, sys
sys.path.insert(0, sys.argv[5])
from build_provenance import validate_role
evidence_path, archive_path, manifest_path = map(pathlib.Path, sys.argv[1:4])
role = sys.argv[4]
evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
values = {}
for line in manifest_path.read_text(encoding="utf-8").splitlines():
    if line and not line.startswith("#") and "=" in line:
        key, value = line.split("=", 1)
        values[key] = value
expected = {
    "sourceCommit": values.get(f"COPPER_{role}_SOURCE_COMMIT"),
    "image": values.get(f"COPPER_{role}_IMAGE"),
    "platform": values.get(f"COPPER_{role}_PLATFORM"),
}
if role in {"WEB", "BACKEND"}:
    expected.update(validate_role(manifest_path, values, role))
else:
    expected["workflowRun"] = values.get(f"COPPER_{role}_WORKFLOW_RUN")
if set(evidence) != {*expected, "archiveSha256"}:
    raise SystemExit(f"{role} 预加载证据字段不正确")
for key, value in expected.items():
    if str(evidence.get(key)) != str(value):
        raise SystemExit(f"{role} 预加载证据未绑定发布清单：{key}")
digest = hashlib.sha256()
with archive_path.open("rb") as reader:
    for chunk in iter(lambda: reader.read(1024 * 1024), b""):
        digest.update(chunk)
if digest.hexdigest() != evidence["archiveSha256"]:
    raise SystemExit(f"{role} 预加载归档 SHA-256 与证据不一致")
print(expected.get("buildImageId", ""))
PY
  )"
  docker load --input "$preloaded_archive" >/dev/null
  docker image inspect "$preloaded_image" >/dev/null 2>&1 \
    || { echo "错误：$role 预加载归档不包含指定运行镜像" >&2; exit 1; }
  [[ "$(docker image inspect --format '{{.Architecture}}' "$preloaded_image")" == "amd64" ]] \
    || { echo "错误：$role 预加载镜像不是 amd64" >&2; exit 1; }
  if [[ -n "$expected_build_image_id" ]]; then
    [[ "$(docker image inspect --format '{{.Id}}' "$preloaded_image")" == "$expected_build_image_id" ]] \
      || { echo "错误：$role 本地构建 imageId 与实际导入镜像不一致" >&2; exit 1; }
  fi
  if [[ "$role" != "mysql" ]]; then
    [[ "$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$preloaded_image")" \
        == "$(manifest_value "COPPER_${role_upper}_SOURCE_COMMIT")" ]] \
      || { echo "错误：$role 预加载镜像源码标签未绑定发布清单" >&2; exit 1; }
  fi
  runtime_images[$index]="$preloaded_image"
  image_sources[$index]="$preloaded_source"
  image_source_evidence[$index]="$(sha256sum "$preloaded_evidence" | awk '{print $1}')"
}

configure_preloaded_image 0 web WEB
configure_preloaded_image 1 backend BACKEND
configure_preloaded_image 2 mysql MYSQL

for index in 0 1 2; do
  image="${published_images[$index]}"
  if [[ "${image_sources[$index]}" != "registry" ]]; then
    continue
  fi
  [[ "$image" =~ @sha256:[0-9a-f]{64}$ ]] || { echo "错误：发布镜像必须使用不可变摘要" >&2; exit 1; }
  image_architecture="$(docker image inspect --format '{{.Architecture}}' "$image" 2>/dev/null || true)"
  if [[ "$image_architecture" != "amd64" ]]; then
    [[ "${COPPER_SECURITY_ALLOW_PULL:-0}" == "1" ]] || {
      echo "错误：缺少待扫描 amd64 发布镜像 $image；联网发布工厂需显式设置 COPPER_SECURITY_ALLOW_PULL=1" >&2
      exit 1
    }
    docker pull --platform linux/amd64 "$image" >/dev/null
  fi
  [[ "$(docker image inspect --format '{{.Architecture}}' "$image")" == "amd64" ]] || {
    echo "错误：待扫描发布镜像不是 amd64：$image" >&2
    exit 1
  }
done

parent="$(cd "$(dirname "$output_dir")" && pwd)"
work=""
container_tmp=""
cleanup() {
  [[ -z "$work" ]] || rm -rf "$work"
  [[ -z "$container_tmp" ]] || rm -rf "$container_tmp"
}
trap cleanup EXIT

work="$(mktemp -d "$parent/.copper-security-$version.XXXXXX")"
container_tmp_root="${COPPER_SECURITY_CONTAINER_TMP_ROOT:-$HOME/.cache/copper-security}"
[[ "$container_tmp_root" == /* && ! -L "$container_tmp_root" ]] || {
  echo "错误：扫描器临时目录根必须是绝对路径且不能是符号链接" >&2
  exit 1
}
mkdir -p "$container_tmp_root"
[[ -d "$container_tmp_root" && "$(stat -c '%u' "$container_tmp_root")" == "$(id -u)" ]] || {
  echo "错误：扫描器临时目录根必须由当前用户拥有" >&2
  exit 1
}
chmod 0700 "$container_tmp_root"
container_tmp="$(mktemp -d "$container_tmp_root/tools.XXXXXX")"
reports="$work/reports"
db_cache="$work/grype-db"
result="$work/result"
mkdir -p "$reports" "$db_cache" "$result"
chmod 0700 "$work" "$reports" "$db_cache" "$result" "$container_tmp"

uid_gid="$(id -u):$(id -g)"
socket_gid="$(stat -c '%g' /var/run/docker.sock)"

scan_secrets_git() {
  local role="$1" source="$2"
  docker run --rm --user "$uid_gid" \
    -v "$source:/source:ro" -v "$reports:/reports" -v "$gitleaks_ignore:/config/gitleaksignore:ro" \
    "$COPPER_GITLEAKS_IMAGE" git --redact=100 --no-banner --report-format json \
    --gitleaks-ignore-path /config/gitleaksignore \
    --report-path "/reports/secrets-$role.json" /source >/dev/null
}

scan_secrets_git source-web "$web_repo"
scan_secrets_git source-backend "$backend_repo"
scan_secrets_git source-deploy "$deploy_repo"
docker run --rm --user "$uid_gid" \
  -v "$(dirname "$manifest"):/source:ro" -v "$reports:/reports" -v "$gitleaks_ignore:/config/gitleaksignore:ro" \
  "$COPPER_GITLEAKS_IMAGE" dir --redact=100 --no-banner --max-archive-depth 2 \
  --gitleaks-ignore-path /config/gitleaksignore \
  --report-format json --report-path /reports/secrets-release-artifacts.json /source >/dev/null

source_roles=(source-web source-backend source-deploy)
source_repositories=("$web_repo" "$backend_repo" "$deploy_repo")
source_snapshot_root="$work/source-snapshots"
source_paths=()
mkdir -p "$source_snapshot_root"
for index in 0 1 2; do
  snapshot="$source_snapshot_root/${source_roles[$index]}"
  mkdir -p "$snapshot"
  git -C "${source_repositories[$index]}" archive --format=tar HEAD | tar -xf - -C "$snapshot"
  source_paths+=("$snapshot")
done
for index in 0 1 2; do
  docker run --rm --user "$uid_gid" -v "$container_tmp:/tmp" \
    -e SYFT_CHECK_FOR_APP_UPDATE=false -e SYFT_CACHE_DIR=/tmp/syft-cache \
    -v "${source_paths[$index]}:/source:ro" -v "$reports:/reports" \
    "$COPPER_SYFT_IMAGE" "dir:/source" -o "cyclonedx-json=/reports/sbom-${source_roles[$index]}.cdx.json" >/dev/null
done

image_roles=(image-web image-backend image-mysql)
for index in 0 1 2; do
  docker run --rm --user "$uid_gid" --group-add "$socket_gid" -v "$container_tmp:/tmp" \
    -e SYFT_CHECK_FOR_APP_UPDATE=false -e SYFT_CACHE_DIR=/tmp/syft-cache \
    -v /var/run/docker.sock:/var/run/docker.sock -v "$reports:/reports" \
    "$COPPER_SYFT_IMAGE" "docker:${runtime_images[$index]}" \
    -o "cyclonedx-json=/reports/sbom-${image_roles[$index]}.cdx.json" >/dev/null
done

provenance_args=(provenance --manifest "$manifest" --output "$reports/image-provenance.json")
plain_roles=(web backend mysql)
for index in 0 1 2; do
  local_id="$(docker image inspect --format '{{.Id}}' "${runtime_images[$index]}")"
  architecture="$(docker image inspect --format '{{.Architecture}}' "${runtime_images[$index]}")"
  provenance_args+=(--image "${plain_roles[$index]},${published_images[$index]},${runtime_images[$index]},$local_id,$architecture,${image_sources[$index]},${image_source_evidence[$index]}")
done
"$SCRIPT_DIR/security-evidence.py" "${provenance_args[@]}"

all_roles=(source-web source-backend source-deploy image-web image-backend image-mysql)
for role in "${all_roles[@]}"; do
  docker run --rm --user "$uid_gid" -v "$container_tmp:/tmp" \
    -e GRYPE_DB_CACHE_DIR=/grype-db -v "$db_cache:/grype-db" -v "$reports:/reports:ro" \
    "$COPPER_GRYPE_IMAGE" "sbom:/reports/sbom-$role.cdx.json" -o json \
    > "$reports/vulnerability-$role.json"
done
docker run --rm --user "$uid_gid" -v "$container_tmp:/tmp" \
  -e GRYPE_DB_CACHE_DIR=/grype-db -v "$db_cache:/grype-db" \
  "$COPPER_GRYPE_IMAGE" db status -o json > "$reports/grype-db-status.json"

bundle="$result/copper-security-$version.tar.gz"
build_args=(build --manifest "$manifest" --policy "$policy" --reports-dir "$reports" --output "$bundle")
[[ -z "$exceptions" ]] || build_args+=(--exceptions "$exceptions")
"$SCRIPT_DIR/security-evidence.py" "${build_args[@]}" >/dev/null
"$SCRIPT_DIR/security-evidence.py" verify --bundle "$bundle" >/dev/null
(cd "$result" && sha256sum "$(basename "$bundle")" > "$(basename "$bundle").sha256")
chmod 0444 "$result"/*
mv "$result" "$output_dir"
trap - EXIT
rm -rf "$work" "$container_tmp"

echo "security_status=PASS"
echo "security_bundle=$output_dir/$(basename "$bundle")"
echo "security_sha256=$(sha256sum "$output_dir/$(basename "$bundle")" | awk '{print $1}')"
