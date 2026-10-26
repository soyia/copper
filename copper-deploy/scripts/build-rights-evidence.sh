#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

manifest="${1:-}"
security_bundle="${2:-}"
web_repo="${3:-}"
backend_repo="${4:-}"
deploy_repo="${5:-}"
ledger="${6:-}"
license_dir="${7:-}"
output="${8:-}"
exceptions="${9:-}"

[[ -n "$manifest" && -n "$security_bundle" && -n "$web_repo" && -n "$backend_repo" \
   && -n "$deploy_repo" && -n "$ledger" && -n "$license_dir" && -n "$output" ]] || {
  echo "用法：scripts/build-rights-evidence.sh RELEASE_MANIFEST SECURITY_BUNDLE WEB_REPO BACKEND_REPO DEPLOY_REPO LEDGER_JSON LICENSE_DIR OUTPUT_BUNDLE [EXCEPTIONS_JSON]" >&2
  exit 2
}
[[ ! -e "$output" && ! -L "$output" ]] || {
  echo "错误：第三方权利证据输出已存在，拒绝覆盖：$output" >&2
  exit 1
}

parent="$(cd "$(dirname "$output")" && pwd)"
work="$(mktemp -d "$parent/.copper-rights-build.XXXXXX")"
cleanup() {
  rm -rf "$work"
}
trap cleanup EXIT

inventory="$work/content-inventory.json"
"$ROOT_DIR/scripts/rights-evidence.py" inventory \
  --manifest "$manifest" \
  --policy "$ROOT_DIR/config/third-party-rights-policy.json" \
  --web-repo "$web_repo" \
  --backend-repo "$backend_repo" \
  --deploy-repo "$deploy_repo" \
  --output "$inventory" >/dev/null

build_args=(
  build
  --manifest "$manifest"
  --policy "$ROOT_DIR/config/third-party-rights-policy.json"
  --security-bundle "$security_bundle"
  --inventory "$inventory"
  --ledger "$ledger"
  --license-dir "$license_dir"
  --output "$output"
)
[[ -z "$exceptions" ]] || build_args+=(--exceptions "$exceptions")
"$ROOT_DIR/scripts/rights-evidence.py" "${build_args[@]}"
"$ROOT_DIR/scripts/rights-evidence.py" verify --bundle "$output" >/dev/null

trap - EXIT
rm -rf "$work"
