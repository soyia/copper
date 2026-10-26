#!/usr/bin/env bash
set -Eeuo pipefail
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "$#" -lt 1 || "$#" -gt 2 ]]; then
  printf 'Usage: %s REDACTED_REPORT_JSON [EXPANDED_SOURCE_ROOT]\n' "$0" >&2
  exit 2
fi
args=(--repo "copper_public=$root_dir" --config "$root_dir/release/CONFIDENTIALITY_POLICY.json" --baseline "$root_dir/release/CONFIDENTIALITY_BASELINE.json" --gate strict --scope all --report "$1")
if [[ "$#" -eq 2 ]]; then
  args+=(--candidate-dir "copper_public=$2")
fi
exec node "$root_dir/copper-web/scripts/security/audit-repositories.mjs" "${args[@]}"
