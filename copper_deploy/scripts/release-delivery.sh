#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib.sh"

tool="$SCRIPT_DIR/delivery-contract.py"
[[ -x "$tool" ]] || die "缺少交付合同工具 $tool"

cleanup_paths=()
cleanup_runtime() {
  local path
  for path in "${cleanup_paths[@]}"; do
    [[ -z "$path" ]] || rm -rf "$path"
  done
}
trap cleanup_runtime EXIT

release_root() {
  printf '%s/releases' "$(required_env_value COPPER_VAR_DIR)"
}

validate_index_hash() {
  [[ "$1" =~ ^[0-9a-f]{64}$ ]] || die "交付索引 SHA-256 必须是 64 位小写十六进制"
}

validate_test_overrides() {
  if [[ -n "${COPPER_DELIVERY_TEST_WEB_IMAGE:-}${COPPER_DELIVERY_TEST_BACKEND_IMAGE:-}${COPPER_DELIVERY_TEST_MYSQL_IMAGE:-}" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" ]] || die "测试镜像覆盖只允许 COPPER_TEST_MODE=1"
    [[ -n "${COPPER_DELIVERY_TEST_WEB_IMAGE:-}" && -n "${COPPER_DELIVERY_TEST_BACKEND_IMAGE:-}" \
      && -n "${COPPER_DELIVERY_TEST_MYSQL_IMAGE:-}" ]] || die "测试镜像覆盖必须同时提供 Web、Backend、MySQL"
  fi
}

runtime_image() {
  local role="$1" published="$2" variable override=""
  case "$role" in
    web) variable=COPPER_DELIVERY_TEST_WEB_IMAGE ;;
    backend) variable=COPPER_DELIVERY_TEST_BACKEND_IMAGE ;;
    mysql) variable=COPPER_DELIVERY_TEST_MYSQL_IMAGE ;;
    *) die "未知镜像角色：$role" ;;
  esac
  override="${!variable:-}"
  if [[ "${COPPER_TEST_MODE:-0}" == "1" && -n "$override" ]]; then
    printf '%s' "$override"
  else
    printf '%s' "$published"
  fi
}

create_image_evidence() {
  local index="$1" mode="$2" output="$3" role published platform indexed_id runtime local_id architecture
  local evidence_args=(evidence --index "$index" --output "$output")
  while IFS=$'\t' read -r role published platform indexed_id; do
    [[ "$platform" == "linux/amd64" ]] || die "交付索引包含不支持的平台：$platform"
    if [[ "$mode" == "offline" || "$mode" == "offline-delta" ]]; then
      [[ "$indexed_id" =~ ^sha256:[0-9a-f]{64}$ ]] || die "离线交付索引缺少归档内容 ID"
      runtime="$indexed_id"
    else
      runtime="$(runtime_image "$role" "$published")"
    fi
    if [[ "$mode" == "online" ]]; then
      if [[ "${COPPER_TEST_MODE:-0}" != "1" || "$runtime" == "$published" ]]; then
        [[ "${COPPER_DELIVERY_TEST_FAIL_PULL:-0}" != "1" ]] || die "测试注入：联网镜像拉取失败"
        note "拉取目标不可变镜像：$published"
        docker pull --platform linux/amd64 "$published"
      fi
    fi
    docker image inspect "$runtime" >/dev/null 2>&1 || die "本机缺少交付镜像：$role=$runtime"
    local_id="$(docker image inspect --format '{{.Id}}' "$runtime")"
    architecture="$(docker image inspect --format '{{.Architecture}}' "$runtime")"
    [[ "$local_id" =~ ^sha256:[0-9a-f]{64}$ ]] || die "无法取得本机不可变镜像 ID"
    if [[ "${COPPER_TEST_MODE:-0}" != "1" ]]; then
      [[ "$architecture" == "amd64" ]] || die "正式交付镜像架构必须是 amd64：$role=$architecture"
    fi
    evidence_args+=(--image "$role,$published,$local_id,$architecture")
  done < <("$tool" images "$index")
  "$tool" "${evidence_args[@]}"
}

stage_validated_directory() {
  local index="$1" expected_hash="$2" mode="$3" root evidence
  validate_test_overrides
  require_command docker
  docker info >/dev/null 2>&1 || die "Docker Engine 不可用"
  root="$(release_root)"
  install -d -o root -g root -m 0700 "$root"
  evidence="$(mktemp "$root/.image-evidence.XXXXXX")"
  cleanup_paths+=("$evidence")
  create_image_evidence "$index" "$mode" "$evidence"
  stage_args=(stage --index "$index" --index-sha256 "$expected_hash" \
    --image-evidence "$evidence" --release-root "$root")
  [[ "${COPPER_TEST_MODE:-0}" != "1" ]] || stage_args+=(--test-mode)
  "$tool" "${stage_args[@]}"
  rm -f "$evidence"
}

