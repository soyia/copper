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
output_dir="${2:-}"
web_repo="${3:-}"
backend_repo="${4:-}"
exceptions="${5:-}"
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.[1-9][0-9]*)?$ \
   && -n "$output_dir" && -n "$web_repo" && -n "$backend_repo" ]] || {
  echo "用法：scripts/build-secure-release.sh [--candidate] VERSION OUTPUT_DIR WEB_REPO BACKEND_REPO [EXCEPTIONS_JSON]" >&2
  exit 2
}
[[ ! -e "$output_dir" && ! -L "$output_dir" ]] || { echo "错误：输出路径已存在，拒绝覆盖" >&2; exit 1; }
mkdir -p "$(dirname "$output_dir")"
parent="$(cd "$(dirname "$output_dir")" && pwd)"
work="$(mktemp -d "$parent/.copper-secure-release-$version.XXXXXX")"
cleanup() {
  rm -rf "$work"
}
trap cleanup EXIT

release_dir="$work/release"
security_dir="$work/security"
build_args=()
[[ "$candidate" == "0" ]] || build_args+=(--candidate)
build_args+=("$version" "$release_dir")
"$ROOT_DIR/scripts/build-release.sh" "${build_args[@]}"

manifest="$release_dir/copper-release-$version.env"
scan_args=("$manifest" "$web_repo" "$backend_repo" "$ROOT_DIR" "$security_dir")
[[ -z "$exceptions" ]] || scan_args+=("$exceptions")
"$ROOT_DIR/scripts/security-scan.sh" "${scan_args[@]}"

bundle="$security_dir/copper-security-$version.tar.gz"
sidecar="$security_dir/copper-security-$version.tar.gz.sha256"
[[ -f "$bundle" && -f "$sidecar" ]] || { echo "错误：安全扫描没有生成完整证据" >&2; exit 1; }
mv "$bundle" "$sidecar" "$release_dir/"

append_relative_checksum() {
  local path="$1" checksum
  checksum="$(sha256sum "$path")"
  printf '%s  %s\n' "${checksum%% *}" "${path##*/}"
}
{
  append_relative_checksum "$release_dir/$(basename "$bundle")"
  append_relative_checksum "$release_dir/$(basename "$sidecar")"
} >> "$release_dir/SHA256SUMS"

chmod 0444 "$release_dir"/*
mv "$release_dir" "$output_dir"
trap - EXIT
rm -rf "$work"
echo "secure_release_status=PASS"
echo "secure_release_directory=$output_dir"
echo "security_bundle=$output_dir/copper-security-$version.tar.gz"