stage_online() {
  local url="${1:-}" expected_hash="${2:-}" root temporary index_name index base name part mode
  [[ -n "$url" && -n "$expected_hash" && $# -eq 2 ]] \
    || die "用法：copperctl release stage online INDEX_URL INDEX_SHA256"
  validate_index_hash "$expected_hash"
  require_command curl
  require_command python3
  require_command sha256sum
  python3 - "$url" "${COPPER_TEST_MODE:-0}" <<'PY'
import sys
from urllib.parse import urlsplit
url = urlsplit(sys.argv[1])
test = sys.argv[2] == "1"
if url.username or url.password or url.query or url.fragment:
    raise SystemExit("交付 URL 禁止凭据、查询参数和片段")
if url.scheme == "https" and url.netloc and url.path.rsplit("/", 1)[-1]:
    raise SystemExit(0)
if test and url.scheme == "http" and url.hostname in {"127.0.0.1", "localhost"} and url.path.rsplit("/", 1)[-1]:
    raise SystemExit(0)
raise SystemExit("生产交付 URL 必须是 HTTPS；测试只允许本机回环 HTTP")
PY
  root="$(release_root)"
  install -d -o root -g root -m 0700 "$root"
  temporary="$(mktemp -d "$root/.online.XXXXXX")"
  cleanup_paths+=("$temporary")
  chmod 0700 "$temporary"
  index_name="${url##*/}"
  [[ "$index_name" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]{0,191}$ ]] || die "交付索引 URL 文件名不安全"
  index="$temporary/$index_name"
  curl_args=(-fL --silent --show-error --retry 3 --connect-timeout 20 --max-time 600)
  if [[ "${COPPER_TEST_MODE:-0}" == "1" && "$url" == http://* ]]; then
    curl_args+=(--proto '=http' --proto-redir '=http')
  else
    curl_args+=(--proto '=https' --proto-redir '=https' --tlsv1.2)
  fi
  curl "${curl_args[@]}" --output "$index.part" "$url"
  [[ "$(sha256sum "$index.part" | awk '{print $1}')" == "$expected_hash" ]] || die "交付索引独立 SHA-256 不匹配"
  mv "$index.part" "$index"
  base="${url%/*}"
  while IFS= read -r name; do
    [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]{0,191}$ ]] || die "交付索引包含不安全文件名"
    part="$temporary/$name.part"
    curl "${curl_args[@]}" --output "$part" "$base/$name"
    mv "$part" "$temporary/$name"
  done < <("$tool" files "$index")
  chmod 0400 "$temporary"/*
  mode="$("$tool" validate "$index" | awk -F= '$1 == "mode" {print $2}')"
  [[ "$mode" == "online" ]] || die "联网暂存只接受 online 交付索引"
  stage_validated_directory "$index" "$expected_hash" online
  rm -rf "$temporary"
}

stage_offline() {
  local index="${1:-}" expected_hash="${2:-}" directory mode archive
  [[ -n "$index" && -n "$expected_hash" && $# -eq 2 ]] \
    || die "用法：copperctl release stage offline INDEX_FILE INDEX_SHA256"
  validate_index_hash "$expected_hash"
  require_command sha256sum
  [[ -f "$index" && ! -L "$index" ]] || die "离线交付索引必须是普通文件且不能是符号链接"
  index="$(cd "$(dirname "$index")" && pwd)/$(basename "$index")"
  [[ "$(sha256sum "$index" | awk '{print $1}')" == "$expected_hash" ]] || die "离线交付索引独立 SHA-256 不匹配"
  mode="$("$tool" validate "$index" | awk -F= '$1 == "mode" {print $2}')"
  [[ "$mode" == "offline" ]] || die "离线暂存只接受 offline 交付索引"
  directory="$(dirname "$index")"
  archive="$directory/$("$tool" image-archive "$index")"
  [[ -f "$archive" && ! -L "$archive" ]] || die "离线镜像归档不存在或不安全"
  require_command docker
  docker info >/dev/null 2>&1 || die "Docker Engine 不可用"
  docker load --input "$archive"
  stage_validated_directory "$index" "$expected_hash" offline
}

stage_offline_delta() {
  local index="${1:-}" expected_hash="${2:-}" directory mode archive archive_name base_version current_version
  local variable configured observed expected_arch base_index_hash base_ready base_ready_hash
  [[ -n "$index" && -n "$expected_hash" && $# -eq 2 ]] \
    || die "用法：copperctl release stage offline-delta INDEX_FILE INDEX_SHA256"
  validate_index_hash "$expected_hash"
  require_command sha256sum
  [[ -f "$index" && ! -L "$index" ]] || die "差异升级索引必须是普通文件且不能是符号链接"
  index="$(cd "$(dirname "$index")" && pwd)/$(basename "$index")"
  [[ "$(sha256sum "$index" | awk '{print $1}')" == "$expected_hash" ]] || die "差异升级索引独立 SHA-256 不匹配"
  mode="$("$tool" validate "$index" | awk -F= '$1 == "mode" {print $2}')"
  [[ "$mode" == "offline-delta" ]] || die "差异暂存只接受 offline-delta 交付索引"
  base_version="$(python3 - "$index" <<'PY'
import json, pathlib, sys
print(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["baseVersion"])
PY
  )"
  base_index_hash="$(python3 - "$index" <<'PY'
import json, pathlib, sys
print(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["baseIndexSha256"])
PY
  )"
  current_version="$(required_env_value COPPER_RELEASE_VERSION)"
  [[ "$current_version" == "$base_version" ]] \
    || die "差异升级包要求当前版本 $base_version，本机实际是 $current_version；请改用匹配的差异包或完整升级包"
  base_ready="$(release_root)/$base_version/ready.json"
  if [[ -e "$base_ready" || -L "$base_ready" ]]; then
    [[ -f "$base_ready" && ! -L "$base_ready" ]] || die "来源版本 READY 记录不安全"
    "$tool" status --release-root "$(release_root)" --version "$base_version" --json >/dev/null
    base_ready_hash="$(python3 - "$base_ready" <<'PY'
import json, pathlib, sys
print(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["indexSha256"])
PY
    )"
    [[ "$base_ready_hash" == "$base_index_hash" ]] \
      || die "当前来源版本 READY 与差异包声明的来源发布索引不一致；请改用完整升级包"
  fi

  require_command docker
  docker info >/dev/null 2>&1 || die "Docker Engine 不可用"
  while IFS=$'\t' read -r role delivery base_id target_id _base_reference _target_reference; do
    case "$role" in
      web) variable=COPPER_WEB_IMAGE ;;
      backend) variable=COPPER_BACKEND_IMAGE ;;
      mysql) variable=COPPER_MYSQL_IMAGE ;;
      *) die "差异升级索引包含未知镜像角色：$role" ;;
    esac
    configured="$(required_env_value "$variable")"
    observed="$(docker image inspect --format '{{.Id}}|{{.Architecture}}' "$configured" 2>/dev/null || true)"
    expected_arch=amd64
    if [[ "${COPPER_TEST_MODE:-0}" == "1" && "$observed" == "$base_id|arm64" ]]; then
      expected_arch=arm64
    fi
    [[ "$observed" == "$base_id|$expected_arch" ]] \
      || die "当前 $role 运行文件与差异包来源不一致；拒绝拼接，需使用完整升级包"
    if [[ "$delivery" == "reuse" ]]; then
      [[ "$target_id" == "$base_id" ]] || die "差异升级 $role 复用结论不一致"
    elif [[ "$delivery" != "included" ]]; then
      die "差异升级 $role 交付方式不正确"
    fi
  done < <("$tool" delta-plan "$index")

  directory="$(dirname "$index")"
  archive_name="$("$tool" image-archive "$index")"
  if [[ -n "$archive_name" ]]; then
    archive="$directory/$archive_name"
    [[ -f "$archive" && ! -L "$archive" ]] || die "差异升级镜像归档不存在或不安全"
    docker load --input "$archive"
  fi
  stage_validated_directory "$index" "$expected_hash" offline-delta
}

status_command() {
  local root version="" json=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --json) json=1; shift ;;
      *) [[ -z "$version" ]] || die "release status 只接受一个可选版本"; version="$1"; shift ;;
    esac
  done
  root="$(release_root)"
  require_command docker
  docker info >/dev/null 2>&1 || die "Docker Engine 不可用"
  args=(status --release-root "$root" --verify-images)
  [[ -z "$version" ]] || args+=(--version "$version")
  [[ "$json" == "0" ]] || args+=(--json)
  [[ "${COPPER_TEST_MODE:-0}" != "1" ]] || args+=(--test-mode)
  "$tool" "${args[@]}"
}

require_root
assert_safe_paths
require_supported_platform >&2
validate_tools
command="${1:-}"
shift || true
case "$command" in
  stage)
    mode="${1:-}"
    shift || true
    case "$mode" in
      online) stage_online "$@" ;;
      offline) stage_offline "$@" ;;
      offline-delta) stage_offline_delta "$@" ;;
      *) die "release stage 只支持 online、offline 或 offline-delta" ;;
    esac
    ;;
  status) status_command "$@" ;;
  *) die "release delivery 只支持 stage 或 status" ;;
esac
